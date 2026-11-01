package dev.zhenlong.reader

import dev.zhenlong.reader.scan.EpubMetaParser
import dev.zhenlong.reader.scan.NaturalOrder
import dev.zhenlong.reader.scan.TocItem
import dev.zhenlong.reader.scan.ZipReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class ScanTest {

    private fun zipOf(vararg files: Pair<String, ByteArray>, storedNames: Set<String> = emptySet()): ZipReader {
        val file = File.createTempFile("test", ".epub").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, bytes) in files) {
                val entry = ZipEntry(name)
                if (name in storedNames) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                out.putNextEntry(entry)
                out.write(bytes)
                out.closeEntry()
            }
        }
        return ZipReader(RandomAccessFile(file, "r").channel)
    }

    @Test
    fun zipReaderReadsStoredAndDeflatedEntries() {
        val big = Random(1).nextBytes(300_000)
        val text = "你好，世界".repeat(1000).toByteArray()
        zipOf("mimetype" to "application/epub+zip".toByteArray(), "a/文本.txt" to text, "big.bin" to big, storedNames = setOf("mimetype"))
            .use { zip ->
                assertEquals(listOf("mimetype", "a/文本.txt", "big.bin"), zip.entries.keys.toList())
                assertEquals("application/epub+zip", zip.readBytes(zip.entries.getValue("mimetype")).decodeToString())
                assertArrayEquals(text, zip.readBytes(zip.entries.getValue("a/文本.txt")))
                assertArrayEquals(big, zip.readBytes(zip.entries.getValue("big.bin")))
            }
    }

    private val container = """<?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>""".toByteArray()

    private fun opf(metadata: String, manifest: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">$metadata</metadata>
          <manifest>$manifest</manifest>
        </package>""".toByteArray()

    private fun parse(metadata: String, manifest: String) =
        zipOf("META-INF/container.xml" to container, "OEBPS/content.opf" to opf(metadata, manifest)).use(EpubMetaParser::parse)!!

    @Test
    fun tocFromNcx() {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="c1"/></spine>
            </package>""".toByteArray()
        val ncx = """<?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="a" playOrder="1">
                  <navLabel><text> 第一辑　依稀杂忆 </text></navLabel>
                  <content src="text/c1.xhtml"/>
                  <navPoint id="b" playOrder="2">
                    <navLabel><text>常州青果巷</text></navLabel>
                    <content src="text/c2.xhtml#top"/>
                  </navPoint>
                </navPoint>
              </navMap>
            </ncx>""".toByteArray()
        val toc = zipOf("META-INF/container.xml" to container, "OEBPS/content.opf" to opf, "OEBPS/toc.ncx" to ncx)
            .use(EpubMetaParser::parseToc)
        assertEquals(listOf(TocItem("第一辑　依稀杂忆", "OEBPS/text/c1.xhtml"), TocItem("常州青果巷", "OEBPS/text/c2.xhtml")), toc)
    }

    @Test
    fun tocPrefersEpub3Nav() {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
              </manifest>
              <spine toc="ncx"/>
            </package>""".toByteArray()
        val nav = """<?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <nav epub:type="landmarks"><ol><li><a href="cover.xhtml">封面</a></li></ol></nav>
              <nav epub:type="toc"><ol>
                <li><a href="ch1.xhtml">第一章</a><ol><li><a href="ch1.xhtml#s2">第二节</a></li></ol></li>
                <li><a href="ch2.xhtml">第二章</a></li>
              </ol></nav>
            </body></html>""".toByteArray()
        val ncx = """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap><navPoint id="x"><navLabel><text>NCX</text></navLabel><content src="ch1.xhtml"/></navPoint></navMap></ncx>""".toByteArray()
        val toc = zipOf("META-INF/container.xml" to container, "OEBPS/content.opf" to opf, "OEBPS/nav.xhtml" to nav, "OEBPS/toc.ncx" to ncx)
            .use(EpubMetaParser::parseToc)
        assertEquals(listOf("第一章", "第二节", "第二章"), toc.map { it.title })
        assertEquals(listOf("OEBPS/ch1.xhtml", "OEBPS/ch1.xhtml", "OEBPS/ch2.xhtml"), toc.map { it.document })
    }

    @Test
    fun epub3CoverImageProperty() {
        val meta = parse(
            "<dc:title> 三体 </dc:title><dc:creator>刘慈欣</dc:creator>",
            """<item id="c" href="images/%E5%B0%81%E9%9D%A2.jpg" media-type="image/jpeg" properties="cover-image"/>""",
        )
        assertEquals("三体", meta.title)
        assertEquals("刘慈欣", meta.author)
        assertEquals("OEBPS/images/封面.jpg", meta.coverEntry)
    }

    @Test
    fun epub2MetaCoverAndParentPath() {
        val meta = parse(
            """<dc:title>T</dc:title><meta name="cover" content="img1"/>""",
            """<item id="img1" href="../Images/c.png" media-type="image/png"/>""",
        )
        assertEquals("Images/c.png", meta.coverEntry)
        assertNull(meta.author)
    }

    @Test
    fun noCoverNoTitle() {
        val meta = parse("", """<item id="p1" href="p1.xhtml" media-type="application/xhtml+xml"/>""")
        assertNull(meta.title)
        assertNull(meta.coverEntry)
    }

    @Test
    fun naturalOrder() {
        val sorted = listOf("第10卷", "第2卷", "第1卷", "Vol.03", "Vol.2", "vol.11").sortedWith(NaturalOrder())
        assertEquals(listOf("Vol.2", "Vol.03", "vol.11", "第1卷", "第2卷", "第10卷"), sorted)
    }
}

class ReaderLogicTest {
    @org.junit.Test
    fun pageEstimatorUsesMeasuredChaptersAndScalesTheRest() {
        val e = dev.zhenlong.reader.reader.PageEstimator(listOf(1000L, 3000L, 10L, 2000L))
        org.junit.Assert.assertNull(e.locate(1, 1))
        e.record(1, 6)                                   // 3000 字节 → 6 页，即 500 字节一页
        org.junit.Assert.assertEquals(4 to 13, e.locate(1, 2))    // 前面 2 页 + 第 2 页；总 2+6+1+4
        e.record(0, 3)                                   // 第一章实测 3 页：比例变成 9/4000
        org.junit.Assert.assertEquals(5 to 15, e.locate(1, 2))    // 3+6+1+round(4.5)=5 → 15
        e.reset()
        org.junit.Assert.assertNull(e.locate(1, 2))
    }

    @org.junit.Test
    fun mangaPagesAreOneImagePerChapter() {
        val e = dev.zhenlong.reader.reader.PageEstimator(List(200) { 400L + it % 7 })
        e.record(57, 1)
        org.junit.Assert.assertEquals(58 to 200, e.locate(57, 1))
    }

    @org.junit.Test
    fun mangaDetection() {
        val d = dev.zhenlong.reader.reader.MangaDetector
        val imagePage = "<html><head><title>第 12 页 很长很长很长很长很长很长的标题</title><style>img{width:100%}</style></head>" +
            "<body><div><img src=\"../image/012.jpg\" alt=\"\"/></div></body></html>"
        val svgPage = "<html><body><svg viewBox=\"0 0 1 1\"><image width=\"1\" xlink:href=\"a.jpg\"/></svg></body></html>"
        val textPage = "<html><body><p>很多年以后他才明白，那个下午其实什么都没有发生。真的什么都没有发生。</p><img src=\"a.jpg\"/></body></html>"
        org.junit.Assert.assertTrue(d.isImagePage(imagePage))
        org.junit.Assert.assertTrue(d.isImagePage(svgPage))
        org.junit.Assert.assertFalse(d.isImagePage(textPage))
        org.junit.Assert.assertTrue(d.isManga(List(11) { imagePage } + textPage))       // 11/12 ≥ 90%
        org.junit.Assert.assertFalse(d.isManga(List(10) { imagePage } + textPage + textPage))
        org.junit.Assert.assertEquals(12, d.sampleIndices(200).size)
        org.junit.Assert.assertEquals(listOf(0, 1, 2), d.sampleIndices(3))
    }
}

class MangaPagesTest {
    @org.junit.Test
    fun imageHrefFromImgAndSvg() {
        val d = dev.zhenlong.reader.reader.MangaDetector
        org.junit.Assert.assertEquals("../image/012.jpg", d.imageHref("<body><div><img class=\"p\" alt=\"\" src=\"../image/012.jpg\"/></div></body>"))
        org.junit.Assert.assertEquals("a b&c.png", d.imageHref("<svg><image width=\"1\" xlink:href='a b&amp;c.png'/></svg>"))
        org.junit.Assert.assertNull(d.imageHref("<body><p>版权页</p></body>"))
        org.junit.Assert.assertEquals("版权页\n第二行", d.plainText("<html><head><title>x</title></head><body><p>版权页</p><p>第二行</p></body></html>"))
    }

    @org.junit.Test
    fun spineOrderAndFixedLayout() {
        val opf = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(
            ("<package xmlns=\"http://www.idpf.org/2007/opf\"><metadata><meta property=\"rendition:layout\">pre-paginated</meta></metadata>" +
                "<manifest><item id=\"b\" href=\"text/p2.xhtml\"/><item id=\"a\" href=\"text/p1.xhtml\"/><item id=\"n\" href=\"nav.xhtml\"/></manifest>" +
                "<spine><itemref idref=\"a\"/><itemref idref=\"n\" linear=\"no\"/><itemref idref=\"b\"/></spine></package>").byteInputStream(),
        )
        val spine = dev.zhenlong.reader.scan.EpubMetaParser.parseSpine(opf, "OEBPS/content.opf")
        org.junit.Assert.assertEquals(listOf("OEBPS/text/p1.xhtml", "OEBPS/text/p2.xhtml"), spine.documents)
        org.junit.Assert.assertTrue(spine.fixedLayout)
        org.junit.Assert.assertEquals("OEBPS/image/012.jpg", dev.zhenlong.reader.scan.EpubMetaParser.resolve("OEBPS/text/p1.xhtml", "../image/012.jpg"))
    }
}
