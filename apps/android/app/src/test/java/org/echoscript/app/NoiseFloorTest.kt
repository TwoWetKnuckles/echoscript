package org.echoscript.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class NoiseFloorTest {
    private fun wave(channels: Int, frames: Int, sample: (Int, Int) -> Int): ByteArray {
        val format = PcmFormat(16000, channels); val data = ByteArray(frames * format.frameBytes)
        repeat(frames) { frame -> repeat(channels) { channel ->
            putPcm16(data, frame * format.frameBytes + 2 * channel, sample(frame, channel))
        } }
        return waveHeader(format, data.size.toLong()) + data
    }
    private fun measure(source: ByteArray, start: Long, end: Long): NoiseFloorMeasurement =
        WaveReader(source.inputStream(), source.size.toLong()).use { measureNoiseFloor(it, start, end, { false }, {}) }

    @Test fun selectedNoiseFloorMeasuresChannelsWithoutPhaseCancellation() {
        val source = wave(2, 32000) { frame, channel ->
            val tone = (3276 * sin(2 * PI * 1000 * frame / 16000)).toInt()
            if (channel == 0) tone else -tone
        }
        val result = measure(source, 200, 1200)
        assertEquals(3200L, result.startFrame); assertEquals(19200L, result.endFrame)
        assertEquals(-23.02, result.channelRmsDb[0], .05)
        assertEquals(result.channelRmsDb[0], result.channelRmsDb[1], .001)
        assertEquals(-23.02, result.gateThresholdDb, .05)
        assertEquals(0L, result.nearFullScaleSamples)
        assertEquals(result, measure(source, 200, 1200))
    }
    @Test fun silenceIsBoundedAndInvalidRangesFailSafely() {
        val source = wave(1, 496000) { _, _ -> 0 }
        val quiet = measure(source, 0, 1000)
        assertEquals(-120.0, quiet.channelRmsDb.single(), .001)
        assertEquals(-90.0, quiet.gateThresholdDb, .001)
        assertThrows(IllegalArgumentException::class.java) { measure(source, 0, 30001) }
        assertThrows(IllegalArgumentException::class.java) { measure(source, 1000, 1000) }
        assertThrows(InterruptedException::class.java) {
            WaveReader(source.inputStream(), source.size.toLong()).use { measureNoiseFloor(it, 0, 1000, { true }, {}) }
        }
    }
}
