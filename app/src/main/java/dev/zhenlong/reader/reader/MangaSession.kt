package dev.zhenlong.reader.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zhenlong.reader.scan.EpubMetaParser
import dev.zhenlong.reader.scan.ZipReader

/** 漫画的一页：一张图；个别不是图片的页（版权页之类）退化成纯文字。 */
class MangaPage(val index: Int, val bitmap: Bitmap?, val text: String?)

/**
 * 漫画直读：不经 WebView，按阅读顺序找到每页 XHTML 引用的那张图，直接从 ZIP 解码（SPEC §5.5 / §6）。
 * 这台设备上 WebView 开一本漫画要 2.5s 以上，直读是一次解码的时间。
 */
class MangaSession(private val zip: ZipReader, val documents: List<String>) {
    val pageCount get() = documents.size
    private val imageEntries = arrayOfNulls<String>(documents.size)

    fun sample(index: Int): String? = zip.entries[documents[index]]?.let { zip.readBytes(it, MAX_XHTML_BYTES).decodeToString() }

    fun indexOf(documentPath: String): Int? = documents.indexOf(documentPath).takeIf { it >= 0 }

    /** 解码第 [index] 页，缩到不小于 [width]×[height] 的最小采样率。耗时，别在主线程调。 */
    fun load(index: Int, width: Int, height: Int): MangaPage {
        val xhtml = runCatching { sample(index) }.getOrNull() ?: return MangaPage(index, null, "")
        val entry = (imageEntries[index] ?: MangaDetector.imageHref(xhtml)
            ?.let { EpubMetaParser.resolve(documents[index], it) }
            ?.also { imageEntries[index] = it })
            ?.let(zip.entries::get)
            ?: return MangaPage(index, null, MangaDetector.plainText(xhtml))

        val bytes = zip.readBytes(entry, MAX_IMAGE_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        return MangaPage(index, bitmap, if (bitmap == null) "这一页的图片无法显示" else null)
    }

    private companion object {
        const val MAX_XHTML_BYTES = 2L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 64L * 1024 * 1024
    }
}
