package dev.zhenlong.reader.reader.text

import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.UnderlineSpan
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** 标记：这段是标题（分页时不让标题孤零零落在页底）。 */
class HeadingSpan

/** 标记：可点的链接。href 是原文里的相对地址。 */
class LinkSpan(val href: String)

/** 一章排版前的内容：带样式的文字 + 锚点位置（目录 / 脚注跳转用）。 */
class ChapterText(val text: Spanned, val anchors: Map<String, Int>)

/**
 * XHTML → 带 span 的文字。只认结构（标题、段落、强调、列表、引用、图片、链接），
 * **不认出版商的 CSS**：换来的是不经 WebView、开书和翻页快一个量级。版式复杂的书可以切到「原版」（Readium）。
 *
 * @param em 正文一个字的宽度（px），缩进用
 * @param image 根据 src 造一个图片 span；返回 null = 这张图取不到，跳过
 */
class ChapterParser(private val em: Int, private val image: (src: String) -> Any?) {
    private val out = SpannableStringBuilder()
    private val anchors = HashMap<String, Int>()
    private var listDepth = 0
    private var spaceFromNewline = false

    /** 传字节而不是字符串：编码交给 jsoup 从 BOM / XML 声明 / meta 里认。 */
    fun parse(xhtml: ByteArray): ChapterText {
        val body = Jsoup.parse(ByteArrayInputStream(xhtml), null, "").body()
        walk(body)
        trimEnd()
        return ChapterText(out, anchors)
    }

    private fun walk(parent: Node) {
        for (node in parent.childNodes()) {
            when (node) {
                is TextNode -> text(node.wholeText)
                is Element -> element(node)
            }
        }
    }

    private fun element(e: Element) {
        e.id().takeIf { it.isNotEmpty() }?.let { anchors.putIfAbsent(it, out.length) }
        val tag = e.normalName()
        when (tag) {
            "script", "style", "head", "title", "rt", "rp" -> return
            "br" -> { out.append('\n'); return }
            "hr" -> { block(); return }
            "img" -> { picture(e.attr("src")); return }
            "image" -> { picture(e.attr("xlink:href").ifEmpty { e.attr("href") }); return }
        }

        val isBlock = tag in BLOCKS
        val isHeading = tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6'
        if (isBlock) block()
        if (isHeading && out.isNotEmpty()) out.append("$BLANK\n")   // 标题上下各空一行
        val start = out.length
        when (tag) {
            "li" -> out.append(if (e.parent()?.normalName() == "ol") "${e.elementSiblingIndex() + 1}. " else "• ")
            "ul", "ol" -> listDepth++
        }
        walk(e)
        if (tag == "ul" || tag == "ol") listDepth--
        val end = out.length
        if (end > start) style(tag, e, start, end)
        if (isHeading && end > start) out.append("\n$BLANK")
        if (isBlock) block()
        if (tag == "td" || tag == "th") out.append("  ")
    }

    private fun style(tag: String, e: Element, start: Int, end: Int) {
        fun span(what: Any) = out.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        when (tag) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                span(HeadingSpan())
                span(StyleSpan(Typeface.BOLD))
                span(RelativeSizeSpan(HEADING_SIZES[tag[1] - '1']))
                if (tag <= "h2") span(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER))
            }
            "p" -> if (listDepth == 0) span(LeadingMarginSpan.Standard(em * 2, 0))   // 首行缩进两字
            "blockquote" -> span(LeadingMarginSpan.Standard(em * 2))
            "li" -> span(LeadingMarginSpan.Standard(em * (listDepth - 1).coerceAtLeast(0), em * listDepth))
            "b", "strong" -> span(StyleSpan(Typeface.BOLD))
            "i", "em", "cite" -> span(StyleSpan(Typeface.ITALIC))
            "u" -> span(UnderlineSpan())
            "sup" -> { span(SuperscriptSpan()); span(RelativeSizeSpan(0.7f)) }
            "sub" -> { span(SubscriptSpan()); span(RelativeSizeSpan(0.7f)) }
            "a" -> e.attr("href").takeIf { it.isNotEmpty() }?.let { span(LinkSpan(it)) }
            "center" -> span(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER))
        }
    }

    /** HTML 的空白折叠。源码里两个汉字之间的**换行**不该变成空格（真打的空格要留着）。 */
    private fun text(raw: String) {
        for (c in raw) {
            val atBlockStart = out.isEmpty() || out.last() == '\n'
            when {
                c == '\u3000' && atBlockStart -> {}                      // 段首的全角空格：统一用缩进
                c == ' ' || c == '\n' || c == '\t' || c == '\r' -> {
                    if (!atBlockStart && out.last() != ' ') {
                        out.append(' ')
                        spaceFromNewline = false
                    }
                    if (c == '\n' || c == '\r') spaceFromNewline = true
                }
                else -> {
                    if (spaceFromNewline && out.length >= 2 && out.last() == ' ' && isCjk(c) && isCjk(out[out.length - 2])) {
                        out.delete(out.length - 1, out.length)
                    }
                    spaceFromNewline = false
                    out.append(c)
                }
            }
        }
    }

    private fun picture(src: String) {
        val span = src.takeIf { it.isNotEmpty() }?.let(image) ?: return
        val start = out.length
        out.append('￼')
        out.setSpan(span, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** 开始 / 结束一个块：保证落在新的一行上，连续的空行并成一个换行。 */
    private fun block() {
        trimEnd()
        if (out.isNotEmpty()) out.append('\n')
    }

    private fun trimEnd() {
        var n = out.length
        while (n > 0 && (out[n - 1] == ' ' || out[n - 1] == '\n')) n--
        out.delete(n, out.length)
    }

    private fun isCjk(c: Char) = c in '⺀'..'鿿' || c in '豈'..'﫿' || c in '＀'..'￯' || c in '　'..'〿'

    private companion object {
        const val BLANK = "\u200B"   // 零宽字符撑起一个空行；纯换行会被 block() 并掉
        val BLOCKS = setOf(
            "p", "div", "section", "article", "aside", "header", "footer", "nav", "main", "figure", "figcaption",
            "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "ul", "ol", "li", "dl", "dt", "dd", "table", "tr", "pre", "center",
        )
        val HEADING_SIZES = floatArrayOf(1.5f, 1.3f, 1.15f, 1.05f, 1f, 1f)
    }
}
