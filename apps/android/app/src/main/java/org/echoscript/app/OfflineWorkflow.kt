package org.echoscript.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

internal fun JSONObject.items(key: String): List<JSONObject> = getJSONArray(key).let { array ->
    (0 until array.length()).map(array::getJSONObject)
}
internal fun JSONObject.artifact(id: String): JSONObject = items("artifacts").firstOrNull { it.getString("id") == id }
    ?: error("Selected artifact is missing")
internal fun JSONObject.activeAudio(): JSONObject = artifact(getString("activeArtifact"))
internal fun JSONObject.latestTranscript(): JSONObject? = items("artifacts").lastOrNull { it.getString("type") in setOf("NORMALIZED_TRANSCRIPT", "HUMAN_TRANSCRIPT") }
internal fun JSONObject.operationParameters(): JSONObject = getJSONObject("operation").getJSONObject("parameters")

// Keep the prior analysis immutable; adjustment records retain the event identity.
internal fun reviseSpeechCandidate(analysis: JSONObject, startMs: Long, endMs: Long, label: String, eventId: String?, newId: String): JSONObject {
    require(startMs >= 0 && endMs > startMs && endMs <= analysis.getLong("durationMs") && label.length <= 160)
    val result = JSONObject(analysis.toString())
    val events = result.getJSONArray("events")
    val index = if (eventId == null) -1 else (0 until events.length()).firstOrNull { events.getJSONObject(it).getString("eventId") == eventId }
        ?: error("Speech candidate is no longer present; reopen analysis")
    if (eventId == null) require(events.length() < 256) { "At most 256 candidates per analysis revision" }
    val event = JSONObject().put("eventId", eventId ?: newId).put("startMs", startMs).put("endMs", endMs)
        .put("sourceAlgorithm", if (eventId == null) "examiner-marked/1" else "examiner-adjusted/1")
        .put("label", label).put("warning", "Examiner-marked candidate; no speech probability inferred")
    if (index < 0) events.put(event) else events.put(index, event)
    return result
}

internal class OfflineWorkflow(private val context: Context, private val vault: EvidenceVault) {
    private inline fun <T> withWave(manifest: JSONObject, audio: JSONObject, block: (WaveReader) -> T): T =
        vault.open(manifest.getString("caseId"), audio.getString("id")).use { stream ->
            block(WaveReader(stream, audio.getLong("bytes")))
        }

    fun prepareAudio(manifest: JSONObject, actor: String, cancelled: () -> Boolean, progress: (Long) -> Unit): JSONObject {
        val active = manifest.activeAudio()
        if (active.getString("type") in setOf("DECODE", "ENHANCED_AUDIO")) return active
        check(active.getString("type") == "SOURCE") { "Select a source or audio revision" }
        val id = active.getString("id")
        val reusable = manifest.items("artifacts").lastOrNull {
            it.getString("type") == "DECODE" && it.getJSONArray("parents").optString(0) == id &&
                it.getJSONObject("operation").getString("algorithm") == "android-authenticated-pcm16-decode"
        }
        if (reusable != null) {
            vault.selectRevision(manifest, actor, reusable.getString("id")); return reusable
        }
        val stageBudget = minOf(MAX_WAVE_DATA, EvidenceVault.MAX_SOURCE_BYTES - 44, (context.filesDir.usableSpace - 64L * 1024 * 1024) / 2)
        require(stageBudget >= 65536) { "Insufficient private storage for a decoded derivative and encrypted staging" }
        var description: DecodedAudio? = null
        var artifact: JSONObject? = null
        vault.withEncryptedStage(stageBudget, { output ->
            description = decodeAudio(vault, manifest.getString("caseId"), active, output, cancelled, progress)
        }, { staged, pcmHash, length ->
            if (cancelled()) throw InterruptedException("Decode cancelled")
            val decoded = checkNotNull(description)
            artifact = vault.addDerivative(manifest, actor, "DECODE", listOf(id), "Decoded PCM16.wav", "android-authenticated-pcm16-decode", "1", active = true) { out ->
                out.write(waveHeader(decoded.format, length))
                copy(staged, out, cancelled)
                decoded.details.put("sampleRate", decoded.format.sampleRate).put("channels", decoded.format.channels)
                    .put("frames", length / decoded.format.frameBytes).put("pcm16Sha256", pcmHash).put("bitDepth", 16)
            }
        })
        return checkNotNull(artifact)
    }

    fun savedAnalysis(manifest: JSONObject): JSONObject? {
        val audio = manifest.getString("activeArtifact")
        val artifact = manifest.items("artifacts").lastOrNull {
            it.getString("type") == "ANALYSIS" && it.getJSONArray("parents").optString(0) == audio &&
                it.getJSONObject("operation").getString("algorithm") in setOf("bounded-envelope-spectrum-and-speech-candidates", "examiner-speech-region-edit") &&
                it.getJSONObject("operation").getString("version") == "1"
        } ?: return null
        return readJson(manifest, artifact, 1024 * 1024)
    }

    fun analyze(manifest: JSONObject, actor: String, cancelled: () -> Boolean, progress: (Long) -> Unit): JSONObject {
        val audio = prepareAudio(manifest, actor, cancelled, progress)
        val (analysis, format) = withWave(manifest, audio) { overview(it, cancelled, progress) to it.format }
        val payload = JSONObject().put("durationMs", analysis.durationMs).put("peaks", JSONArray(analysis.peaks))
            .put("spectrum", JSONArray(analysis.spectrum.map { JSONArray(it) }))
            .put("events", JSONArray(analysis.speechCandidates.mapIndexed { i, range ->
                JSONObject().put("eventId", "candidate-${i + 1}").put("startMs", range.first).put("endMs", range.last)
                    .put("sourceAlgorithm", "energy-zero-crossings/1").put("warning", "Speech candidate requires human review")
            })).put("peakDb", analysis.peakDb).put("rmsDb", analysis.rmsDb).put("clippedSamples", analysis.clippedSamples)
        vault.addDerivative(manifest, actor, "ANALYSIS", listOf(audio.getString("id")), "Audio analysis.json", "bounded-envelope-spectrum-and-speech-candidates", "1", maxBytes = 1024 * 1024) { out ->
            out.write(EvidenceVault.canonical(payload)); JSONObject().put("fftWindow", "Hann").put("fftSize", 256)
                .put("maximumTiles", 128).put("energyFrameMs", 20).put("rmsThreshold", 0.012)
                .put("zeroCrossingRateMin", 0.015).put("zeroCrossingRateMax", 0.35)
                .put("sampleRate", format.sampleRate)
        }
        return payload
    }

    fun inspectSpectrum(manifest: JSONObject, actor: String, settings: DetailSpectrumSettings,
        cancelled: () -> Boolean, progress: (Long) -> Unit): JSONObject {
        val audio = manifest.activeAudio()
        require(audio.getString("type") in setOf("DECODE", "ENHANCED_AUDIO")) { "Analyze this audio before inspecting a time range" }
        val detail = withWave(manifest, audio) { detailSpectrum(it, settings, cancelled, progress) }
        val parameters = JSONObject().put("startMs", settings.startMs).put("endMs", settings.endMs)
            .put("fftSize", settings.fftSize).put("hopSize", settings.hopSize).put("window", settings.window)
            .put("scale", settings.scale).put("floorDb", settings.floorDb).put("sampleRate", detail.sampleRate)
            .put("channels", detail.channels).put("displayBands", 128).put("channelCombination", "strongest-channel-per-bin")
            .put("windowDefinition", "symmetric-n-minus-one").put("aggregation", "peak-magnitude-per-display-band")
            .put("normalization", "window-coherent-gain-onesided-amplitude").put("displayQuantizationDb", 0.1)
            .put("lastPartialHop", "not-padded")
        val payload = JSONObject().put("audioArtifactId", audio.getString("id"))
            .put("settings", parameters).put("startFrame", detail.startFrame).put("endFrame", detail.endFrame)
            .put("analyzedThroughFrame", detail.analyzedThroughFrame).put("firstCenterFrame", detail.firstCenterFrame)
            .put("lowHz", detail.lowHz).put("highHz", detail.highHz)
            .put("peaks", JSONArray(detail.peaks)).put("columns", JSONArray(detail.columns.map { JSONArray(it) }))
        vault.addDerivative(manifest, actor, "ANALYSIS", listOf(audio.getString("id")), "Detailed spectrum.json",
            "windowed-stft-inspection", "1", warnings = listOf(
                "Display bands show peak bins from the strongest channel; they are not power spectral density or evidence of speech.",
                "Windowing limits time/frequency resolution; the final incomplete hop is not padded."
            ), maxBytes = 4L * 1024 * 1024) { out ->
            if (cancelled()) throw InterruptedException("Spectral inspection cancelled")
            out.write(EvidenceVault.canonical(payload)); parameters
        }
        return payload
    }

    fun enhance(manifest: JSONObject, actor: String, settings: RenderSettings, cancelled: () -> Boolean, progress: (Long) -> Unit): JSONObject {
        val parent = prepareAudio(manifest, actor, cancelled, progress)
        return withWave(manifest, parent) { wave ->
            require(context.filesDir.usableSpace > wave.dataBytes + 16 * 1024 * 1024) { "Insufficient private storage for another audio revision" }
            vault.addDerivative(manifest, actor, "ENHANCED_AUDIO", listOf(parent.getString("id")), "Enhanced PCM16.wav", if (settings.rack == null) "native-pcm16-iir-enhancement" else "bounded-native-effect-rack", if (settings.rack == null) "1" else "2",
                warnings = listOf("Enhancement changes a derivative. Retain the original and examine artifacts independently.") + if (settings.rack != null) listOf("Approximate restoration and granular pitch can introduce artifacts/latency. Declip does not recover lost peaks; envelope gating is not source isolation. Time shifts use silence padding.") else emptyList(), active = true) { out ->
                val stats = renderWave(wave, out, settings, cancelled, progress)
                JSONObject().put("gainDb", settings.gainDb).put("lowDb", settings.lowDb).put("midDb", settings.midDb).put("highDb", settings.highDb)
                    .put("highPassHz", settings.highPassHz).put("lowPassHz", settings.lowPassHz).put("humHz", settings.humHz)
                    .put("humHarmonics", if (settings.humHz == 0.0) 0 else 4).put("notchHz", settings.notchHz).put("notchQ", settings.notchQ)
                    .put("noiseFloorDb", settings.noiseFloorDb ?: JSONObject.NULL).put("noiseReductionDb", settings.noiseReductionDb)
                    .put("limiter", settings.limit).put("startMs", settings.startMs).put("endMs", settings.endMs ?: JSONObject.NULL)
                    .put("effectRack", settings.rack?.json() ?: JSONObject.NULL).put("trim", settings.trim).put("workbenchPlan", settings.workbenchPlan?.let { JSONObject(it) } ?: JSONObject.NULL)
                    .put("selectedFrames", stats.selectedFrames).put("overloadSampleCount", stats.clippedSamples)
                    .put("sampleRate", wave.format.sampleRate).put("channels", wave.format.channels).put("frames", wave.frames)
            }
        }
    }

    fun transcribe(manifest: JSONObject, actor: String, cancelled: () -> Boolean, progress: (Long) -> Unit): JSONObject {
        val audio = prepareAudio(manifest, actor, cancelled, progress)
        val audioId = audio.getString("id")
        val caseId = manifest.getString("caseId")
        val analysis = manifest.items("artifacts").lastOrNull { it.getString("type") == "ANALYSIS" && it.getJSONArray("parents").optString(0) == audioId }
            ?.let { readJson(manifest, it, 1024 * 1024) } ?: analyze(manifest, actor, cancelled, progress)
        val candidates = analysis.items("events").map { it.getLong("startMs")..it.getLong("endMs") }
        val input = withWave(manifest, audio) { wave ->
            vault.addDerivative(manifest, actor, "TRANSCRIPTION_TRANSMISSION", listOf(audioId), "Local recognition input.wav", "native-mono-resample-pcm16", "1") { out ->
                val frames = recognitionWave(wave, out, cancelled, progress)
                JSONObject().put("sampleRate", 16000).put("channels", 1).put("frames", frames)
                    .put("transport", "IN_PROCESS_ONLY").put("resampling", "linear with low-pass on downsampling")
            }
        }
        vault.recordEvent(manifest, actor, "LOCAL_TRANSCRIPTION", JSONObject().put("inputId", input.getString("id")).put("sha256", input.getString("sha256"))
            .put("model", SPEECH_MODEL).put("runtime", VOSK_RUNTIME).put("externalTransfer", false))
        var transcript: JSONObject? = null
        val raw = withWave(manifest, input) { wave ->
            vault.addDerivative(manifest, actor, "RAW_PROVIDER_RESULT", listOf(input.getString("id")), "Local decoder result.ndjson", "vosk-android-local", VOSK_RUNTIME,
                maxBytes = 64L * 1024 * 1024) { output ->
                transcript = OfflineSpeech(context).transcribe(wave, input.getString("sha256"), output, candidates, cancelled, progress)
                JSONObject().put("model", SPEECH_MODEL).put("speakerModel", SPEAKER_MODEL).put("windowSeconds", 45).put("requestedAlternatives", 3)
            }
        }
        val payload = checkNotNull(transcript).put("audioArtifactId", audioId).put("duration", input.operationParameters().getLong("frames") / 16000.0)
        if (cancelled()) throw InterruptedException("Transcription cancelled")
        val result = vault.addDerivative(manifest, actor, "NORMALIZED_TRANSCRIPT", listOf(raw.getString("id")), "Draft transcript.json", "native-transcript-normalization", "1",
            maxBytes = 16L * 1024 * 1024) { out ->
            out.write(EvidenceVault.canonical(payload)); JSONObject().put("audioArtifactId", audioId).put("caseId", caseId).put("state", "AI_DRAFT")
        }
        return result
    }

    fun review(manifest: JSONObject, actor: String, parent: JSONObject, segmentId: String, text: String, label: String, verified: Boolean): JSONObject {
        require(text.length <= 10000 && text.none { it.code < 32 && it !in "\n\r\t" } && label.length <= 80 && label.none { it.code < 32 }) { "Review text or speaker label is invalid" }
        val transcript = readTranscript(manifest, parent)
        val segments = transcript.items("segments")
        val segment = segments.singleOrNull { it.getString("id") == segmentId } ?: error("Transcript segment is missing")
        if (segment.getString("text") != text) {
            segment.put("text", text).put("words", JSONArray()).put("confidence", JSONObject.NULL)
            val flags = segment.getJSONArray("flags")
            if ((0 until flags.length()).none { flags.getString(it) == "HUMAN_TEXT_CHANGED" }) flags.put("HUMAN_TEXT_CHANGED")
        }
        segment.put("speakerLabel", label.trim()).put("speakerLabelSource", "HUMAN_EXAMINER")
            .put("verification", if (verified) "HUMAN_VERIFIED" else "HUMAN_EDITED")
        val state = if (segments.isNotEmpty() && segments.all { it.getString("verification") == "HUMAN_VERIFIED" }) "HUMAN_VERIFIED" else "HUMAN_EDITED"
        transcript.put("state", state)
        vault.addDerivative(manifest, actor, "HUMAN_TRANSCRIPT", listOf(parent.getString("id")), "Reviewed transcript.json", "examiner-segment-revision", "1", maxBytes = 16L * 1024 * 1024) { out ->
            out.write(EvidenceVault.canonical(transcript)); JSONObject().put("segmentId", segmentId).put("verified", verified).put("state", state)
        }
        return transcript
    }

    fun readTranscript(manifest: JSONObject, artifact: JSONObject): JSONObject {
        val value = readJson(manifest, artifact, 16 * 1024 * 1024)
        require(value.getString("schema") == "org.echoscript.transcript/1" && value.getString("state") in setOf("AI_DRAFT", "UNDER_REVIEW", "HUMAN_EDITED", "HUMAN_VERIFIED", "SUPERSEDED"))
        require(value.getString("transmissionHash").matches(Regex("[a-f0-9]{64}")))
        require(value.getString("provider").length in 1..128 && value.getString("model").length in 1..256)
        val ids = mutableSetOf<String>()
        val array = value.getJSONArray("segments")
        require(array.length() <= 10000) { "Transcript exceeds the native review policy" }
        fun validText(text: String, max: Int) = text.length <= max && text.none { it.code < 32 && it !in "\n\r\t" }
        fun validTime(time: Double) = time.isFinite() && kotlin.math.abs(time) <= 365 * 86400.0
        fun validScore(score: JSONObject?) {
            if (score == null) return
            val semantics = score.getString("semantics")
            val number = score.getDouble("value")
            require(number.isFinite() && semantics in setOf("provider confidence", "decoder score", "model self-assessment — not calibrated"))
            if (semantics == "provider confidence") require(number in 0.0..1.0)
        }
        for (segment in value.items("segments")) {
            val id = segment.getString("id")
            require(id.length in 1..128 && ids.add(id) && validText(segment.getString("text"), 10000) && validText(segment.optString("speakerLabel"), 80))
            require(validTime(segment.getDouble("start")) && validTime(segment.getDouble("end")))
            require(segment.isNull("speaker") || segment.getString("speaker").matches(Regex("SPEAKER_[1-9][0-9]*")))
            require(segment.getString("verification") in setOf("UNVERIFIED", "HUMAN_EDITED", "HUMAN_VERIFIED"))
            validScore(segment.optJSONObject("confidence"))
            val flags = segment.getJSONArray("flags")
            require(flags.length() <= 64 && (0 until flags.length()).all { validText(flags.getString(it), 128) })
            require(segment.getJSONArray("alternatives").length() <= 5 && segment.getJSONArray("words").length() <= 10000)
            for (alt in segment.items("alternatives")) {
                require(alt.getString("source") == "provider-returned" && validText(alt.getString("text"), 10000))
                require(alt.isNull("score") || alt.getDouble("score").isFinite())
            }
            for (word in segment.items("words")) {
                require(validText(word.getString("text"), 256) && validTime(word.getDouble("start")) && validTime(word.getDouble("end")))
                validScore(word.optJSONObject("confidence"))
            }
        }
        // Resolve the recognition input by its committed hash when opening an older transcript.
        val input = manifest.items("artifacts").lastOrNull {
            it.getString("type") == "TRANSCRIPTION_TRANSMISSION" && it.getString("sha256") == value.getString("transmissionHash")
        }
        if (input != null) {
            val parent = input.getJSONArray("parents").optString(0)
            if (parent.isNotBlank()) value.put("audioArtifactId", parent)
            if (!value.has("duration")) value.put("duration", withWave(manifest, input) { it.frames.toDouble() / it.format.sampleRate })
        }
        return value
    }

    fun readJson(manifest: JSONObject, artifact: JSONObject, maxBytes: Int): JSONObject {
        require(artifact.getLong("bytes") in 1..maxBytes.toLong()) { "Artifact exceeds the review memory limit" }
        val bytes = vault.open(manifest.getString("caseId"), artifact.getString("id")).use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(PCM_BLOCK_BYTES)
            while (true) {
                val n = input.read(chunk); if (n < 0) break
                require(buffer.size() + n <= maxBytes)
                buffer.write(chunk, 0, n)
            }
            buffer.toByteArray()
        }
        check(bytes.size.toLong() == artifact.getLong("bytes") && EvidenceVault.sha(bytes) == artifact.getString("sha256")) { "Artifact hash differs from the case manifest" }
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    fun exportArtifact(manifest: JSONObject, actor: String, artifact: JSONObject, output: OutputStream, cancelled: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val expected = artifact.getString("sha256")
        // Authenticate and verify before writing anything to the destination.
        val caseId = manifest.getString("caseId")
        val id = artifact.getString("id")
        val verify = MessageDigest.getInstance("SHA-256")
        vault.open(caseId, id).use { copy(it, DigestOutputStream(object : OutputStream() {
            override fun write(value: Int) {}
            override fun write(bytes: ByteArray, offset: Int, length: Int) {}
        }, verify), cancelled) }
        check(verify.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expected) { "Artifact cannot be verified for export" }
        vault.open(caseId, id).use { copy(it, DigestOutputStream(output, digest), cancelled) }
        output.flush()
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        check(hash == expected) { "Export hash mismatch" }
        vault.recordEvent(manifest, actor, "EXPORT_ARTIFACT", JSONObject().put("artifactId", id).put("sha256", hash))
        return hash
    }
}

private fun copy(input: java.io.InputStream, output: OutputStream, cancelled: () -> Boolean) {
    val buffer = ByteArray(PCM_BLOCK_BYTES)
    while (true) {
        if (cancelled()) throw InterruptedException("Operation cancelled")
        val count = input.read(buffer); if (count < 0) break
        output.write(buffer, 0, count)
    }
    if (cancelled()) throw InterruptedException("Operation cancelled")
}
