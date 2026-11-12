package org.echoscript.app

import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.io.SequenceInputStream
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
        val oneFrame=wave(1,1) { _,_->32767 }
        assertTrue(analyze(oneFrame).columns.all { column -> column.any { it > -45.0 } })
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

    @Test fun minuteLongSyntheticStreamUsesFixedSizeReads() {
        val frames=16000L*60; val format=PcmFormat(16000,1); val dataBytes=frames*format.frameBytes
        var remaining=dataBytes; var largestRead=0
        val zeros=object:InputStream() {
            override fun read():Int = if (remaining > 0) { remaining--; 0 } else -1
            override fun read(buffer:ByteArray, offset:Int, length:Int):Int {
                if (length == 0) return 0
                if (remaining == 0L) return -1
                largestRead=maxOf(largestRead,length)
                val count=minOf(length.toLong(),remaining).toInt()
                java.util.Arrays.fill(buffer,offset,offset+count,0.toByte()); remaining-=count
                return count
            }
        }
        var progress=0L
        val source=SequenceInputStream(waveHeader(format,dataBytes).inputStream(),zeros)
        val result=WaveReader(source,dataBytes+44).use { fullSpectrum(it,{false},{ progress=it }) }
        assertEquals(frames,progress); assertEquals(7501L,result.windows)
        assertTrue(largestRead <= PCM_BLOCK_BYTES)
        assertTrue(result.columns.flatten().all { it == -100.0 })
    }
}
