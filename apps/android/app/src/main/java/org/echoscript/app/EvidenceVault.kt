package org.echoscript.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.integration.android.AndroidKeysetManager
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
import java.util.zip.ZipOutputStream
import javax.crypto.KeyGenerator

data class VaultListing(val cases: List<JSONObject>, val warnings: List<String>)
class EvidenceVault(private val context: Context) {
    companion object {
        const val MAX_SOURCE_BYTES = 4L * 1024 * 1024 * 1024
        private const val ALIAS = "echoscript.vault.master.v1"
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
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            generator.generateKey()
        }
        check(ks.getKey(ALIAS, null) != null) { "Android Keystore unavailable; vault cannot open" }
        StreamingAeadConfig.register()
        val manager = AndroidKeysetManager.Builder().withSharedPref(context, "stream_keyset", "echoscript_keysets")
            .withKeyTemplate(StreamingAeadKeyTemplates.AES256_GCM_HKDF_1MB).withMasterKeyUri("android-keystore://$ALIAS").build()
        primitive = manager.keysetHandle.getPrimitive(StreamingAead::class.java)
    }
    private fun caseDir(caseId: String): File { require(ID.matches(caseId)); return File(root, caseId) }
    private fun associated(caseId: String, artifactId: String) = "$caseId:$artifactId".toByteArray(Charsets.UTF_8)
    private fun encryptedFile(caseId: String, artifactId: String): File { require(ID.matches(artifactId)); return File(caseDir(caseId), "artifacts/$artifactId.enc") }
    private fun writeEncrypted(file: File, aad: ByteArray, source: InputStream, max: Long, cancelled: () -> Boolean, progress: (Long) -> Unit): Pair<String, Long> {
        check(!file.exists()) { "Immutable artifact already exists" }; file.parentFile!!.mkdirs()
        val temp = File(file.parentFile, ".${uuid()}.pending")
        val hash = MessageDigest.getInstance("SHA-256"); var count = 0L
        try {
            FileOutputStream(temp).use { physical ->
                primitive.newEncryptingStream(physical, aad).use { sealed ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw InterruptedException("Acquisition cancelled")
                        val n = source.read(buffer); if (n < 0) break
                        count += n; require(count <= max) { "File exceeds the 4 GiB evidence policy" }
                        hash.update(buffer, 0, n); sealed.write(buffer, 0, n); progress(count)
                    }
                }
            }
            check(count > 0) { "Empty evidence is not accepted" }
            FileOutputStream(temp, true).use { it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } to count
        } catch (failure: Exception) { temp.delete(); throw failure }
    }
    @Synchronized
    fun importSource(input: InputStream, filename: String, title: String, actor: String, cancelled: () -> Boolean = { false }, progress: (Long) -> Unit = {}): JSONObject {
        require(filename.isNotBlank() && filename.length <= 256 && filename.none { it.code < 32 })
        require(title.isNotBlank() && title.length <= 256 && title.none { it.code < 32 })
        require(actor.isNotBlank() && actor.length <= 128 && actor.none { it.code < 32 })
        val caseId = uuid(); val artifactId = uuid(); val created = utc(); val folder = caseDir(caseId); folder.mkdirs()
        val result = writeEncrypted(encryptedFile(caseId, artifactId), associated(caseId, artifactId), input, MAX_SOURCE_BYTES, cancelled, progress)
        val manifest = JSONObject().put("format", "org.echoscript.case/1").put("schemaVersion", 1).put("caseId", caseId).put("title", title).put("createdAtUtc", created)
            .put("software", JSONObject().put("name", "EchoScript").put("version", "0.2.0-dev")).put("activeArtifact", artifactId)
            .put("artifacts", JSONArray()).put("audit", JSONArray())
        appendAudit(manifest, actor, "CASE_CREATE", JSONObject())
        val artifact = JSONObject().put("id", artifactId).put("caseId", caseId).put("type", "SOURCE").put("parents", JSONArray()).put("name", filename)
            .put("path", "artifacts/$artifactId").put("bytes", result.second).put("sha256", result.first).put("timestampUtc", utc()).put("actor", actor).put("revision", 1)
            .put("softwareVersion", "0.2.0-dev").put("operation", JSONObject().put("algorithm", "android-saf-hash-before-decode").put("version", "1").put("parameters", JSONObject().put("keyStorage", "Android Keystore").put("encryption", "Tink AES256_GCM_HKDF_1MB"))).put("warnings", JSONArray().put("Operator identity is locally supplied, not externally authenticated."))
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
        writeEncrypted(file, "$caseId:manifest:$revision".toByteArray(), canonical(manifest).inputStream(), 8L * 1024 * 1024, { false }, {})
    }
    fun listCases(): VaultListing {
      val warnings = mutableListOf<String>()
      val cases = root.listFiles()?.filter { it.isDirectory && ID.matches(it.name) }?.mapNotNull { folder ->
        val file = File(folder, "manifests").listFiles()?.filter { it.extension == "enc" }?.maxByOrNull { it.name } ?: return@mapNotNull null
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
    @Synchronized
    fun verify(manifest: JSONObject): String {
        check(manifest.getString("format") == "org.echoscript.case/1" && manifest.getInt("schemaVersion") == 1 && ID.matches(manifest.getString("caseId"))) { "Unsupported case manifest" }
        val chain = manifest.getJSONArray("audit"); var previous = "0".repeat(64)
        val commitments = mutableMapOf<String,String>()
        for (i in 0 until chain.length()) {
            val entry = JSONObject(chain.getJSONObject(i).toString()); val expected = entry.getString("entryHash"); entry.remove("entryHash")
            check(entry.getInt("seq") == i + 1 && entry.getString("prevHash") == previous && sha(canonical(entry)) == expected) { "Audit linkage mismatch" }; previous = expected
            if (entry.getString("action") == "ARTIFACT_COMMIT") { val details = entry.getJSONObject("details"); val id = details.getString("artifactId"); check(!commitments.containsKey(id)) { "Duplicate artifact commitment" }; commitments[id] = details.getString("sha256") }
        }
        val artifacts = manifest.getJSONArray("artifacts")
        check(artifacts.length() in 1..10000) { "Invalid artifact inventory" }
        val seen = mutableSetOf<String>(); var sources = 0
        for (i in 0 until artifacts.length()) {
            val artifact = artifacts.getJSONObject(i); val hash = MessageDigest.getInstance("SHA-256"); var count = 0L
            val id = artifact.getString("id"); val parents = artifact.getJSONArray("parents"); val parentIds = (0 until parents.length()).map { parents.getString(it) }
            check(ID.matches(id) && id !in seen && artifact.getString("caseId") == manifest.getString("caseId") && artifact.getString("path") == "artifacts/$id" && artifact.getInt("revision") == i + 1) { "Artifact identifier/revision mismatch" }
            check(parentIds.distinct().size == parentIds.size && parentIds.all { it in seen }) { "Invalid provenance parent" }
            if (artifact.getString("type") == "SOURCE") { sources++; check(parentIds.isEmpty()) { "Source must not have parents" } } else check(parentIds.isNotEmpty()) { "Derivative lacks a parent" }
            check(commitments[id] == artifact.getString("sha256")) { "Missing artifact audit commitment" }
            open(manifest.getString("caseId"), artifact.getString("id")).use { stream -> val buffer = ByteArray(65536); while (true) { val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n); count += n } }
            check(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == artifact.getString("sha256") && count == artifact.getLong("bytes")) { "Artifact integrity mismatch" }
            seen.add(id)
        }
        check(sources == 1 && manifest.getString("activeArtifact") in seen) { "Source or active artifact invariant failed" }
        return "Source hashes, authenticated decryption and audit linkage agree. Signatures and external authenticity are not verified."
    }
    @Synchronized
    fun recordEvent(manifest: JSONObject, actor: String, action: String, details: JSONObject) {
        require(actor.isNotBlank() && actor.length <= 128 && actor.none { it.code < 32 })
        require(action in setOf("PLAYBACK_REQUEST", "VERIFICATION", "ANNOTATION", "BOOKMARK"))
        appendAudit(manifest, actor, action, details); saveSnapshot(manifest)
    }
    @Synchronized
    fun exportPackage(manifest: JSONObject, destination: OutputStream, actor: String): String {
        require(actor.isNotBlank() && actor.length <= 128 && actor.none { it.code < 32 })
        verify(manifest); appendAudit(manifest, actor, "EXPORT_PREPARE", JSONObject().put("format", "org.echoscript.case/1")); saveSnapshot(manifest)
        val bytes = canonical(manifest); val sums = StringBuilder(); val caseId = manifest.getString("caseId")
        val packageDigest = MessageDigest.getInstance("SHA-256")
        ZipOutputStream(DigestOutputStream(destination, packageDigest)).use { zip ->
            zip.setLevel(0); val artifacts = manifest.getJSONArray("artifacts")
            for (i in 0 until artifacts.length()) { val a = artifacts.getJSONObject(i); zip.putNextEntry(ZipEntry(a.getString("path"))); open(caseId, a.getString("id")).use { it.copyTo(zip, 65536) }; zip.closeEntry(); sums.append(a.getString("sha256")).append("  ").append(a.getString("path")).append('\n') }
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(bytes); zip.closeEntry(); sums.append(sha(bytes)).append("  manifest.json\n")
            zip.putNextEntry(ZipEntry("SHA256SUMS.txt")); zip.write(sums.toString().toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt")); zip.write("EchoScript case format 1. This package is unencrypted and unsigned. Preserve its SHA-256 independently. Technical integrity does not establish legal admissibility.\n".toByteArray()); zip.closeEntry()
        }
        val packageHash = packageDigest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        appendAudit(manifest, actor, "EXPORT_COMPLETE", JSONObject().put("format", "org.echoscript.case/1").put("packageSha256", packageHash)); saveSnapshot(manifest)
        return packageHash
    }
}
