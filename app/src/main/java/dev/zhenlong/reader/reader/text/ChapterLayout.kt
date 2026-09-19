package dev.zhenlong.reader.reader.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ReplacementSpan
import kotlin.math.roundToInt

/**
 * 正文里的一张图。排版时只需要尺寸（读文件头就够）；位图在翻到那一页之前由引擎解码好塞进 [bitmap]。
 * 小图（外字、行内小图标）跟着文字走；大图独占一行、缩到不超过版心。
 */
class PictureSpan(
    val entry: String,
    private val srcWidth: Int,
    private val srcHeight: Int,
    maxWidth: Int,
    maxHeight: Int,
    textSize: Float,
    density: Float,
) : ReplacementSpan() {
    @Volatile
    var bitmap: Bitmap? = null

    private val inline = srcHeight <= INLINE_MAX_PX
    private val fit = minOf(maxWidth.toFloat() / srcWidth, maxHeight.toFloat() / srcHeight)
    private val scale = when {
        inline -> textSize / srcHeight
        srcWidth * density >= maxWidth * 0.5f -> fit      // 插图、封面：铺到版心
        else -> minOf(density, fit)                        // 小图：按网页的 CSS 像素大小
    }
    val width = (srcWidth * scale).roundToInt().coerceAtLeast(1)
    val height = (srcHeight * scale).roundToInt().coerceAtLeast(1)
    private val indent = if (inline) 0 else (maxWidth - width) / 2   // 大图居中

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null && !inline) {
            fm.ascent = -height
            fm.top = -height
            fm.descent = 0
            fm.bottom = 0
        }
        return if (inline) width else indent + width
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val b = bitmap ?: return
        val left = x + indent
        val bottomEdge = if (inline) y.toFloat() + paint.descent() / 2 else bottom.toFloat()
        canvas.drawBitmap(b, null, RectF(left, bottomEdge - height, left + width, bottomEdge), BITMAP_PAINT)
    }

    private companion object {
        const val INLINE_MAX_PX = 48
        val BITMAP_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)
    }
}

/** 一章排好版、分好页的结果。 */
class ChapterLayout(val chapter: Int, val content: ChapterText, paint: TextPaint, width: Int, private val pageHeight: Int, lineHeight: Float) {
    // 行距滑块是 CSS 的含义（行高 = 字号 × 倍数）；StaticLayout 的倍数却是乘在字体的自然行高上的，要换算
    private val spacing = lineHeight * paint.textSize / (paint.fontMetrics.descent - paint.fontMetrics.ascent)

    val layout: StaticLayout = StaticLayout.Builder.obtain(content.text, 0, content.text.length, paint, width)
        .setLineSpacing(0f, spacing)
        .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
        .setJustificationMode(LineBreaker.JUSTIFICATION_MODE_INTER_WORD)
        .setIncludePad(false)
        .build()

    /** 每页的第一行；最后多放一个哨兵 = 总行数。 */
    private val pageStarts: IntArray = paginate()
    val pageCount get() = pageStarts.size - 1

    private fun paginate(): IntArray {
        val starts = ArrayList<Int>()
        val lines = layout.lineCount
        var line = 0
        while (line < lines) {
            starts += line
            val top = layout.getLineTop(line)
            var end = line + 1
            while (end < lines && layout.getLineBottom(end) - top <= pageHeight) end++
            // 标题不落在页底：页尾是标题行就整个挪到下一页（整页都是标题时不挪）
            var keep = end
            while (keep > line + 1 && end < lines && isHeading(keep - 1)) keep--
            line = if (keep > line) keep else end
        }
        if (starts.isEmpty()) starts += 0
        starts += lines
        return starts.toIntArray()
    }

    private fun isHeading(line: Int): Boolean {
        val at = layout.getLineStart(line)
        return content.text.getSpans(at, at + 1, HeadingSpan::class.java).isNotEmpty()
    }

    fun pageOf(offset: Int): Int {
        val line = layout.getLineForOffset(offset.coerceIn(0, content.text.length))
        var page = pageStarts.binarySearch(line)
        if (page < 0) page = -page - 2
        return page.coerceIn(0, pageCount - 1)
    }

    fun startOffset(page: Int): Int = layout.getLineStart(pageStarts[page.coerceIn(0, pageCount - 1)])

    fun pictures(page: Int): Array<PictureSpan> {
        val p = page.coerceIn(0, pageCount - 1)
        val from = layout.getLineStart(pageStarts[p])
        val to = layout.getLineEnd(pageStarts[p + 1] - 1)
        return content.text.getSpans(from, to, PictureSpan::class.java)
    }

    /**
     * 这一页的内容在版心里往下挪多少，才不显得「偏上」：
     * - 整页文字：版心高度不是行高的整数倍，多出来的那不到一行的空白上下均分，而不是全堆在页底；
     * - 只有一张图的页（封面、整页插图）：垂直居中；
     * - 其余（章末的半页、被下一页的大图挤短的页）：照常顶着上沿。
     */
    private fun topInset(p: Int): Int {
        val first = pageStarts[p]
        val last = pageStarts[p + 1] - 1
        val spare = pageHeight - (layout.getLineBottom(last) - layout.getLineTop(first))
        if (spare <= 0) return 0
        val lineHeight = layout.getLineBottom(last) - layout.getLineTop(last)
        return if (isPictureOnly(p) || spare <= lineHeight * 3 / 2) spare / 2 else 0
    }

    private fun isPictureOnly(p: Int): Boolean {
        val from = layout.getLineStart(pageStarts[p])
        val to = layout.getLineEnd(pageStarts[p + 1] - 1)
        return pictures(p).size == 1 && (from until to).all { i ->
            val c = content.text[i]
            c == '\uFFFC' || c == '\u200B' || c.isWhitespace()
        }
    }

    /**
     * @param emboldenEm 加粗：字形描边、每一笔加宽这么多（em）。描边不改字距，分页不受影响，所以画的时候才定、不用重排；
     *   任何字体都有效（系统中文字体多半只有一个字重）。正文里本来就粗的（标题、强调）叠加在上面仍然更粗
     */
    fun draw(canvas: Canvas, page: Int, emboldenEm: Float = 0f) {
        val p = page.coerceIn(0, pageCount - 1)
        val top = layout.getLineTop(pageStarts[p])
        val bottom = layout.getLineBottom(pageStarts[p + 1] - 1)
        layout.paint.apply {
            style = if (emboldenEm > 0f) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
            strokeWidth = emboldenEm * textSize
            strokeJoin = Paint.Join.ROUND   // 笔画尖角处不冒刺
        }
        canvas.save()
        canvas.translate(0f, topInset(p).toFloat())
        canvas.clipRect(0, 0, layout.width, bottom - top)
        canvas.translate(0f, -top.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    /** 点在 (x, y)（相对这一页的版心左上角）上的链接。 */
    fun linkAt(page: Int, x: Float, y: Float): LinkSpan? {
        val p = page.coerceIn(0, pageCount - 1)
        val line = layout.getLineForVertical(layout.getLineTop(pageStarts[p]) + y.toInt() - topInset(p))
        if (line >= pageStarts[p + 1] || x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x)
        return (content.text as Spanned).getSpans(offset, offset, LinkSpan::class.java).firstOrNull()
    }
}
