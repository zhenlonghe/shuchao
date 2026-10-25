package dev.zhenlong.reader.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import dev.zhenlong.reader.data.progressLabel
import dev.zhenlong.reader.scan.ScanState
import java.io.File

private const val COLUMNS = 4
private const val MAX_ROWS = 3
private val BarHeight = 48.dp
// 全屏共用的左右边距：封面外缘、顶栏图标的字形边缘都落在这条线上
internal val ScreenMargin = 24.dp
private val ColumnGap = ScreenMargin   // 列间距 = 屏幕边距：封面之间、封面到屏幕边是同一个节奏
private val MinCoverWidth = 80.dp     // 键盘弹出等矮窗口下，宁可少放一行也不把封面压到看不清
private val CoverToCaption = 8.dp
private val MinRowGap = 12.dp         // 行间（以及网格上下）留白的下限；通常远大于此，见 PagedGrid
private val TitleLine = 19.sp         // 行高用 sp：跟随系统字号，说明文字不会被固定 dp 裁掉
private val SectionTitleLine = 20.sp  // 分区标题「最近阅读 / 我的书架」
private val SectionAboveTitle = 16.dp  // 标题上方在均分留白之外再多空一段：标题要离上一区远、离自己这一区近
private val SectionTitleToRule = 6.dp
private val SectionRuleToCovers = 12.dp
private val BadgeSize = 26.dp         // 封面宽约 112dp，角标占不到 1/4；再小就像个污点
private val BadgeInset = 8.dp
private val CaptionGray = Color(0xFF5A5A5A)   // 说明文字压淡一档：封面是主角，黑字会和封面抢眼。16 级灰下仍清楚

@Composable
fun ShelfScreen(
    onOpenBook: (Book) -> Unit,
    onOpenSettings: () -> Unit,
    vm: ShelfViewModel = viewModel(),
) {
    val ui = vm.ui.collectAsStateWithLifecycle().value
    val freshRecent = vm.recent.collectAsStateWithLifecycle().value
    // logcat「Fully drawn」= 冷启动到书架内容出现的耗时（SPEC §9）
    ReportDrawnWhen { ui != null }
    // 书库还没备好：连白底都不画（窗口是透明的，见 themes.xml），不给墨水屏一次空白刷新
    if (ui == null) return
    val recent = freshRecent ?: ui.recent
    val scan = ui.scan
    val message: Pair<String, (() -> Unit)?>? = when {
        scan is ScanState.Running -> (if (scan.total > 0) "正在扫描 ${scan.done}/${scan.total}" else "正在扫描") to null
        !ui.hasLibraryRoot -> "在设置里选择书库文件夹" to onOpenSettings
        scan is ScanState.Failed && ui.items.isEmpty() -> scan.message to onOpenSettings
        ui.items.isEmpty() -> (if (ui.query.isNullOrBlank()) "书库里没有 EPUB" else "没有匹配的书") to null
        else -> null
    }
    // 搜索和设置不常用：书架在时顶栏收起，整屏都给书；在第一屏再下拉才盖在封面上方出现。
    // 没有书架可看（未选书库 / 扫描中 / 空）时顶栏固定显示，否则进不了设置
    val barHidden = message == null && ui.query == null
    var barRevealed by remember(ui.seriesName, ui.page) { mutableStateOf(false) }
    val topBar = @Composable {
        TopBar(seriesName = ui.seriesName, onBack = vm::closeSeries, onSearch = vm::startSearch, onSettings = onOpenSettings)
    }

    BackHandler(enabled = barRevealed || ui.query != null || ui.seriesName != null) {
        when {
            barRevealed -> barRevealed = false
            ui.query != null -> vm.closeSearch()
            else -> vm.closeSeries()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White).stableSystemBarsPadding().imePadding()) {
        when {
            ui.query != null -> SearchBar(initial = ui.query, onQuery = vm::setQuery, onClose = vm::closeSearch)
            !barHidden -> topBar()
        }
        Box(Modifier.fillMaxSize()) {
            if (message != null) {
                CenterText(message.first, message.second)
            } else {
                PagedGrid(
                    items = ui.items,
                    // 顶层书架首屏的第一行是最近读过的几本，第二行起才是书架本身
                    pinned = if (ui.seriesName == null && ui.query == null) recent.map { ShelfItem.Single(it) } else emptyList(),
                    page = ui.page,
                    footerLabel = ui.seriesName.takeIf { barHidden },   // 没有顶栏时，靠页脚知道自己在哪一套里
                    onPage = vm::setPage,
                    onPullDownAtTop = { if (barHidden) barRevealed = true },
                    onClick = { item ->
                        when (item) {
                            is ShelfItem.Single -> onOpenBook(item.book)
                            is ShelfItem.Set -> vm.openSeries(item.series.id)
                        }
                    },
                )
            }
            if (barHidden && barRevealed) {
                // 顶栏是 overlay：出现 / 消失只重绘顶上这一条，网格不动（SPEC §2.5）。点别处或上滑收起
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { barRevealed = false } }
                        .verticalPageSwipe(Unit) { if (it > 0) barRevealed = false },
                )
                Column(Modifier.background(Color.White).pointerInput(Unit) { detectTapGestures { } }) {
                    topBar()
                    Box(Modifier.fillMaxWidth().height(OnePx).background(Color.Black))
                }
            }
        }
    }
}

/** 顶层只有右侧两个图标；进入一套后左侧出现「← 套名」。 */
@Composable
private fun TopBar(seriesName: String?, onBack: () -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(BarHeight).padding(horizontal = ScreenMargin - BarIconInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (seriesName != null) {
            BarIcon(R.drawable.ic_back, "返回", onBack)
            Text(seriesName, Modifier.weight(1f), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Spacer(Modifier.weight(1f))
        }
        BarIcon(R.drawable.ic_search, "搜索", onSearch)
        BarIcon(R.drawable.ic_settings, "设置", onSettings)
    }
}

@Composable
private fun SearchBar(initial: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    // 文本留在本地 state：经 ViewModel 异步绕一圈会打断中文输入法的组词。
    // initial：从阅读器返回时本地 state 已丢，用 ViewModel 里的查询词接上
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    // 只在刚点开搜索时弹键盘；带着查询词从阅读器回来时不弹
    LaunchedEffect(Unit) { if (initial.isEmpty()) focus.requestFocus() }

    Row(
        Modifier.fillMaxWidth().height(BarHeight).padding(horizontal = ScreenMargin - BarIconInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIcon(R.drawable.ic_back, "返回", onClose)
        val style = TextStyle(color = Color.Black, fontSize = 16.sp)
        val hairline = with(LocalDensity.current) { 1.toDp().toPx() }
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
                // 没有光标，就靠一条底线说明「这里可以输入」
                Box(
                    Modifier.fillMaxWidth().height(32.dp).drawBehind {
                        drawLine(Color.Black, Offset(0f, size.height), Offset(size.width, size.height), hairline)
                    },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) Text("书名 / 作者 / 文件名", style = style)
                    field()
                }
            },
        )
        // 清空按钮的位置始终占着，输入第一个字时底线不会跳
        Box(Modifier.size(BarHeight)) {
            if (text.isNotEmpty()) {
                BarIcon(R.drawable.ic_close, "清空") {
                    text = ""
                    onQuery("")
                }
            }
        }
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
 * 整屏分页网格（4 列 × 3 行）：不滚动，上下滑或点页脚左/右半翻屏，瞬切（SPEC §2.6）。
 * [pinned] 非空时占首屏第一行（最多一行），[items] 从首屏第二行接着排。
 */
@Composable
private fun PagedGrid(
    items: List<ShelfItem>,
    pinned: List<ShelfItem>,
    page: Int,
    footerLabel: String?,
    onPage: (Int) -> Unit,
    onPullDownAtTop: () -> Unit,
    onClick: (ShelfItem) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val captionHeight = with(density) { CoverToCaption + TitleLine.toDp() }
        val sectionHeight =
            SectionAboveTitle + with(density) { SectionTitleLine.toDp() } + SectionTitleToRule + OnePx + SectionRuleToCovers
        // 有最近阅读时，首屏分两区，各带一个标题。封面尺寸按「首屏两个标题」算，所有屏一样大，翻屏时封面不跳
        val sections = if (pinned.isNotEmpty()) 2 else 0
        val widthBound = (maxWidth - ScreenMargin * 2 - ColumnGap * (COLUMNS - 1)) / COLUMNS
        // 行数、封面尺寸按「页脚在」算（页脚要不要，得先知道一屏放几本）
        val heightBudget = maxHeight - PagerFooterHeight
        fun coverWidthFor(rows: Int) = minOf(
            widthBound,
            (heightBudget - sectionHeight * sections - MinRowGap * (rows + 1) - captionHeight * rows) / (1.5f * rows),
        )
        val rows = (MAX_ROWS downTo 1).firstOrNull { coverWidthFor(it) >= MinCoverWidth } ?: 1
        val coverWidth = coverWidthFor(rows)
        val coverHeight = coverWidth * 1.5f

        val perPage = rows * COLUMNS
        // 只有一行（矮窗口）时不置顶：否则首屏一本书架上的书都看不到
        val pinnedRow = pinned.take(COLUMNS).takeIf { rows > 1 }.orEmpty()
        val pages = GridPages(items.size, perPage, firstPage = if (pinnedRow.isEmpty()) perPage else perPage - COLUMNS)
        val pageCount = pages.count
        val current = page.coerceIn(0, pageCount - 1)
        // 页脚只在有东西可显示（页码 / 套名）时才占位；只有一屏时那 44dp 空着，书架会显得偏上
        val footerShown = pageCount > 1 || footerLabel != null
        val gridHeight = if (footerShown) heightBudget else maxHeight
        // 首屏有最近一行时：「最近阅读」标题 + 一行最近 + 「我的书架」标题 + 其余几行书架
        val pinnedRows = if (current == 0 && pinnedRow.isNotEmpty()) 1 else 0
        val titles = pinnedRows * 2
        // 封面通常是宽度顶满、高度有余：余量均分到网格上方、行间、下方这 rows + 1 段，上下留白才一样宽。
        // 标题贴着自己那一区，不参与均分
        val gap = (gridHeight - (coverHeight + captionHeight) * rows - sectionHeight * titles) / (rows + 1)
        val go = { delta: Int ->
            val target = current + delta
            when {
                target in 0 until pageCount -> onPage(target)
                target < 0 -> onPullDownAtTop()   // 已经在第一屏还往下拉
            }
        }

        // 相邻两屏的封面提前进内存缓存，翻屏时首帧就是完整画面，不会先空框再补图
        val context = LocalContext.current
        LaunchedEffect(items, current, pages) {
            (pages.range(current + 1) + pages.range(current - 1)).mapNotNull { items.getOrNull(it)?.coverPath }
                .forEach { context.imageLoader.enqueue(coverRequest(context, it)) }
        }

        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalPageSwipe(current, pageCount) { go(it) }   // 留白处也能滑
                    .padding(start = ScreenMargin, end = ScreenMargin, top = gap),
            ) {
                val start = pages.range(current).first
                for (r in 0 until rows) {
                    if (r > 0) Spacer(Modifier.height(gap))
                    if (pinnedRows > 0 && r == 0) SectionTitle("最近阅读")
                    if (pinnedRows > 0 && r == pinnedRows) SectionTitle("我的书架")
                    // 空位也占格：不满一行时封面仍然贴左按列对齐
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (c in 0 until COLUMNS) {
                            val item = if (r < pinnedRows) pinnedRow.getOrNull(c)
                                else items.getOrNull(start + (r - pinnedRows) * COLUMNS + c)
                            if (item != null) {
                                // 最近一行写读到几成而不是书名（没存过进度的旧书退回书名）
                                val caption = if (r < pinnedRows) (item as? ShelfItem.Single)?.book?.progress?.let(::progressLabel) ?: item.title else item.title
                                Cell(item, caption, coverWidth, coverHeight) { onClick(item) }
                            }
                            else Spacer(Modifier.width(coverWidth))
                        }
                    }
                }
            }
            if (footerShown) PagerFooter(current, pageCount, footerLabel) { go(it) }
        }
    }
}

/** 分区标题：左上一行字，下面一条贯穿版心的细线。 */
@Composable
private fun SectionTitle(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = SectionAboveTitle, bottom = SectionRuleToCovers)) {
        Text(text, fontSize = 14.sp, lineHeight = SectionTitleLine, maxLines = 1)
        Spacer(Modifier.height(SectionTitleToRule))
        Box(Modifier.fillMaxWidth().height(OnePx).background(Color.Black))
    }
}

@Composable
private fun Cell(item: ShelfItem, caption: String, coverWidth: Dp, coverHeight: Dp, onClick: () -> Unit) {
    Column(Modifier.width(coverWidth).clickable(onClick = onClick)) {
        Box(Modifier.size(coverWidth, coverHeight)) {
            val path = item.coverPath
            if (path != null) {
                Cover(path, Modifier.fillMaxSize())
            } else {
                // 占位封面：白底黑字书名，像一张只有字的素封面
                Box(Modifier.fillMaxSize().border(OnePx, Color.Black)) {
                    Text(
                        item.title,
                        Modifier.padding(10.dp),
                        fontSize = 13.sp,
                        lineHeight = TitleLine,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (item is ShelfItem.Set) CountBadge(item.count, Modifier.align(Alignment.TopEnd).padding(BadgeInset))
        }
        // 中间省略：同一套的书名前缀相同、区别在末尾（「… - 卷01」），尾部省略会让一整屏看起来一模一样
        Text(
            caption,
            Modifier.padding(top = CoverToCaption),
            color = CaptionGray,
            fontSize = 13.sp,
            lineHeight = TitleLine,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

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
 * 所有封面统一成 2:3：居中裁切铺满槽位（漫画略高、中文书略宽，各裁掉一点边），一屏封面的边缘和间距才整齐。
 * 1px 描边压在槽位最外一圈：深色封面上几乎隐形，浅色封面靠它和白底分开。加载完成前什么都不画，不闪空框。
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
                    color = Color.Black,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke),
                )
            }
        },
        contentScale = ContentScale.Crop,
    )
}

/** 缩略图本身已是展示尺寸，按原尺寸解码，缓存 key 才能和预取的请求对上。 */
internal fun coverRequest(context: Context, path: String): ImageRequest =
    ImageRequest.Builder(context).data(File(path)).size(CoilSize.ORIGINAL).build()
