package dev.zhenlong.reader.reader

/** SPEC §6 规则 2：一个 XHTML 只放了一张图、几乎没有字，就是一页漫画。 */
object MangaDetector {
    private val image = Regex("<(img|image)[\\s/>]", RegexOption.IGNORE_CASE)
    private val invisible = Regex("<(head|script|style)[\\s>].*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tag = Regex("<[^>]*>")
    private val entity = Regex("&[#\\w]+;")

    fun isImagePage(xhtml: String): Boolean {
        if (image.findAll(xhtml).count() != 1) return false
        val text = xhtml.replace(invisible, "").replace(tag, "").replace(entity, "")
        return text.count { !it.isWhitespace() } < MAX_VISIBLE_CHARS
    }

    private val imgSrc = Regex("<img\\b[^>]*?\\ssrc\\s*=\\s*[\"']([^\"']+)", RegexOption.IGNORE_CASE)
    private val svgHref = Regex("<image\\b[^>]*?\\s(?:xlink:)?href\\s*=\\s*[\"']([^\"']+)", RegexOption.IGNORE_CASE)

    /** 这一页的那张图的 href（相对章节文档）；不是图片页就返回 null。 */
    fun imageHref(xhtml: String): String? =
        (imgSrc.find(xhtml) ?: svgHref.find(xhtml))?.groupValues?.get(1)?.replace("&amp;", "&")

    /** 非图片页的兜底：去掉标签后的纯文字。 */
    fun plainText(xhtml: String): String =
        xhtml.replace(invisible, "").replace(Regex("</(p|div|h\\d|li)>|<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(tag, "").replace(entity, " ").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    /** [pages]：抽样的若干页。≥ 90% 是图片页才算漫画。 */
    fun isManga(pages: List<String>): Boolean =
        pages.isNotEmpty() && pages.count(::isImagePage) >= pages.size * 0.9

    /** 从 [count] 个章节里均匀抽最多 [SAMPLE] 个下标。 */
    fun sampleIndices(count: Int): List<Int> =
        if (count <= SAMPLE) (0 until count).toList() else (0 until SAMPLE).map { it * count / SAMPLE }

    private const val MAX_VISIBLE_CHARS = 20
    private const val SAMPLE = 12
}
