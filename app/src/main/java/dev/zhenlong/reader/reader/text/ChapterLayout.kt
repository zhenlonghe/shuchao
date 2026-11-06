package dev.zhenlong.reader.reader.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LineHeightSpan
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
    /** 大图：独占一行、贴着行底画。 */
    val isBlock get() = !inline
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

/**
 * [start, end) 这一行底下要不要加段距：以 [ParagraphBreak] 标着的换行结尾（段末，不是 `<br>`），
 * 且行里没有大图——大图贴着行底画，加了段距空白会跑到图上面，整页插图还会比版心高。
 */
private fun hasParagraphGap(text: Spanned, start: Int, end: Int): Boolean =
    end > start && text[end - 1] == '\n' &&
        text.getSpans(end - 1, end, ParagraphBreak::class.java).isNotEmpty() &&
        text.getSpans(start, end, PictureSpan::class.java).none { it.isBlock }

/** 给段末那一行加 [gap] px 行底（[hasParagraphGap]）。整章挂一个就够：StaticLayout 每排一行都会问它。 */
private class ParagraphGap(private val gap: Int) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        if (!hasParagraphGap(text as Spanned, start, end)) return
        fm.descent += gap
        fm.bottom += gap
    }
}

/** 一段要涂底色的文字（划线、正在选的选区）：章内字符区间 [start, end)，[color] 是 ARGB。 */
class Mark(val start: Int, val end: Int, val color: Int)

/** 一个字符位置在页上画在哪（版心坐标）：[x] 是字的左 / 右沿，[top] / [bottom] 是这一行字形的上下沿。 */
class Caret(val x: Float, val top: Float, val bottom: Float)

/** 一章排好版、分好页的结果。 */
class ChapterLayout(val chapter: Int, val content: ChapterText, paint: TextPaint, width: Int, private val pageHeight: Int, lineHeight: Float) {
    private companion object {
        const val MAX_JUSTIFY = 0.25f   // 差超过版心 1/4 的行不拉
    }

    // 行距滑块是 CSS 的含义（行高 = 字号 × 倍数）；StaticLayout 的倍数却是乘在字体的自然行高上的，要换算
    private val spacing = lineHeight * paint.textSize / (paint.fontMetrics.descent - paint.fontMetrics.ascent)

    /**
     * 段距 = 行距（行与行之间的空白，行高 − 字号）× 1.5：段末那一行底下多垫半个行距，随行距滑块等比变。
     * 垫在行的 descent 上，StaticLayout 之后还会把它乘上 [spacing]，所以先除掉。
     */
    private val paragraphGap = ((lineHeight - 1f) * paint.textSize * 0.5f / spacing).roundToInt()

    // ChapterText 每次排版都是新解析出来的，直接挂上，不复制整章
    init {
        val text = content.text
        if (paragraphGap > 0 && text is Spannable) text.setSpan(ParagraphGap(paragraphGap), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    }

    val layout: StaticLayout = StaticLayout.Builder.obtain(content.text, 0, content.text.length, paint, width)
        .setLineSpacing(0f, spacing)
        .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
        .setJustificationMode(LineBreaker.JUSTIFICATION_MODE_INTER_WORD)
        .setIncludePad(false)
        .build()

    /** 第 [line] 行底下段距占的高度（StaticLayout 把垫进去的乘过 [spacing]）；分页、居中时不算内容。 */
    private fun gapBelow(line: Int): Int =
        if (paragraphGap > 0 && hasParagraphGap(content.text, layout.getLineStart(line), layout.getLineEnd(line))) (paragraphGap * spacing).roundToInt()
        else 0

    /** 第 [line] 行内容的下沿：行底减去段距。 */
    private fun contentBottom(line: Int) = layout.getLineBottom(line) - gapBelow(line)

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
            while (end < lines && contentBottom(end) - top <= pageHeight) end++
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
        val spare = pageHeight - (contentBottom(last) - layout.getLineTop(first))
        if (spare <= 0) return 0
        val lineHeight = contentBottom(last) - layout.getLineTop(last)
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
            val perGlyph = perGlyph(line, slack)
            paint.letterSpacing = perGlyph / paint.textSize
            canvas.save()
            canvas.translate(-perGlyph / 2, 0f)
            drawLine(canvas, line)
            canvas.restore()
        }
        paint.letterSpacing = 0f
        canvas.restore()
    }

    private fun perGlyph(line: Int, slack: Float): Float {
        if (slack <= 0f) return 0f
        val glyphs = Character.codePointCount(content.text, layout.getLineStart(line), layout.getLineVisibleEnd(line)).coerceAtLeast(1)
        return slack / glyphs
    }

    private val markPaint = Paint()

    /**
     * 划线 / 选区的底色（版心坐标），要画在字的**下面一层**：和描边加粗的字画进同一个 Canvas 的话，
     * 前面有一个 drawRect，HWUI 画出来的整页字就细一档（真机截图对比过，画笔设置没变），所以由界面单独一层先画。
     */
    fun drawMarks(canvas: Canvas, page: Int, marks: List<Mark>) {
        if (marks.isEmpty()) return
        val p = page.coerceIn(0, pageCount - 1)
        val first = pageStarts[p]
        val last = pageStarts[p + 1] - 1
        canvas.save()
        canvas.translate(0f, topInset(p) - layout.getLineTop(first).toFloat())
        for (mark in marks) for (line in first..last) drawMark(canvas, line, mark)
        canvas.restore()
    }

    private val bidiPath = Path()

    /** 一段划线在第 [line] 行上的底色（排版坐标），只涂字形的上下沿（[glyphBox]）。 */
    private fun drawMark(canvas: Canvas, line: Int, mark: Mark) {
        val from = maxOf(mark.start, layout.getLineStart(line))
        val to = minOf(mark.end, layout.getLineVisibleEnd(line))
        if (from >= to) return
        val (glyphTop, glyphBottom) = glyphBox(line)
        markPaint.color = mark.color
        if (hasRtl(line)) {
            // 阿拉伯文 / 希伯来文等：字符偏移和 x 不同向，双向混排时选区还会断成几段。交给 Layout 算可视选区
            // （这种行不做齐尾，见 justifySlack，所以和画出来的字对得上）
            bidiPath.reset()
            layout.getSelectionPath(from, to, bidiPath)
            canvas.save()
            canvas.clipRect(0f, glyphTop, layout.width.toFloat(), glyphBottom)
            canvas.drawPath(bidiPath, markPaint)
            canvas.restore()
        } else {
            val pg = perGlyph(line, justifySlack(line))
            canvas.drawRect(drawnX(line, from, pg), glyphTop, drawnX(line, to, pg), glyphBottom, markPaint)
        }
    }

    /** 这一行有没有从右往左书写的字（R / AL 类）。 */
    private fun hasRtl(line: Int): Boolean {
        val text = content.text
        for (i in layout.getLineStart(line) until layout.getLineEnd(line)) {
            when (Character.getDirectionality(text[i])) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            }
        }
        return false
    }

    /**
     * 字形的上下沿（排版坐标）：行顶到基线是字的 ascent，基线往下按画笔的 descent 比例算——
     * 行距加大时 StaticLayout 把多出来的空都加在行底，按整行涂的话上下两行的划线会连成一片。
     */
    private fun glyphBox(line: Int): Pair<Float, Float> {
        val baseline = layout.getLineBaseline(line).toFloat()
        val ascent = -layout.getLineAscent(line).toFloat()
        val fm = layout.paint.fontMetrics
        val descent = ascent * fm.descent / -fm.ascent
        return (baseline - ascent) to minOf(baseline + descent, layout.getLineBottom(line).toFloat())
    }

    /**
     * 字符位置 [offset] 在第 [line] 行画出来的 x：排版位置 + 前面每个字多出来的齐尾间距（见 [draw]）。
     * [offset] 正好是这一行末尾（行尾没有空白、被硬折下去的一行）时，getPrimaryHorizontal 会给出下一行行首，要用行宽。
     */
    private fun drawnX(line: Int, offset: Int, perGlyph: Float): Float {
        val start = layout.getLineStart(line)
        val x = if (offset < layout.getLineEnd(line) || line == layout.lineCount - 1) layout.getPrimaryHorizontal(offset)
        else if (layout.getParagraphAlignment(line) == Layout.Alignment.ALIGN_NORMAL) layout.getLineMax(line)   // 含首行缩进
        else layout.getLineRight(line)
        return x + Character.codePointCount(content.text, start, offset.coerceAtMost(layout.getLineVisibleEnd(line)).coerceAtLeast(start)) * perGlyph
    }

    /**
     * 字符位置在这一页上的位置（版心坐标，含 [topInset]）：选区两端的把手、划线的弹出条用。不在这一页 = null。
     * [end] = 这是一段的结尾：落在行首时算上一行的行尾，而不是下一行的开头。
     */
    fun caretAt(page: Int, offset: Int, end: Boolean): Caret? {
        val p = page.coerceIn(0, pageCount - 1)
        val line = layout.getLineForOffset(if (end && offset > 0) offset - 1 else offset)
        if (line < pageStarts[p] || line >= pageStarts[p + 1]) return null
        val dy = topInset(p) - layout.getLineTop(pageStarts[p]).toFloat()
        val (glyphTop, glyphBottom) = glyphBox(line)
        val x = drawnX(line, offset, perGlyph(line, justifySlack(line)))
        return Caret(x, glyphTop + dy, glyphBottom + dy)
    }

    /**
     * 点在 (x, y)（相对这一页的版心左上角）上的字符位置；点在字的右半边算下一个位置（拖选区时手指停在字中间，选到这个字为止）。
     * [clamp] = 点到了正文外面也就近找一行（拖选区时手指出了版心）；否则不在任何一行的文字上 = null。
     */
    fun offsetAt(page: Int, x: Float, y: Float, clamp: Boolean = false): Int? = hit(page, x, y, clamp)?.second

    /** 手指按住的那个字（长按开始选词用）；不在字上 = null。 */
    fun charAt(page: Int, x: Float, y: Float): Int? {
        val (line, offset) = hit(page, x, y, clamp = false) ?: return null
        val start = layout.getLineStart(line)
        val left = x < drawnX(line, offset, perGlyph(line, justifySlack(line)))
        // 光标 offset 左右两边各是哪个字：从左往右的字在光标右边是 offset、左边是 offset - 1；
        // 从右往左的字（阿拉伯文 / 希伯来文）反过来，offset 那个字在光标左边
        val rtl = offset < layout.getLineEnd(line) && layout.isRtlCharAt(offset)
        val at = when {
            rtl -> if (left) offset else offset - 1
            left -> offset - 1
            else -> offset
        }
        return at.takeIf { it >= start && it < layout.getLineVisibleEnd(line) }
    }

    private fun hit(page: Int, x: Float, y: Float, clamp: Boolean): Pair<Int, Int>? {
        val p = page.coerceIn(0, pageCount - 1)
        val first = pageStarts[p]
        val last = pageStarts[p + 1] - 1
        var line = layout.getLineForVertical(layout.getLineTop(first) + y.toInt() - topInset(p))
        if (line !in first..last) {
            if (!clamp) return null
            line = line.coerceIn(first, last)
        }
        // 齐尾的行画出来比排出来宽，把点按位置按比例缩回排版坐标
        val slack = justifySlack(line)
        val xx = if (slack > 0f) x * layout.getLineMax(line) / (layout.getLineMax(line) + slack) else x
        if (!clamp && (xx < layout.getLineLeft(line) || xx > layout.getLineRight(line))) return null
        return line to layout.getOffsetForHorizontal(line, xx)
    }

    /** 这一页正文的字符区间 [start, end)。 */
    fun pageRange(page: Int): IntRange {
        val p = page.coerceIn(0, pageCount - 1)
        return layout.getLineStart(pageStarts[p]) until layout.getLineEnd(pageStarts[p + 1] - 1)
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
        if (hasRtl(line)) return 0f   // 齐尾是按逻辑顺序往后推字的，对从右往左的字不成立
        val slack = layout.width - layout.getLineMax(line)   // getLineMax 含首行缩进、不含行尾空白
        return if (slack > 0f && slack <= layout.width * MAX_JUSTIFY) slack else 0f
    }

    /** 点在 (x, y)（相对这一页的版心左上角）上的链接。 */
    fun linkAt(page: Int, x: Float, y: Float): LinkSpan? {
        val offset = offsetAt(page, x, y) ?: return null
        return (content.text as Spanned).getSpans(offset, offset, LinkSpan::class.java).firstOrNull()
    }
}
