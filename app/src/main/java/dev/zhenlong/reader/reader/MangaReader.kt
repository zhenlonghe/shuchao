package dev.zhenlong.reader.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zhenlong.reader.MainActivity
import dev.zhenlong.reader.data.BookKind
import dev.zhenlong.reader.ui.ScreenMargin
import kotlin.math.abs

private val SwipeThreshold = 48.dp

/**
 * 漫画：整页一张图，适配屏幕、居中；没有页脚，画面全给图（页数在工具条上看）。
 * 操作和文字阅读器一致：点左右两侧 = 下一页，右滑 = 上一页，左滑 = 下一页，点中间 = 工具条，音量键翻页。
 * 双指张合放大（1–[MAX_ZOOM] 倍，跟手），放大时单指拖动 = 平移、不翻页；缩回 1 倍或翻页即复原。
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

    // 放大：以版心中心为原点，屏幕点 = 中心 + offset + zoom × (图上点 − 中心)
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val bitmapNow by rememberUpdatedState(page?.bitmap)
    // 翻页即复原：在组合里当场改，新页第一帧就是 1 倍（放进 LaunchedEffect 会先按旧倍数画一帧，墨水屏多刷一次）
    var zoomedPage by remember { mutableStateOf(page?.index) }
    if (zoomedPage != page?.index) {
        zoomedPage = page?.index
        zoom = 1f
        offset = Offset.Zero
    }

    /** 放大后图能平移的范围：图（按适配后的大小）超出版心的那一半；没超出的方向是 0、不让动。 */
    fun maxPan(z: Float): Offset {
        val bmp = bitmapNow ?: return Offset.Zero
        if (viewport.width <= 0 || viewport.height <= 0) return Offset.Zero
        val fit = minOf(viewport.width / bmp.width.toFloat(), viewport.height / bmp.height.toFloat())
        return Offset(
            ((bmp.width * fit * z - viewport.width) / 2f).coerceAtLeast(0f),
            ((bmp.height * fit * z - viewport.height) / 2f).coerceAtLeast(0f),
        )
    }
    fun clamp(o: Offset, z: Float): Offset {
        val max = maxPan(z)
        return Offset(o.x.coerceIn(-max.x, max.x), o.y.coerceIn(-max.y, max.y))
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    BackHandler {
        when (val c = chrome) {
            Chrome.Hidden -> exit()
            is Chrome.Bars -> chrome = when (c.panel) { null -> Chrome.Hidden; Panel.FONT_LIST -> Chrome.Bars(Panel.TYPE); else -> Chrome.Bars() }
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
                // 点 / 滑 / 双指缩放 / 放大后拖动，一个手势循环里判定，不叠 detectXxx（会互相抢事件）
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    var pinched = false
                    var moved = false
                    var travel = Offset.Zero
                    val center = Offset(size.width / 2f, size.height / 2f)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) {
                            pinched = true
                            event.changes.forEach { it.consume() }
                            if (bitmapNow == null) continue   // 没有图（文字页、还在解码）：不缩放，免得看不见的倍数吞掉滑动翻页
                            val z = event.calculateZoom()
                            val c = event.calculateCentroid(useCurrent = true) - center
                            val newZoom = (zoom * z).coerceIn(MIN_PINCH, MAX_ZOOM)
                            val k = newZoom / zoom
                            zoom = newZoom
                            offset = clamp(offset * k + c * (1 - k) + event.calculatePan(), newZoom)
                        } else if (!pinched) {
                            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                            travel += change.positionChange()
                            if (travel.getDistance() > slop) moved = true
                            if (moved && zoom > 1f) {
                                offset = clamp(offset + change.positionChange(), zoom)
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    when {
                        pinched -> if (zoom <= 1f) { zoom = 1f; offset = Offset.Zero } else offset = clamp(offset, zoom)
                        !moved -> when {
                            chromeNow != Chrome.Hidden -> chrome = Chrome.Hidden
                            down.position.x < size.width / 3f || down.position.x > size.width * 2 / 3f -> turn(forward = true)
                            else -> chrome = Chrome.Bars()
                        }
                        // 放大时拖动是平移；只有横向没超出版心、又是明显的横滑时才照常翻页
                        zoom > 1f && (maxPan(zoom).x > 0f || abs(travel.x) <= abs(travel.y)) -> {}
                        travel.x <= -SwipeThreshold.toPx() -> turn(forward = true)
                        travel.x >= SwipeThreshold.toPx() -> turn(forward = false)
                    }
                }
            },
    ) {
        Box(
            Modifier.fillMaxSize()
                // 漫画自己的那套边距偏好，默认四边为 0
                .padding(horizontal = (style.pageMargins * MARGIN_UNIT_DP).dp, vertical = style.verticalMarginDp.dp)
                .onSizeChanged {
                    viewport = it
                    offset = clamp(offset, zoom)   // 改了边距，版心变了：平移范围跟着收
                    vm.setViewport(it.width, it.height)
                },
            contentAlignment = Alignment.Center,
        ) {
            val current = page
            val bitmap = current?.bitmap
            when {
                bitmap != null -> Image(
                    bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = offset.x
                        translationY = offset.y
                    },
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
            author = ready.book.author,
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
            val current = remember(entries) {
                val here = page?.index ?: return@remember -1
                currentTocIndex(entries.orEmpty(), tocPosition(here)) { entry ->
                    entry.link.url().removeFragment().path?.let { ready.manga.indexOf(it) }?.let { tocPosition(it) }
                }
            }
            TocScreen(
                entries = entries,
                current = current,
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

/** 最多放大几倍。图按「不小于版心」解码，放到 4 倍时一般已经到原图像素。 */
private const val MAX_ZOOM = 4f
/** 捏的过程中允许缩到 1 倍以下一点（手感上有回弹），松手回到 1。 */
private const val MIN_PINCH = 0.8f
