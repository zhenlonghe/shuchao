package dev.zhenlong.reader.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressLabelTest {
    @Test fun roundsToWholePercent() {
        assertEquals("读到 0%", progressLabel(0f))
        assertEquals("读到 67%", progressLabel(0.674f))
        assertEquals("读到 68%", progressLabel(0.675f))
    }

    @Test fun lastPageIsFinished() {
        assertEquals("读到 99%", progressLabel(0.99f))
        assertEquals("已读完", progressLabel(0.995f))
        assertEquals("已读完", progressLabel(1f))
    }
}
