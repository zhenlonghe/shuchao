package dev.zhenlong.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShelfOrderTest {
    private fun book(id: String, seriesId: String? = null) = Book(
        id = id, fileUri = "", seriesId = seriesId, title = id, author = null, fileName = "$id.epub",
        kind = BookKind.TEXT, kindOverride = null, coverPath = null, lastModified = 0, size = 0,
        locatorJson = null, pageIndex = null, pageCount = null, lastOpenedAt = null,
    )
    private fun single(id: String) = ShelfItem.Single(book(id))
    private fun set(id: String, vararg volumes: String) = ShelfItem.Set(Series(id, "", id, null), volumes.toList())
    private fun read(id: String, at: Long, progress: Float? = 0.5f, seriesId: String? = null) = id to ReadState(id, seriesId, at, progress)

    @Test fun readFirstByRecencyUnreadKeepOrder() {
        val items = listOf(single("a"), single("b"), single("c"), single("d"), single("e"))
        val reads = mapOf(read("d", 10), read("b", 20))
        assertEquals(listOf("b", "d", "a", "c", "e"), arrangeByRecent(items, reads).map { it.key })
    }

    @Test fun setRanksByItsMostRecentVolume() {
        val items = listOf(single("a"), set("s", "s1", "s2", "s3"), single("z"))
        val reads = mapOf(read("a", 10), read("s1", 5, seriesId = "s"), read("s3", 30, seriesId = "s"))
        assertEquals(listOf("s", "a", "z"), arrangeByRecent(items, reads).map { it.key })
    }

    @Test fun nothingReadKeepsOrder() {
        val items = listOf(single("a"), set("s", "s1"), single("b"))
        assertEquals(items, arrangeByRecent(items, emptyMap()))
    }

    @Test fun singleProgress() {
        assertEquals(0.3f, shelfProgress(single("a"), mapOf(read("a", 1, 0.3f)))!!, 1e-6f)
        assertNull(shelfProgress(single("a"), emptyMap()))
        assertNull(shelfProgress(single("a"), mapOf(read("a", 1, null))))   // 读过但没存过进度
    }

    @Test fun setProgressIsPositionOfMostRecentVolume() {
        val onePiece = set("op", *Array(91) { "v$it" })
        // 第 13 卷（下标 12）读到一半，之前读过第 1 卷
        val reads = mapOf(read("v0", 1, 1f), read("v12", 2, 0.5f))
        assertEquals(12.5f / 91, shelfProgress(onePiece, reads)!!, 1e-6f)
        assertNull(shelfProgress(onePiece, emptyMap()))
    }
}
