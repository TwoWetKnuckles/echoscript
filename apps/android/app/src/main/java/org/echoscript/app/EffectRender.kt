package org.echoscript.app

import java.io.OutputStream
import kotlin.math.*

// Fixed memory: input lookahead is 100 ms + 64 frames; no full-file PCM buffer.
internal fun renderRack(input: WaveReader, output: OutputStream, settings: RenderSettings, rack: EffectRack,
    cancelled: () -> Boolean, progress: (Long) -> Unit): RenderStatistics {
    val f = input.format; val rate = f.sampleRate; val channels = f.channels
    val start = settings.startMs * rate / 1000
    val end = settings.endMs?.let { it * rate / 1000 } ?: input.frames
    require(start >= 0 && start < end && end <= input.frames) { "Region exceeds the recording" }
    require(rack.amount("width") == 0.0 || channels == 2) { "Stereo width requires exactly two channels" }
    require(rack.amount("buzz") == 0.0 || rack.buzzHz < rate / 2.0) { "Buzz frequency must be below Nyquist" }
    require(rack.amount("parametric") == 0.0 || rack.paramHz < rate / 2.0) { "EQ frequency must be below Nyquist" }
    require(rack.amount("frequency") == 0.0 || rack.bandHighHz < rate / 2.0) { "Band frequencies must be below Nyquist" }
    val capacity = rate / 4 + 512
    val source = Array(channels) { DoubleArray(capacity) }
    val inBytes = ByteArray(PCM_BLOCK_BYTES); var inCount = 0; var inOffset = 0; var loaded = 0L
    fun ensure(target: Long) {
        while (loaded <= target && loaded < input.frames) {
            if (inOffset == inCount) { inCount = input.read(inBytes); inOffset = 0; check(inCount > 0) }
            for (c in 0 until channels) source[c][(loaded % capacity).toInt()] = pcm16(inBytes, inOffset + 2 * c) / 32768.0
            inOffset += f.frameBytes; loaded++
        }
    }
    fun raw(n: Long, c: Int): Double {
        if (n < 0 || n >= input.frames) return 0.0
        check(n < loaded && n >= loaded - capacity) { "Audio lookahead policy exceeded" }
        return source[c][(n % capacity).toInt()]
    }
    fun eq(cut: Double, db: Double) = List(channels) { if (db == 0.0) null else filter("peak", min(cut, rate * .4), .75, rate, db) }
    val bass = eq(180.0, rack.amount("bass") * 18); val mid = eq(1000.0, rack.amount("presence") * 18); val high = eq(4000.0, rack.amount("treble") * 18)
    val parametric = List(channels) { if (rack.amount("parametric") == 0.0) null else filter("peak", rack.paramHz, rack.paramQ, rate, rack.amount("parametric") * 18) }
    val frequency = List(channels) { if (rack.amount("frequency") == 0.0) emptyList() else listOf(filter("high", rack.bandLowHz, .7071, rate), filter("low", rack.bandHighHz, .7071, rate)) }
    val band = List(channels) { listOf(filter("high", 100.0, .7071, rate), filter("low", min(4000.0, rate * .4), .7071, rate)) }
    val hum = List(channels) { (1..4).map { filter("notch", rack.humHz * it.toDouble(), 28.0, rate) } }
    val buzz = List(channels) { if (rack.buzzHz < rate / 2.0) filter("notch", rack.buzzHz, 25.0, rate) else null }
    val delaySize = rate + 16
    val delays = Array(channels) { DoubleArray(delaySize) }; val pitched = Array(channels) { DoubleArray(delaySize) }
    val env = DoubleArray(channels); val samples = DoubleArray(channels)
    val smoothing = exp(-1.0 / (.015 * rate)); val noiseFloor = 10.0.pow(rack.noiseThresholdDb / 20)
    val gain = 10.0.pow(rack.amount("gain") * 30 / 20)
    val shift = (rack.amount("shift") * rate / 10).roundToInt()
    val pitchRatio = 2.0.pow(rack.amount("pitch"))
    val grain = max(64, rate / 25)
    fun delay(buffer: DoubleArray, n: Long, amount: Double): Double {
        val position = n - amount; val lo = floor(position).toLong(); val fraction = position - lo
        fun get(i: Long) = if (i < 0) 0.0 else buffer[(i % delaySize).toInt()]
        return get(lo) * (1 - fraction) + get(lo + 1) * fraction
    }
    output.write(waveHeader(f, if (settings.trim) (end - start) * f.frameBytes else input.dataBytes))
    val out = ByteArray(PCM_BLOCK_BYTES / f.frameBytes * f.frameBytes); var used = 0; var overload = 0L
    for (n in 0 until input.frames) {
        if (n % 4096 == 0L) { if (cancelled()) throw InterruptedException("Audio render cancelled"); progress(n) }
        ensure(n + rate / 10 + 65)
        for (c in 0 until channels) {
            val index = n - shift
            var x = raw(index, c)
            if (rack.amount("declip") != 0.0 && abs(x) >= .999) {
                var left = 1; var right = 1
                while (left <= 32 && index - left >= 0 && abs(raw(index - left, c)) >= .999) left++
                while (right <= 32 && index + right < input.frames && abs(raw(index + right, c)) >= .999) right++
                if (left <= 32 && right <= 32 && index - left >= 0 && index + right < input.frames) {
                    val estimate = (raw(index - left, c) * right + raw(index + right, c) * left) / (left + right)
                    x += rack.amount("declip") * (estimate - x)
                }
            }
            x *= gain
            x = bass[c]?.next(x) ?: x; x = mid[c]?.next(x) ?: x; x = high[c]?.next(x) ?: x
            x = parametric[c]?.next(x) ?: x
            var filtered = x; for (q in frequency[c]) filtered = q.next(filtered)
            x += rack.amount("frequency") * (filtered - x)
            filtered = x; for (q in band[c]) filtered = q.next(filtered)
            x += rack.amount("band") * (filtered - x)
            filtered = x; for (q in hum[c]) filtered = q.next(filtered)
            x += rack.amount("hum") * (filtered - x)
            filtered = buzz[c]?.next(x) ?: x; x += rack.amount("buzz") * (filtered - x)
            delays[c][(n % delaySize).toInt()] = x
            val room = delay(delays[c], n, rate * .031) * .35 + delay(delays[c], n, rate * .067) * .22 + delay(delays[c], n, rate * .113) * .13
            x += rack.amount("room") * room
            x += rack.amount("echo") * .8 * delay(delays[c], n, rate * rack.echoMs / 1000.0)
            env[c] = env[c] * smoothing + (1 - smoothing) * abs(x)
            val quiet = 1 - (env[c] / noiseFloor - .5).coerceIn(0.0, 1.0)
            val removal = 1 - 10.0.pow(-24 * abs(rack.amount("noise")) / 20)
            x *= 1 - sign(rack.amount("noise")) * removal * quiet
            pitched[c][(n % delaySize).toInt()] = x
            if (rack.amount("pitch") != 0.0) {
                val phase = ((n * (1 - pitchRatio) / grain) % 1 + 1) % 1
                val other = (phase + .5) % 1
                val weight = .5 - .5 * cos(2 * PI * phase)
                x = delay(pitched[c], n, 2 + phase * grain) * weight + delay(pitched[c], n, 2 + other * grain) * (1 - weight)
            }
            samples[c] = x
        }
        if (channels == 2) {
            val middle = (samples[0] + samples[1]) / 2; val side = (samples[0] - samples[1]) / 2 * (1 + rack.amount("width"))
            samples[0] = middle + side; samples[1] = middle - side
        }
        val selected = n in start until end
        if (settings.trim && !selected) continue
        for (c in 0 until channels) {
            var x = if (selected) samples[c] else raw(n, c)
            if (selected && abs(x) > 32767.0 / 32768) overload++
            if (selected && settings.limit && abs(x) > .9) x = sign(x) * (.9 + .099 * (abs(x) - .9) / (.099 + abs(x) - .9))
            putPcm16(out, used, (x * 32768).roundToInt().coerceIn(-32768, 32767)); used += 2
        }
        if (used == out.size) { output.write(out); used = 0 }
    }
    if (used > 0) output.write(out, 0, used)
    progress(input.frames)
    return RenderStatistics(end - start, overload)
}
