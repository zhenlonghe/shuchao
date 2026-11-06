@file:OptIn(ExperimentalReadiumApi::class)

package dev.zhenlong.reader.reader

import android.content.Context
import android.graphics.Color as AndroidColor
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import dev.zhenlong.reader.ui.EdgeGray
import dev.zhenlong.reader.ui.PageScrollbar
import androidx.compose.runtime.produceState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.zhenlong.reader.ui.batteryPercent
import dev.zhenlong.reader.ui.currentTime
import dev.zhenlong.reader.ui.minuteTick
import dev.zhenlong.reader.ui.ScreenMargin
import dev.zhenlong.reader.ui.launchOrToast
import dev.zhenlong.reader.ui.verticalPageSwipe
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.Color as CssColor
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import androidx.compose.ui.text.font.FontFamily as ComposeFontFamily
import androidx.compose.ui.text.font.Typeface as ComposeTypeface
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.util.AbsoluteUrl

private const val NAVIGATOR_TAG = "epub-navigator"
private val FooterHeight = 22.dp
internal val HeaderHeight = FooterHeight

/**
 * 版心下方、页脚上方还要垫多少 dp。页脚本身已经占了 [FooterHeight]，把它算进下边距里：
 * 「下留白 + 页脚」≈ 上留白，版心才落在屏幕正中；再留 [FOOTER_GAP_DP] 不让正文贴着页脚的小字。
 */
internal fun bottomMarginDp(verticalMarginDp: Int): Int =
    (verticalMarginDp - FooterHeight.value.toInt()).coerceAtLeast(0) + FOOTER_GAP_DP

/** 有页眉（章名）时版心上方的垫高，和 [bottomMarginDp] 对称：页眉算进上边距，版心仍在正中。 */
internal fun topMarginDp(verticalMarginDp: Int): Int =
    (verticalMarginDp - HeaderHeight.value.toInt()).coerceAtLeast(0) + FOOTER_GAP_DP

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

/**
 * 底栏的两个面板（用户 2026-11-01 要求把字号 / 字体 / 粗细 / 行距 / 边距重新归组）：
 * 字体 = 字体 · 字号 · 粗细（字本身）；版式 = 行距 · 左右 · 上下（字在页上怎么排）。[FONT_LIST] 是从「字体」那一行点进去的字体列表。
 */
internal enum class Panel { TYPE, LAYOUT, FONT_LIST }

internal sealed interface Chrome {
    data object Hidden : Chrome
    data class Bars(val panel: Panel? = null) : Chrome
    data object Toc : Chrome
}

/** 全书的当前页 / 总页数（估算，见 [PageEstimator]）。 */
internal data class PageInfo(val page: Int, val pages: Int) {
    override fun toString() = "$page/$pages"
    /** 全书读到百分之几：最后一页是 100，第一页至少 1（翻开了就不写 0）。 */
    val percent: Int get() = if (pages <= 0) 0 else (page * 100 / pages).coerceIn(1, 100)
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
            is Chrome.Bars -> chrome = when (c.panel) { null -> Chrome.Hidden; Panel.FONT_LIST -> Chrome.Bars(Panel.TYPE); else -> Chrome.Bars() }
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
            author = ready.book.author,
            mode = BookKind.WEB,
            pageInfo = pageInfo,
            onMode = onMode,
            style = style,
            fonts = fonts,
            panel = c.panel,
            onPickFontsFolder = { pickFontsFolder.launchOrToast(activity, DEFAULT_FONTS_FOLDER) },
            onBack = exit,
            onStyle = vm::setStyle,
            onPanel = { chrome = Chrome.Bars(it.takeIf { p -> p != c.panel }) },
            onToc = { chrome = Chrome.Toc },
        )
        Chrome.Toc -> {
            val entries = remember(ready) { flattenToc(ready.publication.tableOfContents) }
            val current = remember(entries) {
                val chapterOf = { path: String? -> ready.publication.readingOrder.indexOfFirst { it.url().path == path }.takeIf { it >= 0 } }
                val here = chapterOf(vm.lastLocator?.href?.path) ?: return@remember -1
                currentTocIndex(entries, tocPosition(here)) { entry -> chapterOf(entry.link.url().removeFragment().path)?.let { tocPosition(it) } }
            }
            TocScreen(
                entries = entries,
                current = current,
                onBack = { chrome = Chrome.Bars() },
                onPick = { link ->
                    chrome = Chrome.Hidden
                    navigator?.go(link, animated = false)
                },
            )
        }
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

/**
 * 页脚：左系统时间 + 小闪电和电量百分数（用户 2026-11-03，取代 11-02 的电池图标；闪电把它和右边的阅读百分比区分开），
 * 右全书的「当前页/总页数 · 百分比」（用户 2026-10-29 要的全书进度；和书架上的进度同源）。
 */
@Composable
internal fun PageFooter(info: PageInfo?) {
    val tick = minuteTick()
    Box(Modifier.fillMaxWidth().height(FooterHeight).padding(horizontal = ScreenMargin)) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            Text(currentTime(tick), fontSize = 10.sp)
            Spacer(Modifier.width(8.dp))
            Bolt()
            Spacer(Modifier.width(2.dp))
            Text("${batteryPercent(tick)}%", fontSize = 10.sp)
        }
        Text(info?.let { "$it · ${it.percent}%" }.orEmpty(), Modifier.align(Alignment.CenterEnd), fontSize = 10.sp)
    }
}

/** 电量前的小闪电：实心黑色，比 10sp 的数字略矮，和数字的字身对齐。 */
@Composable
private fun Bolt() {
    Canvas(Modifier.size(width = 5.dp, height = 8.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.62f, 0f)
            lineTo(0f, h * 0.58f)
            lineTo(w * 0.44f, h * 0.58f)
            lineTo(w * 0.34f, h)
            lineTo(w, h * 0.40f)
            lineTo(w * 0.56f, h * 0.40f)
            close()
        }
        drawPath(path, Color.Black)
    }
}

/** 页眉：左上角的章名（目录里的标题，文字模式才有；用户 2026-10-29 指定从页脚移上来）。 */
@Composable
internal fun PageHeader(chapter: String?) {
    Box(Modifier.fillMaxWidth().height(HeaderHeight).padding(horizontal = ScreenMargin)) {
        Text(
            chapter.orEmpty(),
            Modifier.align(Alignment.CenterStart),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Rule() = Box(Modifier.fillMaxWidth().height(OnePx).background(Color.Black))

private val BottomButtonWidth = 64.dp
private val PanelRowHeight = 52.dp
private val PanelLabelWidth = 40.dp

/** 工具条是盖在书页上的 overlay，出现 / 消失只重绘自己那两条（SPEC §2.5）。 */
@Composable
internal fun Bars(
    title: String,
    author: String?,
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
                // 书名旁边灰字写作者（用户 2026-11-02：作者平时不显示，呼出工具条时看一眼就够）。书名优先，作者最多占一截
                Row(Modifier.weight(1f).padding(end = BarIconInset), verticalAlignment = Alignment.Bottom) {
                    Text(title, Modifier.weight(1f, fill = false), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!author.isNullOrBlank()) {
                        Text(
                            author,
                            Modifier.padding(start = 8.dp, bottom = 1.dp).widthIn(max = 120.dp),
                            fontSize = 13.sp,
                            color = PanelLabelGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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
                Panel.TYPE -> TypePanel(mode, style, fonts, onStyle) { onPanel(Panel.FONT_LIST) }
                Panel.FONT_LIST -> FontPanel(style, fonts, onStyle, onPickFontsFolder) { onPanel(Panel.TYPE) }
                Panel.LAYOUT -> LayoutPanel(mode, style, onStyle)
                null -> {}
            }
            if (panel != null) Rule()
            // 左：目录（去哪儿）；右：字体 / 版式（长什么样）。两组意思不同，中间用留白隔开。漫画没有字，只有版式
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = ScreenMargin - (BottomButtonWidth - 24.dp) / 2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomButton(R.drawable.ic_toc, "目录", selected = false, onClick = onToc)
                Spacer(Modifier.weight(1f))
                if (mode != BookKind.MANGA) {
                    BottomButton(R.drawable.ic_type, "字体", panel == Panel.TYPE || panel == Panel.FONT_LIST) { onPanel(Panel.TYPE) }
                }
                BottomButton(R.drawable.ic_layout, "版式", panel == Panel.LAYOUT) { onPanel(Panel.LAYOUT) }
            }
            // 漫画平时没有页脚（画面全给图），呼出工具条时在最底下补一行时间 / 电量（用户 2026-11-05）；页数已在顶栏
            if (mode == BookKind.MANGA) PageFooter(null)
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.height(48.dp).clickable(onClick = onClick).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 14.sp, textDecoration = if (selected) TextDecoration.Underline else null)
    }
}

/**
 * 只有图标（用户 2026-11-03：三个按钮认得出，不用再写字；[label] 只给无障碍读）。
 * 打开着的面板，图标下面画一道短横线（和顶栏模式切换的下划线一个说法）。
 */
@Composable
private fun BottomButton(icon: Int, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(BottomButtonWidth).fillMaxHeight().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(BottomMarkGap + 2.dp))   // 和下面那道横线对称，图标落在正中
        Image(painterResource(icon), label, Modifier.size(24.dp))
        Spacer(Modifier.height(BottomMarkGap))
        Box(Modifier.width(16.dp).height(2.dp).background(if (selected) Color.Black else Color.Transparent))
    }
}

private val BottomMarkGap = 6.dp

/** 字体面板：字体（点进列表）· 字号 · 粗细。粗细只有原生引擎能做（Readium 3.3.0 的 fontWeight 是空实现）。 */
@Composable
private fun TypePanel(mode: BookKind, style: ReaderStyle, fonts: List<UserFont>, onStyle: (ReaderStyle) -> Unit, onFontList: () -> Unit) {
    val fontName = when (style.font) {
        ReaderFont.CUSTOM -> fonts.firstOrNull { it.fileName == style.customFont }?.let(FontNames::of) ?: ReaderFont.PUBLISHER.label
        else -> style.font.label
    }
    Row(
        Modifier.fillMaxWidth().height(PanelRowHeight).clickable(onClick = onFontList).padding(horizontal = ScreenMargin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelLabel("字体")
        Text(fontName, Modifier.weight(1f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, textAlign = TextAlign.End)
        Spacer(Modifier.width(4.dp))
        Image(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp))
    }
    StepSlider(
        "字号", ReaderStyle.FONT_STEPS, style.fontSizeStep,
        less = { Text("A", fontSize = 13.sp) },
        more = { Text("A", fontSize = 21.sp) },
    ) { onStyle(style.copy(fontSizeStep = it)) }
    if (mode == BookKind.TEXT) {
        StepSlider(
            "粗细", ReaderStyle.WEIGHT_STEPS, style.fontWeightStep,
            less = { Text("A", fontSize = 17.sp, fontWeight = FontWeight.Light) },
            more = { Text("A", fontSize = 17.sp, fontWeight = FontWeight.Black) },
        ) { onStyle(style.copy(fontWeightStep = it)) }
    }
}

/** 版式面板：行距 · 左右 · 上下。两端是示意图标：紧 / 松的行、窄 / 宽的页边。漫画没有字，不出现行距。 */
@Composable
private fun LayoutPanel(mode: BookKind, style: ReaderStyle, onStyle: (ReaderStyle) -> Unit) {
    if (mode != BookKind.MANGA) {
        StepSlider(
            "行距", ReaderStyle.LINE_STEPS, style.lineHeightStep,
            less = { SliderIcon(R.drawable.ic_lines_tight) },
            more = { SliderIcon(R.drawable.ic_lines_loose) },
        ) { onStyle(style.copy(lineHeightStep = it)) }
    }
    StepSlider(
        "左右", ReaderStyle.MARGIN_STEPS, style.marginStep,
        less = { SliderIcon(R.drawable.ic_margin_h_narrow) },
        more = { SliderIcon(R.drawable.ic_margin_h_wide) },
    ) { onStyle(style.copy(marginStep = it)) }
    StepSlider(
        "上下", ReaderStyle.VERTICAL_MARGIN_STEPS, style.verticalMarginStep,
        less = { SliderIcon(R.drawable.ic_margin_v_narrow) },
        more = { SliderIcon(R.drawable.ic_margin_v_wide) },
    ) { onStyle(style.copy(verticalMarginStep = it)) }
}

@Composable
private fun PanelLabel(text: String) {
    Text(text, Modifier.width(PanelLabelWidth), fontSize = 13.sp, color = PanelLabelGray)
}

private val PanelLabelGray = Color(0xFF5A5A5A)   // 和书架说明文字同一档灰：标签让位给数值和控件

@Composable
private fun SliderIcon(resId: Int) = Image(painterResource(resId), null, Modifier.size(22.dp))

private class FontChoice(val label: String, val family: ComposeFontFamily?, val selected: Boolean, val hint: Boolean = false, val onPick: () -> Unit)

/**
 * 字体列表（用户 2026-11-03，参考微信读书）：三列方块，每块用那个字体本身写出它的名字（name 表里的家族名，见 [FontNames]）。
 * 内置三种在前，字体文件夹里的随后，末块用来授权 / 更换字体文件夹。一屏放不下就整屏分页，右边距里是和书架同款的滚动提示条。
 * 选中的那块描黑粗边，其余 #999 细边（不铺灰底：彩膜上浅灰底就是白）。字体名和 Typeface 在 IO 线程按屏读，读好之前先用文件名、默认字体。
 */
@Composable
private fun FontPanel(style: ReaderStyle, fonts: List<UserFont>, onStyle: (ReaderStyle) -> Unit, onPickFolder: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val perPage = FONT_ROWS * FONT_COLUMNS
    val count = 3 + fonts.size + 1
    val pageCount = (count + perPage - 1) / perPage
    val selectedIndex = when (style.font) {
        ReaderFont.PUBLISHER -> 0
        ReaderFont.SERIF -> 1
        ReaderFont.SANS -> 2
        ReaderFont.CUSTOM -> fonts.indexOfFirst { it.fileName == style.customFont }.takeIf { it >= 0 }?.plus(3) ?: 0
    }
    var page by remember { mutableIntStateOf(selectedIndex / perPage) }
    val current = page.coerceIn(0, pageCount - 1)
    val turn = { delta: Int -> page = (current + delta).coerceIn(0, pageCount - 1) }

    // 这一屏的用户字体：名字 + Typeface。一次读完一屏再换上，只多刷一次
    val pageFonts = fonts.drop((current * perPage - 3).coerceAtLeast(0)).take(perPage)
    val loaded by produceState<Map<String, Pair<String, ComposeFontFamily?>>>(emptyMap(), pageFonts) {
        value = withContext(Dispatchers.IO) {
            pageFonts.associate { f ->
                f.fileName to (FontNames.resolve(context, f) to FontNames.typeface(context, f)?.let { ComposeFontFamily(ComposeTypeface(it)) })
            }
        }
    }
    val choices = listOf(
        FontChoice(ReaderFont.PUBLISHER.label, ComposeFontFamily.Default, style.font == ReaderFont.PUBLISHER) { onStyle(style.copy(font = ReaderFont.PUBLISHER, customFont = null)) },
        FontChoice(ReaderFont.SERIF.label, ComposeFontFamily.Serif, style.font == ReaderFont.SERIF) { onStyle(style.copy(font = ReaderFont.SERIF, customFont = null)) },
        FontChoice(ReaderFont.SANS.label, ComposeFontFamily.SansSerif, style.font == ReaderFont.SANS) { onStyle(style.copy(font = ReaderFont.SANS, customFont = null)) },
    ) + fonts.map { f ->
        val info = loaded[f.fileName]
        FontChoice(info?.first ?: FontNames.of(f), info?.second, style.font == ReaderFont.CUSTOM && style.customFont == f.fileName) {
            onStyle(style.copy(font = ReaderFont.CUSTOM, customFont = f.fileName))
        }
    } + FontChoice(if (fonts.isEmpty()) "选择字体文件夹…" else "更换字体文件夹…", null, false, hint = true, onPick = onPickFolder)

    Row(
        Modifier.fillMaxWidth().height(PanelRowHeight).padding(horizontal = ScreenMargin - BarIconInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIcon(R.drawable.ic_back, "返回", onBack)
        Text("字体", fontSize = 15.sp)
    }
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().verticalPageSwipe(current, pageCount) { turn(it) }
                .padding(start = ScreenMargin, end = ScreenMargin, bottom = FontTileGap * 2),
            verticalArrangement = Arrangement.spacedBy(FontTileGap),
        ) {
            // 行数固定：翻到不满的一页时面板高度不跳
            val rows = if (pageCount > 1) FONT_ROWS else (count + FONT_COLUMNS - 1) / FONT_COLUMNS
            for (r in 0 until rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(FontTileGap)) {
                    for (c in 0 until FONT_COLUMNS) {
                        val choice = choices.getOrNull(current * perPage + r * FONT_COLUMNS + c)
                        Box(Modifier.weight(1f).height(FontTileHeight)) { if (choice != null) FontTile(choice) }
                    }
                }
            }
        }
        if (pageCount > 1) {
            PageScrollbar(
                current, pageCount,
                Modifier.align(Alignment.TopEnd).padding(bottom = FontTileGap * 2).width(ScreenMargin).height(FontTileHeight * FONT_ROWS + FontTileGap * (FONT_ROWS - 1)),
            ) { turn(it) }
        }
    }
}

@Composable
private fun FontTile(choice: FontChoice) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.fillMaxSize().clip(shape)
            .border(if (choice.selected) 2.dp else OnePx, if (choice.selected) Color.Black else EdgeGray, shape)
            .clickable(onClick = choice.onPick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            choice.label,
            fontFamily = choice.family,
            fontSize = if (choice.hint) 14.sp else 16.sp,
            color = if (choice.hint) PanelLabelGray else Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val FONT_COLUMNS = 3
private val FontTileHeight = 48.dp
private val FontTileGap = 8.dp

/**
 * 离散滑动条：标签 · 小端 ——●—— 大端。两端可点、一次一档；拖动时只有滑块在动（局部重绘），松手才提交，正文只重排一次。
 */
@Composable
private fun StepSlider(
    label: String,
    steps: Int,
    value: Int,
    less: @Composable () -> Unit,
    more: @Composable () -> Unit,
    onChange: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = dragging ?: value
    // 手势处理器只按 steps 建一次，里面要调「最新」的回调：同一面板里有好几条滑块，
    // 先调字号再拖粗细时，旧闭包里的 style 还是打开面板时的，会把字号冲回去
    val latestOnChange by rememberUpdatedState(onChange)
    Row(
        Modifier.fillMaxWidth().height(PanelRowHeight).padding(start = ScreenMargin, end = ScreenMargin - BarIconInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelLabel(label)
        SliderEnd(content = less) { if (value > 0) onChange(value - 1) }
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
                    latestOnChange(step)
                }
            },
        ) {
            val inset = SliderThumbRadius.toPx()
            val y = size.height / 2
            val span = size.width - inset * 2
            val thumbX = inset + span * shown / (steps - 1)
            // 已选的一段画粗黑、其余细线：一眼看出「在哪一档」，不用数刻度
            drawLine(Color.Black, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 1f)
            drawLine(Color.Black, Offset(inset, y), Offset(thumbX, y), strokeWidth = 2.dp.toPx())
            for (i in 0 until steps) {
                val x = inset + span * i / (steps - 1)
                drawLine(Color.Black, Offset(x, y - 3.dp.toPx()), Offset(x, y + 3.dp.toPx()), strokeWidth = 1f)
            }
            drawCircle(Color.Black, inset, Offset(thumbX, y))
        }
        SliderEnd(content = more) { if (value < steps - 1) onChange(value + 1) }
    }
}

@Composable
private fun SliderEnd(content: @Composable () -> Unit, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

internal class TocEntry(val link: Link, val depth: Int)

internal fun flattenToc(links: List<Link>, depth: Int = 0): List<TocEntry> =
    links.flatMap { listOf(TocEntry(it, depth)) + flattenToc(it.children, depth + 1) }
