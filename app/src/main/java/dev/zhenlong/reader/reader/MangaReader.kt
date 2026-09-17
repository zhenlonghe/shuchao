package dev.zhenlong.reader.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zhenlong.reader.MainActivity
import dev.zhenlong.reader.data.BookKind
import dev.zhenlong.reader.ui.ScreenMargin

private val SwipeThreshold = 48.dp

/**
 * 漫画：整页一张图，适配屏幕、居中；没有页脚，画面全给图（页数在工具条上看）。
 * 操作和文字阅读器一致：点左右两侧 = 下一页，右滑 = 上一页，左滑 = 下一页，点中间 = 工具条，音量键翻页。
 */
@Composable
internal fun MangaReader(ready: HostState.Ready, vm: ReaderHostViewModel, onBack: () -> Unit) {
    val activity = LocalContext.current as MainActivity
    val style by vm.mangaStyle.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    var chrome by remember { mutableStateOf<Chrome>(Chrome.Hidden) }
    val chromeNow by rememberUpdatedState(chrome)

    val exit = {
        vm.flush()
        onBack()
    }
    fun turn(forward: Boolean) {
        if (chromeNow != Chrome.Hidden) chrome = Chrome.Hidden
        vm.turn(forward)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    BackHandler {
        when (val c = chrome) {
            Chrome.Hidden -> exit()
            is Chrome.Bars -> chrome = if (c.panel != null) Chrome.Bars() else Chrome.Hidden
            Chrome.Toc -> chrome = Chrome.Bars()
        }
    }
    DisposableEffect(Unit) {
        activity.pageKeyHandler = ::turn
        onDispose { activity.pageKeyHandler = null }
    }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { at ->
                    when {
                        chromeNow != Chrome.Hidden -> chrome = Chrome.Hidden
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
        Box(
            Modifier.fillMaxSize()
                // 漫画自己的那套边距偏好，默认四边为 0
                .padding(horizontal = (style.pageMargins * MARGIN_UNIT_DP).dp, vertical = style.verticalMarginDp.dp)
                .onSizeChanged { vm.setViewport(it.width, it.height) },
            contentAlignment = Alignment.Center,
        ) {
            val current = page
            val bitmap = current?.bitmap
            when {
                bitmap != null -> Image(
                    bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                )
                current?.text != null -> Text(current.text, Modifier.padding(ScreenMargin), fontSize = 15.sp, lineHeight = 24.sp)
            }
        }
    }

    when (val c = chrome) {
        Chrome.Hidden -> {}
        is Chrome.Bars -> Bars(
            title = ready.book.title,
            mode = BookKind.MANGA,
            pageInfo = page?.let { PageInfo(it.index + 1, ready.manga.pageCount) },
            onMode = { vm.setMode(it) },
            style = style,
            fonts = emptyList(),
            panel = c.panel,
            onPickFontsFolder = {},
            onBack = exit,
            onStyle = vm::setMangaStyle,
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
                    link.url().removeFragment().path?.let { ready.manga.indexOf(it) }?.let(vm::go)
                },
            )
        }
    }
}

/** 左右边距滑块的一档（Readium 的 pageMargins 倍数）在漫画里折算成多少 dp。 */
private const val MARGIN_UNIT_DP = 16
