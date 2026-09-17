package dev.zhenlong.reader.reader

import dev.zhenlong.reader.scan.ZipReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.RelativeUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.archive.ArchiveProperties
import org.readium.r2.shared.util.archive.archive
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.file.FileSystemError
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.filename
import java.io.IOException

/**
 * 把 [ZipReader] 接给 Readium。Readium 自带的 content:// 支持每次区间读都要重新开流再 skip，
 * 这里直接在 fd 上随机读。文字书的条目都很小：首次访问整条读进内存，区间读在内存里切。
 */
class ZipContainer(private val zip: ZipReader) : Container<Resource> {

    private val byUrl: Map<Url, ZipReader.Entry> = zip.entries.values
        .filterNot { it.name.endsWith("/") }
        .mapNotNull { entry -> Url.fromDecodedPath(entry.name)?.let { it to entry } }
        .toMap()

    override val entries: Set<Url> get() = byUrl.keys

    override fun get(url: Url): Resource? {
        val path = (url as? RelativeUrl)?.path ?: return null
        val key = Url.fromDecodedPath(path) ?: return null
        return byUrl[key]?.let { EntryResource(key, it) }
    }

    /** 条目解压后的字节数，给全书页数估算用；不读内容。 */
    fun sizeOf(url: Url): Long = (url as? RelativeUrl)?.path?.let(Url::fromDecodedPath)?.let(byUrl::get)?.size ?: 0L

    override fun close() = zip.close()

    private inner class EntryResource(private val url: Url, private val entry: ZipReader.Entry) : Resource {
        private val mutex = Mutex()
        private var bytes: ByteArray? = null

        override val sourceUrl: AbsoluteUrl? = null

        override suspend fun properties(): Try<Resource.Properties, ReadError> = Try.success(
            Resource.Properties {
                filename = url.filename
                archive = ArchiveProperties(entryLength = entry.compressedSize, isEntryCompressed = entry.method != 0)
            },
        )

        override suspend fun length(): Try<Long, ReadError> = Try.success(entry.size)

        override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> = try {
            val all = mutex.withLock {
                bytes ?: withContext(Dispatchers.IO) { zip.readBytes(entry) }.also { bytes = it }
            }
            Try.success(
                if (range == null) all
                else {
                    val from = range.first.coerceIn(0, all.size.toLong()).toInt()
                    val to = (range.last + 1).coerceIn(from.toLong(), all.size.toLong()).toInt()
                    all.copyOfRange(from, to)
                },
            )
        } catch (e: IOException) {
            Try.failure(ReadError.Access(FileSystemError.IO(e)))
        }

        override fun close() {
            bytes = null
        }
    }
}
