package dev.zhenlong.reader.reader

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import dev.zhenlong.reader.scan.NaturalOrder
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.content.ContentResource
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.resource.Resource

/** 用户字体文件夹（默认 /sdcard/Fonts，经 SAF 授权）里的一个字体文件。 */
class UserFont(val fileName: String, val uri: Uri) {
    val name: String = fileName.substringBeforeLast('.')
    private val id = Integer.toHexString(fileName.hashCode())
    private val path = "$FONT_DIR/$id.${fileName.substringAfterLast('.').lowercase()}"

    /** CSS font-family 名。 */
    val family = "UserFont_$id"

    /** Readium 把书内资源挂在 https://readium_package/ 下，字体也从这里取（见 [FontContainer]）。 */
    val url: AbsoluteUrl get() = AbsoluteUrl("https://readium_package/$path")!!

    fun matches(url: Url) = url.path?.endsWith(path) == true

    private companion object {
        const val FONT_DIR = "__reader_font"
    }
}

/** 列出文件夹顶层的 ttf / otf。目录不可读（授权被撤）时返回空。 */
fun listUserFonts(context: Context, tree: Uri): List<UserFont> = runCatching {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val fonts = ArrayList<UserFont>()
    context.contentResolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)
        ?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith(".") || name.substringAfterLast('.').lowercase() !in FONT_EXTENSIONS) continue
                fonts += UserFont(name, DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)))
            }
        }
    fonts.sortedWith(compareBy(NaturalOrder()) { it.name })
}.getOrDefault(emptyList())

private val FONT_EXTENSIONS = setOf("ttf", "otf")

/**
 * 把用户字体「塞进」书的资源容器：Readium 只肯从 APK assets 或书本身给 WebView 供文件，
 * 于是让字体看起来像书里的一个文件。entries 为空，解析器看不到它；只有 WebView 按 URL 来取时才命中。
 * 字体直接从 SAF 流式读，不复制（用户的字体单个就有 20MB+）。
 */
class FontContainer(private val contentResolver: ContentResolver) : Container<Resource> {
    @Volatile
    var fonts: List<UserFont> = emptyList()

    override val entries: Set<Url> = emptySet()
    override fun get(url: Url): Resource? = fonts.firstOrNull { it.matches(url) }?.let { ContentResource(it.uri, contentResolver) }
    override fun close() {}
}
