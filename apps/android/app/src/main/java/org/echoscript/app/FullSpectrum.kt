package org.echoscript.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

internal data class FullSpectrum(
    val sampleRate: Int,
    val channels: Int,
    val frames: Long,
    val windows: Long,
    val columns: List<List<Double>>,
)

// A bounded overview, not a substitute for detailed inspection. Consecutive
// overlapping FFT windows cover the full recording; each display cell retains
// the strongest channel/bin magnitude over windows touching that time cell.
// Audio, windows, and per-file results are never held in an unbounded buffer.
internal fun fullSpectrum(input: WaveReader, cancelled: () -> Boolean, progress: (Long) -> Unit): FullSpectrum {
    val format=input.format; val frames=input.frames
    val n=256; val hop=128; val times=128; val bands=32; val bins=n/2+1
    val weights=DoubleArray(n) { .5-.5*cos(2*PI*it/(n-1)) }
    val gain=weights.sum()
    val windowed=Array(format.channels) { DoubleArray(n) }
    val real=DoubleArray(n); val imaginary=DoubleArray(n)
    val reverse=IntArray(n) { Integer.reverse(it) ushr 24 }
    val cosine=DoubleArray(n/2) { cos(-2*PI*it/n) }
    val sine=DoubleArray(n/2) { sin(-2*PI*it/n) }
    val magnitudeScale=DoubleArray(bins) { k ->
        val scale=if(k==0 || k==n/2) 1.0 else 2.0
        scale*scale/(gain*gain)
    }
    val maxima=Array(times) { DoubleArray(bands) }
    val windowBands=DoubleArray(bands)
    var virtual=hop.toLong() // Start with half a window of zero padding.
    var windowCount=0L

    fun inspectWindow() {
        if(cancelled()) throw InterruptedException("Full spectral overview cancelled")
        val center=virtual-n
        val from=max(0L,center-hop); val to=min(frames,center+hop)
        if(to<=from) return
        java.util.Arrays.fill(windowBands,0.0)
        for(channel in 0 until format.channels) {
            for(i in 0 until n) { real[i]=windowed[channel][((virtual+i)%n).toInt()]*weights[i]; imaginary[i]=0.0 }
            for(i in 0 until n) {
                val j=reverse[i]
                if(i<j) { val v=real[i];real[i]=real[j];real[j]=v }
            }
            var size=2
            while(size<=n) {
                val stride=n/size
                for(base in 0 until n step size) for(offset in 0 until size/2) {
                    val left=base+offset; val right=left+size/2; val k=offset*stride
                    val tr=cosine[k]*real[right]-sine[k]*imaginary[right]
                    val ti=cosine[k]*imaginary[right]+sine[k]*real[right]
                    real[right]=real[left]-tr;imaginary[right]=imaginary[left]-ti
                    real[left]+=tr;imaginary[left]+=ti
                }
                size*=2
            }
            for(k in 0 until bins) {
                val band=min(bands-1,k*bands/bins)
                val power=(real[k]*real[k]+imaginary[k]*imaginary[k])*magnitudeScale[k]
                windowBands[band]=max(windowBands[band],power)
            }
        }
        val first=min(times-1,(from*times/frames).toInt())
        val last=min(times-1,((to-1)*times/frames).toInt())
        for(t in first..last) for(b in 0 until bands) maxima[t][b]=max(maxima[t][b],windowBands[b])
        windowCount++
    }

    val buffer=ByteArray(PCM_BLOCK_BYTES);var frame=0L
    while(true) {
        if(cancelled()) throw InterruptedException("Full spectral overview cancelled")
        val count=input.read(buffer)
        if(count==0) break
        for(offset in 0 until count step format.frameBytes) {
            val slot=(virtual%n).toInt()
            for(c in 0 until format.channels) windowed[c][slot]=pcm16(buffer,offset+2*c)/32768.0
            virtual++;frame++
            if(virtual>=n && (virtual-n)%hop==0L) inspectWindow()
        }
        progress(frame)
    }
    // Include the first hop-aligned center at or after the file end. A fixed
    // half-window alone can leave a tail sample near the Hann window's zero.
    val padding = hop + ((hop - frames % hop) % hop).toInt()
    repeat(padding) {
        val slot=(virtual%n).toInt()
        for(c in 0 until format.channels) windowed[c][slot]=0.0
        virtual++
        if(virtual>=n && (virtual-n)%hop==0L) inspectWindow()
    }
    check(frame==frames && windowCount>0)
    val columns=maxima.map { column -> column.map { power ->
        val db=(10*log10(max(1e-12,power))).coerceIn(-100.0,0.0)
        round(db*10)/10
    } }
    return FullSpectrum(format.sampleRate,format.channels,frames,windowCount,columns)
}
