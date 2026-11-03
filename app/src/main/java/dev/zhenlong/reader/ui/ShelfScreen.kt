package dev.zhenlong.reader.ui

import android.content.Context
import android.util.DisplayMetrics
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size as CoilSize
import dev.zhenlong.reader.R
import dev.zhenlong.reader.data.Book
import dev.zhenlong.reader.data.ShelfItem
import dev.zhenlong.reader.data.ReadState
import dev.zhenlong.reader.data.arrangeByRecent
import dev.zhenlong.reader.data.shelfProgress
import dev.zhenlong.reader.scan.ScanState
import java.io.File
import kotlin.math.roundToInt

private const val COLUMNS = 4
private const val MAX_ROWS = 3
private val BarHeight = 48.dp
// 全屏共用的左右边距：封面外缘、顶栏图标的字形边缘都落在这条线上
internal val ScreenMargin = 24.dp
private val ColumnGap = ScreenMargin   // 列间距 = 屏幕边距：封面之间、封面到屏幕边是同一个节奏
private val MinCoverWidth = 80.dp     // 键盘弹出等矮窗口下，宁可少放一行也不把封面压到看不清
private val CoverToCaption = 8.dp
private val MinRowGap = 8.dp          // 行间（以及网格上下）留白的下限；通常远大于此，见 PagedGrid
private val TitleLine = 19.sp         // 行高用 sp：跟随系统字号，说明文字不会被固定 dp 裁掉
private val ProgressBarHeight = 2.dp
private val CoverToProgress = 5.dp    // 进度条贴着封面下沿，像书脚的一道线；不写百分数（进书就能看到）
private val BadgeSize = 26.dp         // 封面宽约 112dp，角标占不到 1/4；再小就像个污点
private val BadgeInset = 8.dp
private val CaptionGray = Color(0xFF5A5A5A)   // 说明文字压淡一档：封面是主角，黑字会和封面抢眼。16 级灰下仍清楚
// 封面描边与进度条底轨：#999 正好是 16 级灰的第 9 级，比黑框轻、比 #CCC 看得见（对照微信读书：封面是浅灰细边，不是黑框）
internal val EdgeGray = Color(0xFF999999)

/**
 * 顶层书架一格封面的像素尺寸（竖屏、三行都放得下时宽度顶满的情形）。
 * 扫描器按这个尺寸生成缩略图，书架上 1:1 贴图，不再二次缩放。和 [PagedGrid] 的算法保持一致。
 */
fun shelfCoverSizePx(metrics: DisplayMetrics): IntSize {
    val widthDp = (metrics.widthPixels / metrics.density - ScreenMargin.value * 2 - ColumnGap.value * (COLUMNS - 1)) / COLUMNS
    return IntSize((widthDp * metrics.density).roundToInt(), (widthDp * 1.5f * metrics.density).roundToInt())
}

@Composable
fun ShelfScreen(
    onOpenBook: (Book) -> Unit,
    onOpenSettings: () -> Unit,
    vm: ShelfViewModel = viewModel(),
) {
    val ui = vm.ui.collectAsStateWithLifecycle().value
    val freshReads = vm.reads.collectAsStateWithLifecycle().value
    // logcat「Fully drawn」= 冷启动到书架内容出现的耗时（SPEC §9）
    ReportDrawnWhen { ui != null }
    // 书库还没备好：连白底都不画（窗口是透明的，见 themes.xml），不给墨水屏一次空白刷新
    if (ui == null) return
    val reads: Map<String, ReadState> = freshReads ?: ui.reads
    // 顶层书架（含刚点开搜索、还没输字时）按最近阅读排；套内按卷序，搜索结果按书名
    val topLevel = ui.seriesName == null && ui.query.isNullOrBlank()
    val items = if (topLevel) remember(ui.items, reads) { arrangeByRecent(ui.items, reads) } else ui.items
    val scan = ui.scan
    val message: Pair<String, (() -> Unit)?>? = when {
        // 已经有书时扫描（下拉刷新）不换掉书架，只在顶栏写一句：整屏换成提示再换回来，墨水屏要多刷两次
        scan is ScanState.Running && items.isEmpty() -> (if (scan.total > 0) "正在扫描 ${scan.done}/${scan.total}" else "正在扫描") to null
        !ui.hasLibraryRoot -> "在设置里选择书库文件夹" to onOpenSettings
        scan is ScanState.Failed && items.isEmpty() -> scan.message to onOpenSettings
        items.isEmpty() -> (if (ui.query.isNullOrBlank()) "书库里没有 EPUB" else "没有匹配的书") to null
        else -> null
    }
    // 顶栏常驻（用户 2026-11-03）：顶层是搜索框（最右是设置），套内是「← 套名」。顶层书架在第一屏再下拉 = 刷新书架（重新扫描），扫描时搜索框里写一句
    val status = when {
        message != null -> null
        scan is ScanState.Running -> "正在刷新书架…"   // 不写进度数字：每变一次就局刷一次顶栏
        scan is ScanState.Failed -> scan.message
        else -> null
    }

    BackHandler(enabled = ui.query != null || ui.seriesName != null) {
        if (ui.query != null) vm.closeSearch() else vm.closeSeries()
    }

    Column(Modifier.fillMaxSize().background(Color.White).stableSystemBarsPadding().imePadding()) {
        when {
            ui.query != null -> SearchBar(initial = ui.query, onQuery = vm::setQuery, onClose = vm::closeSearch)
            ui.seriesName != null -> SeriesBar(ui.seriesName, onBack = vm::closeSeries)
            else -> SearchPill(hint = status.orEmpty(), onSearch = vm::startSearch, onSettings = onOpenSettings)
        }
        Box(Modifier.fillMaxSize()) {
            if (message != null) {
                CenterText(message.first, message.second)
            } else {
                PagedGrid(
                    items = items,
                    // 顶层书架封面下是进度条：书名封面上已经有了。套内（区别只在「卷01」）和搜索（要看哪个字匹配上）仍写书名
                    progressOf = if (ui.seriesName == null && ui.query == null) { item -> shelfProgress(item, reads) } else null,
                    page = ui.page,
                    onPage = vm::setPage,
                    onPullDownAtTop = { if (topLevel && ui.query == null) vm.rescan() },
                    onClick = { item ->
                        when (item) {
                            is ShelfItem.Single -> onOpenBook(item.book)
                            is ShelfItem.Set -> vm.openSeries(item.series.id)
                        }
                    },
                )
            }
        }
    }
}

/**
 * 顶层书架的搜索框（用户 2026-11-03，参考微信读书）：圆角胶囊，左 🔍、最右设置齿轮，不写提示语、不画竖线（用户 2026-11-03：都知道怎么搜）。点框 = 进搜索。
 * 扫描时框里写一句「正在刷新书架…」：不另占位置，也不写进度数字（每变一次就局刷一次）。
 */
@Composable
private fun SearchPill(hint: String, onSearch: () -> Unit, onSettings: () -> Unit) {
    PillRow {
        Row(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onSearch), verticalAlignment = Alignment.CenterVertically) {
            PillSearchIcon()
            Text(hint, color = CaptionGray, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PillIcon(R.drawable.ic_settings, "设置", onSettings)
    }
}

/** 进一套之后：「← 套名」，和搜索框一样高，进出一套时网格不上下跳。 */
@Composable
private fun SeriesBar(name: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(ShelfBarHeight).padding(horizontal = ScreenMargin - BarIconInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIcon(R.drawable.ic_back, "返回", onBack)
        Text(name, Modifier.weight(1f), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 搜索中：同一个胶囊，🔍 换成返回箭头（点了退出搜索，同返回键），中间是输入框，最右的设置换成清空（有字时才有）。
 * 框的位置、大小、输入框的起点都不变，只换图标。
 */
@Composable
private fun SearchBar(initial: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    // 文本留在本地 state：经 ViewModel 异步绕一圈会打断中文输入法的组词。
    // initial：从阅读器返回时本地 state 已丢，用 ViewModel 里的查询词接上
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    // 只在刚点开搜索时弹键盘；带着查询词从阅读器回来时不弹
    LaunchedEffect(Unit) { if (initial.isEmpty()) focus.requestFocus() }

    PillRow {
        // 和 🔍 占同一块位置（左 14 + 18 + 右 8），点区是整块高度
        Box(Modifier.fillMaxHeight().clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_back), "返回", Modifier.padding(start = 14.dp, end = 8.dp).size(18.dp))
        }
        val style = TextStyle(color = Color.Black, fontSize = 15.sp)
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                onQuery(it)
            },
            modifier = Modifier.weight(1f).focusRequester(focus),
            singleLine = true,
            textStyle = style,
            // 光标闪烁是 Compose 内置动画、关不掉，墨水屏上会持续局刷：直接不画光标（SPEC §2.1）
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    field()
                }
            },
        )
        // 清空按钮的位置始终占着（和设置齿轮同宽），输入第一个字时输入框不会变窄
        Box(Modifier.fillMaxHeight().width(PillIconWidth)) {
            if (text.isNotEmpty()) {
                PillIcon(R.drawable.ic_close, "清空") {
                    text = ""
                    onQuery("")
                }
            }
        }
    }
}

private val ShelfBarHeight = 56.dp
private val PillHeight = 40.dp
private val PillIconWidth = 48.dp
private val BarBelowPill = (ShelfBarHeight - PillHeight) / 2
// 胶囊只描一道 #999 细边、不铺灰底：彩膜屏上浅灰底几乎就是白（见 EdgeGray），细边才看得出「这里是个框」
private val PillShape = RoundedCornerShape(50)

/** 胶囊外框：左右缘对齐封面外缘（ScreenMargin）。 */
@Composable
private fun PillRow(content: @Composable RowScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().height(ShelfBarHeight).padding(horizontal = ScreenMargin), contentAlignment = Alignment.Center) {
        Row(
            Modifier.fillMaxWidth().height(PillHeight).clip(PillShape).border(OnePx, EdgeGray, PillShape),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun PillSearchIcon() {
    Image(
        painterResource(R.drawable.ic_search),
        null,
        Modifier.padding(start = 14.dp, end = 8.dp).size(18.dp),
        colorFilter = ColorFilter.tint(CaptionGray),
    )
}


@Composable
private fun PillIcon(resId: Int, description: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxHeight().width(PillIconWidth).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Image(painterResource(resId), description, Modifier.size(20.dp))
    }
}

/** 48dp 触控区里居中一个 24dp 字形，字形边缘距触控区边缘 [BarIconInset]。 */
internal val BarIconInset = 12.dp

@Composable
internal fun BarIcon(resId: Int, description: String, onClick: () -> Unit) {
    Box(Modifier.size(BarHeight).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Image(painterResource(resId), description, Modifier.size(24.dp))
    }
}

@Composable
private fun CenterText(text: String, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.fillMaxSize().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 16.sp)
    }
}

/**
 * 整屏分页网格（4 列 × 3 行）：不滚动，上下滑翻屏，瞬切（SPEC §2.6）。右边距里一道滚动提示条（[PageScrollbar]）说明在第几屏，点它的上 / 下半也能翻。
 * [progressOf] 非 null 时封面下画进度条，否则写书名。
 */
@Composable
private fun PagedGrid(
    items: List<ShelfItem>,
    progressOf: ((ShelfItem) -> Float?)?,
    page: Int,
    onPage: (Int) -> Unit,
    onPullDownAtTop: () -> Unit,
    onClick: (ShelfItem) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 封面下的说明：顶层只有一道进度条，套内 / 搜索是一行书名。封面尺寸由宽度定（三种视图一样大），说明省下的高度归入留白
        val captionHeight = if (progressOf != null) CoverToProgress + ProgressBarHeight
            else with(LocalDensity.current) { CoverToCaption + TitleLine.toDp() }
        val widthBound = (maxWidth - ScreenMargin * 2 - ColumnGap * (COLUMNS - 1)) / COLUMNS
        val heightBudget = maxHeight
        fun coverWidthFor(rows: Int) =
            minOf(widthBound, (heightBudget - MinRowGap * (rows + 1) - captionHeight * rows) / (1.5f * rows))
        val rows = (MAX_ROWS downTo 1).firstOrNull { coverWidthFor(it) >= MinCoverWidth } ?: 1
        val coverWidth = coverWidthFor(rows)
        val coverHeight = coverWidth * 1.5f

        val perPage = rows * COLUMNS
        val pageCount = ((items.size + perPage - 1) / perPage).coerceAtLeast(1)
        val current = page.coerceIn(0, pageCount - 1)
        // 封面通常是宽度顶满、高度有余：余量均分到网格上方、行间、下方这 rows + 1 段。
        // 顶栏里搜索框下面本来就有一截空（[BarBelowPill]），算进上方那段，看上去「框底 → 封面顶」和行间一样宽
        val content = (coverHeight + captionHeight) * rows
        val gap = (maxHeight + BarBelowPill - content) / (rows + 1)
        val topGap = (gap - BarBelowPill).coerceAtLeast(MinRowGap)
        val go = { delta: Int ->
            val target = current + delta
            when {
                target in 0 until pageCount -> onPage(target)
                target < 0 -> onPullDownAtTop()   // 已经在第一屏还往下拉
            }
        }

        // 相邻两屏的封面提前进内存缓存，翻屏时首帧就是完整画面，不会先空框再补图
        val context = LocalContext.current
        LaunchedEffect(items, current, perPage) {
            val next = (current + 1) * perPage until (current + 2) * perPage
            val previous = (current - 1) * perPage until current * perPage
            (next + previous).mapNotNull { items.getOrNull(it)?.coverPath }
                .forEach { context.imageLoader.enqueue(coverRequest(context, it)) }
        }

        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalPageSwipe(current, pageCount) { go(it) }   // 留白处也能滑
                    .padding(start = ScreenMargin, end = ScreenMargin, top = topGap),
            ) {
                val start = current * perPage
                for (r in 0 until rows) {
                    if (r > 0) Spacer(Modifier.height(gap))
                    // 空位也占格：不满一行时封面仍然贴左按列对齐
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (c in 0 until COLUMNS) {
                            val item = items.getOrNull(start + r * COLUMNS + c)
                            if (item != null) Cell(item, progressOf, coverWidth, coverHeight) { onClick(item) }
                            else Spacer(Modifier.width(coverWidth))
                        }
                    }
                }
            }
            if (pageCount > 1) {
                PageScrollbar(
                    current, pageCount,
                    Modifier.align(Alignment.TopEnd).padding(top = topGap, bottom = gap).width(ScreenMargin).fillMaxHeight(),
                ) { go(it) }
            }
        }
    }
}

/**
 * 按下不做视觉反馈：试过 Kindle 式的「按下封面反相」，录屏看到阅读器的白页在点击后 60ms 就盖上来了，
 * 反相根本来不及显示，只多一帧。白页本身就是「点中了」的反馈。
 */
@Composable
private fun Cell(item: ShelfItem, progressOf: ((ShelfItem) -> Float?)?, coverWidth: Dp, coverHeight: Dp, onClick: () -> Unit) {
    Column(Modifier.width(coverWidth).clickable(onClick = onClick)) {
        Box(Modifier.size(coverWidth, coverHeight)) {
            val path = item.coverPath
            if (path != null) {
                Cover(path, Modifier.fillMaxSize())
            } else {
                // 占位封面：白底黑字书名，像一张只有字的素封面
                Box(Modifier.fillMaxSize().border(OnePx, EdgeGray)) {
                    Text(
                        item.title,
                        Modifier.padding(10.dp),
                        fontFamily = FontFamily.Serif,   // 设备有 Noto Serif CJK：像一张只印了书名的素封面
                        fontSize = 13.sp,
                        lineHeight = TitleLine,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (item is ShelfItem.Set) CountBadge(item.count, Modifier.align(Alignment.TopEnd).padding(BadgeInset))
        }
        if (progressOf != null) {
            ProgressCaption(progressOf(item))
        } else {
            // 中间省略：同一套的书名前缀相同、区别在末尾（「… - 卷01」），尾部省略会让一整屏看起来一模一样
            Text(
                item.title,
                Modifier.padding(top = CoverToCaption),
                color = CaptionGray,
                fontSize = 13.sp,
                lineHeight = TitleLine,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}

/**
 * 封面下的极简进度：贴着封面下沿、和封面同宽的一道线，不写数字（用户 2026-11-03）。
 * 读过的部分是 2dp 黑色实线，没读的部分是一道浅灰（[EdgeGray]）细虚线（用户 2026-11-03：不要「方框」似的整条灰底轨）。
 * 没读的部分要画出来：只有黑段时 1% 只是封面下一个黑点，看着像污渍（用户 2026-10-29）。读过一点的黑段至少 [MinProgressFill]。
 * [progress] 为 null（没读过）时什么都不画，但仍占位，封面上下对齐不变。
 */
@Composable
private fun ProgressCaption(progress: Float?) {
    Canvas(Modifier.fillMaxWidth().padding(top = CoverToProgress).height(ProgressBarHeight)) {
        if (progress == null) return@Canvas
        val read = size.width * (if (progress > 0f) progress.coerceIn(MinProgressFill, 1f) else 0f)
        val y = size.height / 2
        if (read > 0f) drawLine(Color.Black, Offset(0f, y), Offset(read, y), strokeWidth = size.height)
        if (read < size.width) {
            val dash = ProgressDash.toPx()
            // 虚线从右端往回排：右端总是落在整段上，各格的线尾对齐；左端紧挨黑段处空一个间隔
            drawLine(
                EdgeGray, Offset(size.width, y), Offset(read + dash, y),
                strokeWidth = ProgressDashWidth.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
            )
        }
    }
}

private val ProgressDash = 3.dp
private val ProgressDashWidth = 1.5.dp
private const val MinProgressFill = 0.03f

/** 成套条目的册数：封面右上角一枚白底黑边的圆形角标，在深浅封面上都看得清；三位数会拉成胶囊。 */
@Composable
private fun CountBadge(count: Int, modifier: Modifier) {
    Box(
        modifier
            .defaultMinSize(BadgeSize, BadgeSize)
            .background(Color.White, CircleShape)
            .border(OnePx, Color.Black, CircleShape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$count", fontSize = 12.sp, lineHeight = 12.sp, maxLines = 1)
    }
}

/**
 * 缩略图在扫描时已经裁成 2:3、缩到这一格的像素（[shelfCoverSizePx]）并按墨水屏调过色，这里 1:1 贴上；
 * Crop 只在矮窗口（键盘弹出）封面缩小时才真正起作用。
 * 1px 浅灰描边压在槽位最外一圈：浅色封面靠它和白底分开，又不像黑框那样把封面「框死」（微信读书也是浅灰细边）。
 * 加载完成前什么都不画，不闪空框。
 */
@Composable
private fun Cover(path: String, modifier: Modifier) {
    val painter = rememberAsyncImagePainter(coverRequest(LocalContext.current, path))
    Image(
        painter = painter,
        contentDescription = null,
        modifier = modifier.drawWithContent {
            drawContent()
            if (painter.intrinsicSize.isSpecified) {
                val stroke = 1f
                drawRect(
                    color = EdgeGray,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke),
                )
            }
        },
        contentScale = ContentScale.Crop,
    )
}

/** 缩略图本身就是展示像素，按原尺寸解码（不缩放），缓存 key 才能和预取的请求对上。 */
internal fun coverRequest(context: Context, path: String): ImageRequest =
    ImageRequest.Builder(context).data(File(path)).size(CoilSize.ORIGINAL).build()
