package org.echoscript.app

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

internal data class NoiseFloorMeasurement(
    val startFrame: Long,
    val endFrame: Long,
    val sampleRate: Int,
    val channelRmsDb: List<Double>,
    val strongestChannel: Int,
    val gateThresholdDb: Double,
    val nearFullScaleSamples: Long,
)

// Measures broadband RMS per channel over the examiner's chosen interval. This
// is a threshold estimate for the existing envelope gate, not spectral source
// separation. The original remains unchanged and no full-file buffer is used.
internal fun measureNoiseFloor(input: WaveReader, startMs: Long, endMs: Long,
    cancelled: () -> Boolean, progress: (Long) -> Unit): NoiseFloorMeasurement {
    val format = input.format
    require(startMs >= 0 && endMs > startMs && endMs <= format.millis(input.frames)) { "Choose a valid noise-only interval" }
    require(endMs - startMs <= 30000) { "Use a noise-only selection no longer than 30 seconds" }
    val start = startMs * format.sampleRate / 1000
    val end = endMs * format.sampleRate / 1000
    require(end > start) { "The selection contains no audio frames" }
    val energy = DoubleArray(format.channels)
    var frame = 0L; var clipped = 0L
    val buffer = ByteArray(PCM_BLOCK_BYTES)
    while (frame < end) {
        if (cancelled()) throw InterruptedException("Noise-floor measurement cancelled")
        val count = input.read(buffer)
        check(count > 0) { "Audio ended before the selected interval" }
        for (offset in 0 until count step format.frameBytes) {
            if (frame >= end) break
            if (frame >= start) for (channel in 0 until format.channels) {
                val sample = pcm16(buffer, offset + channel * 2) / 32768.0
                energy[channel] += sample * sample
                if (abs(sample) >= 32767.0 / 32768) clipped++
            }
            frame++
        }
        progress(frame)
    }
    val levels = energy.map { 20 * log10(max(1e-6, sqrt(it / (end - start)))) }
    val strongest = levels.indices.maxBy { levels[it] }
    return NoiseFloorMeasurement(start, end, format.sampleRate, levels, strongest,
        levels[strongest].coerceIn(-90.0, -10.0), clipped)
}
