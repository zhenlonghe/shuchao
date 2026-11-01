package dev.zhenlong.reader.scan

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.InputStream
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

data class EpubMeta(
    val title: String?,
    val author: String?,
    val coverEntry: String?,   // 封面图片在 ZIP 内的条目名
)

/** 阅读顺序：每个章节文档在 ZIP 内的条目名。 */
data class EpubSpine(val documents: List<String>, val fixedLayout: Boolean)

/** 目录里的一条：标题 + 指向的章节文档（ZIP 条目名，不带片段）。 */
data class TocItem(val title: String, val document: String)

object EpubMetaParser {

    fun parse(zip: ZipReader): EpubMeta? = readOpf(zip)?.let { (opf, path) -> parseOpf(opf, path) }

    fun parseSpine(zip: ZipReader): EpubSpine? = readOpf(zip)?.let { (opf, path) -> parseSpine(opf, path) }

    /**
     * 目录（EPUB 3 的 nav 文档优先，其次 EPUB 2 的 NCX），按目录顺序。几毫秒，不经 Readium。
     * 文字引擎用它认章标题、页脚写章名；解析失败 / 没有目录 = 空列表。
     */
    fun parseToc(zip: ZipReader): List<TocItem> = readOpf(zip)?.let { (opf, path) -> parseToc(opf, path, zip) }.orEmpty()

    internal fun parseToc(opf: Document, opfPath: String, zip: ZipReader): List<TocItem> {
        val items = opf.elements("item")
        val nav = items.firstOrNull { "nav" in it.getAttribute("properties").split(' ') }?.getAttribute("href")
        val ncxId = opf.elements("spine").firstOrNull()?.getAttribute("toc").orEmpty()
        val ncx = items.firstOrNull {
            it.getAttribute("media-type") == "application/x-dtbncx+xml" || (ncxId.isNotEmpty() && it.getAttribute("id") == ncxId)
        }?.getAttribute("href")
        for (href in listOfNotNull(nav, ncx).filter { it.isNotEmpty() }) {
            val path = resolve(opfPath, href)
            val entry = zip.entries[path] ?: continue
            // nav 文档是 XHTML，带 DTD 实体的解析不了：那就退到 NCX
            val doc = runCatching { zip.open(entry).use(::parseXml) }.getOrNull() ?: continue
            val found = if (href == nav) parseNav(doc, path) else parseNcx(doc, path)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun parseNcx(doc: Document, path: String): List<TocItem> = doc.elements("navPoint").mapNotNull { point ->
        // 文档顺序里第一个 text / content 就是它自己的（navLabel 在子 navPoint 前面）
        val title = point.getElementsByTagNameNS("*", "text").item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        val src = (point.getElementsByTagNameNS("*", "content").item(0) as? Element)?.getAttribute("src")?.takeIf { it.isNotEmpty() }
        if (title != null && src != null) TocItem(title, resolve(path, src)) else null
    }

    private fun parseNav(doc: Document, path: String): List<TocItem> {
        val navs = doc.elements("nav")
        val toc = navs.firstOrNull { nav ->
            val type = nav.getAttribute("epub:type").ifEmpty { nav.getAttributeNS("http://www.idpf.org/2007/ops", "type") }
            "toc" in type.split(' ')
        } ?: navs.firstOrNull() ?: return emptyList()
        val links = toc.getElementsByTagNameNS("*", "a")
        return List(links.length) { links.item(it) as Element }.mapNotNull { a ->
            val title = a.textContent?.trim()?.takeIf { it.isNotEmpty() }
            val href = a.getAttribute("href").takeIf { it.isNotEmpty() }
            if (title != null && href != null) TocItem(title, resolve(path, href)) else null
        }
    }

    private fun readOpf(zip: ZipReader): Pair<Document, String>? {
        val container = zip.entries["META-INF/container.xml"] ?: return null
        val opfPath = zip.open(container).use(::parseXml)
            .elements("rootfile").firstOrNull()?.getAttribute("full-path")
            ?.takeIf { it.isNotEmpty() } ?: return null
        val opfEntry = zip.entries[opfPath] ?: return null
        return zip.open(opfEntry).use(::parseXml) to opfPath
    }

    internal fun parseSpine(opf: Document, opfPath: String): EpubSpine {
        val hrefById = opf.elements("item").associate { it.getAttribute("id") to it.getAttribute("href") }
        val documents = opf.elements("itemref")
            .filter { it.getAttribute("linear") != "no" }
            .mapNotNull { hrefById[it.getAttribute("idref")]?.takeIf(String::isNotEmpty) }
            .map { resolve(opfPath, it) }
        val fixed = opf.elements("meta").any {
            it.getAttribute("property") == "rendition:layout" && it.textContent.trim() == "pre-paginated"
        }
        return EpubSpine(documents, fixed)
    }

    internal fun parseOpf(opf: Document, opfPath: String): EpubMeta {
        val title = opf.elements("title").firstNotNullOfOrNull { it.textContent?.trim()?.takeIf(String::isNotEmpty) }
        val author = opf.elements("creator").firstNotNullOfOrNull { it.textContent?.trim()?.takeIf(String::isNotEmpty) }

        val items = opf.elements("item")
        fun Element.isImage() = getAttribute("media-type").startsWith("image/")

        val coverId = opf.elements("meta").firstOrNull { it.getAttribute("name") == "cover" }?.getAttribute("content")
        val coverItem =
            items.firstOrNull { "cover-image" in it.getAttribute("properties").split(' ') }
                ?: coverId?.let { id -> items.firstOrNull { it.getAttribute("id") == id && it.isImage() } }
                ?: coverId?.let { href -> items.firstOrNull { it.getAttribute("href") == href && it.isImage() } }
                ?: items.firstOrNull {
                    it.isImage() && (it.getAttribute("id").contains("cover", true) || it.getAttribute("href").contains("cover", true))
                }

        return EpubMeta(title, author, coverItem?.getAttribute("href")?.let { resolve(opfPath, it) })
    }

    /** 把 [basePath]（OPF 或某个章节文档）里的相对 href 解析成 ZIP 条目名。 */
    fun resolve(basePath: String, href: String): String {
        val decoded = URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8")
        val parts = ArrayDeque(basePath.split('/').dropLast(1))
        for (seg in decoded.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun parseXml(input: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // JVM 上避免联网取 DTD；Android 的解析器不认识这个 feature，本来也不取
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        return factory.newDocumentBuilder().parse(input)
    }

    private fun Document.elements(localName: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", localName)
        return List(nodes.length) { nodes.item(it) as Element }
    }
}
