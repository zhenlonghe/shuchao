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
    private companion object {
        const val MAX_JUSTIFY = 0.25f   // 差超过版心 1/4 的行不拉
    }

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
     *
     * 中文齐尾：API 34 只有按空格拉伸的两端对齐，中文一行没有空格就等于没对齐，行尾参差。
     * 这里逐行画：该齐尾的行（[justifySlack]）把差的宽度均分到字间（临时设画笔的 letterSpacing），
     * 断行不变、分页不变，只是画的时候每个字挪一点。英文 / 中英混排（行里有空格）仍由 StaticLayout 拉空格。
     */
    fun draw(canvas: Canvas, page: Int, emboldenEm: Float = 0f) {
        val p = page.coerceIn(0, pageCount - 1)
        val first = pageStarts[p]
        val last = pageStarts[p + 1] - 1
        val top = layout.getLineTop(first)
        val bottom = layout.getLineBottom(last)
        val paint = layout.paint.apply {
            style = if (emboldenEm > 0f) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
            strokeWidth = emboldenEm * textSize
            strokeJoin = Paint.Join.ROUND   // 笔画尖角处不冒刺
            letterSpacing = 0f
        }
        // 先量后画：行宽是用当前画笔现量的，字距一改就不准了
        val slacks = FloatArray(last - first + 1) { justifySlack(first + it) }
        canvas.save()
        canvas.translate(0f, topInset(p).toFloat())
        canvas.clipRect(0, 0, layout.width, bottom - top)
        canvas.translate(0f, -top.toFloat())
        for (line in first..last) {
            val slack = slacks[line - first]
            if (slack <= 0f) {
                paint.letterSpacing = 0f
                drawLine(canvas, line)
                continue
            }
            // letterSpacing 加在每个字的左右各一半：整体左移半个间距，首字仍贴左沿；行尾差半个间距，不到 1px
            val glyphs = Character.codePointCount(content.text, layout.getLineStart(line), layout.getLineVisibleEnd(line)).coerceAtLeast(1)
            val perGlyph = slack / glyphs
            paint.letterSpacing = perGlyph / paint.textSize
            canvas.save()
            canvas.translate(-perGlyph / 2, 0f)
            drawLine(canvas, line)
            canvas.restore()
        }
        paint.letterSpacing = 0f
        canvas.restore()
    }

    /** 只画这一行：Layout 没有公开的按行画的接口，用裁剪让它只画裁剪范围里的那一行（画布已在排版坐标系里）。 */
    private fun drawLine(canvas: Canvas, line: Int) {
        canvas.save()
        canvas.clipRect(0, layout.getLineTop(line), layout.width, layout.getLineBottom(line))
        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * 这一行要补到右沿的宽度（px）；0 = 不齐尾：段落末行、居中的行（标题）、有图的行、
     * 有空格的行（StaticLayout 已经拉过空格）、差得太多的行（下一个词太长挤下去的，硬拉会很稀）。
     * 画笔的 letterSpacing 必须是 0 时调用。
     */
    private fun justifySlack(line: Int): Float {
        val text = content.text
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line)
        if (end >= text.length || text[end - 1] == '\n') return 0f
        if (layout.getParagraphAlignment(line) != Layout.Alignment.ALIGN_NORMAL) return 0f
        if (text.getSpans(start, end, PictureSpan::class.java).isNotEmpty()) return 0f
        for (i in start until layout.getLineVisibleEnd(line)) if (text[i] == ' ') return 0f
        val slack = layout.width - layout.getLineMax(line)   // getLineMax 含首行缩进、不含行尾空白
        return if (slack > 0f && slack <= layout.width * MAX_JUSTIFY) slack else 0f
    }

    /** 点在 (x, y)（相对这一页的版心左上角）上的链接。 */
    fun linkAt(page: Int, x: Float, y: Float): LinkSpan? {
        val p = page.coerceIn(0, pageCount - 1)
        val line = layout.getLineForVertical(layout.getLineTop(pageStarts[p]) + y.toInt() - topInset(p))
        if (line >= pageStarts[p + 1]) return null
        // 齐尾的行画出来比排出来宽，把点按位置按比例缩回排版坐标
        val slack = justifySlack(line)
        val xx = if (slack > 0f) x * layout.getLineMax(line) / (layout.getLineMax(line) + slack) else x
        if (xx < layout.getLineLeft(line) || xx > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, xx)
        return (content.text as Spanned).getSpans(offset, offset, LinkSpan::class.java).firstOrNull()
    }
}
