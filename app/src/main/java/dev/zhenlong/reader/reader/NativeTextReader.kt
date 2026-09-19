package dev.zhenlong.reader.reader

import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zhenlong.reader.MainActivity
import dev.zhenlong.reader.data.BookKind
import dev.zhenlong.reader.reader.text.Spot
import dev.zhenlong.reader.ui.launchOrToast

private val SwipeThreshold = 48.dp

/** 左右边距滑块的 1.0 档折算成多少 dp（和之前 Readium 的观感一致）。 */
private const val MARGIN_UNIT_DP = 30

/**
 * 文字书（原生排版）：整页画在 Canvas 上。操作和漫画一致：点左右两侧 = 下一页，右滑 = 上一页，
 * 点中间 = 工具条，音量键翻页；另外正文里的链接（脚注、书内目录）可点，返回键回到来处。
 */
@Composable
internal fun NativeTextReader(ready: HostState.Ready, vm: ReaderHostViewModel, onBack: () -> Unit) {
    val activity = LocalContext.current as MainActivity
    val density = LocalDensity.current
    val style by vm.textStyle.collectAsStateWithLifecycle()
    val fonts by vm.fonts.collectAsStateWithLifecycle()
    val page by ready.text.page.collectAsStateWithLifecycle()
    val pageNow by rememberUpdatedState(page)
    var chrome by remember { mutableStateOf<Chrome>(Chrome.Hidden) }
    val chromeNow by rememberUpdatedState(chrome)
    val pickFontsFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setFontsFolder(uri)
    }

    val exit = {
        vm.flush()
        onBack()
    }
    fun turn(forward: Boolean) {
        if (chromeNow != Chrome.Hidden) chrome = Chrome.Hidden
        ready.text.turn(forward)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    BackHandler {
        when (val c = chrome) {
            Chrome.Hidden -> if (!ready.text.back()) exit()
            is Chrome.Bars -> chrome = if (c.panel != null) Chrome.Bars() else Chrome.Hidden
            Chrome.Toc -> chrome = Chrome.Bars()
        }
    }
    DisposableEffect(Unit) {
        activity.pageKeyHandler = ::turn
        onDispose { activity.pageKeyHandler = null }
    }

    val margins = PaddingValues(
        start = (style.pageMargins * MARGIN_UNIT_DP).dp,
        end = (style.pageMargins * MARGIN_UNIT_DP).dp,
        top = style.verticalMarginDp.dp,
        bottom = bottomMarginDp(style.verticalMarginDp).dp,
    )
    val marginsNow by rememberUpdatedState(margins)

    Column(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { at ->
                    val link = pageNow?.let { p ->
                        val left = marginsNow.calculateLeftPadding(LayoutDirection.Ltr).toPx()
                        p.layout.linkAt(p.page, at.x - left, at.y - marginsNow.calculateTopPadding().toPx())
                    }
                    when {
                        chromeNow != Chrome.Hidden -> chrome = Chrome.Hidden
                        link != null -> ready.text.follow(link)
                        at.x < size.width / 3f || at.x > size.width * 2 / 3f -> turn(forward = true)
                        else -> chrome = Chrome.Bars()
                    }
                }
            }
            .pointerInput(Unit) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (dragged <= -SwipeThreshold.toPx()) turn(forward = true)
                        else if (dragged >= SwipeThreshold.toPx()) turn(forward = false)
                    },
                ) { _, delta -> dragged += delta }
            },
    ) {
        Canvas(
            Modifier.fillMaxWidth().weight(1f).padding(margins)
                .onSizeChanged { vm.setTextViewport(it.width, it.height, density.density, density.density * density.fontScale) },
        ) {
            page?.let { p -> drawIntoCanvas { p.layout.draw(it.nativeCanvas, p.page, style.emboldenEm) } }
        }
        PageFooter(page?.let { PageInfo(it.bookPage, it.bookPages) })
    }

    when (val c = chrome) {
        Chrome.Hidden -> {}
        is Chrome.Bars -> Bars(
            title = ready.book.title,
            mode = BookKind.TEXT,
            pageInfo = null,
            onMode = { vm.setMode(it) },
            style = style,
            fonts = fonts,
            panel = c.panel,
            onPickFontsFolder = {
                pickFontsFolder.launchOrToast(activity, DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Fonts"))
            },
            onBack = exit,
            onStyle = vm::setTextStyle,
            onPanel = { chrome = Chrome.Bars(it.takeIf { p -> p != c.panel }) },
            onToc = { chrome = Chrome.Toc },
        )
        Chrome.Toc -> {
            val entries by produceState<List<TocEntry>?>(null) { value = vm.loadToc() }
            TocScreen(
                entries = entries,
                onBack = { chrome = Chrome.Bars() },
                onPick = { link ->
                    chrome = Chrome.Hidden
                    val url = link.url()
                    url.removeFragment().path?.let(ready.text::chapterOf)?.let { chapter ->
                        ready.text.go(chapter, url.fragment?.let { Spot.Anchor(it) } ?: Spot.Offset(0))
                    }
                },
            )
        }
    }
}
