package org.echoscript.app

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.util.zip.CRC32

private const val MAX_ENTRIES = 10003L
private const val MAX_DIRECTORY = 8L * 1024 * 1024

/** Bound archive metadata before handing any attacker-supplied directory to the ZIP parser. */
internal fun preflightArchive(channel: SeekableByteChannel, bytes: Long) {
    require(bytes >= 22 && bytes <= EvidenceVault.MAX_PACKAGE_BYTES)
    fun at(position: Long, size: Int): ByteBuffer {
        require(position >= 0 && size.toLong() <= bytes - position) { "Truncated case archive" }
        val data = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(position)
        while (data.hasRemaining()) check(channel.read(data) > 0) { "Truncated case archive" }
        data.flip()
        return data
    }
    fun ByteBuffer.u16(offset: Int) = getShort(offset).toInt() and 0xffff
    fun ByteBuffer.u32(offset: Int) = getInt(offset).toLong() and 0xffffffffL
    val size = minOf(bytes, 65557).toInt()
    val tail = at(bytes - size, size)
    val index = (size - 22 downTo 0).firstOrNull { tail.getInt(it) == 0x06054b50 && it + 22 + tail.u16(it + 20) == size }
        ?: error("Case archive has no valid end record")
    val end = bytes - size + index
    require(tail.u16(index + 4) == 0 && tail.u16(index + 6) == 0) { "Multi-volume packages are unsupported" }
    var entries = tail.u16(index + 10).toLong()
    require(tail.u16(index + 8).toLong() == entries)
    var directory = tail.u32(index + 12)
    var position = tail.u32(index + 16)
    var directoryEnd = end
    if (entries == 0xffffL || directory == 0xffffffffL || position == 0xffffffffL) {
        val locator = at(end - 20, 20)
        require(locator.getInt(0) == 0x07064b50 && locator.getInt(4) == 0 && locator.getInt(16) == 1) { "Invalid ZIP64 locator" }
        val zip64 = locator.getLong(8)
        val record = at(zip64, 56)
        require(record.getInt(0) == 0x06064b50 && record.getInt(16) == 0 && record.getInt(20) == 0) { "Invalid ZIP64 directory" }
        val recordSize = record.getLong(4)
        require(recordSize >= 44 && recordSize == end - 20 - zip64 - 12)
        entries = record.getLong(32)
        require(record.getLong(24) == entries)
        directory = record.getLong(40)
        position = record.getLong(48)
        directoryEnd = zip64
    }
    require(entries in 3..MAX_ENTRIES && directory in 1..MAX_DIRECTORY && position >= 0 && directory == directoryEnd - position) { "Case archive exceeds its directory limits" }
    // Count the actual records within the bounded directory. Do not trust the footer count alone.
    val records = at(position, directory.toInt())
    var cursor = 0
    var counted = 0L
    while (cursor < records.limit()) {
        require(records.limit() - cursor >= 46 && records.getInt(cursor) == 0x02014b50 && ++counted <= entries) { "Invalid ZIP directory entry" }
        cursor += 46 + records.u16(cursor + 28) + records.u16(cursor + 30) + records.u16(cursor + 32)
    }
    require(cursor == records.limit() && counted == entries) { "ZIP directory count mismatch" }
    channel.position(0)
}

internal fun <T> readArchiveEntry(
    zip: ZipFile,
    entry: ZipArchiveEntry,
    expectedBytes: Long,
    cancelled: () -> Boolean,
    read: (InputStream) -> T,
): T {
    require(entry.size == expectedBytes && expectedBytes >= 0 && entry.crc >= 0)
    val crc = CRC32()
    var count = 0L
    zip.getInputStream(entry).use { input ->
        val verified = object : InputStream() {
            override fun read(): Int {
                if (cancelled()) throw InterruptedException("Case import cancelled")
                val value = input.read()
                if (value >= 0) { require(++count <= expectedBytes); crc.update(value) }
                return value
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (cancelled()) throw InterruptedException("Case import cancelled")
                val n = input.read(bytes, offset, length)
                if (n > 0) { count += n; require(count <= expectedBytes); crc.update(bytes, offset, n) }
                return n
            }
        }
        val value = read(verified)
        check(count == expectedBytes && crc.value == entry.crc) { "Case archive length or CRC mismatch" }
        return value
    }
}
