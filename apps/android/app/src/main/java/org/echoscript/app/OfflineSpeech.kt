package org.echoscript.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal const val SPEECH_MODEL = "vosk-model-small-en-us-0.15"
internal const val SPEAKER_MODEL = "vosk-model-spk-0.4"
internal const val VOSK_RUNTIME = "0.3.75"
private const val SPEECH_RATE = 16000
private const val RECOGNITION_WINDOW_SECONDS = 45
private const val GROUP_THRESHOLD = 0.68
private const val AMBIGUITY_GAP = 0.10

/** Produce the exact PCM bytes the local recognizer will receive. */
internal fun recognitionWave(input: WaveReader, output: OutputStream, cancelled: () -> Boolean, progress: (Long) -> Unit): Long {
    val source = input.format
    val frames = input.frames * SPEECH_RATE / source.sampleRate
    output.write(waveHeader(PcmFormat(SPEECH_RATE, 1), frames * 2))
    val inputBuffer = ByteArray(PCM_BLOCK_BYTES)
    val outputBuffer = ByteArray(PCM_BLOCK_BYTES)
    var used = 0
    var i = 0L
    var j = 0L
    var previous = 0.0
    val lp = DoubleArray(4)
    val lowPass = if (source.sampleRate > SPEECH_RATE) 1 - exp(-2 * Math.PI * 6300 / source.sampleRate) else 1.0
    fun emit(sample: Double) {
        putPcm16(outputBuffer, used, sample.roundToInt().coerceIn(-32768, 32767)); used += 2; j++
        if (used == outputBuffer.size) { output.write(outputBuffer); used = 0 }
    }
    while (true) {
        if (cancelled()) throw InterruptedException("Recognition input cancelled")
        val count = input.read(inputBuffer)
        if (count == 0) break
        for (offset in 0 until count step source.frameBytes) {
            var sample = 0.0
            for (c in 0 until source.channels) sample += pcm16(inputBuffer, offset + c * 2).toDouble() / source.channels
            if (lowPass < 1) {
                for (pole in lp.indices) {
                    lp[pole] += lowPass * (sample - lp[pole]); sample = lp[pole]
                }
            }
            if (i == 0L) previous = sample
            while (j < frames && j * source.sampleRate <= i * SPEECH_RATE) {
                val fraction = (j * source.sampleRate - (i - 1) * SPEECH_RATE).toDouble() / SPEECH_RATE
                emit(previous + (sample - previous) * fraction.coerceIn(0.0, 1.0))
            }
            previous = sample; i++
        }
        progress(i)
    }
    while (j < frames) emit(previous)
    output.write(outputBuffer, 0, used)
    return frames
}

private object BundledModels {
    private const val ROOT = "offline-models"
    private val lock = Any()
    private val pathPattern = Regex("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)+")

    fun prepare(context: Context, cancelled: () -> Boolean): File = synchronized(lock) {
        val inventory = context.assets.open("$ROOT/inventory.json").bufferedReader().use { JSONObject(it.readText()) }
        val models = inventory.getJSONArray("models")
        require(models.length() == 2)
        val files = mutableListOf<JSONObject>()
        val tags = mutableSetOf<String>()
        for (i in 0 until models.length()) {
            val model = models.getJSONObject(i)
            val tag = model.getString("name")
            require(tag in setOf(SPEECH_MODEL, SPEAKER_MODEL) && tags.add(tag))
            val entries = model.getJSONArray("files")
            require(entries.length() in 1..256)
            for (j in 0 until entries.length()) {
                val entry = entries.getJSONObject(j)
                val path = entry.getString("path")
                require(path.startsWith("$tag/") && pathPattern.matches(path) && path.split('/').none { it == "." || it == ".." })
                files.add(entry)
            }
        }
        val parent = File(context.filesDir, "OfflineModels").apply { mkdirs() }
        val destination = File(parent, EvidenceVault.sha(EvidenceVault.canonical(inventory)))
        fun verify(folder: File) {
            for (item in files) {
                if (cancelled()) throw InterruptedException("Model preparation cancelled")
                val file = File(folder, item.getString("path"))
                require(file.isFile && file.length() == item.getLong("bytes")) { "Bundled model file is missing or changed" }
                val hash = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(PCM_BLOCK_BYTES)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        hash.update(buffer, 0, count)
                    }
                }
                require(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == item.getString("sha256")) { "Bundled model hash mismatch" }
            }
        }
        if (destination.exists()) verify(destination) else {
            val staging = File(parent, ".${UUID.randomUUID()}.pending").apply { mkdirs() }
            try {
                for (item in files) {
                    if (cancelled()) throw InterruptedException("Model preparation cancelled")
                    val name = item.getString("path")
                    val file = File(staging, name)
                    file.parentFile!!.mkdirs()
                    context.assets.open("$ROOT/$name").use { input -> FileOutputStream(file).use { input.copyTo(it, PCM_BLOCK_BYTES); it.fd.sync() } }
                }
                verify(staging)
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally { staging.deleteRecursively() }
        }
        destination
    }
}

/** Conservative online grouping of utterance embeddings. Scores measure similarity, never identity. */
internal class SpeakerGroups {
    private data class Group(val sum: DoubleArray, var count: Int)
    private val groups = mutableListOf<Group>()
    fun assign(values: JSONArray?, speechFrames: Int): String? {
        if (values == null || values.length() !in 16..1024 || speechFrames < 100) return null
        val vector = DoubleArray(values.length()) { values.optDouble(it) }
        if (vector.any { !it.isFinite() }) return null
        val norm = sqrt(vector.sumOf { it * it })
        if (!norm.isFinite() || norm <= 1e-10) return null
        for (i in vector.indices) vector[i] /= norm
        val rankings = groups.mapIndexedNotNull { index, group ->
            if (group.sum.size != vector.size) null else {
                val denominator = sqrt(group.sum.sumOf { it * it })
                index to (vector.indices.sumOf { vector[it] * group.sum[it] } / denominator)
            }
        }.sortedByDescending { it.second }
        val best = rankings.firstOrNull()
        if (best != null && best.second >= GROUP_THRESHOLD) {
            if (rankings.getOrNull(1)?.let { best.second - it.second < AMBIGUITY_GAP } == true) return null
            val group = groups[best.first]
            for (i in vector.indices) group.sum[i] += vector[i]
            group.count++
            return "SPEAKER_${best.first + 1}"
        }
        if ((best != null && best.second >= GROUP_THRESHOLD - AMBIGUITY_GAP) || groups.size >= 16) return null
        groups.add(Group(vector, 1))
        return "SPEAKER_${groups.size}"
    }
}

private fun score(value: Double, semantics: String): Any =
    if (value.isFinite()) JSONObject().put("value", value).put("semantics", semantics) else JSONObject.NULL

internal class OfflineSpeech(private val context: Context) {
    fun transcribe(
        input: WaveReader,
        inputHash: String,
        raw: OutputStream,
        candidates: List<LongRange>,
        cancelled: () -> Boolean,
        progress: (Long) -> Unit,
    ): JSONObject {
        require(input.format == PcmFormat(SPEECH_RATE, 1)) { "Local decoder requires PCM16 mono 16 kHz" }
        val models = BundledModels.prepare(context, cancelled)
        val segments = JSONArray()
        val groups = SpeakerGroups()
        var seenResults = 0
        var normalizedBytes = 0L
        var previousEnd = -1.0
        val totalSeconds = input.frames.toDouble() / SPEECH_RATE
        val block = ByteArray(8000) // 250 ms, a divisor of the bounded recognition window.
        var processed = 0L
        Model(File(models, SPEECH_MODEL).absolutePath).use { model ->
            SpeakerModel(File(models, SPEAKER_MODEL).absolutePath).use { speakers ->
                while (processed < input.frames) {
                    val origin = processed.toDouble() / SPEECH_RATE
                    val endFrame = minOf(input.frames, processed + SPEECH_RATE * RECOGNITION_WINDOW_SECONDS)
                    val windowEnd = endFrame.toDouble() / SPEECH_RATE
                    fun save(result: String) {
                        require(++seenResults <= 20000 && result.length <= 1_000_000) { "Decoder result exceeds the transcript policy" }
                        val encoded = JSONObject().put("offsetSeconds", origin).put("windowEndSeconds", windowEnd).put("resultUtf8", result)
                        raw.write(encoded.toString().toByteArray(Charsets.UTF_8)); raw.write('\n'.code)
                        val returned = JSONObject(result)
                        val alternatives = returned.optJSONArray("alternatives")
                        require(alternatives == null || alternatives.length() <= 5)
                        val primary = alternatives?.optJSONObject(0) ?: returned
                        val text = primary.optString("text")
                        if (text.isBlank()) return
                        require(text.length <= 10000 && segments.length() < 10000)
                        val words = JSONArray()
                        val flags = mutableSetOf<String>()
                        val alignment = primary.optJSONArray("result") ?: JSONArray()
                        require(alignment.length() <= 10000)
                        var wordEnd = -1.0
                        for (i in 0 until alignment.length()) {
                            val word = alignment.getJSONObject(i)
                            val start = origin + word.getDouble("start")
                            val end = origin + word.getDouble("end")
                            if (start < wordEnd || end <= start) flags.add("TIMESTAMP_ANOMALY")
                            if (start < 0 || end > totalSeconds || end > windowEnd + 0.1) flags.add("OUT_OF_RANGE")
                            wordEnd = end
                            val confidence = word.optDouble("conf")
                            words.put(JSONObject().put("text", word.getString("word")).put("start", start).put("end", end)
                                .put("confidence", score(if (confidence in 0.0..1.0) confidence else Double.NaN, "provider confidence")))
                        }
                        val start = words.optJSONObject(0)?.getDouble("start") ?: origin
                        val end = words.optJSONObject(words.length() - 1)?.getDouble("end") ?: windowEnd
                        if (words.length() == 0) flags.add("TIMESTAMP_ANOMALY")
                        if (end <= start) flags.add("TIMESTAMP_ANOMALY")
                        if (start < previousEnd) flags.add("OVERLAP")
                        if (candidates.none { it.first / 1000.0 < end && it.last / 1000.0 > start }) flags.add("LOW_ACOUSTIC_SUPPORT")
                        previousEnd = end
                        val speaker = groups.assign(returned.optJSONArray("spk"), returned.optInt("spk_frames"))
                        if (speaker == null) flags.add("SPEAKER_UNASSIGNED")
                        if (windowEnd < totalSeconds && end >= windowEnd - 0.5) flags.add("WINDOW_BOUNDARY_REVIEW")
                        val other = JSONArray()
                        if (alternatives != null) for (i in 1 until alternatives.length()) {
                            val item = alternatives.getJSONObject(i)
                            val value = item.optDouble("confidence")
                            other.put(JSONObject().put("text", item.getString("text")).put("score", if (value.isFinite()) value else JSONObject.NULL).put("source", "provider-returned"))
                        }
                        val segment = JSONObject().put("id", "segment-${segments.length() + 1}").put("text", text)
                            .put("start", start).put("end", end).put("words", words).put("alternatives", other)
                            .put("speaker", speaker ?: JSONObject.NULL).put("confidence", score(primary.optDouble("confidence"), "decoder score"))
                            .put("flags", JSONArray(flags.toList())).put("verification", "UNVERIFIED")
                        normalizedBytes += segment.toString().toByteArray(Charsets.UTF_8).size
                        require(normalizedBytes <= 12 * 1024 * 1024) { "Transcript exceeds the local review limit" }
                        segments.put(segment)
                    }
                    Recognizer(model, SPEECH_RATE.toFloat(), speakers).use { decoder ->
                        decoder.setWords(true)
                        decoder.setMaxAlternatives(3)
                        while (processed < endFrame) {
                            if (cancelled()) throw InterruptedException("Transcription cancelled")
                            val count = input.read(block)
                            check(count > 0 && processed + count / 2 <= endFrame) { "Recognition input ended unexpectedly" }
                            processed += count / 2
                            if (decoder.acceptWaveForm(block, count)) save(decoder.result)
                            progress(processed)
                        }
                        save(decoder.finalResult)
                    }
                }
            }
        }
        return JSONObject().put("schema", "org.echoscript.transcript/1").put("state", "AI_DRAFT").put("language", "en")
            .put("provider", "vosk-android-local").put("model", SPEECH_MODEL).put("transmissionHash", inputHash)
            .put("diarization", JSONObject().put("supported", true).put("model", SPEAKER_MODEL).put("algorithm", "utterance-cosine-centroids/1")
                .put("threshold", GROUP_THRESHOLD).put("ambiguityGap", AMBIGUITY_GAP).put("minimumSpeechFrames", 100)
                .put("warning", "Draft speaker groups. Speaker changes inside an utterance and overlapping speech require manual review."))
            .put("segments", segments).put("warnings", JSONArray(listOf(
                "Computer-generated transcription requires independent human verification.",
                "Decoder scores are not calibrated probabilities. Fixed 45-second windows can split speech.",
                "Speaker groups are acoustic estimates and do not establish identity.",
            )))
    }
}
