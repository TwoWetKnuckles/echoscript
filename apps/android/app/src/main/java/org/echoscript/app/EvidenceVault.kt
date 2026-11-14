package org.echoscript.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.subtle.Hex
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates
import org.erdtman.jcs.JsonCanonicalizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.KeyGenerator
import org.echoscript.security.SignedCheckpoint

data class VaultListing(val cases: List<JSONObject>, val warnings: List<String>)
class EvidenceVault(private val context: Context) {
    companion object {
        const val MAX_SOURCE_BYTES = 4L * 1024 * 1024 * 1024
        const val MAX_PROCESS_SOURCE_BYTES = 750_000_000L
        const val MAX_PACKAGE_BYTES = 16L * 1024 * 1024 * 1024
        val VERSION = BuildConfig.VERSION_NAME
        private const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
        private const val ALIAS = "echoscript.vault.master.v1"
        private val KEY_LOCK = Any()
        private val COMMIT_LOCK = Any()
        private val ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun canonical(json: JSONObject): ByteArray = JsonCanonicalizer(json.toString()).encodedUTF8
        fun utc(): String = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
        private fun uuid() = UUID.randomUUID().toString()
    }
    private val root = File(context.filesDir, "EvidenceVault/cases").apply { mkdirs() }
    private val primitive: StreamingAead
    private val deviceId: String = context.getSharedPreferences("echoscript_local_device", Context.MODE_PRIVATE).let { prefs -> prefs.getString("id", null) ?: uuid().also { check(prefs.edit().putString("id", it).commit()) } }
    init {
      primitive = synchronized(KEY_LOCK) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val stored = File(context.filesDir, "EvidenceVault/streaming-keyset.v1.enc")
        val legacy = context.getSharedPreferences("echoscript_keysets", Context.MODE_PRIVATE).getString("stream_keyset", null)
        if (!ks.containsAlias(ALIAS)) {
            check(!stored.exists() && legacy == null && root.listFiles().isNullOrEmpty()) { "Master key is missing; existing evidence cannot be opened. Preserve the vault for recovery." }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            generator.generateKey()
        }
        check(ks.getKey(ALIAS, null) != null) { "Android Keystore unavailable; vault cannot open" }
        StreamingAeadConfig.register()
        val master = AndroidKeystoreKmsClient().getAead("android-keystore://$ALIAS")
        val aad = "org.echoscript.vault.keyset/1".toByteArray(Charsets.UTF_8)
        val handle = when {
            stored.exists() -> {
                check(stored.length() in 1..1048576) { "Invalid encrypted keyset length" }
                TinkProtoKeysetFormat.parseEncryptedKeyset(stored.readBytes(), master, aad)
            }
            legacy != null -> {
                check(legacy.length <= 2097152) { "Invalid legacy encrypted keyset" }
                // Migrate only an authenticated encrypted keyset. No cleartext-read fallback.
                TinkProtoKeysetFormat.parseEncryptedKeyset(Hex.decode(legacy), master, byteArrayOf())
            }
            else -> KeysetHandle.generateNew(StreamingAeadKeyTemplates.AES256_GCM_HKDF_1MB)
        }
        if (!stored.exists()) {
            stored.parentFile!!.mkdirs(); val pending = File(stored.parentFile, ".${uuid()}.keyset-pending")
            try {
                val encrypted = TinkProtoKeysetFormat.serializeEncryptedKeyset(handle, master, aad)
                FileOutputStream(pending).use { it.write(encrypted); it.fd.sync() }
                FileOutputStream(pending, true).use { it.fd.sync() }
                Files.move(pending.toPath(), stored.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (failure: Exception) { pending.delete(); throw failure }
        }
        handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
      }
    }
    private fun caseDir(caseId: String): File { require(ID.matches(caseId)); return File(root, caseId) }
    private fun associated(caseId: String, artifactId: String) = "$caseId:$artifactId".toByteArray(Charsets.UTF_8)
    private fun encryptedFile(caseId: String, artifactId: String): File { require(ID.matches(artifactId)); return File(caseDir(caseId), "artifacts/$artifactId.enc") }
    private fun writeGenerated(file: File, aad: ByteArray, max: Long, produce: (OutputStream) -> Unit): Pair<String, Long> {
        check(!file.exists()) { "Immutable artifact already exists" }; file.parentFile!!.mkdirs()
        val temp = File(file.parentFile, ".${uuid()}.pending")
        val hash = MessageDigest.getInstance("SHA-256"); var count = 0L
        try {
            FileOutputStream(temp).use { physical ->
                primitive.newEncryptingStream(physical, aad).use { sealed ->
                    val bounded = object : OutputStream() {
                        private fun add(size: Int) {
                            count += size
                            require(count <= max) { "Artifact exceeds its $max byte storage limit" }
                        }
                        override fun write(value: Int) { add(1); hash.update(value.toByte()); sealed.write(value) }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            add(length); hash.update(bytes, offset, length); sealed.write(bytes, offset, length)
                        }
                        override fun flush() = sealed.flush()
                    }
                    produce(bounded)
                }
            }
            check(count > 0) { "Empty evidence is not accepted" }
            FileOutputStream(temp, true).use { it.fd.sync() }
            synchronized(COMMIT_LOCK) {
                check(!file.exists()) { "Immutable artifact already exists" }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } to count
        } catch (failure: Throwable) {
            // A handled allocation failure must not leave an uncommitted staging
            // file looking like a completed artifact. A real process kill still
            // leaves encrypted pending bytes for explicit recovery review.
            temp.delete()
            throw failure
        }
    }
    private fun writeEncrypted(file: File, aad: ByteArray, source: InputStream, max: Long, cancelled: () -> Boolean, progress: (Long) -> Unit): Pair<String, Long> =
        writeGenerated(file, aad, max) { output -> copyBounded(source, output, cancelled, progress) }

    private fun copyBounded(input: InputStream, output: OutputStream, cancelled: () -> Boolean, progress: (Long) -> Unit = {}) {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            if (cancelled()) throw InterruptedException("Operation cancelled")
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            copied += count
            progress(copied)
        }
        if (cancelled()) throw InterruptedException("Operation cancelled")
    }

    private fun actorCheck(actor: String) {
        require(actor.isNotBlank() && actor.length <= 128 && actor.none { it.code < 32 }) { "Enter an examiner identifier" }
    }

    private fun replaceManifest(target: JSONObject, replacement: JSONObject) {
        val names = target.keys().asSequence().toList()
        names.forEach(target::remove)
        replacement.keys().forEach { key -> target.put(key, replacement.get(key)) }
    }

    @Synchronized
    internal fun addDerivative(
        manifest: JSONObject,
        actor: String,
        type: String,
        parents: List<String>,
        name: String,
        algorithm: String,
        algorithmVersion: String,
        warnings: List<String> = emptyList(),
        active: Boolean = false,
        maxBytes: Long = MAX_SOURCE_BYTES,
        produce: (OutputStream) -> JSONObject,
    ): JSONObject {
        actorCheck(actor)
        require(type in setOf("DECODE", "ANALYSIS", "ENHANCED_AUDIO", "TRANSCRIPTION_TRANSMISSION", "RAW_PROVIDER_RESULT", "NORMALIZED_TRANSCRIPT", "HUMAN_TRANSCRIPT", "AUDIO_EXPORT", "DOCUMENT_EXPORT", "REPORT"))
        require(name.isNotBlank() && name.length <= 256 && name.none { it.code < 32 })
        val next = JSONObject(manifest.toString())
        val caseId = next.getString("caseId")
        val artifacts = next.getJSONArray("artifacts")
        val existing = (0 until artifacts.length()).map { artifacts.getJSONObject(it).getString("id") }.toSet()
        require(artifacts.length() < 10000 && parents.isNotEmpty() && parents.distinct().size == parents.size && parents.all { it in existing }) { "Invalid derivative provenance" }
        val revision = next.getJSONArray("audit").length()
        check(latestRevision(caseId) == revision) { "Case history changed. Reopen the case before editing." }
        val id = uuid()
        val file = encryptedFile(caseId, id)
        var parameters = JSONObject()
        try {
            val (hash, size) = writeGenerated(file, associated(caseId, id), maxBytes) { parameters = produce(it) }
            val artifact = JSONObject().put("id", id).put("caseId", caseId).put("type", type)
                .put("parents", JSONArray(parents)).put("name", name).put("path", "artifacts/$id")
                .put("bytes", size).put("sha256", hash).put("timestampUtc", utc()).put("actor", actor)
                .put("revision", artifacts.length() + 1).put("softwareVersion", VERSION)
                .put("operation", JSONObject().put("algorithm", algorithm).put("version", algorithmVersion).put("parameters", parameters))
                .put("warnings", JSONArray(warnings))
            artifacts.put(artifact)
            appendAudit(next, actor, "ARTIFACT_COMMIT", JSONObject().put("artifactId", id).put("sha256", hash).put("type", type).put("parents", JSONArray(parents)))
            if (active) next.put("activeArtifact", id)
            saveSnapshot(next)
            replaceManifest(manifest, next)
            return artifact
        } catch (failure: Exception) {
            if (latestRevision(caseId) == revision) file.delete()
            throw failure
        }
    }

    internal fun withEncryptedStage(maxBytes: Long, produce: (OutputStream) -> Unit, read: (InputStream, String, Long) -> Unit) {
        val folder = File(context.filesDir, "EvidenceVault/staging").apply { mkdirs() }
        val id = uuid()
        val file = File(folder, "$id.enc")
        val aad = "echoscript-staging:$id".toByteArray()
        try {
            val (hash, size) = writeGenerated(file, aad, maxBytes, produce)
            FileInputStream(file).use { physical -> primitive.newDecryptingStream(physical, aad).use { read(it, hash, size) } }
        } finally { file.delete() }
    }
    @Synchronized
    fun importSource(input: InputStream, filename: String, title: String, actor: String, cancelled: () -> Boolean = { false }, progress: (Long) -> Unit = {}, capture: JSONObject? = null): JSONObject {
        require(filename.isNotBlank() && filename.length <= 256 && filename.none { it.code < 32 })
        require(title.isNotBlank() && title.length <= 256 && title.none { it.code < 32 })
        actorCheck(actor)
        val caseId = uuid(); val artifactId = uuid(); val created = utc(); val folder = caseDir(caseId); folder.mkdirs()
        val result = try { writeEncrypted(encryptedFile(caseId, artifactId), associated(caseId, artifactId), input, MAX_SOURCE_BYTES, cancelled, progress) }
            catch (failure: Exception) {
                // Handled cancellation removed its pending file. Remove only an
                // empty acquisition tree; preserve any bytes for recovery review.
                if (!folder.walkTopDown().any { it.isFile }) folder.deleteRecursively()
                throw failure
            }
        val manifest = JSONObject().put("format", "org.echoscript.case/1").put("schemaVersion", 1).put("caseId", caseId).put("title", title).put("createdAtUtc", created)
            .put("software", JSONObject().put("name", "EchoScript").put("version", VERSION)).put("activeArtifact", artifactId)
            .put("artifacts", JSONArray()).put("audit", JSONArray())
        appendAudit(manifest, actor, "CASE_CREATE", JSONObject())
        val artifact = JSONObject().put("id", artifactId).put("caseId", caseId).put("type", "SOURCE").put("parents", JSONArray()).put("name", filename)
            .put("path", "artifacts/$artifactId").put("bytes", result.second).put("sha256", result.first).put("timestampUtc", utc()).put("actor", actor).put("revision", 1)
            .put("softwareVersion", VERSION).put("operation", JSONObject().put("algorithm", if (capture == null) "android-saf-hash-before-decode" else "android-audiorecord-pcm16").put("version", "1").put("parameters", JSONObject().put("keyStorage", "Android Keystore").put("encryption", "Tink AES256_GCM_HKDF_1MB").put("capture", capture ?: JSONObject.NULL))).put("warnings", JSONArray().put("Operator identity is locally supplied, not externally authenticated."))
        manifest.getJSONArray("artifacts").put(artifact)
        appendAudit(manifest, actor, "ARTIFACT_COMMIT", JSONObject().put("artifactId", artifactId).put("sha256", result.first).put("type", "SOURCE").put("parents", JSONArray()))
        saveSnapshot(manifest)
        return manifest
    }
    private fun appendAudit(manifest: JSONObject, actor: String, action: String, details: JSONObject) {
        val chain = manifest.getJSONArray("audit")
        val previous = if (chain.length() == 0) "0".repeat(64) else chain.getJSONObject(chain.length() - 1).getString("entryHash")
        val entry = JSONObject().put("seq", chain.length() + 1).put("timestampUtc", utc()).put("actor", actor).put("deviceId", deviceId)
            .put("action", action).put("details", details).put("prevHash", previous)
        entry.put("entryHash", sha(canonical(entry))); chain.put(entry)
    }
    private fun saveSnapshot(manifest: JSONObject) {
        val caseId = manifest.getString("caseId"); val revision = manifest.getJSONArray("audit").length()
        val file = File(caseDir(caseId), "manifests/${revision.toString().padStart(10, '0')}.enc")
        writeEncrypted(file, "$caseId:manifest:$revision".toByteArray(), canonical(manifest).inputStream(), MAX_MANIFEST_BYTES, { false }, {})
    }
    private fun latestRevision(caseId: String): Int? = File(caseDir(caseId), "manifests").listFiles()
        ?.filter { it.extension == "enc" }?.maxByOrNull { it.name }?.nameWithoutExtension?.toIntOrNull()
    fun listCases(): VaultListing {
      val warnings = mutableListOf<String>()
      val cases = root.listFiles()?.filter { it.isDirectory && ID.matches(it.name) }?.mapNotNull { folder ->
        val file = File(folder, "manifests").listFiles()?.filter { it.extension == "enc" }?.maxByOrNull { it.name }
        if (file == null) {
            if (folder.walkTopDown().any { it.isFile }) warnings.add("Case ${folder.name}: uncommitted acquisition data was preserved after interruption. It is not a complete case. Preserve and review the vault; no evidence was silently repaired or discarded.")
            return@mapNotNull null
        }
        if (folder.walkTopDown().any { it.isFile && it.name.endsWith(".pending") })
            warnings.add("Case ${folder.name}: interrupted pending data was preserved. The completed snapshot is listed separately; review incomplete work before retrying.")
        try {
            val revision = file.nameWithoutExtension.toInt()
            FileInputStream(file).use { physical ->
                primitive.newDecryptingStream(physical, "${folder.name}:manifest:$revision".toByteArray()).use { stream ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(65536)
                    while (true) { val n = stream.read(buffer); if (n < 0) break; require(output.size() + n <= 8 * 1024 * 1024); output.write(buffer, 0, n) }
                    val bytes = output.toByteArray()
                    val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
                    check(manifest.getString("caseId") == folder.name && manifest.getJSONArray("audit").length() == revision && canonical(manifest).contentEquals(bytes)) { "Snapshot metadata mismatch" }
                    manifest
                }
            }
        } catch (_: Exception) { warnings.add("Case ${folder.name}: latest snapshot could not be authenticated or validated. No older snapshot was silently substituted. Preserve the vault for recovery."); null }
      }?.sortedByDescending { it.getString("createdAtUtc") } ?: emptyList()
      if (File(context.filesDir, "EvidenceVault/staging").walkTopDown().any { it.isFile })
          warnings.add("Encrypted staging data remains after an interruption. It is not completed evidence; preserve and review it under the recovery policy.")
      return VaultListing(cases, warnings)
    }
    fun open(caseId: String, artifactId: String): InputStream {
        val physical = FileInputStream(encryptedFile(caseId, artifactId))
        try { return primitive.newDecryptingStream(physical, associated(caseId, artifactId)) }
        catch (failure: Exception) { physical.close(); throw failure }
    }
    fun seekable(caseId: String, artifactId: String): SeekableByteChannel {
        val physical = Files.newByteChannel(encryptedFile(caseId, artifactId).toPath())
        try { return primitive.newSeekableDecryptingChannel(physical, associated(caseId, artifactId)) }
        catch (failure: Exception) { physical.close(); throw failure }
    }
    private fun checkedInventory(manifest: JSONObject): List<JSONObject> {
        val caseId = manifest.getString("caseId")
        check(manifest.getString("format") == "org.echoscript.case/1" && manifest.getInt("schemaVersion") == 1 && ID.matches(caseId)) { "Unsupported case manifest" }
        val title = manifest.getString("title")
        check(title.isNotBlank() && title.length <= 256 && manifest.getJSONObject("software").getString("name") == "EchoScript") { "Invalid case metadata" }
        val chain = manifest.getJSONArray("audit")
        check(chain.length() in 1..100000) { "Invalid audit length" }
        var previous = "0".repeat(64)
        val commitments = mutableMapOf<String, JSONObject>()
        for (i in 0 until chain.length()) {
            val entry = JSONObject(chain.getJSONObject(i).toString())
            val expected = entry.getString("entryHash"); entry.remove("entryHash")
            check(entry.getInt("seq") == i + 1 && entry.getString("prevHash") == previous && sha(canonical(entry)) == expected) { "Audit linkage mismatch" }
            previous = expected
            if (entry.getString("action") == "ARTIFACT_COMMIT") {
                val details = entry.getJSONObject("details")
                check(commitments.put(details.getString("artifactId"), details) == null) { "Duplicate artifact commitment" }
            }
        }
        val inventory = manifest.getJSONArray("artifacts")
        check(inventory.length() in 1..10000 && commitments.size == inventory.length()) { "Invalid artifact inventory" }
        val seen = mutableSetOf<String>()
        val types = setOf("SOURCE", "DECODE", "ANALYSIS", "ENHANCED_AUDIO", "TRANSCRIPTION_TRANSMISSION", "RAW_PROVIDER_RESULT", "NORMALIZED_TRANSCRIPT", "HUMAN_TRANSCRIPT", "REPORT", "AUDIO_EXPORT", "DOCUMENT_EXPORT")
        var sources = 0
        val artifacts = (0 until inventory.length()).map { i ->
            val artifact = inventory.getJSONObject(i)
            val id = artifact.getString("id")
            val type = artifact.getString("type")
            val parents = artifact.getJSONArray("parents")
            val parentIds = (0 until parents.length()).map { parents.getString(it) }
            check(type in types && ID.matches(id) && id !in seen && artifact.getString("caseId") == caseId && artifact.getString("path") == "artifacts/$id" && artifact.getInt("revision") == i + 1) { "Artifact identifier/revision mismatch" }
            check(artifact.getLong("bytes") in 1..MAX_SOURCE_BYTES && artifact.getString("sha256").matches(Regex("[a-f0-9]{64}"))) { "Invalid artifact length or hash" }
            check(artifact.getString("name").length in 1..256 && artifact.getJSONObject("operation").getString("algorithm").isNotBlank()) { "Invalid artifact description" }
            artifact.getJSONObject("operation").getJSONObject("parameters")
            check(parentIds.distinct().size == parentIds.size && parentIds.all { it in seen }) { "Invalid provenance parent" }
            if (type == "SOURCE") { sources++; check(parentIds.isEmpty()) { "Source must not have parents" } } else check(parentIds.isNotEmpty()) { "Derivative lacks a parent" }
            val committed = commitments.getValue(id)
            check(committed.getString("sha256") == artifact.getString("sha256") && committed.getString("type") == type && committed.getJSONArray("parents").toString() == parents.toString()) { "Artifact audit commitment mismatch" }
            seen.add(id)
            artifact
        }
        check(sources == 1 && manifest.getString("activeArtifact") in seen) { "Source or active artifact invariant failed" }
        return artifacts
    }

    @Synchronized
    fun verify(manifest: JSONObject, cancelled: () -> Boolean = { false }): String {
        val caseId = manifest.getString("caseId")
        for (artifact in checkedInventory(manifest)) {
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L
            open(caseId, artifact.getString("id")).use { stream ->
                val buffer = ByteArray(65536)
                while (true) {
                    if (cancelled()) throw InterruptedException("Verification cancelled")
                    val n = stream.read(buffer); if (n < 0) break
                    hash.update(buffer, 0, n); count += n
                }
            }
            check(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == artifact.getString("sha256") && count == artifact.getLong("bytes")) { "Artifact integrity mismatch" }
        }
        val checkpoints = verifyCheckpoints(manifest, cancelled)
        return if (checkpoints.isEmpty()) "Source and derivative hashes, authenticated decryption and audit linkage agree. Independent custody and signatures require separate verification."
        else "Source and derivative hashes, authenticated decryption and audit linkage agree. ${checkpoints.size} device checkpoint signatures are mathematically valid. Confirm signer fingerprints independently; later events are outside earlier checkpoints."
    }

    internal fun verifyCheckpoints(manifest: JSONObject, cancelled: () -> Boolean = { false }): List<SignedCheckpoint.Verification> {
        val checkpoints = manifest.items("artifacts").filter { it.getJSONObject("operation").getString("algorithm") == SignedCheckpoint.ALGORITHM }
        require(checkpoints.size <= SignedCheckpoint.MAX_CHECKPOINTS) { "Too many signed checkpoints" }
        return checkpoints.map { artifact ->
            require(artifact.getString("type") == "REPORT" && artifact.getJSONObject("operation").getString("version") == "1") { "Unsupported checkpoint artifact" }
            require(artifact.getLong("bytes") in 1..SignedCheckpoint.MAX_BYTES.toLong()) { "Oversized checkpoint" }
            val data = ByteArrayOutputStream()
            open(manifest.getString("caseId"), artifact.getString("id")).use { input ->
                val block = ByteArray(65536)
                while (true) {
                    if (cancelled()) throw InterruptedException("Checkpoint verification cancelled")
                    val n = input.read(block); if (n < 0) break
                    require(data.size().toLong() + n <= artifact.getLong("bytes")) { "Checkpoint length differs" }
                    data.write(block, 0, n)
                }
            }
            val bytes = data.toByteArray()
            check(bytes.size.toLong() == artifact.getLong("bytes") && sha(bytes) == artifact.getString("sha256")) { "Checkpoint artifact hash differs" }
            SignedCheckpoint.verify(bytes, manifest).also {
                check(it.coveredArtifacts + 1 == artifact.getInt("revision")) { "Checkpoint does not cover its immediately preceding artifact history" }
                check(artifact.getJSONObject("operation").getJSONObject("parameters").getString("keySha256") == it.keySha256) { "Checkpoint signer metadata differs" }
            }
        }
    }

    @Synchronized
    fun importPackage(input: InputStream, actor: String, expectedHash: String?, cancelled: () -> Boolean, progress: (Long) -> Unit): JSONObject {
        actorCheck(actor)
        require(expectedHash == null || expectedHash.matches(Regex("[a-f0-9]{64}"))) { "Enter a 64-character SHA-256" }
        val stage = File(context.filesDir, "EvidenceVault/staging/${uuid()}.enc")
        val aad = "echoscript-package:${stage.name}".toByteArray()
        try {
            val (hash, length) = writeEncrypted(stage, aad, input, MAX_PACKAGE_BYTES, cancelled, progress)
            check(expectedHash == null || hash == expectedHash) { "Package differs from the independently supplied hash" }
            Files.newByteChannel(stage.toPath()).use { physical ->
                primitive.newSeekableDecryptingChannel(physical, aad).use { channel ->
                    preflightArchive(channel, length)
                    ZipFile.builder().setSeekableByteChannel(channel).get().use { zip ->
                        val entries = linkedMapOf<String, org.apache.commons.compress.archivers.zip.ZipArchiveEntry>()
                        var expanded = 0L
                        for (entry in zip.entries.asSequence()) {
                            require(!entry.isDirectory && !entry.isUnixSymlink && entry.method in listOf(0, 8) && zip.canReadEntryData(entry)) { "Unsupported ZIP entry" }
                            require(entry.name.length <= 128 && entries.put(entry.name, entry) == null) { "Duplicate or oversized ZIP path" }
                            require(entry.size in 1..MAX_SOURCE_BYTES && entry.compressedSize in 0..length)
                            expanded += entry.size
                            require(expanded <= minOf(MAX_PACKAGE_BYTES, length * 100)) { "Case expansion exceeds the safety limit" }
                        }
                        fun metadata(name: String, limit: Long): ByteArray {
                            val entry = entries[name] ?: error("Package is missing $name")
                            require(entry.size in 1..limit) { "Package metadata exceeds its limit" }
                            return readArchiveEntry(zip, entry, entry.size, cancelled) { it.readBytes() }
                        }
                        val bytes = metadata("manifest.json", MAX_MANIFEST_BYTES)
                        val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
                        check(canonical(manifest).contentEquals(bytes)) { "Case manifest is not canonical" }
                        val artifacts = checkedInventory(manifest)
                        val allowed = artifacts.map { it.getString("path") }.toSet() + setOf("manifest.json", "SHA256SUMS.txt", "README.txt")
                        require(entries.keys.all { it in allowed } && artifacts.all { entries.containsKey(it.getString("path")) }) { "Case package inventory mismatch" }
                        entries["README.txt"]?.let { metadata("README.txt", 65536) }
                        val checksums = metadata("SHA256SUMS.txt", 2L * 1024 * 1024).toString(Charsets.UTF_8)
                        val expectedSums = (artifacts.map { "${it.getString("sha256")}  ${it.getString("path")}" } + "${sha(bytes)}  manifest.json").joinToString("\n", postfix = "\n")
                        check(checksums == expectedSums) { "Package checksum sidecar mismatch" }
                        val caseId = manifest.getString("caseId")
                        val folder = caseDir(caseId)
                        check(folder.mkdir()) { "This case already exists on the device. No local history was replaced." }
                        var committed = false
                        try {
                            var installed = 0L
                            for (artifact in artifacts) {
                                val id = artifact.getString("id")
                                val entry = entries.getValue(artifact.getString("path"))
                                val (result, _) = readArchiveEntry(zip, entry, artifact.getLong("bytes"), cancelled) { decoded ->
                                    writeEncrypted(encryptedFile(caseId, id), associated(caseId, id), decoded, artifact.getLong("bytes"), cancelled) { progress(length + installed + it) }
                                }
                                check(result == artifact.getString("sha256")) { "Imported artifact hash mismatch" }
                                installed += entry.size
                            }
                            verifyCheckpoints(manifest, cancelled)
                            if (cancelled()) throw InterruptedException("Case import cancelled")
                            appendAudit(manifest, actor, "CASE_IMPORT", JSONObject().put("packageSha256", hash).put("trustedExternalHash", expectedHash != null))
                            saveSnapshot(manifest); committed = true
                            return manifest
                        } finally { if (!committed) folder.deleteRecursively() }
                    }
                }
            }
        } finally { stage.delete() }
    }

    @Synchronized
    fun selectRevision(manifest: JSONObject, actor: String, id: String) {
        actorCheck(actor)
        val next = JSONObject(manifest.toString())
        val artifacts = next.getJSONArray("artifacts")
        require((0 until artifacts.length()).any { artifacts.getJSONObject(it).getString("id") == id && artifacts.getJSONObject(it).getString("type") in setOf("SOURCE", "DECODE", "ENHANCED_AUDIO") })
        check(latestRevision(next.getString("caseId")) == next.getJSONArray("audit").length()) { "Case history changed. Reopen the case." }
        val previous = next.getString("activeArtifact")
        next.put("activeArtifact", id)
        appendAudit(next, actor, "REVISION_SELECT", JSONObject().put("previous", previous).put("active", id))
        saveSnapshot(next); replaceManifest(manifest, next)
    }
    @Synchronized
    fun recordEvent(manifest: JSONObject, actor: String, action: String, details: JSONObject) {
        actorCheck(actor)
        require(action in setOf("PLAYBACK_REQUEST", "VERIFICATION", "ANNOTATION", "BOOKMARK", "LOCAL_TRANSCRIPTION", "AUDIO_SUBMISSION_APPROVED", "AUDIO_CHUNK_SUBMISSION", "WORKLOAD_UPDATE", "REVISION_SELECT", "EXPORT_ARTIFACT", "PROTECTED_TRANSFER_EXPORT", "PROTECTED_TRANSFER_IMPORT"))
        val next = JSONObject(manifest.toString())
        check(latestRevision(next.getString("caseId")) == next.getJSONArray("audit").length()) { "Case history changed. Reopen the case." }
        appendAudit(next, actor, action, details); saveSnapshot(next); replaceManifest(manifest, next)
    }
    @Synchronized
    fun exportPackage(manifest: JSONObject, destination: OutputStream, actor: String, cancelled: () -> Boolean = { false }): String {
        require(actor.isNotBlank() && actor.length <= 128 && actor.none { it.code < 32 })
        verify(manifest, cancelled); appendAudit(manifest, actor, "EXPORT_PREPARE", JSONObject().put("format", "org.echoscript.case/1")); saveSnapshot(manifest)
        val bytes = canonical(manifest); val sums = StringBuilder(); val caseId = manifest.getString("caseId")
        val packageDigest = MessageDigest.getInstance("SHA-256")
        ZipOutputStream(DigestOutputStream(destination, packageDigest)).use { zip ->
            zip.setLevel(0); val artifacts = manifest.getJSONArray("artifacts")
            for (i in 0 until artifacts.length()) { val a = artifacts.getJSONObject(i); zip.putNextEntry(ZipEntry(a.getString("path"))); open(caseId, a.getString("id")).use { copyBounded(it, zip, cancelled) }; zip.closeEntry(); sums.append(a.getString("sha256")).append("  ").append(a.getString("path")).append('\n') }
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(bytes); zip.closeEntry(); sums.append(sha(bytes)).append("  manifest.json\n")
            zip.putNextEntry(ZipEntry("SHA256SUMS.txt")); zip.write(sums.toString().toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt")); zip.write("EchoScript case format 1. This ZIP layer is plaintext; it may be inside a protected transfer container. Device-signed checkpoint artifacts, when present, cover stated historical prefixes only. Verify signatures and independently trust public-key fingerprints. Preserve the package SHA-256 through a trusted channel. Technical validation does not establish legal admissibility.\n".toByteArray()); zip.closeEntry()
        }
        val packageHash = packageDigest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        appendAudit(manifest, actor, "EXPORT_COMPLETE", JSONObject().put("format", "org.echoscript.case/1").put("packageSha256", packageHash)); saveSnapshot(manifest)
        return packageHash
    }
}
