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

    /** [volumeIds]：套内各卷，按卷序（书名自然排序）。 */
    data class Set(val series: Series, val volumeIds: List<String>) : ShelfItem {
        override val key get() = series.id
        override val title get() = series.name
        override val coverPath get() = series.coverPath
        val count get() = volumeIds.size
    }
}

/**
 * 顶层书架的顺序：读过的在前，按最近阅读倒序（一套按套内最近读的那一卷算）；没读过的在后，保持原来的书名顺序。
 * 只给顶层用；套内按卷序，读漫画是顺着卷读的。
 */
fun arrangeByRecent(items: List<ShelfItem>, reads: Map<String, ReadState>): List<ShelfItem> =
    items.map { it to lastRead(it, reads)?.lastOpenedAt }
        .sortedByDescending { it.second ?: Long.MIN_VALUE }   // 稳定排序：没读过的之间保持原顺序
        .map { it.first }

/**
 * 封面下进度条的值（0–1），null = 不画（没读过，或者读过但还没存过进度）。
 * 一套 = (最近读的那一卷的卷序 + 那一卷的进度) / 卷数：91 卷读到第 13 卷一半 ≈ 14%。
 */
fun shelfProgress(item: ShelfItem, reads: Map<String, ReadState>): Float? {
    val read = lastRead(item, reads) ?: return null
    val progress = read.progress ?: return null
    return when (item) {
        is ShelfItem.Single -> progress
        is ShelfItem.Set -> (item.volumeIds.indexOf(read.id) + progress) / item.count
    }
}

private fun lastRead(item: ShelfItem, reads: Map<String, ReadState>): ReadState? = when (item) {
    is ShelfItem.Single -> reads[item.book.id]
    is ShelfItem.Set -> item.volumeIds.mapNotNull(reads::get).maxByOrNull { it.lastOpenedAt }
}

/** 排好序的书库快照（SPEC §4 排序规则）。 */
class Library(books: List<Book>, series: List<Series>) {
    /** 书名自然排序；顶层书架实际显示的顺序是 [arrangeByRecent] 之后的。 */
    val top: List<ShelfItem>
    val bySeries: Map<String, List<ShelfItem>>
    val seriesNames: Map<String, String> = series.associate { it.id to it.name }
    val all: List<Book>
    /** 读过的书。阅读时这份快照不更新，回书架后以 ReaderApp.reads 为准。 */
    val reads: Map<String, ReadState> = books.mapNotNull { b ->
        b.lastOpenedAt?.let { ReadState(b.id, b.seriesId, it, b.progress) }
    }.associateBy { it.id }

    init {
        val order = NaturalOrder()
        all = books.sortedWith(compareBy(order) { it.title })
        val grouped = all.groupBy { it.seriesId }   // 组内沿用 all 的顺序
        top = (grouped[null].orEmpty().map { ShelfItem.Single(it) } +
            series.mapNotNull { s -> grouped[s.id]?.let { v -> ShelfItem.Set(s, v.map { it.id }) } })
            .sortedWith(compareBy(order) { it.title })
        bySeries = series.associate { s -> s.id to grouped[s.id].orEmpty().map { ShelfItem.Single(it) } }
    }
}
