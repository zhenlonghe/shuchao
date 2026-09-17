package dev.zhenlong.reader.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import dev.zhenlong.reader.data.Book
import dev.zhenlong.reader.data.BookKind
import dev.zhenlong.reader.data.LibraryDao
import dev.zhenlong.reader.data.Series
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

sealed interface ScanState {
    data object Idle : ScanState
    data class Running(val done: Int, val total: Int) : ScanState
    data class Failed(val message: String) : ScanState
}

class LibraryScanner(private val context: Context, private val dao: LibraryDao) {

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state

    private val mutex = Mutex()
    private val coverDir = File(context.filesDir, "covers")
    private val order = NaturalOrder()
    private val parseDispatcher = Dispatchers.IO.limitedParallelism(PARSE_PARALLELISM)

    private class FoundFile(val uri: Uri, val name: String, val lastModified: Long, val size: Long)
    private class FoundSeries(val docId: String, val name: String) {
        val books = ArrayList<FoundFile>()
        var cover: FoundFile? = null
    }

    /** 只在用户操作（选目录 / 点重新扫描）时调用。已在扫描中则忽略。 */
    suspend fun scan(root: Uri) {
        if (!mutex.tryLock()) return
        try {
            _state.value = ScanState.Running(0, 0)
            val started = SystemClock.uptimeMillis()
            withContext(Dispatchers.IO) { doScan(root) }
            Log.i(TAG, "scan finished in ${SystemClock.uptimeMillis() - started}ms")
            _state.value = ScanState.Idle
        } catch (e: Exception) {
            Log.w(TAG, "scan failed", e)
            _state.value = ScanState.Failed("无法读取书库文件夹")
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun doScan(root: Uri) {
        coverDir.mkdirs()

        // 1. 遍历目录树
        val singles = ArrayList<FoundFile>()
        val seriesList = ArrayList<FoundSeries>()
        listChildren(root, DocumentsContract.getTreeDocumentId(root)) { docId, name, mime, modified, size ->
            when {
                name.isHidden() -> {}
                mime == Document.MIME_TYPE_DIR -> {
                    val series = FoundSeries(docId, name)
                    collectSeries(root, docId, series, topLevel = true)
                    if (series.books.isNotEmpty()) seriesList += series
                }
                name.isEpub() ->
                    singles += FoundFile(DocumentsContract.buildDocumentUriUsingTree(root, docId), name, modified, size)
            }
        }

        // 2. 增量解析：未变化的直接沿用，其余并行解析；按原顺序 await，落库和进度保持单线程
        val existing = dao.allBooks().associateBy { it.id }
        val total = singles.size + seriesList.sumOf { it.books.size }
        var done = 0
        var lastTick = 0L
        _state.value = ScanState.Running(0, total)

        val seen = HashSet<String>(total * 2)
        val pending = ArrayList<Book>()
        val seriesRows = ArrayList<Series>()

        coroutineScope {
            fun resolve(file: FoundFile, seriesId: String?): Deferred<Book> {
                val id = hash(file.uri.toString())
                seen += id
                val old = existing[id]
                val unchanged = old != null && old.lastModified == file.lastModified && old.size == file.size
                return when {
                    !unchanged -> async(parseDispatcher) { parseBook(id, file, seriesId, old) }
                    old!!.seriesId == seriesId -> CompletableDeferred(old)
                    else -> CompletableDeferred(old.copy(seriesId = seriesId))
                }
            }

            suspend fun collect(job: Deferred<Book>): Book {
                val book = job.await()
                if (book !== existing[book.id]) pending += book
                if (pending.size >= BATCH) {
                    dao.upsertBooks(pending.toList())
                    pending.clear()
                }
                done++
                // 墨水屏上每变一次字就是一次刷新，进度文本限速
                val now = SystemClock.uptimeMillis()
                if (now - lastTick >= PROGRESS_INTERVAL_MS || done == total) {
                    lastTick = now
                    _state.value = ScanState.Running(done, total)
                }
                return book
            }

            val singleJobs = singles.map { resolve(it, null) }
            val seriesJobs = seriesList.map { s ->
                val seriesUri = DocumentsContract.buildDocumentUriUsingTree(root, s.docId).toString()
                val seriesId = hash(seriesUri)
                Triple(s, Series(seriesId, seriesUri, s.name, null), s.books.map { resolve(it, seriesId) })
            }

            singleJobs.forEach { collect(it) }
            for ((found, row, jobs) in seriesJobs) {
                val books = jobs.map { collect(it) }
                val cover = found.cover?.let { seriesCover(row.id, it) }
                    ?: books.sortedWith(compareBy(order) { it.title }).firstNotNullOfOrNull { it.coverPath }
                seriesRows += row.copy(coverPath = cover)
            }
        }
        if (pending.isNotEmpty()) dao.upsertBooks(pending)

        // 3. 收尾：删除消失的书、重建 Series、清理孤儿封面
        val removed = existing.keys - seen
        dao.finishScan(removed.toList(), seriesRows)
        val keep = HashSet<String>()
        seriesRows.mapNotNullTo(keep) { it.coverPath }
        seen.mapTo(keep) { bookCoverFile(it).path }
        coverDir.listFiles()?.forEach { if (it.path !in keep) it.delete() }
    }

    private fun collectSeries(root: Uri, dirId: String, into: FoundSeries, topLevel: Boolean) {
        listChildren(root, dirId) { docId, name, mime, modified, size ->
            when {
                name.isHidden() -> {}
                mime == Document.MIME_TYPE_DIR -> collectSeries(root, docId, into, topLevel = false)
                name.isEpub() ->
                    into.books += FoundFile(DocumentsContract.buildDocumentUriUsingTree(root, docId), name, modified, size)
                topLevel && name.lowercase() in FOLDER_COVERS && into.cover?.name?.lowercase() != FOLDER_COVERS[0] ->
                    into.cover = FoundFile(DocumentsContract.buildDocumentUriUsingTree(root, docId), name, modified, size)
            }
        }
    }

    private inline fun listChildren(
        root: Uri,
        parentId: String,
        block: (docId: String, name: String, mime: String, lastModified: Long, size: Long) -> Unit,
    ) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, parentId)
        val cursor = context.contentResolver.query(children, PROJECTION, null, null, null)
            ?: throw IllegalStateException("query returned null: $children")
        cursor.use {
            while (it.moveToNext()) {
                block(it.getString(0), it.getString(1) ?: continue, it.getString(2) ?: "", it.getLong(3), it.getLong(4))
            }
        }
    }

    private fun parseBook(id: String, file: FoundFile, seriesId: String?, old: Book?): Book {
        var meta: EpubMeta? = null
        var coverPath: String? = null
        try {
            openZip(context, file.uri).use { zip ->
                meta = EpubMetaParser.parse(zip)
                val entry = meta?.coverEntry?.let(zip.entries::get)
                if (entry != null) {
                    val out = bookCoverFile(id)
                    if (writeThumbnail(zip.readBytes(entry, MAX_COVER_BYTES), out)) coverPath = out.path
                }
            }
        } catch (e: Exception) {
            // 坏文件照样上架（文件名做书名），打开时再报错
            Log.w(TAG, "parse failed: ${file.name}", e)
        }
        if (coverPath == null) bookCoverFile(id).delete()
        return Book(
            id = id,
            fileUri = file.uri.toString(),
            seriesId = seriesId,
            title = meta?.title ?: file.name.substringBeforeLast('.'),
            author = meta?.author,
            fileName = file.name,
            kind = old?.kind ?: BookKind.TEXT,   // 漫画判定在 M3
            kindOverride = old?.kindOverride,
            coverPath = coverPath,
            lastModified = file.lastModified,
            size = file.size,
            locatorJson = old?.locatorJson,
            pageIndex = old?.pageIndex,
            pageCount = null,
            lastOpenedAt = old?.lastOpenedAt,
        )
    }

    private fun seriesCover(seriesId: String, file: FoundFile): String? {
        // 文件名带 lastModified：没变就复用，变了旧文件在收尾时被清理
        val out = File(coverDir, "s_${seriesId}_${file.lastModified}.jpg")
        if (out.exists()) return out.path
        return try {
            val bytes = context.contentResolver.openInputStream(file.uri)!!.use { it.readBytes() }
            if (writeThumbnail(bytes, out)) out.path else null
        } catch (e: Exception) {
            Log.w(TAG, "series cover failed: ${file.name}", e)
            null
        }
    }

    private fun bookCoverFile(bookId: String) = File(coverDir, "b_$bookId.jpg")

    private fun writeThumbnail(bytes: ByteArray, out: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return false
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_WIDTH) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        val thumb = if (decoded.width > THUMB_WIDTH) {
            Bitmap.createScaledBitmap(decoded, THUMB_WIDTH, decoded.height * THUMB_WIDTH / decoded.width, true)
                .also { if (it !== decoded) decoded.recycle() }
        } else decoded
        out.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        thumb.recycle()
        return true
    }

    private fun String.isEpub() = endsWith(".epub", ignoreCase = true)

    /** 点开头的文件 / 目录一律不看：macOS 拷贝带来的 `._xxx.epub` 伴生文件、`.Trashes` 等，不是书。 */
    private fun String.isHidden() = startsWith(".")

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(10).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "LibraryScanner"
        const val BATCH = 20
        const val PARSE_PARALLELISM = 4
        const val PROGRESS_INTERVAL_MS = 300L
        const val THUMB_WIDTH = 320   // 书架封面宽约 210px（4 列 @1072px），留出余量
        const val MAX_COVER_BYTES = 30L * 1024 * 1024
        val FOLDER_COVERS = listOf("cover.jpg", "cover.png")
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
        )
    }
}
