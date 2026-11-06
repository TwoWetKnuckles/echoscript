package org.echoscript.app

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class OfflineAudioTest {
    private val format = PcmFormat(16000, 1)
    private fun wave(frames: Int, sample: (Int) -> Int): ByteArray {
        val bytes = ByteArray(frames * 2)
        repeat(frames) { putPcm16(bytes, it * 2, sample(it)) }
        return waveHeader(format, bytes.size.toLong()) + bytes
    }

    @Test fun gainRenderPreservesUnselectedSamplesAndIsDeterministic() {
        val source = wave(16000) { 1000 }
        val settings = RenderSettings(gainDb = 6.020599913279624, limit = false, startMs = 250, endMs = 750)
        fun run(): ByteArray = ByteArrayOutputStream().also { output ->
            WaveReader(source.inputStream(), source.size.toLong()).use { assertEquals(8000L, renderWave(it, output, settings, { false }, {}).selectedFrames) }
        }.toByteArray()
        val result = run()
        assertArrayEquals(run(), result)
        for (frame in 0 until 16000) {
            assertEquals(if (frame in 4000 until 12000) 2000 else 1000, pcm16(result, 44 + frame * 2))
            assertEquals(1000, pcm16(source, 44 + frame * 2))
        }
    }

    @Test fun humNotchAttenuatesMainsWhilePreservingHigherTone() {
        val source = wave(32000) { (4000 * sin(2 * PI * 60 * it / 16000) + 3000 * sin(2 * PI * 900 * it / 16000)).toInt() }
        val output = ByteArrayOutputStream()
        WaveReader(source.inputStream(), source.size.toLong()).use { renderWave(it, output, RenderSettings(humHz = 60.0, limit = false), { false }, {}) }
        val rendered = output.toByteArray()
        fun amplitude(bytes: ByteArray, hz: Int) = abs((16000 until 32000).sumOf { frame -> pcm16(bytes, 44 + frame * 2) * sin(2 * PI * hz * frame / 16000) } / 8000)
        assertTrue(amplitude(rendered, 60) < amplitude(source, 60) / 10)
        assertTrue(amplitude(rendered, 900) > amplitude(source, 900) * 0.95)
    }

    @Test fun canonicalWavTruncationAndSilentSpeechCandidates() {
        val silent = wave(16000) { 0 }
        WaveReader(silent.inputStream(), silent.size.toLong()).use {
            val analysis = overview(it, { false }, {})
            assertEquals(1000L, analysis.durationMs)
            assertTrue(analysis.speechCandidates.isEmpty())
            assertTrue(analysis.peaks.all { peak -> peak == 0.0 })
        }
        assertThrows(IllegalArgumentException::class.java) { WaveReader(silent.copyOf(silent.size - 1).inputStream(), silent.size.toLong() - 1) }
    }

    @Test fun recognitionInputKeepsExactPcmAtDecoderRate() {
        val source = wave(16001) { if (it % 2 == 0) -123 else 321 }
        val output = ByteArrayOutputStream()
        WaveReader(source.inputStream(), source.size.toLong()).use { assertEquals(16001L, recognitionWave(it, output, { false }, {})) }
        assertArrayEquals(source, output.toByteArray())
    }

    @Test fun draftSpeakerGroupsRequireSufficientFramesAndRejectAmbiguity() {
        val groups = SpeakerGroups()
        fun vector(x: Double, y: Double) = JSONArray(listOf(x, y) + List(14) { 0.0 })
        assertNull(groups.assign(vector(1.0, 0.0), 30))
        assertEquals("SPEAKER_1", groups.assign(vector(1.0, 0.0), 180))
        assertEquals("SPEAKER_2", groups.assign(vector(0.0, 1.0), 180))
        assertNull(groups.assign(vector(1.0, 1.0), 180))
        assertEquals("SPEAKER_1", groups.assign(vector(0.96, 0.03), 150))
    }
}
