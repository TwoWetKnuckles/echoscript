package org.echoscript.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import org.json.JSONObject
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

internal data class DecodedAudio(val format: PcmFormat, val details: JSONObject)

/** A decoder may seek in authenticated source bytes. No plaintext source file is created. */
private class EncryptedMediaSource(private val channel: SeekableByteChannel, private val length: Long) : MediaDataSource() {
    init {
        try {
            check(channel.read(ByteBuffer.allocate(1)) == 1 && channel.size() == length) { "Source size or authentication mismatch" }
        } catch (failure: Exception) { channel.close(); throw failure }
    }
    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        require(position >= 0)
        if (size == 0) return 0
        if (position >= length) return -1
        channel.position(position)
        return channel.read(ByteBuffer.wrap(buffer, offset, minOf(size.toLong(), length - position).toInt()))
    }
    override fun getSize() = length
    @Synchronized
    override fun close() = channel.close()
}

internal fun decodeAudio(
    vault: EvidenceVault,
    caseId: String,
    source: JSONObject,
    output: OutputStream,
    cancelled: () -> Boolean,
    progress: (Long) -> Unit,
): DecodedAudio {
    val id = source.getString("id")
    val sourceBytes = source.getLong("bytes")
    require(sourceBytes <= EvidenceVault.MAX_PROCESS_SOURCE_BYTES) { "Processing supports source files up to 750 MB. The preserved source remains in the vault." }
    // Probe a supported WAV before emitting any bytes. A damaged supported WAV fails
    // without silently changing decoders. Other formats go through Android codecs.
    vault.open(caseId, id).use { stream ->
        val wave = try { WaveReader(stream, sourceBytes) } catch (_: UnsupportedWave) { null }
        if (wave != null) {
            val buffer = ByteArray(PCM_BLOCK_BYTES)
            var count = 0L
            while (true) {
                if (cancelled()) throw InterruptedException("Decode cancelled")
                val n = wave.read(buffer)
                if (n == 0) break
                output.write(buffer, 0, n); count += n; progress(count)
            }
            return DecodedAudio(wave.format, JSONObject().put("decoder", "echoscript-pcm16-wave/1").put("sourceMime", "audio/wav"))
        }
    }
    val extractor = MediaExtractor()
    EncryptedMediaSource(vault.seekable(caseId, id), sourceBytes).use { data ->
        try {
            extractor.setDataSource(data)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No supported audio track was found")
            val inputFormat = extractor.getTrackFormat(track)
            val mime = checkNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                val buffer = ByteArray(PCM_BLOCK_BYTES)
                var inputEnded = false
                var outputEnded = false
                var format: PcmFormat? = null
                var written = 0L
                var firstPts: Long? = null
                var lastPts: Long? = null
                var lastActivity = System.nanoTime()
                fun outputFormat(): PcmFormat {
                    val description = codec.outputFormat
                    val encoding = if (description.containsKey(MediaFormat.KEY_PCM_ENCODING)) description.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    require(encoding == AudioFormat.ENCODING_PCM_16BIT) { "Decoder output is not PCM16" }
                    return PcmFormat(description.getInteger(MediaFormat.KEY_SAMPLE_RATE), description.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                }
                while (!outputEnded) {
                    if (cancelled()) throw InterruptedException("Decode cancelled")
                    check(System.nanoTime() - lastActivity < 30_000_000_000L) { "Android decoder stopped making progress" }
                    if (!inputEnded) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val input = checkNotNull(codec.getInputBuffer(index)).apply { clear() }
                            val sampleSize = extractor.sampleSize
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                require(sampleSize <= input.remaining()) { "Encoded packet exceeds decoder capacity" }
                                require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted media tracks require an external authorized decoder" }
                                val size = extractor.readSampleData(input, 0)
                                check(size >= 0 && size.toLong() == sampleSize) { "Encoded audio packet is incomplete" }
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                            lastActivity = System.nanoTime()
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val current = outputFormat()
                            require(format == null || written == 0L || format == current) { "Audio format changes within this track" }
                            format = current
                        }
                        else -> if (index >= 0) {
                            try {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    val pcm = format ?: outputFormat().also { format = it }
                                    require(info.size % pcm.frameBytes == 0) { "Incomplete PCM frame" }
                                    require(written + info.size <= MAX_WAVE_DATA) { "Decoded PCM exceeds the WAV storage limit" }
                                    val bytes = checkNotNull(codec.getOutputBuffer(index))
                                    require(info.offset >= 0 && info.size <= bytes.capacity() - info.offset)
                                    bytes.limit(info.offset + info.size).position(info.offset)
                                    if (firstPts == null) firstPts = info.presentationTimeUs
                                    lastPts = info.presentationTimeUs
                                    while (bytes.hasRemaining()) {
                                        val count = minOf(bytes.remaining(), buffer.size)
                                        bytes.get(buffer, 0, count); output.write(buffer, 0, count)
                                    }
                                    written += info.size; progress(written)
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally { codec.releaseOutputBuffer(index, false) }
                            lastActivity = System.nanoTime()
                        }
                    }
                }
                require(written > 0) { "The selected track produced no PCM audio" }
                return DecodedAudio(checkNotNull(format), JSONObject().put("decoder", codec.name).put("androidSdk", Build.VERSION.SDK_INT)
                    .put("sourceMime", mime).put("trackIndex", track).put("firstDecoderTimestampUs", firstPts).put("lastDecoderTimestampUs", lastPts)
                    .put("timeline", "Sequential decoded frames starting at zero"))
            } finally { codec.release() }
        } finally { extractor.release() }
    }
}
