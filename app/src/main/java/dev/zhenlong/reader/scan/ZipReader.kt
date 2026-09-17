package dev.zhenlong.reader.scan

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * 基于 FileChannel 的只读 ZIP 访问。SAF 只给 fd 不给路径，java.util.zip.ZipFile 用不了；
 * ZipInputStream 又得顺序读完整个文件（漫画 EPUB 动辄上百 MB）。这里只读中央目录，按需随机读条目。
 * 不支持 ZIP64 与加密。
 */
class ZipReader(private val channel: FileChannel, private val owner: Closeable? = null) : Closeable {

    class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val size: Long,
        internal val headerOffset: Long,
    )

    val entries: Map<String, Entry> = readCentralDirectory()

    fun open(entry: Entry): InputStream {
        val header = readFully(entry.headerOffset, 30)
        if (header.getInt(0) != LOCAL_SIG) throw IOException("bad local header: ${entry.name}")
        val dataStart = entry.headerOffset + 30 + header.u16(26) + header.u16(28)
        return when (entry.method) {
            0 -> RangeStream(dataStart, entry.compressedSize, padding = false)
            8 -> {
                val inflater = Inflater(true)
                object : InflaterInputStream(RangeStream(dataStart, entry.compressedSize, padding = true), inflater) {
                    override fun close() {
                        super.close()
                        inflater.end()
                    }
                }
            }
            else -> throw IOException("unsupported compression ${entry.method}: ${entry.name}")
        }
    }

    fun readBytes(entry: Entry, limit: Long = Long.MAX_VALUE): ByteArray {
        if (entry.size > limit) throw IOException("entry too large: ${entry.name}")
        return open(entry).use { it.readBytes() }
    }

    override fun close() {
        channel.close()
        owner?.close()
    }

    private fun readCentralDirectory(): Map<String, Entry> {
        val fileSize = channel.size()
        val tailSize = minOf(fileSize, 22L + 0xFFFF).toInt()
        if (tailSize < 22) throw IOException("not a zip")
        val tail = readFully(fileSize - tailSize, tailSize)
        var eocd = tailSize - 22
        while (eocd >= 0 && tail.getInt(eocd) != EOCD_SIG) eocd--
        if (eocd < 0) throw IOException("zip end record not found")

        val count = tail.u16(eocd + 10)
        val cdSize = tail.u32(eocd + 12)
        val cdOffset = tail.u32(eocd + 16)
        if (count == 0xFFFF || cdOffset == 0xFFFFFFFFL) throw IOException("zip64 not supported")
        if (cdOffset + cdSize > fileSize || cdSize > Int.MAX_VALUE) throw IOException("corrupt zip")

        val cd = readFully(cdOffset, cdSize.toInt())
        val result = LinkedHashMap<String, Entry>(count * 2)
        var p = 0
        repeat(count) {
            if (p + 46 > cd.limit() || cd.getInt(p) != CENTRAL_SIG) throw IOException("corrupt central directory")
            val nameLen = cd.u16(p + 28)
            val name = String(cd.array(), p + 46, nameLen, Charsets.UTF_8)
            result[name] = Entry(
                name = name,
                method = cd.u16(p + 10),
                compressedSize = cd.u32(p + 20),
                size = cd.u32(p + 24),
                headerOffset = cd.u32(p + 42),
            )
            p += 46 + nameLen + cd.u16(p + 30) + cd.u16(p + 32)
        }
        return result
    }

    private fun readFully(position: Long, length: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        var pos = position
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) throw IOException("unexpected end of zip")
            pos += n
        }
        buf.flip()
        return buf
    }

    /** 文件的一段。padding：raw deflate 的 Inflater 需要在末尾多喂一个字节（同 ZipFile 的做法）。 */
    private inner class RangeStream(private var pos: Long, length: Long, private var padding: Boolean) : InputStream() {
        private val end = pos + length

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= end) {
                if (!padding) return -1
                padding = false
                b[off] = 0
                return 1
            }
            val want = minOf(len.toLong(), end - pos).toInt()
            val n = channel.read(ByteBuffer.wrap(b, off, want), pos)
            if (n < 0) throw IOException("unexpected end of zip")
            pos += n
            return n
        }
    }

    private fun ByteBuffer.u16(index: Int): Int = getShort(index).toInt() and 0xFFFF
    private fun ByteBuffer.u32(index: Int): Long = getInt(index).toLong() and 0xFFFFFFFFL

    private companion object {
        const val LOCAL_SIG = 0x04034b50
        const val CENTRAL_SIG = 0x02014b50
        const val EOCD_SIG = 0x06054b50
    }
}
