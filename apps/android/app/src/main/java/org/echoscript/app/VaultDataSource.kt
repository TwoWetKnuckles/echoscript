package org.echoscript.app
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VaultDataSource(private val vault: EvidenceVault, private val caseId: String, private val artifactId: String) : BaseDataSource(false) {
    private var channel: SeekableByteChannel? = null
    private var remaining = 0L
    private var opened = false
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val source = vault.seekable(caseId, artifactId)
        try {
            // Tink selects/authenticates its decryption channel on the first read.
            // size() is deliberately unavailable before that initialization.
            check(source.read(ByteBuffer.allocate(1)) == 1) { "Evidence is empty or unavailable" }
            require(dataSpec.position <= source.size()) { "Playback position exceeds evidence length" }
            source.position(dataSpec.position)
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) source.size() - dataSpec.position else minOf(dataSpec.length, source.size() - dataSpec.position)
            channel = source; opened = true; transferStarted(dataSpec); return remaining
        } catch (failure: Exception) { source.close(); throw failure }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val count = channel!!.read(ByteBuffer.wrap(buffer, offset, minOf(length.toLong(), remaining).toInt()))
        if (count < 0) return C.RESULT_END_OF_INPUT
        remaining -= count; bytesTransferred(count); return count
    }
    override fun getUri(): Uri = Uri.parse("echoscript://$caseId/$artifactId")
    override fun close() { try { channel?.close() } finally { channel = null; if (opened) { opened = false; transferEnded() } } }
}
