package dev.zhenlong.reader.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 所有书的划线统一写进书库根目录的一个文本文件（用户 2026-11-03）：[FILE_NAME]，UTF-8 纯文本，电脑上直接打开、复制、检索。
 * 库是唯一的真源：每次划线 / 改色 / 删除之后整份重写，文件里手改的内容会被覆盖、也不会同步回书里。
 *
 * 格式（[format]）：文件头一段说明；然后按「最近划线的书在前」一本一段，书名行以「《》」开头、下面是作者 / 文件名；
 * 每条划线是「[时间] 【颜色】章名」一行 + 摘录原文，条与条之间空一行，书内按划线时间排。
 */
object HighlightExport {
    /** 「页边批注」：旧时读书人写在书页空白处的笔记，正是划线摘录的老名字。 */
    const val FILE_NAME = "Marginalia.txt"
    private const val LEGACY_FILE_NAME = "墨案划线.txt"   // 11-03 第一版用的名字：见到就改名接上
    private const val TAG = "HighlightExport"

    /** 和 [dev.zhenlong.reader.reader.HighlightColors] 的序号一一对应。 */
    val COLOR_NAMES = listOf("黄", "青", "紫", "绿")

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private const val RULE = "════════════════════════════════"

    fun format(highlights: List<Highlight>, books: Map<String, Book>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        val byBook = highlights.groupBy { it.bookId }
            .entries.sortedByDescending { (_, list) -> list.maxOf { it.createdAt } }
        appendLine("墨案 · 划线摘录")
        appendLine("共 ${byBook.size} 本书、${highlights.size} 条。由墨案自动生成：每次划线、改色、删除后整份重写，在这里修改不会同步回书里。")
        for ((bookId, list) in byBook) {
            val book = books[bookId]
            appendLine()
            appendLine(RULE)
            appendLine("《${book?.title ?: "（已不在书库里的书）"}》")
            book?.author?.takeIf { it.isNotBlank() }?.let { appendLine("作者：$it") }
            appendLine("文件：${book?.fileName ?: bookId}")
            appendLine(RULE)
            for (h in list.sortedBy { it.createdAt }) {
                appendLine()
                val time = TIME.format(Instant.ofEpochMilli(h.createdAt).atZone(zone))
                val color = COLOR_NAMES.getOrElse(h.color) { "黄" }
                appendLine(listOfNotNull("[$time]", "【$color】", h.chapterTitle?.takeIf { it.isNotBlank() }).joinToString(" "))
                // 摘录里的空行压成单个换行：空行只用来分隔条目
                appendLine(h.text.replace(Regex("\\s*\\n\\s*"), "\n").trim())
            }
        }
    }

    /**
     * 写进书库根目录（SAF 树，要有写入授权）。内容和上次写的一样就不写。
     * @return 写成功（或无需写）= true；没有写入授权 / 目录不可用 = false
     */
    fun write(context: Context, tree: Uri, text: String, createIfMissing: Boolean = true): Boolean = runCatching {
        val resolver = context.contentResolver
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val existing = findChild(context, tree, FILE_NAME)
            ?: findChild(context, tree, LEGACY_FILE_NAME)?.let { DocumentsContract.renameDocument(resolver, it, FILE_NAME) }
        if (existing != null) {
            val old = resolver.openInputStream(existing)?.use { it.readBytes().toString(Charsets.UTF_8) }
            if (old == text) return true
        }
        if (existing == null && !createIfMissing) return true
        val target = existing ?: DocumentsContract.createDocument(resolver, rootDoc, "text/plain", FILE_NAME) ?: return false
        resolver.openOutputStream(target, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        true
    }.getOrElse {
        Log.w(TAG, "export failed", it)
        false
    }

    private fun findChild(context: Context, tree: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        return null
    }
}
