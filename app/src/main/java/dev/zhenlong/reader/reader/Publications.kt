package dev.zhenlong.reader.reader

import android.content.Context
import android.net.Uri
import dev.zhenlong.reader.scan.openZip
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.data.CompositeContainer
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

class OpenedPublication(val publication: Publication, val zip: ZipContainer)

/** 用 Readium 解析一本 EPUB（走自己的 [ZipContainer]）。[extra]：额外挂进书里的资源（用户字体）。失败抛异常。 */
suspend fun openPublication(context: Context, fileUri: String, extra: Container<Resource>? = null): OpenedPublication {
    val httpClient = DefaultHttpClient()
    val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    val opener = PublicationOpener(DefaultPublicationParser(context, httpClient, assetRetriever, pdfFactory = null))

    val zip = ZipContainer(openZip(context, Uri.parse(fileUri)))
    val container = if (extra != null) CompositeContainer(zip, extra) else zip
    val asset = assetRetriever.retrieve(container, MediaType.EPUB).getOrElse {
        container.close()
        error("retrieve failed: $it")
    }
    val publication = opener.open(asset, allowUserInteraction = false).getOrElse {
        asset.close()
        error("open failed: $it")
    }
    return OpenedPublication(publication, zip)
}
