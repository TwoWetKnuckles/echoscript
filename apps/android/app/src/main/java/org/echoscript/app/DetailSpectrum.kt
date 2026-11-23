package org.echoscript.app

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class DetailSpectrumSettings(
    val startMs: Long,
    val endMs: Long,
    val fftSize: Int = 1024,
    val hopSize: Int = 512,
    val window: String = "hann",
    val scale: String = "linear",
    val floorDb: Int = -90,
) {
    init {
        require(startMs >= 0 && endMs > startMs)
        require(fftSize in setOf(256, 512, 1024, 2048))
        require(hopSize in 1..fftSize && hopSize >= fftSize / 8)
        require(window in setOf("hann", "hamming") && scale in setOf("linear", "log"))
        require(floorDb in -120..-40)
    }
}

internal data class DetailSpectrum(
    val sampleRate: Int,
    val channels: Int,
    val startFrame: Long,
    val endFrame: Long,
    val analyzedThroughFrame: Long,
    val firstCenterFrame: Double,
    val lowHz: Double,
    val highHz: Double,
    val peaks: List<Double>,
    val columns: List<List<Double>>,
)

// In-place radix-2 FFT. The caller provides a power-of-two window and explicitly
// scales magnitudes by its coherent gain; this is a display, not a PSD estimate.
private fun detailFft(real: DoubleArray, imaginary: DoubleArray) {
    val n = real.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
        j = j xor bit
        if (i < j) {
            val r = real[i]; real[i] = real[j]; real[j] = r
            val v = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = v
        }
    }
    var size = 2
    while (size <= n) {
        val angle = -2.0 * PI / size
        val stepReal = cos(angle); val stepImaginary = sin(angle)
        for (base in 0 until n step size) {
            var wr = 1.0; var wi = 0.0
            for (offset in 0 until size / 2) {
                val left = base + offset; val right = left + size / 2
                val tr = wr * real[right] - wi * imaginary[right]
                val ti = wr * imaginary[right] + wi * real[right]
                real[right] = real[left] - tr; imaginary[right] = imaginary[left] - ti
                real[left] += tr; imaginary[left] += ti
                val nextReal = wr * stepReal - wi * stepImaginary
                wi = wr * stepImaginary + wi * stepReal; wr = nextReal
            }
        }
        size *= 2
    }
}

// Consecutive, optionally overlapping windows cover the requested time range.
// Processing is streaming and capped by the number and cost of FFT windows.
// Frequency bands show the strongest channel/bin; they do not mix channels or
// create additional frequency resolution. The final partial hop is not padded.
internal fun detailSpectrum(input: WaveReader, settings: DetailSpectrumSettings,
    cancelled: () -> Boolean, progress: (Long) -> Unit): DetailSpectrum {
    val format = input.format; val rate = format.sampleRate; val channels = format.channels
    require(settings.endMs <= format.millis(input.frames)) { "Choose a range inside the audio" }
    val start = settings.startMs * rate / 1000
    val end = settings.endMs * rate / 1000
    require(start >= 0 && end <= input.frames && end > start) { "Choose a range inside the audio" }
    val length = end - start
    val n = settings.fftSize; val hop = settings.hopSize
    require(length >= n) { "The selection is shorter than one FFT window" }
    val windows = 1 + (length - n) / hop
    val windowLimit = min(256, 750_000 / (n * channels)).coerceAtLeast(1)
    require(windows <= windowLimit) { "Choose a shorter selection or a larger hop for this sample rate and channel count" }
    val weights = DoubleArray(n) { i ->
        val wave = cos(2 * PI * i / (n - 1))
        if (settings.window == "hann") .5 - .5 * wave else .54 - .46 * wave
    }
    val gain = weights.sum()
    val countBands = 128
    val high = rate / 2.0
    val low = if (settings.scale == "log") max(20.0, rate.toDouble() / n) else 0.0
    fun boundary(i: Int): Double = if (settings.scale == "log") low * (high / low).pow(i.toDouble() / countBands)
        else high * i / countBands
    val bins = List(countBands) { i ->
        val first = ceil(boundary(i) * n / rate).toInt().coerceIn(0, n / 2)
        val last = (if (i == countBands - 1) n / 2 else ceil(boundary(i + 1) * n / rate).toInt() - 1).coerceIn(0, n / 2)
        if (first <= last) first..last else {
            val nearest = (((boundary(i) + boundary(i + 1)) / 2) * n / rate).roundToInt().coerceIn(0, n / 2)
            nearest..nearest
        }
    }
    val ring = Array(channels) { DoubleArray(n) }
    val peaks = DoubleArray(512)
    val columns = ArrayList<List<Double>>(windows.toInt())
    val buffer = ByteArray(PCM_BLOCK_BYTES)
    var frame = 0L
    var selected = 0L
    while (frame < end) {
        if (cancelled()) throw InterruptedException("Spectral inspection cancelled")
        val count = input.read(buffer)
        check(count > 0) { "Audio ended before the selected interval" }
        for (offset in 0 until count step format.frameBytes) {
            if (frame >= end) break
            if (frame >= start) {
                var magnitude = 0.0
                for (c in 0 until channels) {
                    val sample = pcm16(buffer, offset + 2 * c) / 32768.0
                    ring[c][(selected % n).toInt()] = sample
                    magnitude = max(magnitude, kotlin.math.abs(sample))
                }
                val peakIndex = min(peaks.lastIndex, (selected * peaks.size / length).toInt())
                peaks[peakIndex] = max(peaks[peakIndex], magnitude)
                selected++
                if (selected >= n && (selected - n) % hop == 0L) {
                    if (cancelled()) throw InterruptedException("Spectral inspection cancelled")
                    val levels = DoubleArray(n / 2 + 1)
                    for (c in 0 until channels) {
                        val real = DoubleArray(n) { i -> ring[c][((selected + i) % n).toInt()] * weights[i] }
                        val imaginary = DoubleArray(n)
                        detailFft(real, imaginary)
                        for (k in levels.indices) {
                            val scale = if (k == 0 || k == n / 2) 1.0 else 2.0
                            levels[k] = max(levels[k], hypot(real[k], imaginary[k]) * scale / gain)
                        }
                    }
                    columns.add(bins.map { range ->
                        val amplitude = range.maxOf { levels[it] }
                        val db = (20 * log10(max(1e-12, amplitude))).coerceIn(settings.floorDb.toDouble(), 0.0)
                        round(db * 10) / 10
                    })
                }
            }
            frame++
        }
        progress(frame)
    }
    check(columns.size.toLong() == windows)
    return DetailSpectrum(rate, channels, start, end, start + n + (windows - 1) * hop,
        start + n / 2.0, low, high, peaks.toList(), columns)
}
