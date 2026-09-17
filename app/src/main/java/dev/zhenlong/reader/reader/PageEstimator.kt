package dev.zhenlong.reader.reader

import kotlin.math.roundToInt

/**
 * 全书的「当前页 / 总页数」。Readium 只知道当前这一章排成了几页；没翻到的章节要整章排版才知道页数，
 * 在这台设备上一章就要几百毫秒，改一次字号还得全部重来——不值。
 *
 * 所以：翻到过的章用真实页数，没翻到的按字节数比例估（比例取自已知章节），至少 1 页。
 * 漫画一章一图、字节数相近，估出来就是精确的一章一页；文字书会随阅读逐步校准。
 */
class PageEstimator(private val chapterBytes: List<Long>) {
    private val measured = arrayOfNulls<Int>(chapterBytes.size)

    /** 排版变了（字号、边距…），之前量到的页数全部作废。 */
    fun reset() = measured.fill(null)

    fun record(chapter: Int, pages: Int) {
        if (chapter in measured.indices && pages > 0) measured[chapter] = pages
    }

    /** @return (全书当前页, 全书总页数)，都从 1 起；还没有任何章节量过时返回 null。 */
    fun locate(chapter: Int, pageInChapter: Int): Pair<Int, Int>? {
        if (chapter !in measured.indices) return null
        var knownPages = 0
        var knownBytes = 0L
        for (i in measured.indices) measured[i]?.let {
            knownPages += it
            knownBytes += chapterBytes[i]
        }
        if (knownPages == 0) return null
        val pagesPerByte = if (knownBytes > 0) knownPages.toDouble() / knownBytes else 0.0
        fun pagesOf(i: Int) = measured[i] ?: (chapterBytes[i] * pagesPerByte).roundToInt().coerceAtLeast(1)

        var before = 0
        var total = 0
        for (i in measured.indices) {
            val pages = pagesOf(i)
            if (i < chapter) before += pages
            total += pages
        }
        return (before + pageInChapter.coerceIn(1, pagesOf(chapter))) to total
    }
}
