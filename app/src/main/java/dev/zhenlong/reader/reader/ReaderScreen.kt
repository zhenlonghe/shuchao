@file:OptIn(ExperimentalReadiumApi::class)

package dev.zhenlong.reader.reader

import android.graphics.Color as AndroidColor
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.DocumentsContract
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import dev.zhenlong.reader.ui.PagerFooter
import dev.zhenlong.reader.ui.verticalPageSwipe
import java.util.Date
import kotlin.math.roundToInt
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.zhenlong.reader.MainActivity
import dev.zhenlong.reader.R
import dev.zhenlong.reader.data.BookKind
import dev.zhenlong.reader.data.ReaderFont
import dev.zhenlong.reader.data.ReaderStyle
import dev.zhenlong.reader.ui.BarIcon
import dev.zhenlong.reader.ui.BarIconInset
import dev.zhenlong.reader.ui.OnePx
import dev.zhenlong.reader.ui.ScreenMargin
import kotlinx.coroutines.delay
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.epub.css.Color as CssColor
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.navigator.preferences.Color as ReadiumColor

private const val NAVIGATOR_TAG = "epub-navigator"
private val FooterHeight = 22.dp

/**
 * 版心下方、页脚上方还要垫多少 dp。页脚本身已经占了 [FooterHeight]，把它算进下边距里：
 * 「下留白 + 页脚」≈ 上留白，版心才落在屏幕正中；再留 [FOOTER_GAP_DP] 不让正文贴着页脚的小字。
 */
internal fun bottomMarginDp(verticalMarginDp: Int): Int =
    (verticalMarginDp - FooterHeight.value.toInt()).coerceAtLeast(0) + FOOTER_GAP_DP

private const val FOOTER_GAP_DP = 6
private const val REFLOW_SETTLE_MS = 600L
private const val REVEAL_DELAY_MS = 120L
private const val FONT_ROWS = 5
private val SliderThumbRadius = 9.dp

/** 选字体文件夹时，让系统选择器直接落在 /sdcard/Fonts。 */
private val DEFAULT_FONTS_FOLDER =
    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Fonts")
private const val PAGE_INFO_JS =
    "(function(){var w=window.innerWidth,e=document.scrollingElement;" +
        "return Math.round(Math.abs(e.scrollLeft)/w)+','+Math.round(e.scrollWidth/w);})()"

internal enum class Panel(val label: String) { FONT_SIZE("字号"), FONT("字体"), LINE_HEIGHT("行距"), MARGIN("边距") }

internal sealed interface Chrome {
    data object Hidden : Chrome
    data class Bars(val panel: Panel? = null) : Chrome
    data object Toc : Chrome
}

/** 全书的当前页 / 总页数（估算，见 [PageEstimator]）。 */
internal data class PageInfo(val page: Int, val pages: Int) {
    override fun toString() = "$page/$pages"
}

private fun Pair<Int, Int>?.toPageInfo() = this?.let { PageInfo(it.first, it.second) }

/** 阅读页入口：按模式选渲染器——漫画直接解码图片，文字书用原生排版，「原版」才走 Readium / WebView。 */
@Composable
fun ReaderScreen(onBack: () -> Unit, host: ReaderHostViewModel = viewModel()) {
    val state by host.state.collectAsStateWithLifecycle()
    val mode by host.mode.collectAsStateWithLifecycle()
    Immersive()

    Box(Modifier.fillMaxSize().background(Color.White)) {
        when (val s = state) {
            HostState.Loading -> {}
            HostState.Failed -> CannotOpen(onBack)
            is HostState.Ready -> when (mode) {
                BookKind.MANGA -> MangaReader(s, host, onBack)
                BookKind.TEXT -> NativeTextReader(s, host, onBack)
                BookKind.WEB -> WebReader(host, onBack)
            }
        }
    }
}

@Composable
private fun CannotOpen(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = ScreenMargin - BarIconInset)) { BarIcon(R.drawable.ic_back, "返回", onBack) }
        Text("无法打开这本书", Modifier.align(Alignment.Center), fontSize = 16.sp)
    }
}

@Composable
private fun WebReader(hostVm: ReaderHostViewModel, onBack: () -> Unit, vm: ReaderViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    val exit = {
        vm.flush()
        onBack()
    }
    when (val s = state) {
        ReaderState.Loading -> {}
        ReaderState.Failed -> CannotOpen(exit)
        is ReaderState.Ready -> ReaderContent(
            ready = s,
            vm = vm,
            onMode = { kind ->
                vm.flush()
                hostVm.setMode(kind, chapterHint = vm.lastLocator?.href?.path, progressionHint = vm.lastLocator?.locations?.progression)
            },
            exit = exit,
        )
    }
}

/** 阅读时全屏无状态栏；离开时还原。 */
@Composable
private fun Immersive() {
    val activity = LocalContext.current as MainActivity
    DisposableEffect(Unit) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
    // 从系统的文件夹选择器回来后状态栏会重新出现
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
private fun ReaderContent(ready: ReaderState.Ready, vm: ReaderViewModel, onMode: (BookKind) -> Unit, exit: () -> Unit) {
    val activity = LocalContext.current as MainActivity
    val style by vm.style.collectAsStateWithLifecycle()
    var chapter by remember { mutableIntStateOf(0) }
    val fonts by vm.fonts.collectAsStateWithLifecycle()
    val pickFontsFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setFontsFolder(uri)
    }
    var chrome by remember { mutableStateOf<Chrome>(Chrome.Hidden) }
    var pageInfo by remember { mutableStateOf<PageInfo?>(null) }
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    val chromeNow by rememberUpdatedState(chrome)

    fun turn(forward: Boolean) {
        if (chromeNow != Chrome.Hidden) chrome = Chrome.Hidden
        navigator?.let { if (forward) it.goForward(animated = false) else it.goBackward(animated = false) }
    }

    BackHandler {
        when (val c = chrome) {
            Chrome.Hidden -> exit()
            is Chrome.Bars -> chrome = if (c.panel != null) Chrome.Bars() else Chrome.Hidden
            Chrome.Toc -> chrome = Chrome.Bars()
        }
    }

    // 音量键 / 翻页键
    DisposableEffect(Unit) {
        activity.pageKeyHandler = ::turn
        onDispose { activity.pageKeyHandler = null }
    }

    // 字体声明在渲染器创建时就定死，所以字体列表变了（刚授权了字体文件夹）要重建一次，从当前位置接着读
    DisposableEffect(ready, fonts) {
        pageInfo = null
        val fm = activity.supportFragmentManager
        fm.fragmentFactory = EpubNavigatorFactory(ready.publication).createFragmentFactory(
            initialLocator = vm.lastLocator ?: ready.initialLocator,
            initialPreferences = style.toPreferences(fonts),
            listener = object : EpubNavigatorFragment.Listener {
                override fun onExternalLinkActivated(url: AbsoluteUrl) {}   // 纯本地，不开浏览器
            },
            paginationListener = object : EpubNavigatorFragment.PaginationListener {
                override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                    chapter = ready.publication.readingOrder.indexOfFirstWithHref(locator.href) ?: 0
                    ready.pages.record(chapter, totalPages)
                    pageInfo = ready.pages.locate(chapter, pageIndex + 1).toPageInfo()
                    vm.onFirstPage()
                }
            },
            configuration = EpubNavigatorFragment.Configuration {
                shouldApplyInsetsPadding = false
                // 链接、选中文字也只用黑白（SPEC §2.2）；ReadiumCSS 默认是蓝色链接、浅蓝选区
                readiumCssRsProperties = RsProperties(
                    linkColor = CssColor.Hex("#000000"),
                    visitedColor = CssColor.Hex("#000000"),
                    selectionBackgroundColor = CssColor.Hex("#000000"),
                    selectionTextColor = CssColor.Hex("#FFFFFF"),
                )
                // @font-face 是惰性的：全部声明，浏览器只会去取真正用到的那个
                fonts.forEach { font ->
                    addFontFamilyDeclaration(FontFamily(font.family)) { addFontFace { addSource(font.url) } }
                }
            },
        )
        fm.commitNow { replace(R.id.reader_container, EpubNavigatorFragment::class.java, null, NAVIGATOR_TAG) }
        val fragment = fm.findFragmentByTag(NAVIGATOR_TAG) as EpubNavigatorFragment
        fragment.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                val width = fragment.view?.width?.takeIf { it > 0 } ?: return false
                when {
                    chromeNow != Chrome.Hidden -> chrome = Chrome.Hidden
                    // 左右两侧都是下一页（左右手都能单手翻）；上一页靠右滑
                    event.point.x < width / 3f || event.point.x > width * 2 / 3f -> turn(forward = true)
                    else -> chrome = Chrome.Bars()
                }
                return true
            }
        })
        navigator = fragment
        onDispose {
            navigator = null
            fm.findFragmentByTag(NAVIGATOR_TAG)?.let { fm.commitNow(allowStateLoss = true) { remove(it) } }
        }
    }

    LaunchedEffect(navigator) {
        navigator?.currentLocator?.collect(vm::onLocator)
    }

    // 排版改动立即应用。重排不触发 Readium 的翻页回调，页脚的「页/总页」得自己向 WebView 问一次
    var appliedStyle by remember { mutableStateOf(style) }
    LaunchedEffect(navigator, style, fonts) {
        val nav = navigator ?: return@LaunchedEffect
        nav.submitPreferences(style.toPreferences(fonts))
        if (style == appliedStyle) return@LaunchedEffect
        appliedStyle = style
        ready.pages.reset()   // 重排后各章页数全变了
        delay(REFLOW_SETTLE_MS)
        val (page, pages) = nav.evaluateJavascript(PAGE_INFO_JS)?.trim('"')?.split(',')?.mapNotNull(String::toIntOrNull)
            ?.takeIf { it.size == 2 } ?: return@LaunchedEffect
        ready.pages.record(chapter, pages)
        pageInfo = ready.pages.locate(chapter, page + 1).toPageInfo()
    }

    Column(Modifier.fillMaxSize()) {
        AndroidView(
            // 上下留白：正文不顶着屏幕上缘，也不偏上（见 bottomMarginDp）
            modifier = Modifier.fillMaxWidth().weight(1f)
                .padding(top = style.verticalMarginDp.dp, bottom = bottomMarginDp(style.verticalMarginDp).dp),
            factory = { context ->
                SwipeInterceptLayout(context) { forward -> turn(forward) }.apply {
                    addView(FragmentContainerView(context).apply { id = R.id.reader_container })
                }
            },
        )
        PageFooter(pageInfo)
    }
    // Readium 会先亮出章首、再滚到上次的位置：首页就位前盖一层白，墨水屏上只刷一次。
    // WebView 的画面比回调晚一两帧才上屏，所以回调到了再等一小会儿才揭开，否则页脚会比正文先出现
    var revealed by remember(ready, fonts) { mutableStateOf(false) }
    LaunchedEffect(pageInfo != null) {
        if (pageInfo == null) revealed = false
        else {
            delay(REVEAL_DELAY_MS)
            revealed = true
        }
    }
    if (!revealed) Box(Modifier.fillMaxSize().background(Color.White))

    when (val c = chrome) {
        Chrome.Hidden -> {}
        is Chrome.Bars -> Bars(
            title = ready.publication.metadata.title ?: ready.book.title,
            mode = BookKind.WEB,
            pageInfo = pageInfo,
            onMode = onMode,
            style = style,
            fonts = fonts,
            panel = c.panel,
            onPickFontsFolder = { pickFontsFolder.launch(DEFAULT_FONTS_FOLDER) },
            onBack = exit,
            onStyle = vm::setStyle,
            onPanel = { chrome = Chrome.Bars(it.takeIf { p -> p != c.panel }) },
            onToc = { chrome = Chrome.Toc },
        )
        Chrome.Toc -> TocScreen(
            entries = remember(ready) { flattenToc(ready.publication.tableOfContents) },
            onBack = { chrome = Chrome.Bars() },
            onPick = { link ->
                chrome = Chrome.Hidden
                navigator?.go(link, animated = false)
            },
        )
    }
}

private fun ReaderStyle.toPreferences(fonts: List<UserFont>) = EpubPreferences(
    scroll = false,
    columnCount = ColumnCount.ONE,
    publisherStyles = false,   // 关掉才能覆盖行距
    theme = Theme.LIGHT,
    textColor = ReadiumColor(AndroidColor.BLACK),   // LIGHT 主题的字是 #121212，墨水屏上要纯黑
    backgroundColor = ReadiumColor(AndroidColor.WHITE),
    fontSize = fontScale,
    fontFamily = when (font) {
        ReaderFont.SERIF -> FontFamily.SERIF
        ReaderFont.SANS -> FontFamily.SANS_SERIF
        ReaderFont.PUBLISHER -> null
        // 字体文件不在了（被删 / 授权被撤）就退回出版商默认
        ReaderFont.CUSTOM -> fonts.firstOrNull { it.fileName == customFont }?.let { FontFamily(it.family) }
    },
    lineHeight = lineHeight,
    pageMargins = pageMargins,
)

/** 页脚：左系统时间，右全书的「当前页/总页数」。 */
@Composable
internal fun PageFooter(info: PageInfo?) {
    Row(
        Modifier.fillMaxWidth().height(FooterHeight).padding(horizontal = ScreenMargin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(currentTime(), Modifier.weight(1f), fontSize = 10.sp)
        Text(info?.toString().orEmpty(), fontSize = 10.sp)
    }
}

/** 跟着系统的 TIME_TICK 广播每分钟变一次：自己不起定时器，不在阅读页时也不监听。 */
@Composable
private fun currentTime(): String {
    val context = LocalContext.current
    val format = remember { DateFormat.getTimeFormat(context) }   // 跟随系统的 12 / 24 小时制
    var now by remember { mutableStateOf(format.format(Date())) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                now = format.format(Date())
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_TIME_TICK).apply { addAction(Intent.ACTION_TIME_CHANGED) })
        now = format.format(Date())
        onDispose { context.unregisterReceiver(receiver) }
    }
    return now
}

@Composable
private fun Rule() = Box(Modifier.fillMaxWidth().height(OnePx).background(Color.Black))

/** 工具条是盖在书页上的 overlay，出现 / 消失只重绘自己那两条（SPEC §2.5）。 */
@Composable
internal fun Bars(
    title: String,
    mode: BookKind,
    pageInfo: PageInfo?,
    onMode: (BookKind) -> Unit,
    style: ReaderStyle,
    fonts: List<UserFont>,
    panel: Panel?,
    onPickFontsFolder: () -> Unit,
    onBack: () -> Unit,
    onStyle: (ReaderStyle) -> Unit,
    onPanel: (Panel) -> Unit,
    onToc: () -> Unit,
) {
    val swallowTaps = Modifier.pointerInput(Unit) { detectTapGestures { } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopCenter).background(Color.White).then(swallowTaps)) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = ScreenMargin - BarIconInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarIcon(R.drawable.ic_back, "返回", onBack)
                Text(title, Modifier.weight(1f).padding(end = BarIconInset), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (mode == BookKind.MANGA) Text(pageInfo?.toString().orEmpty(), Modifier.padding(end = 8.dp), fontSize = 13.sp)
                // 当前模式加下划线。文字 = 原生排版（快）；原版 = Readium / WebView，还原出版商的版式（慢）；漫画 = 整页图
                ModeButton("文字", mode == BookKind.TEXT) { onMode(BookKind.TEXT) }
                ModeButton("原版", mode == BookKind.WEB) { onMode(BookKind.WEB) }
                ModeButton("漫画", mode == BookKind.MANGA) { onMode(BookKind.MANGA) }
            }
            Rule()
        }

        Column(Modifier.align(Alignment.BottomCenter).background(Color.White).then(swallowTaps)) {
            Rule()
            when (panel) {
                Panel.FONT_SIZE -> StepSlider(ReaderStyle.FONT_STEPS, style.fontSizeStep) { onStyle(style.copy(fontSizeStep = it)) }
                Panel.FONT -> FontPanel(style, fonts, onStyle, onPickFontsFolder)
                Panel.LINE_HEIGHT -> StepSlider(ReaderStyle.LINE_STEPS, style.lineHeightStep) { onStyle(style.copy(lineHeightStep = it)) }
                Panel.MARGIN -> {
                    StepSlider(ReaderStyle.MARGIN_STEPS, style.marginStep, "左右") { onStyle(style.copy(marginStep = it)) }
                    StepSlider(ReaderStyle.VERTICAL_MARGIN_STEPS, style.verticalMarginStep, "上下") {
                        onStyle(style.copy(verticalMarginStep = it))
                    }
                }
                null -> {}
            }
            if (panel != null && panel != Panel.FONT) Rule()   // 字体面板自带
            Row(Modifier.fillMaxWidth().height(56.dp)) {
                // 漫画没有字，字号 / 字体 / 行距不出现
                val panels = if (mode == BookKind.MANGA) listOf(Panel.MARGIN) else Panel.entries
                panels.forEach { p -> BarButton(p.label, selected = p == panel) { onPanel(p) } }
                BarButton("目录", onClick = onToc)
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.height(48.dp).clickable(onClick = onClick).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 14.sp, textDecoration = if (selected) TextDecoration.Underline else null)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarButton(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.weight(1f).fillMaxSize().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 15.sp, textDecoration = if (selected) TextDecoration.Underline else null)
    }
}

private class FontChoice(val label: String, val selected: Boolean, val onPick: () -> Unit)

/** 字体：内置三种 + 字体文件夹里的文件，竖排、整屏分页；末项用来授权 / 更换字体文件夹。 */
@Composable
private fun FontPanel(style: ReaderStyle, fonts: List<UserFont>, onStyle: (ReaderStyle) -> Unit, onPickFolder: () -> Unit) {
    val choices = listOf(ReaderFont.PUBLISHER, ReaderFont.SERIF, ReaderFont.SANS).map { f ->
        FontChoice(f.label, style.font == f) { onStyle(style.copy(font = f, customFont = null)) }
    } + fonts.map { f ->
        FontChoice(f.name, style.font == ReaderFont.CUSTOM && style.customFont == f.fileName) {
            onStyle(style.copy(font = ReaderFont.CUSTOM, customFont = f.fileName))
        }
    } + FontChoice(if (fonts.isEmpty()) "选择字体文件夹…" else "更换字体文件夹…", false, onPickFolder)

    val pageCount = (choices.size + FONT_ROWS - 1) / FONT_ROWS
    var page by remember { mutableIntStateOf(choices.indexOfFirst { it.selected }.coerceAtLeast(0) / FONT_ROWS) }
    val current = page.coerceIn(0, pageCount - 1)
    val turn = { delta: Int -> page = (current + delta).coerceIn(0, pageCount - 1) }

    Column(Modifier.fillMaxWidth().verticalPageSwipe(current, pageCount) { turn(it) }) {
        // 行数固定：翻到不满的一页时面板高度不跳
        val rows = if (pageCount > 1) FONT_ROWS else choices.size
        for (i in 0 until rows) {
            val choice = choices.getOrNull(current * FONT_ROWS + i)
            Box(
                Modifier.fillMaxWidth().height(48.dp)
                    .then(if (choice != null) Modifier.clickable(onClick = choice.onPick) else Modifier)
                    .padding(horizontal = ScreenMargin),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (choice != null) {
                    Text(
                        choice.label,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        textDecoration = if (choice.selected) TextDecoration.Underline else null,
                    )
                }
            }
        }
        if (pageCount > 1) PagerFooter(current, pageCount) { turn(it) }
    }
    Rule()
}

/**
 * 离散滑动条：− ——●—— +。拖动时只有滑块在动（局部重绘），松手才提交，正文只重排一次。
 */
@Composable
private fun StepSlider(steps: Int, value: Int, label: String? = null, onChange: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = dragging ?: value
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = ScreenMargin - BarIconInset), verticalAlignment = Alignment.CenterVertically) {
        if (label != null) Text(label, Modifier.padding(start = BarIconInset, end = 4.dp), fontSize = 13.sp)
        SliderEnd("−") { if (value > 0) onChange(value - 1) }
        Canvas(
            Modifier.weight(1f).fillMaxHeight().pointerInput(steps) {
                val inset = SliderThumbRadius.toPx()
                fun stepAt(x: Float) = (((x - inset) / (size.width - inset * 2)) * (steps - 1)).roundToInt().coerceIn(0, steps - 1)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var step = stepAt(down.position.x)
                    dragging = step
                    drag(down.id) { change ->
                        step = stepAt(change.position.x)
                        dragging = step
                        change.consume()
                    }
                    dragging = null
                    onChange(step)
                }
            },
        ) {
            val inset = SliderThumbRadius.toPx()
            val y = size.height / 2
            val span = size.width - inset * 2
            drawLine(Color.Black, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 1f)
            for (i in 0 until steps) {
                val x = inset + span * i / (steps - 1)
                drawLine(Color.Black, Offset(x, y - 4.dp.toPx()), Offset(x, y + 4.dp.toPx()), strokeWidth = 1f)
            }
            drawCircle(Color.Black, inset, Offset(inset + span * shown / (steps - 1), y))
        }
        SliderEnd("+") { if (value < steps - 1) onChange(value + 1) }
    }
}

@Composable
private fun SliderEnd(label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Text(label, fontSize = 18.sp) }
}

internal class TocEntry(val link: Link, val depth: Int)

internal fun flattenToc(links: List<Link>, depth: Int = 0): List<TocEntry> =
    links.flatMap { listOf(TocEntry(it, depth)) + flattenToc(it.children, depth + 1) }
