package dev.zhenlong.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GridPagesTest {
    @Test fun uniformPages() {
        val p = GridPages(itemCount = 25, perPage = 12)
        assertEquals(3, p.count)
        assertEquals(0 until 12, p.range(0))
        assertEquals(24 until 36, p.range(2))
    }

    @Test fun firstPageShorterForPinnedRow() {
        val p = GridPages(itemCount = 20, perPage = 12, firstPage = 8)
        assertEquals(2, p.count)
        assertEquals(0 until 8, p.range(0))
        assertEquals(8 until 20, p.range(1))
        assertEquals(20 until 32, p.range(2))
        assertEquals(IntRange.EMPTY, p.range(-1))
    }

    @Test fun boundaries() {
        assertEquals(1, GridPages(0, 12, 8).count)
        assertEquals(1, GridPages(8, 12, 8).count)
        assertEquals(2, GridPages(9, 12, 8).count)
        assertEquals(2, GridPages(20, 12, 8).count)
        assertEquals(3, GridPages(21, 12, 8).count)
    }
}
