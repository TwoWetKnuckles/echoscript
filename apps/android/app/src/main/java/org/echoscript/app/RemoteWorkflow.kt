package org.echoscript.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

internal data class RemoteBatch(val caseId: String, val audioId: String, val canonical: JSONObject, val chunks: List<JSONObject>,
    val approval: JSONObject, val config: CompanionConfig, val provider: String, val model: String) {
    val duration get() = (canonical.getLong("bytes") - 44) / 32000.0
}

/** All requests are committed and hashed in the encrypted vault before approval. */
internal class RemoteWorkflow(private val vault: EvidenceVault, private val workflow: OfflineWorkflow) {
    fun prepare(manifest: JSONObject, actor: String, config: CompanionConfig, provider: String, cancelled: () -> Boolean, progress: (Long) -> Unit): RemoteBatch {
        require(provider == "openai-whisper")
        val audio = workflow.prepareAudio(manifest, actor, cancelled, progress)
        val audioId = audio.getString("id"); val caseId = manifest.getString("caseId")
        val model = "whisper-1"
        val canonical = manifest.items("artifacts").lastOrNull {
            it.getJSONObject("operation").getString("algorithm") == "companion-mono-resample-pcm16" &&
                it.getJSONArray("parents").optString(0) == audioId && it.operationParameters().optString("provider") == provider
        } ?: vault.addDerivative(manifest, actor, "TRANSCRIPTION_TRANSMISSION",
            listOf(audioId), "Prepared companion input.wav", "companion-mono-resample-pcm16", "1") { out ->
            vault.open(caseId, audioId).use { stream -> WaveReader(stream, audio.getLong("bytes")).use { wave ->
                val frames = recognitionWave(wave, out, cancelled, progress)
                JSONObject().put("sampleRate", 16000).put("channels", 1).put("frames", frames).put("provider", provider)
                    .put("resampling", "linear with low-pass on downsampling").put("transport", "CHUNKED_ONLY_AFTER_BATCH_APPROVAL")
            } }
        }
        val chunks = mutableListOf<JSONObject>()
        vault.open(caseId, canonical.getString("id")).use { stream -> WaveReader(stream, canonical.getLong("bytes")).use { wave ->
            val buffer = ByteArray(30 * 32000); var offset = 0L
            while (offset < wave.frames) {
                if (cancelled()) throw InterruptedException("Preparation cancelled; no audio has been sent")
                val length = wave.read(buffer)
                val start = offset; val frames = length / 2
                val existing = manifest.items("artifacts").lastOrNull {
                    it.getJSONObject("operation").getString("algorithm") == "companion-exact-pcm-chunk" &&
                        it.getJSONArray("parents").optString(0) == canonical.getString("id") && it.operationParameters().optLong("startFrame", -1) == start
                }
                val chunk = existing ?: vault.addDerivative(manifest, actor, "TRANSCRIPTION_TRANSMISSION",
                    listOf(canonical.getString("id")), "Prepared audio chunk ${chunks.size + 1}.wav", "companion-exact-pcm-chunk", "1", maxBytes = buffer.size + 44L) { out ->
                    out.write(waveHeader(PcmFormat(16000, 1), length.toLong())); out.write(buffer, 0, length)
                    JSONObject().put("startFrame", start).put("frames", frames).put("provider", provider).put("model", model)
                }
                chunks.add(chunk); offset += frames; progress(offset)
            }
        } }
        val payload = JSONObject().put("purpose", provider).put("model", model).put("endpoint", config.origin)
            .put("audioArtifactId", audioId).put("canonicalHash", canonical.getString("sha256"))
            .put("chunks", JSONArray(chunks.map { JSONObject().put("artifactId", it.getString("id")).put("sha256", it.getString("sha256"))
                .put("bytes", it.getLong("bytes")).put("startFrame", it.operationParameters().getLong("startFrame")).put("frames", it.operationParameters().getLong("frames")) }))
        val approval = vault.addDerivative(manifest, actor, "REPORT", listOf(canonical.getString("id")), "Audio submission manifest.json", "companion-batch-manifest", "1",
            maxBytes = 1024 * 1024) { out -> out.write(EvidenceVault.canonical(payload)); JSONObject().put("provider", provider).put("model", model).put("endpoint", config.origin) }
        return RemoteBatch(caseId, audioId, canonical, chunks, approval, config, provider, model)
    }

    fun execute(manifest: JSONObject, actor: String, batch: RemoteBatch, cancelled: () -> Boolean, progress: (Long) -> Unit): JSONObject {
        require(manifest.getString("caseId") == batch.caseId && manifest.getString("activeArtifact") == batch.audioId) { "Active audio changed; prepare a new submission" }
        require(batch.chunks.isNotEmpty() && batch.approval.getString("sha256") == manifest.artifact(batch.approval.getString("id")).getString("sha256"))
        vault.recordEvent(manifest, actor, "AUDIO_SUBMISSION_APPROVED", JSONObject().put("manifestId", batch.approval.getString("id"))
            .put("manifestHash", batch.approval.getString("sha256")).put("provider", batch.provider).put("model", batch.model).put("endpoint", batch.config.origin))
        val client = CompanionClient(batch.config, cancelled)
        val results = mutableListOf<JSONObject>(); val segments = JSONArray()
        for ((index, chunk) in batch.chunks.withIndex()) {
            if (cancelled()) throw InterruptedException("Processing cancelled; completed results remain for an approved retry")
            val old = manifest.items("artifacts").lastOrNull {
                it.getString("type") == "RAW_PROVIDER_RESULT" && it.getJSONArray("parents").optString(0) == chunk.getString("id") &&
                    it.operationParameters().optString("provider") == batch.provider && it.operationParameters().optString("endpoint") == batch.config.origin
            }
            val result = old ?: run {
                val input = readArtifact(manifest, chunk, 30 * 32000 + 44)
                require(EvidenceVault.sha(input) == chunk.getString("sha256")) { "Prepared payload hash mismatch" }
                vault.recordEvent(manifest, actor, "AUDIO_CHUNK_SUBMISSION", JSONObject().put("artifactId", chunk.getString("id")).put("sha256", chunk.getString("sha256"))
                    .put("bytes", input.size).put("provider", batch.provider).put("model", batch.model).put("approvalId", batch.approval.getString("id")))
                val response = client.submit(batch.provider, batch.model, input)
                val details = JSONObject().put("provider", batch.provider).put("model", batch.model).put("endpoint", batch.config.origin)
                    .put("inputHash", chunk.getString("sha256")).put("submissionManifestId", batch.approval.getString("id"))
                vault.addDerivative(manifest, actor, "RAW_PROVIDER_RESULT", listOf(chunk.getString("id")),
                    "Whisper chunk ${index + 1}.json",
                    "companion-${batch.provider}-result", "1", maxBytes = 8L * 1024 * 1024) { out -> out.write(response); details }
            }
            results.add(result)
            val raw = JSONObject(readArtifact(manifest, result, 8 * 1024 * 1024).toString(Charsets.UTF_8))
            appendWhisperSegments(segments, raw, chunk.operationParameters().getLong("startFrame") / 16000.0,
                chunk.operationParameters().getLong("frames") / 16000.0)
            progress(index + 1L)
        }
        if (cancelled()) throw InterruptedException("Processing cancelled")
        val transcript = JSONObject().put("schema", "org.echoscript.transcript/1").put("state", "AI_DRAFT").put("provider", "openai-whisper")
            .put("model", "whisper-1").put("language", "provider-detected").put("audioArtifactId", batch.audioId).put("duration", batch.duration)
            .put("transmissionHash", batch.canonical.getString("sha256")).put("submissionManifestId", batch.approval.getString("id"))
            .put("diarization", JSONObject().put("supported", false)).put("segments", segments)
            .put("warnings", JSONArray(listOf("Unverified machine transcription; review against the audio.", "Whisper provides no confidence, N-best or speaker identification here.", "Independent 30-second requests can split speech; timestamps refer to the transmitted mono 16 kHz render.")))
        return vault.addDerivative(manifest, actor, "NORMALIZED_TRANSCRIPT", results.map { it.getString("id") }, "Whisper draft transcript.json", "native-whisper-normalization", "1", maxBytes = 16L * 1024 * 1024) { out ->
            out.write(EvidenceVault.canonical(transcript)); JSONObject().put("audioArtifactId", batch.audioId).put("state", "AI_DRAFT").put("provider", batch.provider)
        }
    }

    private fun readArtifact(manifest: JSONObject, artifact: JSONObject, limit: Int): ByteArray {
        require(artifact.getLong("bytes") <= limit)
        val result = ByteArrayOutputStream()
        vault.open(manifest.getString("caseId"), artifact.getString("id")).use { input ->
            val block = ByteArray(PCM_BLOCK_BYTES)
            while (true) { val count = input.read(block); if (count < 0) break; require(result.size() + count <= limit); result.write(block, 0, count) }
        }
        return result.toByteArray().also { require(it.size.toLong() == artifact.getLong("bytes") && EvidenceVault.sha(it) == artifact.getString("sha256")) { "Stored provider artifact hash mismatch" } }
    }
}

internal fun appendWhisperSegments(output: JSONArray, raw: JSONObject, offset: Double, duration: Double) {
    require(raw.opt("text") is String)
    val supplied = raw.optJSONArray("segments")
    val providedWords = raw.optJSONArray("words")
    val wordSpan = providedWords != null && providedWords.length() > 0
    val entries = if (supplied != null && supplied.length() > 0) (0 until supplied.length()).map(supplied::getJSONObject)
        else if (raw.getString("text").isBlank()) emptyList() else listOf(JSONObject().put("text", raw.getString("text"))
            .put("start", if (wordSpan) providedWords!!.getJSONObject(0).getDouble("start") else 0.0)
            .put("end", if (wordSpan) providedWords!!.getJSONObject(providedWords.length() - 1).getDouble("end") else duration))
    for (entry in entries) {
        require(output.length() < 10000)
        val text = entry.getString("text"); require(text.length <= 10000 && text.none { it.code < 32 && it !in "\n\r\t" })
        val start = entry.getDouble("start"); val end = entry.getDouble("end"); require(start.isFinite() && end.isFinite() && kotlin.math.abs(start) <= 365 * 86400 && kotlin.math.abs(end) <= 365 * 86400)
        val flags = JSONArray()
        if (start < 0 || end < start || end > duration + 0.1) flags.put("PROVIDER_TIMESTAMP_ANOMALY")
        if (supplied == null || supplied.length() == 0) flags.put(if (wordSpan) "SEGMENT_FROM_RETURNED_WORD_TIMES" else "CHUNK_BOUNDARY_TIMING_ONLY")
        val words = JSONArray(); val suppliedWords = raw.optJSONArray("words")
        if (suppliedWords != null) for (i in 0 until suppliedWords.length()) {
            val word = suppliedWords.getJSONObject(i); val a = word.getDouble("start"); val b = word.getDouble("end"); val value = word.getString("word")
            require(a.isFinite() && b.isFinite() && kotlin.math.abs(a) <= 365 * 86400 && kotlin.math.abs(b) <= 365 * 86400 && value.length <= 256 && value.none { it.code < 32 })
            if (a < 0 || b < a || b > duration + 0.1) { if (!(0 until flags.length()).any { flags.getString(it) == "PROVIDER_TIMESTAMP_ANOMALY" }) flags.put("PROVIDER_TIMESTAMP_ANOMALY") }
            if (a >= start && a < end) words.put(JSONObject().put("text", value).put("start", offset + a).put("end", offset + b).put("confidence", JSONObject.NULL))
        }
        output.put(JSONObject().put("id", UUID.randomUUID().toString()).put("text", text).put("start", offset + start).put("end", offset + end)
            .put("speaker", JSONObject.NULL).put("speakerLabel", "").put("speakerLabelSource", "UNAVAILABLE").put("confidence", JSONObject.NULL)
            .put("alternatives", JSONArray()).put("words", words).put("flags", flags).put("verification", "UNVERIFIED"))
    }
}
