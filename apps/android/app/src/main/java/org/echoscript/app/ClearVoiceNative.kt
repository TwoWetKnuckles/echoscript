package org.echoscript.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.roundToInt

internal const val CLEARVOICE_MODEL = "FRCRN_SE_16K"
internal const val CLEARVOICE_ONNX_HASH = "5fedce707fd607dc5280e8c52182057b24fc5565a2e4215f3dccb6e77ab067c2"
private const val CLEARVOICE_WINDOW = 32000
private const val CLEARVOICE_CORE = 16000
private const val CLEARVOICE_CONTEXT = 8000

/** Local, bounded inference. No networking, implicit source replacement or model downloads. */
internal class ClearVoiceNative(private val context: Context, private val vault: EvidenceVault, private val workflow: OfflineWorkflow) {
    private fun modelFile(cancelled: () -> Boolean): File {
        val folder = File(context.filesDir, "ClearVoiceModels/$CLEARVOICE_ONNX_HASH").apply { mkdirs() }
        val destination = File(folder, "model.onnx")
        if (!destination.exists()) {
            val pending = File(folder, "model.pending")
            try {
                context.assets.open("clearvoice-model/frcrn-16k-2s.onnx").use { input -> FileOutputStream(pending).use { output ->
                    val block = ByteArray(PCM_BLOCK_BYTES)
                    while (true) { if (cancelled()) throw InterruptedException("Model preparation cancelled"); val count = input.read(block); if (count < 0) break; output.write(block, 0, count) }
                    output.fd.sync()
                } }
                Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally { pending.delete() }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        destination.inputStream().use { input ->
            val block = ByteArray(PCM_BLOCK_BYTES)
            while (true) { if (cancelled()) throw InterruptedException("Model verification cancelled"); val count = input.read(block); if (count < 0) break; digest.update(block, 0, count) }
        }
        require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == CLEARVOICE_ONNX_HASH) { "The bundled ClearVoice model is missing or changed" }
        return destination
    }

    fun enhance(manifest: JSONObject, actor: String, placement: String, cancelled: () -> Boolean, progress: (Long) -> Unit, workbenchPlan: String? = null, processing: ProcessingStep? = null): JSONObject {
        if (cancelled()) throw InterruptedException("Enhancement cancelled")
        processing?.reuse(manifest, "ENHANCED_AUDIO", "clearvoice-frcrn-onnx-context-render")?.let {
            vault.selectRevision(manifest, actor, it.getString("id")); return it
        }
        val model = modelFile(cancelled)
        val audio = workflow.prepareAudio(manifest, actor, cancelled, progress)
        val audioId = audio.getString("id"); val caseId = manifest.getString("caseId")
        val inputStep = processing?.child("input")
        val canonical = inputStep?.reuse(manifest, "AUDIO_EXPORT", "clearvoice-local-mono-resample") ?: vault.addDerivative(manifest, actor, "AUDIO_EXPORT", listOf(audioId), "Local ClearVoice input.wav", "clearvoice-local-mono-resample", "1") { out ->
            vault.open(caseId, audioId).use { input -> WaveReader(input, audio.getLong("bytes")).use { wave ->
                val frames = recognitionWave(wave, out, cancelled, progress)
                JSONObject().put("frames", frames).put("sampleRate", 16000).put("channels", 1).put("transport", "IN_PROCESS_ONLY")
                    .put("resampling", "linear with low-pass on downsampling").forProcessing(inputStep)
            } }
        }
        check(android.system.Os.getenv("ORT_DISABLE_TELEMETRY") == "1") { "Inference telemetry must be disabled before initialization" }
        val environment = OrtEnvironment.getEnvironment().apply { setTelemetry(false) }
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            // The default arena retains large transient buffers across these fixed windows.
            // Use direct allocations to reduce peak/retained memory on Android 10 devices.
            options.setCPUArenaAllocator(false); options.setMemoryPatternOptimization(false)
            environment.createSession(model.absolutePath, options).use { session ->
                return vault.addDerivative(manifest, actor, "ENHANCED_AUDIO", listOf(audioId, canonical.getString("id")), "ClearVoice enhanced speech.wav",
                    "clearvoice-frcrn-onnx-context-render", "1", active = true,
                    warnings = listOf("AI enhancement can alter or suppress speech. Compare with the preserved original.", "Mono 16 kHz; 2-second neural windows with half-second context and 1-second output hops. Not validated for faint-speech recovery.")) { out ->
                    val frames = canonical.operationParameters().getLong("frames")
                    out.write(waveHeader(PcmFormat(16000, 1), frames * 2))
                    var completed = 0L; var clipped = 0L; var windows = 0L
                    vault.open(caseId, canonical.getString("id")).use { input -> WaveReader(input, canonical.getLong("bytes")).use { wave ->
                        val samples = FloatArray(CLEARVOICE_WINDOW)
                        fun fill(start: Int, count: Int) {
                            java.util.Arrays.fill(samples, start, start + count, 0f)
                            val pcm = ByteArray(count * 2); val bytes = wave.read(pcm)
                            for (i in 0 until bytes / 2) samples[start + i] = pcm16(pcm, i * 2) / 32768f
                        }
                        fill(CLEARVOICE_CONTEXT, CLEARVOICE_WINDOW - CLEARVOICE_CONTEXT)
                        val encoded = ByteArray(CLEARVOICE_CORE * 2)
                        while (completed < frames) {
                            if (cancelled()) throw InterruptedException("ClearVoice cancelled; completed originals and revisions are preserved")
                            val count = minOf(CLEARVOICE_CORE.toLong(), frames - completed).toInt()
                            OnnxTensor.createTensor(environment, FloatBuffer.wrap(samples), longArrayOf(1, CLEARVOICE_WINDOW.toLong())).use { tensor ->
                                session.run(mapOf("audio" to tensor)).use { result ->
                                    val array = (result[0].value as Array<*>).single() as FloatArray
                                    require(array.size == CLEARVOICE_WINDOW && array.all { it.isFinite() }) { "ClearVoice returned invalid audio" }
                                    for (i in 0 until count) {
                                        val value = array[CLEARVOICE_CONTEXT + i] * 32768.0
                                        if (value < -32768 || value > 32767) clipped++
                                        putPcm16(encoded, i * 2, value.roundToInt().coerceIn(-32768, 32767))
                                    }
                                }
                            }
                            if (cancelled()) throw InterruptedException("ClearVoice cancelled")
                            out.write(encoded, 0, count * 2); completed += count; windows++; progress(completed)
                            if (completed < frames) {
                                System.arraycopy(samples, CLEARVOICE_CORE, samples, 0, CLEARVOICE_CORE)
                                fill(CLEARVOICE_CORE, CLEARVOICE_CORE)
                            }
                        }
                    } }
                    JSONObject().put("model", CLEARVOICE_MODEL).put("modelSha256", CLEARVOICE_ONNX_HASH)
                        .put("checkpointRevision", "3766e6a64b0d8cb58f08d913d617bf129f11ed53").put("runtime", "onnxruntime-android/1.32.0")
                        .put("placement", placement).put("transport", "IN_PROCESS_ONLY").put("runtimeTelemetry", "DISABLED_BEFORE_INITIALIZATION").put("sampleRate", 16000).put("channels", 1).put("frames", frames)
                        .put("windowFrames", CLEARVOICE_WINDOW).put("hopFrames", CLEARVOICE_CORE).put("contextFrames", CLEARVOICE_CONTEXT)
                        .put("windows", windows).put("clippedOutputSamples", clipped).put("intraOpThreads", 1)
                        .put("cpuArenaAllocator", false).put("memoryPatternOptimization", false)
                        .put("workbenchPlan", workbenchPlan?.let { JSONObject(it) } ?: JSONObject.NULL).forProcessing(processing)
                }
            }
        }
    }
}
