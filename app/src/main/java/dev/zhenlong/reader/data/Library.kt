package dev.zhenlong.reader.data

import dev.zhenlong.reader.scan.NaturalOrder
import kotlin.math.roundToInt

sealed interface ShelfItem {
    val key: String
    val title: String
    val coverPath: String?

    data class Single(val book: Book) : ShelfItem {
        override val key get() = book.id
        override val title get() = book.title
        override val coverPath get() = book.coverPath
    }

    data class Set(val series: Series, val count: Int) : ShelfItem {
        override val key get() = series.id
        override val title get() = series.name
        override val coverPath get() = series.coverPath
    }
}

/**
 * 「最近阅读」行封面下的字：读到几成（这一行的意义就是接着读，书名封面上已经有了）。
 * [progress] 0–1；最后一页算读完。文字书的进度是估算页码，小幅跳动正常。
 */
fun progressLabel(progress: Float): String {
    if (progress >= 0.995f) return "已读完"
    return "读到 ${(progress * 100).roundToInt().coerceIn(0, 99)}%"
}

/** 书架首屏第一行放几本最近读过的书（= 一行的列数）。 */
const val RECENT_COUNT = 4

/** 排好序的书库快照（SPEC §4 排序规则）。 */
class Library(books: List<Book>, series: List<Series>) {
    val top: List<ShelfItem>
    val bySeries: Map<String, List<ShelfItem>>
    val seriesNames: Map<String, String> = series.associate { it.id to it.name }
    val all: List<Book>
    /** 最近读过的几本，最近的在前。阅读时这份快照不更新，回书架后的新顺序以 ReaderApp.recent 为准。 */
    val recent: List<Book> = books.filter { it.lastOpenedAt != null }
        .sortedByDescending { it.lastOpenedAt }.take(RECENT_COUNT)

    init {
        val order = NaturalOrder()
        all = books.sortedWith(compareBy(order) { it.title })
        val grouped = all.groupBy { it.seriesId }   // 组内沿用 all 的顺序
        top = (grouped[null].orEmpty().map { ShelfItem.Single(it) } +
            series.mapNotNull { s -> grouped[s.id]?.let { ShelfItem.Set(s, it.size) } })
            .sortedWith(compareBy(order) { it.title })
        bySeries = series.associate { s -> s.id to grouped[s.id].orEmpty().map { ShelfItem.Single(it) } }
    }
}
