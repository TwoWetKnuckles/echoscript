package org.echoscript.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.*

class EffectRackTest {
    private fun audio(channels: Int = 1, frames: Int = 16000, sample: (Int, Int) -> Int): ByteArray {
        val f=PcmFormat(16000,channels);val b=ByteArray(frames*f.frameBytes)
        repeat(frames){n->repeat(channels){c->putPcm16(b,n*f.frameBytes+2*c,sample(n,c))}}
        return waveHeader(f,b.size.toLong())+b
    }
    private fun render(source: ByteArray, values: Map<String,EffectValue> = emptyMap(), start: Long = 0, end: Long? = null, trim: Boolean = false): ByteArray = ByteArrayOutputStream().also { out ->
        WaveReader(source.inputStream(),source.size.toLong()).use { renderWave(it,out,RenderSettings(rack=EffectRack(values),limit=false,startMs=start,endMs=end,trim=trim),{false},{}) }
    }.toByteArray()
    @Test fun neutralAndDisabledAreByteExactAndLongerThanRing() {
        val s=audio(frames=100000){n,_->(n%5000)-2500}
        assertArrayEquals(s,render(s));assertArrayEquals(s,render(s,mapOf("pitch" to EffectValue(false,100))))
        val rack=EffectRack(mapOf("gain" to EffectValue(true,-15)))
        assertEquals(rack,EffectRack.fromJson(rack.json()))
    }
    @Test fun signedGainRegionsOverlapAndClipRetainCoordinateSemantics() {
        val s=audio{_,_->1000}; val gain=mapOf("gain" to EffectValue(true,20))
        val a=render(s,gain,100,500);val b=render(a,gain,300,700)
        assertEquals(1000,pcm16(b,44));assertEquals(1995,pcm16(b,44+3200*2));assertEquals(3980,pcm16(b,44+6400*2));assertEquals(1995,pcm16(b,44+9600*2));assertEquals(1000,pcm16(b,44+14400*2))
        val clip=render(s,start=250,end=500,trim=true)
        WaveReader(clip.inputStream(),clip.size.toLong()).use{assertEquals(4000L,it.frames)}
        assertArrayEquals(s,render(s))
    }
    @Test fun signedTimeShiftHasExactSilencePaddingAndDuration() {
        val s=audio{n,_->if(n==4000) 10000 else 0}
        for(strength in listOf(-100,100)) {
            val r=render(s,mapOf("shift" to EffectValue(true,strength)))
            assertEquals(s.size,r.size);assertEquals(10000,pcm16(r,44+(4000+strength*16)*2));assertEquals(0,pcm16(r,44+4000*2))
        }
    }
    @Test fun mainsNotchReducesHumWithoutDestroyingSpeechTone() {
        val s=audio(frames=32000){n,_->(4000*sin(2*PI*60*n/16000)+3000*sin(2*PI*900*n/16000)).toInt()}
        val r=render(s,mapOf("hum" to EffectValue(true,100)))
        fun amplitude(b:ByteArray,hz:Int)=abs((16000 until 32000).sumOf{n->pcm16(b,44+2*n)*sin(2*PI*hz*n/16000)}/8000)
        assertTrue(amplitude(r,60)<amplitude(s,60)*.1);assertTrue(amplitude(r,900)>amplitude(s,900)*.95)
    }
    @Test fun stereoCollapseAndExpansionAreMeasured() {
        val s=audio(channels=2){_,c->if(c==0) 1000 else -1000}
        val mono=render(s,mapOf("width" to EffectValue(true,-100)))
        assertEquals(0,pcm16(mono,44+4000));assertEquals(0,pcm16(mono,44+4002))
        val wide=render(s,mapOf("width" to EffectValue(true,100)))
        assertEquals(2000,pcm16(wide,44+4000));assertEquals(-2000,pcm16(wide,44+4002))
    }
    @Test fun echoRoomGateAndPlateauInterpolationChangeExpectedSignal() {
        val impulse=audio{n,_->if(n==100) 10000 else 0}
        val echo=render(impulse,mapOf("echo" to EffectValue(true,100)))
        assertEquals(8000,pcm16(echo,44+(100+1920)*2))
        val room=render(impulse,mapOf("room" to EffectValue(true,100)))
        assertEquals(3500,pcm16(room,44+(100+496)*2))
        val quiet=audio{_,_->30};val denoise=render(quiet,mapOf("noise" to EffectValue(true,100)))
        assertTrue(abs(pcm16(denoise,44+12000*2))<10)
        val clipped=audio{n,_->if(n in 100..105) 32767 else 10000}
        val restored=render(clipped,mapOf("declip" to EffectValue(true,100)))
        assertEquals(10000,pcm16(restored,44+102*2))
    }
    @Test fun granularPitchMovesToneAndKeepsLengthDeterministically() {
        val s=audio(frames=32000){n,_->(3000*sin(2*PI*440*n/16000)).toInt()}
        val effects=mapOf("pitch" to EffectValue(true,100));val r=render(s,effects)
        assertEquals(s.size,r.size);assertArrayEquals(r,render(s,effects))
        fun amplitude(hz:Int)=abs((16000 until 32000).sumOf{n->pcm16(r,44+2*n)*sin(2*PI*hz*n/16000)}/8000)
        assertTrue(amplitude(880)>amplitude(440)*3)
    }
    @Test fun cancellationAndInvalidParametersFailBeforeFalseCompletion() {
        assertThrows(IllegalArgumentException::class.java){EffectValue(true,101)}
        assertThrows(IllegalArgumentException::class.java){EffectRack(mapOf("unknown" to EffectValue()))}
        val s=audio{_,_->0}
        assertThrows(InterruptedException::class.java){WaveReader(s.inputStream(),s.size.toLong()).use{renderWave(it,ByteArrayOutputStream(),RenderSettings(rack=EffectRack()),{true},{})}}
    }
}
