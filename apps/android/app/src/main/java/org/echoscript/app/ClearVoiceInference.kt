package org.echoscript.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil

/** ORT retains these direct buffers; one input/output allocation for the entire render. */
internal class ClearVoiceInference(environment: OrtEnvironment, private val session: OrtSession) : AutoCloseable {
    private val inputBuffer = ByteBuffer.allocateDirect(32000 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val outputBuffer = ByteBuffer.allocateDirect(32000 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val input = OnnxTensor.createTensor(environment, inputBuffer, longArrayOf(1, 32000))
    private val output = try { OnnxTensor.createTensor(environment, outputBuffer, longArrayOf(1, 32000)) }
        catch (failure: Throwable) { input.close(); throw failure }
    private val inputs = mapOf("audio" to input)
    private val outputs = mapOf("enhanced" to output)

    fun run(samples: FloatArray): FloatBuffer {
        require(samples.size == 32000)
        inputBuffer.clear(); inputBuffer.put(samples); inputBuffer.rewind()
        session.run(inputs, outputs).use { }
        // Pinned outputs are caller-owned and survive Result.close().
        for (index in 0 until 32000) require(outputBuffer.get(index).isFinite()) { "ClearVoice returned invalid audio" }
        return outputBuffer.asReadOnlyBuffer()
    }

    override fun close() { try { output.close() } finally { input.close() } }
}

internal fun clearVoiceProgress(completed: Long, total: Long, inferenceMillis: Long, windows: Long): String {
    require(total > 0 && completed in 0..total && inferenceMillis >= 0 && windows >= 0)
    val percent = completed * 100 / total
    if (completed == total) return "Enhancing speech · 100%"
    if (completed == 0L || windows < 5 || inferenceMillis < 2000) return "Enhancing speech · $percent% · estimating time"
    val remainingSeconds = ceil(inferenceMillis.toDouble() / 1000 * (total - completed) / completed).toLong().coerceAtLeast(1)
    val remainingMinutes = ceil(remainingSeconds / 60.0).toLong()
    val remaining = when {
        remainingMinutes >= 60 -> "${remainingMinutes / 60} h ${remainingMinutes % 60} min"
        remainingSeconds >= 60 -> "$remainingMinutes min"
        else -> "$remainingSeconds s"
    }
    return "Enhancing speech · $percent% · about $remaining left for enhancement"
}
