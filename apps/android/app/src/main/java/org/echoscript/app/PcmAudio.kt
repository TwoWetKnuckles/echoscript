package org.echoscript.app

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal const val PCM_BLOCK_BYTES = 64 * 1024
internal const val MAX_WAVE_DATA = 0xffffffffL - 36L

internal data class PcmFormat(val sampleRate: Int, val channels: Int) {
    init {
        require(sampleRate in 8000..192000 && channels in 1..8) { "Unsupported PCM sample rate or channel count" }
    }
    val frameBytes get() = channels * 2
    fun millis(frames: Long) = frames * 1000 / sampleRate
}

internal class UnsupportedWave : IllegalArgumentException("Source is not a supported PCM16 WAV")

internal class WaveReader(input: InputStream, availableBytes: Long) : AutoCloseable {
    private val stream = DataInputStream(input)
    val format: PcmFormat
    val dataBytes: Long
    val frames: Long get() = dataBytes / format.frameBytes
    private var remaining: Long

    init {
        fun tag() = ByteArray(4).also(stream::readFully).toString(Charsets.US_ASCII)
        fun uint() = Integer.reverseBytes(stream.readInt()).toLong() and 0xffffffffL
        fun ushort() = java.lang.Short.reverseBytes(stream.readShort()).toInt() and 0xffff
        fun skipExact(count: Long) {
            var left = count
            while (left > 0) {
                val skipped = stream.skip(left)
                if (skipped == 0L) { stream.readByte(); left-- } else left -= skipped
            }
        }
        if (availableBytes < 12 || tag() != "RIFF") throw UnsupportedWave()
        val riff = uint()
        if (tag() != "WAVE") throw UnsupportedWave()
        require(riff >= 4 && riff + 8 <= availableBytes) { "Truncated WAV container" }
        var left = riff - 4
        var parsed: PcmFormat? = null
        var length = -1L
        var chunks = 0
        while (left >= 8 && length < 0) {
            require(++chunks <= 4096) { "Excessive WAV metadata chunks" }
            val name = tag()
            val size = uint()
            val padded = size + (size and 1)
            left -= 8
            require(padded <= left) { "Invalid WAV chunk length" }
            when (name) {
                "fmt " -> {
                    require(parsed == null && size >= 16) { "Invalid WAV format chunk" }
                    val codec = ushort()
                    val channels = ushort()
                    val rate = uint()
                    val bytesPerSecond = uint()
                    val alignment = ushort()
                    val bits = ushort()
                    if (codec != 1 || bits != 16) throw UnsupportedWave()
                    require(rate <= Int.MAX_VALUE) { "Invalid WAV sample rate" }
                    val pcm = PcmFormat(rate.toInt(), channels)
                    require(alignment == pcm.frameBytes && bytesPerSecond == rate * alignment) { "Invalid WAV block alignment" }
                    parsed = pcm
                    skipExact(padded - 16)
                }
                "data" -> length = size
                else -> skipExact(padded)
            }
            left -= padded
        }
        format = parsed ?: throw UnsupportedWave()
        require(length > 0 && length % format.frameBytes == 0L) { "WAV audio is empty or has an incomplete frame" }
        dataBytes = length
        remaining = length
    }

    fun read(buffer: ByteArray): Int {
        require(buffer.size >= format.frameBytes)
        if (remaining == 0L) return 0
        val count = minOf(remaining, (buffer.size / format.frameBytes * format.frameBytes).toLong()).toInt()
        stream.readFully(buffer, 0, count)
        remaining -= count
        return count
    }

    override fun close() = stream.close()
}

internal fun waveHeader(format: PcmFormat, dataBytes: Long): ByteArray {
    require(dataBytes in 1..MAX_WAVE_DATA && dataBytes % format.frameBytes == 0L) { "Decoded audio exceeds the PCM WAV policy" }
    return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt((dataBytes + 36).toInt()); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(format.channels.toShort()); putInt(format.sampleRate)
        putInt(format.sampleRate * format.frameBytes); putShort(format.frameBytes.toShort()); putShort(16)
        put("data".toByteArray()); putInt(dataBytes.toInt())
    }.array()
}

internal fun pcm16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()

internal fun putPcm16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value shr 8).toByte()
}

internal data class RenderSettings(
    val rack: EffectRack? = null,
    val trim: Boolean = false,
    val workbenchPlan: String? = null,
    val gainDb: Double = 0.0,
    val lowDb: Double = 0.0,
    val midDb: Double = 0.0,
    val highDb: Double = 0.0,
    val highPassHz: Double = 0.0,
    val lowPassHz: Double = 0.0,
    val humHz: Double = 0.0,
    val notchHz: Double = 0.0,
    val notchQ: Double = 25.0,
    val noiseFloorDb: Double? = null,
    val noiseReductionDb: Double = 0.0,
    val limit: Boolean = true,
    val startMs: Long = 0,
    val endMs: Long? = null,
) {
    init {
        require(gainDb in -30.0..30.0 && listOf(lowDb, midDb, highDb).all { it in -18.0..18.0 })
        require(listOf(highPassHz, lowPassHz, notchHz).all { it.isFinite() && it >= 0.0 })
        require(humHz in setOf(0.0, 50.0, 60.0) && notchQ in 1.0..80.0)
        require(noiseFloorDb == null || noiseFloorDb in -90.0..-10.0)
        require(noiseReductionDb in 0.0..24.0 && startMs >= 0 && (endMs == null || endMs > startMs))
        require(highPassHz == 0.0 || lowPassHz == 0.0 || highPassHz < lowPassHz) { "High-pass cutoff must be below low-pass cutoff" }
    }
}

internal class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    private var z1 = 0.0
    private var z2 = 0.0
    fun next(input: Double): Double {
        val output = b0 * input + z1
        z1 = b1 * input - a1 * output + z2
        z2 = b2 * input - a2 * output
        return output
    }
}

internal fun filter(kind: String, hz: Double, q: Double, rate: Int, db: Double = 0.0): Biquad {
    require(hz > 0 && hz < rate / 2.0) { "Filter frequency must be below Nyquist (${rate / 2} Hz)" }
    val theta = 2 * PI * hz / rate
    val c = cos(theta)
    val alpha = sin(theta) / (2 * q)
    val a = 10.0.pow(db / 40.0)
    val den = 1 + if (kind == "peak") alpha / a else alpha
    val tail = 1 - if (kind == "peak") alpha / a else alpha
    val (x, y, z) = when (kind) {
        "high" -> Triple((1 + c) / 2, -(1 + c), (1 + c) / 2)
        "low" -> Triple((1 - c) / 2, 1 - c, (1 - c) / 2)
        "notch" -> Triple(1.0, -2 * c, 1.0)
        "peak" -> Triple(1 + alpha * a, -2 * c, 1 - alpha * a)
        else -> error("Unknown filter")
    }
    return Biquad(x / den, y / den, z / den, -2 * c / den, tail / den)
}

internal data class RenderStatistics(val selectedFrames: Long, val clippedSamples: Long)

internal fun renderWave(
    input: WaveReader,
    output: OutputStream,
    settings: RenderSettings,
    cancelled: () -> Boolean,
    progress: (Long) -> Unit,
): RenderStatistics {
    settings.rack?.let { return renderRack(input, output, settings, it, cancelled, progress) }
    val format = input.format
    val rate = format.sampleRate
    val duration = format.millis(input.frames)
    require(settings.startMs <= duration && (settings.endMs == null || settings.endMs <= duration + 1)) { "Selected time interval exceeds audio duration" }
    val start = settings.startMs * rate / 1000
    val end = settings.endMs?.let { (it * rate / 1000).coerceAtMost(input.frames) } ?: input.frames
    require(end > start) { "Selection contains no audio frames" }
    val filters = List(format.channels) {
        buildList {
            if (settings.highPassHz > 0) add(filter("high", settings.highPassHz, 0.7071, rate))
            if (settings.humHz > 0) for (h in 1..4) add(filter("notch", settings.humHz * h, 28.0, rate))
            if (settings.notchHz > 0) add(filter("notch", settings.notchHz, settings.notchQ, rate))
            if (settings.lowDb != 0.0) add(filter("peak", 180.0, 0.75, rate, settings.lowDb))
            if (settings.midDb != 0.0) add(filter("peak", 1000.0, 0.75, rate, settings.midDb))
            if (settings.highDb != 0.0) add(filter("peak", minOf(4000.0, rate * 0.4), 0.75, rate, settings.highDb))
            if (settings.lowPassHz > 0) add(filter("low", settings.lowPassHz, 0.7071, rate))
        }
    }
    val gain = 10.0.pow(settings.gainDb / 20)
    val floor = settings.noiseFloorDb?.let { 10.0.pow(it / 20) }
    val attenuate = 10.0.pow(-settings.noiseReductionDb / 20)
    val envelope = DoubleArray(format.channels)
    val smoothing = kotlin.math.exp(-1.0 / (0.015 * rate))
    val buffer = ByteArray(PCM_BLOCK_BYTES)
    output.write(waveHeader(format, input.dataBytes))
    var frame = 0L
    var clipped = 0L
    while (true) {
        if (cancelled()) throw InterruptedException("Audio render cancelled")
        val count = input.read(buffer)
        if (count == 0) break
        for (base in 0 until count step format.frameBytes) {
            for (channel in 0 until format.channels) {
                val offset = base + channel * 2
                var sample = pcm16(buffer, offset) / 32768.0
                for (biquad in filters[channel]) sample = biquad.next(sample)
                if (floor != null) {
                    envelope[channel] = envelope[channel] * smoothing + (1 - smoothing) * abs(sample)
                    val weight = (envelope[channel] / floor - 0.5).coerceIn(0.0, 1.0)
                    sample *= attenuate + (1 - attenuate) * weight
                }
                if (frame in start until end) {
                    sample *= gain
                    if (abs(sample) > 32767.0 / 32768) clipped++
                    if (settings.limit && abs(sample) > 0.90) {
                        val magnitude = abs(sample) - 0.90
                        sample = kotlin.math.sign(sample) * (0.90 + 0.099 * magnitude / (0.099 + magnitude))
                    }
                    putPcm16(buffer, offset, (sample * 32768).roundToInt().coerceIn(-32768, 32767))
                }
            }
            frame++
        }
        output.write(buffer, 0, count)
        progress(frame)
    }
    return RenderStatistics(end - start, clipped)
}

internal data class AudioOverview(
    val durationMs: Long,
    val peaks: List<Double>,
    val spectrum: List<List<Double>>,
    val speechCandidates: List<LongRange>,
    val peakDb: Double,
    val rmsDb: Double,
    val clippedSamples: Long,
)

internal fun overview(input: WaveReader, cancelled: () -> Boolean, progress: (Long) -> Unit): AudioOverview {
    val format = input.format
    val peaks = DoubleArray(256)
    val framesPerTile = maxOf(256L, (input.frames + 127) / 128)
    val tiles = mutableListOf<List<Double>>()
    val fftWindow = DoubleArray(256)
    val candidates = mutableListOf<LongRange>()
    val gateFrames = format.sampleRate / 50
    var energy = 0.0
    var gateEnergy = 0.0
    var gateCrossings = 0
    var previous = 0.0
    var start = -1L
    var clipped = 0L
    var frame = 0L
    var peak = 0.0
    val buffer = ByteArray(PCM_BLOCK_BYTES)
    while (true) {
        if (cancelled()) throw InterruptedException("Analysis cancelled")
        val count = input.read(buffer)
        if (count == 0) break
        for (offset in 0 until count step format.frameBytes) {
            var mono = 0.0
            val bin = minOf(255, (frame * 256 / input.frames).toInt())
            for (channel in 0 until format.channels) {
                val sample = pcm16(buffer, offset + 2 * channel) / 32768.0
                mono += sample / format.channels
                val magnitude = abs(sample)
                peak = maxOf(peak, magnitude)
                peaks[bin] = maxOf(peaks[bin], magnitude)
                if (magnitude >= 32767.0 / 32768) clipped++
                energy += sample * sample
            }
            val tileOffset = (frame % framesPerTile).toInt()
            if (tileOffset < fftWindow.size) {
                fftWindow[tileOffset] = mono * (0.5 - 0.5 * cos(2 * PI * tileOffset / (fftWindow.size - 1)))
                if (tileOffset == fftWindow.lastIndex) tiles.add(spectrumTile(fftWindow))
            }
            gateEnergy += mono * mono
            if ((mono < 0) != (previous < 0)) gateCrossings++
            previous = mono
            frame++
            if (frame % gateFrames == 0L) {
                val rms = sqrt(gateEnergy / gateFrames)
                val zcr = gateCrossings.toDouble() / gateFrames
                val candidate = rms > 0.012 && zcr in 0.015..0.35
                if (candidate && start < 0) start = frame - gateFrames
                if (!candidate && start >= 0) {
                    val end = frame - gateFrames
                    if (end - start >= gateFrames * 3 && candidates.size < 256) candidates.add(format.millis(start)..format.millis(end))
                    start = -1
                }
                gateEnergy = 0.0; gateCrossings = 0
            }
        }
        progress(frame)
    }
    if (start >= 0 && frame - start >= gateFrames * 3 && candidates.size < 256) candidates.add(format.millis(start)..format.millis(frame))
    fun db(amplitude: Double) = 20 * log10(maxOf(amplitude, 1e-6))
    return AudioOverview(format.millis(input.frames), peaks.toList(), tiles, candidates, db(peak), db(sqrt(energy / (input.frames * format.channels))), clipped)
}

// Hann-windowed spectral snapshots. Sampling fixed positions keeps the preview bounded
// for long recordings and makes the analysis recipe repeatable.
private fun spectrumTile(input: DoubleArray): List<Double> = List(32) { bin ->
    val k = bin * 4 + 1
    var real = 0.0
    var imag = 0.0
    for (n in input.indices) {
        val angle = -2 * PI * k * n / input.size
        real += input[n] * cos(angle)
        imag += input[n] * sin(angle)
    }
    (20 * log10(maxOf(1e-6, 2 * sqrt(real * real + imag * imag) / input.size))).coerceIn(-100.0, 0.0)
}
