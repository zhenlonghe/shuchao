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

/**
 * 字体自己的名字（字体文件 name 表里的家族名，如「仓耳今楷05」「霞鹜文楷」），字体列表按它显示；文件名只是兜底。
 * 每个字体只读一次（几 KB），进程内缓存。开书路径上不读：只在打开字体列表时按需解析（[resolve] 在 IO 线程调）。
 */
object FontNames {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val typefaces = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Typeface>()

    // 键用文档 URI：换了字体文件夹后，同名不同内容的字体不会拿到旧文件夹的名字和 Typeface
    private val UserFont.key get() = uri.toString()

    /** 已经解析过的名字；没有就用文件名。 */
    fun of(font: UserFont): String = cache[font.key] ?: font.name

    fun resolve(context: Context, font: UserFont): String = cache.getOrPut(font.key) {
        runCatching {
            context.contentResolver.openFileDescriptor(font.uri, "r")!!.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).channel.use(::readFontFamilyName)
            }
        }.getOrNull() ?: font.name
    }

    /** 把字体名用它自己画出来要的 Typeface（直接映射文件，不复制）；读不了 = null。 */
    fun typeface(context: Context, font: UserFont): android.graphics.Typeface? = typefaces[font.key] ?: runCatching {
        context.contentResolver.openFileDescriptor(font.uri, "r")!!.use { android.graphics.Typeface.Builder(it.fileDescriptor).build() }
    }.getOrNull()?.also { typefaces[font.key] = it }
}

/**
 * 从 TrueType / OpenType 的 name 表里挑家族名：优先「排版家族名」(ID 16)，其次家族名 (ID 1)、全名 (ID 4)；
 * 语言优先中文，其次英文。只认 Unicode / Windows 平台（UTF-16BE）。不是合法字体 = null。
 */
internal fun readFontFamilyName(channel: java.nio.channels.FileChannel): String? {
    fun read(position: Long, size: Int): java.nio.ByteBuffer {
        val buffer = java.nio.ByteBuffer.allocate(size)
        while (buffer.hasRemaining() && channel.read(buffer, position + buffer.position()) > 0) Unit
        return buffer.flip() as java.nio.ByteBuffer
    }
    val header = read(0, 12)
    if (header.remaining() < 12) return null
    val tables = header.getShort(4).toInt() and 0xFFFF
    val directory = read(12, tables * 16)
    var nameOffset = -1L
    var nameLength = 0
    for (i in 0 until minOf(tables, directory.remaining() / 16)) {
        val at = i * 16
        if (directory.getInt(at) == 0x6E616D65) {   // 'name'
            nameOffset = directory.getInt(at + 8).toLong() and 0xFFFFFFFFL
            nameLength = directory.getInt(at + 12)
        }
    }
    if (nameOffset < 0 || nameLength <= 6) return null
    val table = read(nameOffset, nameLength.coerceAtMost(256 * 1024))
    val count = table.getShort(2).toInt() and 0xFFFF
    val strings = table.getShort(4).toInt() and 0xFFFF
    var best: String? = null
    var bestScore = 0
    for (i in 0 until count) {
        val at = 6 + i * 12
        if (at + 12 > table.limit()) break
        val platform = table.getShort(at).toInt() and 0xFFFF
        val language = table.getShort(at + 4).toInt() and 0xFFFF
        val nameId = table.getShort(at + 6).toInt() and 0xFFFF
        val length = table.getShort(at + 8).toInt() and 0xFFFF
        val offset = strings + (table.getShort(at + 10).toInt() and 0xFFFF)
        if (platform != 0 && platform != 3) continue
        val idScore = when (nameId) { 16 -> 3; 1 -> 2; 4 -> 1; else -> continue }
        val langScore = when {
            platform == 3 && language in CHINESE_LANGUAGES -> 3
            platform == 3 && language == 0x0409 -> 2
            else -> 1
        }
        val score = langScore * 10 + idScore
        if (score <= bestScore || offset + length > table.limit()) continue
        val bytes = ByteArray(length).also { table.position(offset); table.get(it) }
        val text = String(bytes, Charsets.UTF_16BE).trim()
        if (text.isNotEmpty()) {
            best = text
            bestScore = score
        }
    }
    return best
}

/** Windows 平台的中文语言 ID：简体（中国 / 新加坡）、繁体（台湾 / 香港 / 澳门）。 */
private val CHINESE_LANGUAGES = setOf(0x0804, 0x1004, 0x0404, 0x0C04, 0x1404)
