package dev.zhenlong.reader.reader.text

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannedString
import android.text.TextPaint
import dev.zhenlong.reader.reader.PageEstimator
import dev.zhenlong.reader.scan.EpubMetaParser
import dev.zhenlong.reader.scan.ZipReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 屏幕上的这一页。[bookPage] / [bookPages]：全书页码（估算，见 [PageEstimator]）。 */
class TextPage(val layout: ChapterLayout, val page: Int, val bookPage: Int, val bookPages: Int) {
    val chapter get() = layout.chapter
    val offset get() = layout.startOffset(page)
    val chapterLength get() = layout.content.text.length
}

/** 排版参数；任何一项变了，已排好的章节全部作废。 */
data class TextParams(
    val width: Int,
    val height: Int,
    val textSize: Float,
    val lineHeight: Float,
    val typeface: Typeface,
    val density: Float,
)

/** 在某一章里落到哪儿。 */
sealed interface Spot {
    data class Offset(val offset: Int) : Spot
    data class Progression(val fraction: Double) : Spot
    data class Anchor(val id: String) : Spot
    data object LastPage : Spot
}

/**
 * 原生文字引擎：XHTML → [ChapterParser] → StaticLayout 分页 → 直接画在 Canvas 上，不经 WebView。
 * 一个循环始终朝最新的目标干活（同 [dev.zhenlong.reader.reader.ReaderHostViewModel] 的漫画翻页）；
 * 章内翻页是同步的（换个页号重画），跨章才需要排版，所以相邻章节提前排好。
 */
class TextEngine(
    private val scope: CoroutineScope,
    private val zip: ZipReader,
    private val documents: List<String>,
    private val estimator: PageEstimator,
    startChapter: Int,
    startSpot: Spot,
) {
    private val _page = MutableStateFlow<TextPage?>(null)
    val page: StateFlow<TextPage?> = _page

    private var params: TextParams? = null
    private var target: Pair<Int, Spot>? = startChapter.coerceIn(0, documents.size - 1) to startSpot
    private val layouts = HashMap<Int, ChapterLayout>()   // 只在主线程碰
    private var loader: Job? = null
    private var backStack: Pair<Int, Int>? = null         // 点链接前的位置，返回键回去

    fun setParams(p: TextParams) {
        if (p == params || p.width <= 0 || p.height <= 0) return
        params = p
        layouts.clear()
        estimator.reset()
        // 保持读到的那个字不动，而不是保持页号
        _page.value?.let { target = it.chapter to Spot.Offset(it.offset) }
        load()
    }

    fun turn(forward: Boolean) {
        val now = _page.value ?: return
        val next = now.page + if (forward) 1 else -1
        when {
            next in 0 until now.layout.pageCount && picturesReady(now.layout, next) -> show(now.layout, next)
            next in 0 until now.layout.pageCount -> go(now.chapter, Spot.Offset(now.layout.startOffset(next)))
            forward && now.chapter + 1 < documents.size -> go(now.chapter + 1, Spot.Offset(0))
            !forward && now.chapter > 0 -> go(now.chapter - 1, Spot.LastPage)
        }
    }

    fun go(chapter: Int, spot: Spot) {
        if (chapter !in documents.indices) return
        target = chapter to spot
        load()
    }

    /** 点了正文里的链接。书内的就跳过去（记住来处），书外的不理。@return 是否处理了 */
    fun follow(link: LinkSpan): Boolean {
        val now = _page.value ?: return false
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(link.href)) return true
        val path = link.href.substringBefore('#')
        val chapter = if (path.isEmpty()) now.chapter else documents.indexOf(EpubMetaParser.resolve(documents[now.chapter], path))
        if (chapter < 0) return true
        backStack = now.chapter to now.offset
        go(chapter, link.href.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let { Spot.Anchor(it) } ?: Spot.Offset(0))
        return true
    }

    /** @return 是否有「来处」可回 */
    fun back(): Boolean {
        val (chapter, offset) = backStack ?: return false
        backStack = null
        go(chapter, Spot.Offset(offset))
        return true
    }

    fun chapterOf(documentPath: String): Int? = documents.indexOf(documentPath).takeIf { it >= 0 }

    private fun load() {
        if (loader?.isActive == true) return
        loader = scope.launch {
            while (true) {
                val p = params ?: return@launch
                val (chapter, spot) = target ?: break
                val layout = layouts[chapter] ?: build(chapter, p) ?: run { target = null; return@launch }
                if (p != params) continue
                val page = when (spot) {
                    is Spot.Offset -> layout.pageOf(spot.offset)
                    is Spot.Progression -> layout.pageOf((spot.fraction * layout.content.text.length).toInt())
                    is Spot.Anchor -> layout.pageOf(layout.content.anchors[spot.id] ?: 0)
                    Spot.LastPage -> layout.pageCount - 1
                }
                decodePictures(layout, page)
                if (target != chapter to spot) continue
                target = null
                show(layout, page)

                // 备好下一步：前后页的图、下一章的版
                decodePictures(layout, page + 1)
                decodePictures(layout, page - 1)
                if (target == null && p == params && chapter + 1 < documents.size && chapter + 1 !in layouts) build(chapter + 1, p)
                if (target == null) break
            }
            _page.value?.chapter?.let { c -> layouts.keys.retainAll { it in c - 1..c + 1 } }
        }
    }

    private fun show(layout: ChapterLayout, page: Int) {
        estimator.record(layout.chapter, layout.pageCount)
        val (bookPage, bookPages) = estimator.locate(layout.chapter, page + 1) ?: (page + 1 to layout.pageCount)
        _page.value = TextPage(layout, page, bookPage, bookPages)
        // 章内翻页走的是同步路径，这里顺手把再下一页的图也备上，并放掉走远了的
        scope.launch {
            decodePictures(layout, page + 1)
            decodePictures(layout, page - 1)
            for (far in listOf(page - 2, page + 2)) if (far in 0 until layout.pageCount) layout.pictures(far).forEach { it.bitmap = null }
        }
    }

    private fun picturesReady(layout: ChapterLayout, page: Int) = layout.pictures(page).all { it.bitmap != null }

    private suspend fun decodePictures(layout: ChapterLayout, page: Int) {
        if (page !in 0 until layout.pageCount) return
        val missing = layout.pictures(page).filter { it.bitmap == null }
        if (missing.isEmpty()) return
        withContext(Dispatchers.Default) {
            for (span in missing) runCatching {
                val bytes = zip.readBytes(zip.entries.getValue(span.entry), MAX_IMAGE_BYTES)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= span.width && bounds.outHeight / (sample * 2) >= span.height) sample *= 2
                span.bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }

    private suspend fun build(chapter: Int, p: TextParams): ChapterLayout? = withContext(Dispatchers.Default) {
        // 每章一支自己的画笔：排版在后台线程，主线程同时可能在用别的章的画笔画
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = p.textSize
            typeface = p.typeface
        }
        runCatching {
            val document = documents[chapter]
            val parser = ChapterParser(em = p.textSize.toInt()) { src ->
                val entry = EpubMetaParser.resolve(document, src)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                zip.entries[entry]?.let { e -> zip.open(e).use { BitmapFactory.decodeStream(it, null, bounds) } }
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    PictureSpan(entry, bounds.outWidth, bounds.outHeight, p.width, p.height, p.textSize, p.density)
                } else null
            }
            val bytes = zip.readBytes(zip.entries.getValue(document), MAX_XHTML_BYTES)
            ChapterLayout(chapter, parser.parse(bytes), paint, p.width, p.height, p.lineHeight)
        }.getOrElse {
            // 坏掉的一章不能把整本书卡住
            ChapterLayout(chapter, ChapterText(SpannedString("（这一章无法显示）"), emptyMap()), paint, p.width, p.height, p.lineHeight)
        }
    }.also { if (it != null && p == params) layouts[chapter] = it }

    private companion object {
        const val MAX_XHTML_BYTES = 16L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 64L * 1024 * 1024
    }
}
