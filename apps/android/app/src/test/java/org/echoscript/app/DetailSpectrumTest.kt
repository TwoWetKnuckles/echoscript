package org.echoscript.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DetailSpectrumTest {
    private fun wave(channels: Int, frames: Int, sample: (Int, Int) -> Int): ByteArray {
        val format = PcmFormat(16000, channels)
        val data = ByteArray(frames * format.frameBytes)
        repeat(frames) { frame -> repeat(channels) { channel ->
            putPcm16(data, frame * format.frameBytes + channel * 2, sample(frame, channel))
        } }
        return waveHeader(format, data.size.toLong()) + data
    }
    private fun inspect(source: ByteArray, settings: DetailSpectrumSettings): DetailSpectrum =
        WaveReader(source.inputStream(), source.size.toLong()).use { detailSpectrum(it, settings, { false }, {}) }

    @Test fun continuousWindowsFindShortToneAndKeepOppositeChannelsVisible() {
        val source = wave(2, 16000) { frame, channel ->
            if (frame in 6400 until 8000) {
                val tone = (8000 * sin(2 * PI * 1000 * frame / 16000)).toInt()
                if (channel == 0) tone else -tone
            } else 0
        }
        val settings = DetailSpectrumSettings(200, 700, 256, 128)
        val detail = inspect(source, settings)
        assertEquals(2, detail.channels)
        assertEquals(61, detail.columns.size)
        assertEquals(3328.0, detail.firstCenterFrame, .001)
        assertEquals(11136L, detail.analyzedThroughFrame)
        fun centerMs(index: Int) = (detail.firstCenterFrame + index * settings.hopSize) * 1000 / detail.sampleRate
        val before = detail.columns.indices.first { centerMs(it) in 300.0..350.0 }
        val during = detail.columns.indices.first { centerMs(it) in 430.0..450.0 }
        assertTrue(detail.columns[before][16] < -75)
        assertTrue(detail.columns[during][16] > -15)
        assertTrue(detail.columns[during][16] > detail.columns[during][4] + 30)
        assertEquals(detail, inspect(source, settings))
        assertTrue(detail.peaks.any { it > .20 })
    }

    @Test fun settingsAndWorkBoundsRejectInvalidAndOversizedRanges() {
        val source = wave(1, 32000) { _, _ -> 0 }
        assertThrows(IllegalArgumentException::class.java) { DetailSpectrumSettings(0, 100, 1024, 1025) }
        assertThrows(IllegalArgumentException::class.java) { inspect(source, DetailSpectrumSettings(0, 2)) }
        assertThrows(IllegalArgumentException::class.java) { inspect(source, DetailSpectrumSettings(0, 2001)) }
        assertThrows(IllegalArgumentException::class.java) { inspect(source, DetailSpectrumSettings(0, 2000, 256, 32)) }
        val log = inspect(source, DetailSpectrumSettings(0, 500, 512, 256, "hamming", "log", -100))
        assertTrue(log.lowHz > 0); assertEquals(8000.0, log.highHz, 0.0)
        assertTrue(log.columns.all { bins -> bins.all { it == -100.0 } })
        assertThrows(InterruptedException::class.java) {
            WaveReader(source.inputStream(), source.size.toLong()).use {
                detailSpectrum(it, DetailSpectrumSettings(0, 500), { true }, {})
            }
        }
    }

    @Test fun highestFrequencyBandIncludesNyquistWithoutDoublingItsAmplitude() {
        val source = wave(1, 16000) { frame, _ -> if (frame % 2 == 0) 10000 else -10000 }
        val detail = inspect(source, DetailSpectrumSettings(0, 500, 256, 128))
        val highest = detail.columns[2].last()
        // PCM amplitude is about 0.305, or -10.3 dBFS. Nyquist is not doubled.
        assertTrue(highest in -11.0..-9.5)
        assertTrue(highest > detail.columns[2][64] + 25)
    }
}
