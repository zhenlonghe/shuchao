package dev.zhenlong.reader.data

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightExportTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun book(id: String, title: String, author: String?) = Book(
        id = id, fileUri = "content://$id", seriesId = null, title = title, author = author, fileName = "$title.epub",
        kind = BookKind.TEXT, kindOverride = null, coverPath = null, lastModified = 0, size = 0,
        locatorJson = null, pageIndex = null, pageCount = null, lastOpenedAt = null,
    )

    private fun mark(bookId: String, at: Long, color: Int, text: String, chapter: String? = null) =
        Highlight(bookId = bookId, document = "c.xhtml", startOffset = 0, endOffset = 1, color = color, text = text, createdAt = at, chapterTitle = chapter)

    @Test fun groupsByBookNewestFirstAndKeepsSeconds() {
        // 2026-11-03 10:00:05 +08:00
        val t = 1_793_671_205_000L
        val text = HighlightExport.format(
            listOf(
                mark("a", t, 0, "第一条", "第一章"),
                mark("b", t + 60_000, 1, "另一本书\n\n第二段"),
                mark("a", t + 1_000, 3, "第二条"),
            ),
            mapOf("a" to book("a", "甲书", "张三"), "b" to book("b", "乙书", null)),
            zone,
        )
        val expected = """
墨案 · 划线摘录
共 2 本书、3 条。由墨案自动生成：每次划线、改色、删除后整份重写，在这里修改不会同步回书里。

════════════════════════════════
《乙书》
文件：乙书.epub
════════════════════════════════

[2026-11-03 10:01:05] 【青】
另一本书
第二段

════════════════════════════════
《甲书》
作者：张三
文件：甲书.epub
════════════════════════════════

[2026-11-03 10:00:05] 【黄】 第一章
第一条

[2026-11-03 10:00:06] 【绿】
第二条
""".trimStart()
        assertEquals(expected, text)
    }
}
