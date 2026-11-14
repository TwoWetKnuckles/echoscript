package org.echoscript.app

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

internal val JOB_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
internal enum class LocalJobKind { RENDER, TRANSCRIBE, RENDER_TRANSCRIBE, CLEARVOICE, CLEARVOICE_TRANSCRIBE }
internal enum class JobState { QUEUED, RUNNING, PAUSED, SUCCEEDED, FAILED, CANCELLED;
    val terminal get() = this in setOf(SUCCEEDED, FAILED, CANCELLED)
}

/** Only local operations are serializable. Origins, credentials and remote approval are never queued. */
internal data class ProcessingPlan(
    val id: String,
    val rootId: String,
    val createdAtUtc: String,
    val caseId: String,
    val baseId: String,
    val baseSha256: String,
    val actor: String,
    val kind: LocalJobKind,
    val regions: List<RenderSettings> = emptyList(),
    val clearVoice: ClearVoicePlacement? = null,
    val requestedProvider: String = "NONE",
    val softwareVersion: String = BuildConfig.VERSION_NAME,
    val modelHash: String = CLEARVOICE_ONNX_HASH,
) {
    init {
        require(listOf(id, rootId, caseId, baseId).all(JOB_ID::matches)) { "Invalid local job identity" }
        require(baseSha256.matches(Regex("[a-f0-9]{64}")))
        require(actor.isNotBlank() && actor.length <= 128 && actor.none { it.code < 32 })
        require(createdAtUtc.length in 20..40 && runCatching { java.time.Instant.parse(createdAtUtc) }.isSuccess)
        require(softwareVersion.length in 1..128 && modelHash.matches(Regex("[a-f0-9]{64}")))
        require(requestedProvider in setOf("NONE", "VOSK", "WHISPER"))
        val renders = kind in setOf(LocalJobKind.RENDER, LocalJobKind.RENDER_TRANSCRIBE)
        require(if (renders) regions.size in 1..32 else regions.isEmpty())
        require(renders || clearVoice == null)
        require(kind !in setOf(LocalJobKind.TRANSCRIBE, LocalJobKind.RENDER_TRANSCRIBE, LocalJobKind.CLEARVOICE_TRANSCRIBE) || requestedProvider == "VOSK") {
            "Remote transcription cannot run as an unattended local job"
        }
        require(regions.all { (it.workbenchPlan?.length ?: 0) <= 65536 })
    }
    fun body(): JSONObject = JSONObject().put("caseId", caseId).put("baseId", baseId).put("baseSha256", baseSha256)
        .put("actor", actor).put("kind", kind.name).put("regions", JSONArray(regions.map(::renderSettingsJson)))
        .put("clearVoice", clearVoice?.name ?: JSONObject.NULL).put("requestedProvider", requestedProvider)
        .put("softwareVersion", softwareVersion).put("modelHash", modelHash)
        .put("speechModel", SPEECH_MODEL).put("speakerModel", SPEAKER_MODEL).put("speechRuntime", VOSK_RUNTIME)
    val requestSha256 get() = EvidenceVault.sha(EvidenceVault.canonical(body()))
    fun json(): JSONObject = JSONObject().put("schema", "org.echoscript.local-job/1").put("id", id).put("rootId", rootId)
        .put("createdAtUtc", createdAtUtc).put("request", body()).put("requestSha256", requestSha256)
    fun retry(requestedActor: String): ProcessingPlan {
        require(requestedActor == actor) { "The saved job uses a different examiner identifier. Review its case and start a new job with your current identifier." }
        return copy(id = UUID.randomUUID().toString(), createdAtUtc = EvidenceVault.utc())
    }
    fun checkRuntime() {
        require(softwareVersion == BuildConfig.VERSION_NAME && modelHash == CLEARVOICE_ONNX_HASH) {
            "This unfinished job uses another software/model version. Review its saved case and start a new job with current settings."
        }
    }
    companion object {
        fun parse(value: JSONObject): ProcessingPlan {
            require(value.keys().asSequence().toSet() == setOf("schema", "id", "rootId", "createdAtUtc", "request", "requestSha256"))
            require(value.getString("schema") == "org.echoscript.local-job/1")
            val body = value.getJSONObject("request")
            require(EvidenceVault.sha(EvidenceVault.canonical(body)) == value.getString("requestSha256")) { "Local job settings differ from their recorded hash" }
            require(body.keys().asSequence().toSet() == setOf("caseId", "baseId", "baseSha256", "actor", "kind", "regions", "clearVoice", "requestedProvider", "softwareVersion", "modelHash", "speechModel", "speakerModel", "speechRuntime"))
            require(body.getString("speechModel") == SPEECH_MODEL && body.getString("speakerModel") == SPEAKER_MODEL && body.getString("speechRuntime") == VOSK_RUNTIME)
            val rows = body.getJSONArray("regions"); require(rows.length() <= 32)
            val plan = ProcessingPlan(value.getString("id"), value.getString("rootId"), value.getString("createdAtUtc"),
                body.getString("caseId"), body.getString("baseId"), body.getString("baseSha256"), body.getString("actor"),
                LocalJobKind.valueOf(body.getString("kind")), (0 until rows.length()).map { parseRenderSettings(rows.getJSONObject(it)) },
                if (body.isNull("clearVoice")) null else ClearVoicePlacement.valueOf(body.getString("clearVoice")),
                body.getString("requestedProvider"), body.getString("softwareVersion"), body.getString("modelHash"))
            require(EvidenceVault.canonical(plan.body()).contentEquals(EvidenceVault.canonical(body))) { "Local job settings contain ambiguous values" }
            require(plan.requestSha256 == value.getString("requestSha256")) { "Local job settings differ from their recorded hash" }
            return plan
        }
    }
}

internal fun renderSettingsJson(s: RenderSettings): JSONObject = JSONObject()
    .put("rack", s.rack?.json() ?: JSONObject.NULL).put("trim", s.trim).put("workbenchPlan", s.workbenchPlan ?: JSONObject.NULL)
    .put("gainDb", s.gainDb).put("lowDb", s.lowDb).put("midDb", s.midDb).put("highDb", s.highDb)
    .put("highPassHz", s.highPassHz).put("lowPassHz", s.lowPassHz).put("humHz", s.humHz).put("notchHz", s.notchHz)
    .put("notchQ", s.notchQ).put("noiseFloorDb", s.noiseFloorDb ?: JSONObject.NULL).put("noiseReductionDb", s.noiseReductionDb)
    .put("limit", s.limit).put("startMs", s.startMs).put("endMs", s.endMs ?: JSONObject.NULL)

internal fun parseRenderSettings(value: JSONObject): RenderSettings {
    require(value.keys().asSequence().toSet() == setOf("rack", "trim", "workbenchPlan", "gainDb", "lowDb", "midDb", "highDb", "highPassHz", "lowPassHz", "humHz", "notchHz", "notchQ", "noiseFloorDb", "noiseReductionDb", "limit", "startMs", "endMs"))
    require(value.opt("trim") is Boolean && value.opt("limit") is Boolean)
    return RenderSettings(if (value.isNull("rack")) null else EffectRack.fromJson(value.getJSONObject("rack")), value.getBoolean("trim"),
        if (value.isNull("workbenchPlan")) null else value.getString("workbenchPlan"), value.getDouble("gainDb"), value.getDouble("lowDb"),
        value.getDouble("midDb"), value.getDouble("highDb"), value.getDouble("highPassHz"), value.getDouble("lowPassHz"), value.getDouble("humHz"),
        value.getDouble("notchHz"), value.getDouble("notchQ"), if (value.isNull("noiseFloorDb")) null else value.getDouble("noiseFloorDb"),
        value.getDouble("noiseReductionDb"), value.getBoolean("limit"), value.getLong("startMs"), if (value.isNull("endMs")) null else value.getLong("endMs"))
}

/** A completed pass is reused only after its immutable bytes and request linkage verify. */
internal class ProcessingContext(val plan: ProcessingPlan, private val vault: EvidenceVault, private val cancelled: () -> Boolean, val attempt: Int = 1) {
    fun step(name: String) = ProcessingStep(this, name)
    fun reuse(manifest: JSONObject, name: String, type: String, algorithm: String): JSONObject? {
        require(manifest.getString("caseId") == plan.caseId)
        val matches = manifest.items("artifacts").filter { artifact ->
            val tag = artifact.operationParameters().optJSONObject("processingJob")
            tag != null && tag.optString("id") == plan.rootId && tag.optString("requestSha256") == plan.requestSha256 && tag.optString("step") == name
        }
        check(matches.size <= 1) { "More than one completed output claims this job pass. Preserve the case for review." }
        val artifact = matches.singleOrNull() ?: return null
        val operation = artifact.getJSONObject("operation")
        check(artifact.getString("type") == type && operation.getString("algorithm") == algorithm &&
            operation.getString("version") == if (algorithm == "bounded-native-effect-rack") "3" else "1") { "Saved job output has unexpected provenance" }
        val visited = mutableSetOf<String>()
        val pending = java.util.ArrayDeque<String>().apply { add(artifact.getString("id")) }
        while (pending.isNotEmpty()) {
            val ancestor = pending.removeFirst()
            if (!visited.add(ancestor)) continue
            val parents = manifest.artifact(ancestor).getJSONArray("parents")
            for (index in 0 until parents.length()) pending.add(parents.getString(index))
        }
        check(plan.baseId in visited) { "Saved job output does not descend from the recorded input" }
        val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
        vault.open(plan.caseId, artifact.getString("id")).use { input ->
            val block = ByteArray(PCM_BLOCK_BYTES)
            while (true) {
                if (cancelled()) throw InterruptedException("Job cancelled")
                val n = input.read(block); if (n < 0) break
                count += n; require(count <= artifact.getLong("bytes")); digest.update(block, 0, n)
            }
        }
        check(count == artifact.getLong("bytes") && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == artifact.getString("sha256")) { "Saved job output failed its hash check" }
        return artifact
    }
}

internal class ProcessingStep(private val context: ProcessingContext, private val name: String) {
    init { require(name.matches(Regex("[a-z0-9.-]{1,80}"))) }
    fun child(suffix: String) = context.step("$name.$suffix")
    fun reuse(manifest: JSONObject, type: String, algorithm: String) = context.reuse(manifest, name, type, algorithm)
    fun attach(parameters: JSONObject): JSONObject = parameters.put("processingJob", JSONObject()
        .put("id", context.plan.rootId).put("attemptId", context.plan.id).put("attempt", context.attempt).put("requestSha256", context.plan.requestSha256).put("step", name))
}

internal fun JSONObject.forProcessing(step: ProcessingStep?): JSONObject = step?.attach(this) ?: this
