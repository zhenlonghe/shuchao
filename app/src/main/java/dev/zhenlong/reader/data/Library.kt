package dev.zhenlong.reader.data

import dev.zhenlong.reader.scan.NaturalOrder

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

/** 排好序的书库快照（SPEC §4 排序规则）。 */
class Library(books: List<Book>, series: List<Series>) {
    val top: List<ShelfItem>
    val bySeries: Map<String, List<ShelfItem>>
    val seriesNames: Map<String, String> = series.associate { it.id to it.name }
    val all: List<Book>

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
