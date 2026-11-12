package org.echoscript.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FullSpectrumTest {
    private fun wave(channels:Int,frames:Int,sample:(Int,Int)->Int):ByteArray {
        val f=PcmFormat(16000,channels);val bytes=ByteArray(frames*f.frameBytes)
        repeat(frames) { frame -> repeat(channels) { channel ->
            putPcm16(bytes,frame*f.frameBytes+channel*2,sample(frame,channel))
        } }
        return waveHeader(f,bytes.size.toLong())+bytes
    }
    private fun analyze(source:ByteArray)=WaveReader(source.inputStream(),source.size.toLong()).use { fullSpectrum(it,{false},{}) }

    @Test fun transientBetweenSampledSnapshotsAppearsInFullOverview() {
        val source=wave(1,64000) { frame,_->
            if(frame in 300..419) (8192*sin(2*PI*1000*frame/16000)).toInt() else 0
        }
        val sampled=WaveReader(source.inputStream(),source.size.toLong()).use { overview(it,{false},{}) }
        val full=analyze(source)
        assertTrue(sampled.spectrum.flatten().all { it <= -90.0 })
        assertTrue(full.columns.flatten().any { it > -40.0 })
        assertEquals(128,full.columns.size);assertTrue(full.columns.all { it.size==32 })
        assertEquals(full,analyze(source))
    }

    @Test fun stereoAntiphaseAndFileEdgesAreRepresented() {
        val stereo=wave(2,16000) { frame,channel ->
            val tone=(8192*sin(2*PI*1000*frame/16000)).toInt();if(channel==0) tone else -tone
        }
        val result = analyze(stereo)
        // A 1 kHz, quarter-scale tone is FFT bin 16, mapped to display band 3.
        assertEquals(-12.0, result.columns[40][3], 0.2)
        assertTrue(result.columns[40][3] > result.columns[40][10] + 35)
        val edges=wave(1,10) { frame,_->if(frame==0 || frame==9) 32767 else 0 }
        assertTrue(analyze(edges).columns.flatten().any { it > -45.0 })
        val partialHopTail=wave(1,127) { frame,_->if(frame==126) 32767 else 0 }
        assertTrue(analyze(partialHopTail).columns.last().any { it > -45.0 })
    }

    @Test fun nyquistAmplitudeIsNotDoubled() {
        val source=wave(1,16000) { frame,_->if(frame%2==0) 10000 else -10000 }
        val highest=analyze(source).columns[40].last()
        assertTrue(highest in -11.0..-9.5)
    }

    @Test fun silenceAndCancellationRemainBounded() {
        val source=wave(1,1024) { _,_->0 }
        assertTrue(analyze(source).columns.flatten().all { it == -100.0 })
        assertThrows(InterruptedException::class.java) {
            WaveReader(source.inputStream(),source.size.toLong()).use { fullSpectrum(it,{true},{}) }
        }
    }
}
