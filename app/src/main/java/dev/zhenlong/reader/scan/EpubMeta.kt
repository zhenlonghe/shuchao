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

object EpubMetaParser {

    fun parse(zip: ZipReader): EpubMeta? = readOpf(zip)?.let { (opf, path) -> parseOpf(opf, path) }

    fun parseSpine(zip: ZipReader): EpubSpine? = readOpf(zip)?.let { (opf, path) -> parseSpine(opf, path) }

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
