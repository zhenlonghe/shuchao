package dev.zhenlong.reader.reader

import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zhenlong.reader.MainActivity
import dev.zhenlong.reader.data.BookKind
import dev.zhenlong.reader.data.Highlight
import dev.zhenlong.reader.reader.text.Caret
import dev.zhenlong.reader.reader.text.Mark
import dev.zhenlong.reader.reader.text.Spot
import dev.zhenlong.reader.ui.launchOrToast
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private val SwipeThreshold = 48.dp
private val HandleReach = 28.dp
private val BarGap = 16.dp

/** 左右边距滑块的 1.0 档折算成多少 dp（和之前 Readium 的观感一致）。 */
private const val MARGIN_UNIT_DP = 30

/** 一次按下最后成了什么：点按、拖动（翻页的滑动）、长按（开始选字）。 */
private enum class Press { TAP, DRAG, LONG }

/**
 * 文字书（原生排版）：整页画在 Canvas 上。操作和漫画一致：点左右两侧 = 下一页，右滑 = 上一页，
 * 点中间 = 工具条，音量键翻页；另外正文里的链接（脚注、书内目录）可点，返回键回到来处。
 *
 * 划线（用户 2026-11-02，仿 Kobo）：长按选中一个词，按住拖动往两边扩；松手弹出颜色条，点颜色划上。
 * 松手后选区两端有把手，拖把手可以再调。长按已有的划线 = 改色 / 删除。选区不跨页。点按始终还是翻页。
 */
@Composable
internal fun NativeTextReader(ready: HostState.Ready, vm: ReaderHostViewModel, onBack: () -> Unit) {
    val activity = LocalContext.current as MainActivity
    val density = LocalDensity.current
    val style by vm.textStyle.collectAsStateWithLifecycle()
    val fonts by vm.fonts.collectAsStateWithLifecycle()
    val page by ready.text.page.collectAsStateWithLifecycle()
    val highlights by vm.highlights.collectAsStateWithLifecycle()
    val pageNow by rememberUpdatedState(page)
    var chrome by remember { mutableStateOf<Chrome>(Chrome.Hidden) }
    val chromeNow by rememberUpdatedState(chrome)
    var selection by remember { mutableStateOf<Selection?>(null) }
    val selectionNow by rememberUpdatedState(selection)
    // 刚点了颜色 / 删除、库里还没回来时先按改好的样子画：不然要先闪一下没划线的页，再出现划线，墨水屏多刷一次
    var pendingEdit by remember { mutableStateOf<((List<Highlight>) -> List<Highlight>)?>(null) }
    LaunchedEffect(highlights) { pendingEdit = null }
    val shownHighlights = pendingEdit?.invoke(highlights) ?: highlights
    val shownHighlightsNow by rememberUpdatedState(shownHighlights)
    val words = remember { WordFinder() }
    val pickFontsFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setFontsFolder(uri)
    }

    // 换了页（翻页、重排）选区就作废
    LaunchedEffect(page) { selection = null }

    val exit = {
        vm.flush()
        onBack()
    }
    fun turn(forward: Boolean) {
        if (chromeNow != Chrome.Hidden) chrome = Chrome.Hidden
        selection = null
        ready.text.turn(forward)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    BackHandler {
        if (selection != null) {
            selection = null
            return@BackHandler
        }
        when (val c = chrome) {
            Chrome.Hidden -> if (!ready.text.back()) exit()
            is Chrome.Bars -> chrome = when (c.panel) { null -> Chrome.Hidden; Panel.FONT_LIST -> Chrome.Bars(Panel.TYPE); else -> Chrome.Bars() }
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
        top = topMarginDp(style.verticalMarginDp).dp,
        bottom = bottomMarginDp(style.verticalMarginDp).dp,
    )
    val marginsNow by rememberUpdatedState(margins)

    /** 屏幕坐标 → 版心坐标（版心上方还有一行页眉）。 */
    fun Density.toPage(at: Offset) = Offset(
        at.x - marginsNow.calculateLeftPadding(LayoutDirection.Ltr).toPx(),
        at.y - HeaderHeight.toPx() - marginsNow.calculateTopPadding().toPx(),
    )

    fun onTap(at: Offset, width: Int, pagePoint: Offset) {
        val link = pageNow?.let { p -> p.layout.linkAt(p.page, pagePoint.x, pagePoint.y) }
        when {
            selectionNow != null -> selection = null
            chromeNow != Chrome.Hidden -> chrome = Chrome.Hidden
            link != null -> ready.text.follow(link)
            at.x < width / 3f || at.x > width * 2 / 3f -> turn(forward = true)
            else -> chrome = Chrome.Bars()
        }
    }

    /** 长按：按在已有划线上 = 选中它（改色 / 删除）；否则选中按住的那个词，开始一段新选区。 */
    fun onLongPress(pagePoint: Offset): Boolean {
        selection = null
        if (chromeNow != Chrome.Hidden) {
            chrome = Chrome.Hidden
            return false
        }
        val p = pageNow ?: return false
        val at = p.layout.charAt(p.page, pagePoint.x, pagePoint.y) ?: return false
        val document = ready.text.documentOf(p.chapter)
        // 逆序找：重叠的划线后画的盖在上面（drawMarks 按列表顺序画），改色 / 删除要作用在看得见的那条上
        shownHighlightsNow.lastOrNull { it.document == document && at >= it.startOffset && at < it.endOffset }?.let { h ->
            selection = Selection(p.layout, p.page, h.startOffset until h.endOffset, h.startOffset, h.endOffset, existing = h, settled = true)
            return false
        }
        val range = p.layout.pageRange(p.page)
        val word = words.wordAt(p.layout.content.text, at) ?: return false
        val start = word.first.coerceAtLeast(range.first)
        val end = (word.last + 1).coerceAtMost(range.last + 1)
        selection = Selection(p.layout, p.page, start until end, start, end)
        return true
    }

    /** 拖动：长按后的拖动从 anchor 往两边扩；拖把手只动那一头。[handle]：null = 长按后的拖动，true = 起点把手，false = 终点把手。 */
    fun onSelectDrag(pagePoint: Offset, handle: Boolean?) {
        val s = selectionNow ?: return
        val text = s.layout.content.text
        val range = s.layout.pageRange(s.page)
        val at = s.layout.offsetAt(s.page, pagePoint.x, pagePoint.y, clamp = true)?.coerceIn(range.first, range.last + 1) ?: return
        val (start, end) = when (handle) {
            true -> words.unitAt(text, at).first.coerceAtMost(s.end - 1) to s.end
            false -> s.start to (words.unitAt(text, at - 1).last + 1).coerceAtLeast(s.start + 1)
            null -> when {
                at > s.anchor.last + 1 -> s.anchor.first to words.unitAt(text, at - 1).last + 1
                at < s.anchor.first -> words.unitAt(text, at).first to s.anchor.last + 1
                else -> s.anchor.first to s.anchor.last + 1
            }
        }
        selection = s.copy(start = start.coerceAtLeast(range.first), end = end.coerceAtMost(range.last + 1))
    }

    /** 按下的点是不是在选区某一头的把手上：true = 起点，false = 终点，null = 都不是。 */
    fun Density.handleAt(pagePoint: Offset): Boolean? {
        val s = selectionNow?.takeIf { it.settled && it.existing == null } ?: return null
        val reach = HandleReach.toPx()
        // 命中区内到把手的距离；不在命中区 = null。选区只有一两个字时两端的命中区会重叠，取近的那一端
        fun distance(c: Caret?): Float? {
            if (c == null || abs(pagePoint.x - c.x) > reach || pagePoint.y !in c.top - reach..c.bottom + reach) return null
            val dy = (pagePoint.y - pagePoint.y.coerceIn(c.top, c.bottom))
            return hypot(pagePoint.x - c.x, dy)
        }
        val toEnd = distance(s.layout.caretAt(s.page, s.end, end = true))
        val toStart = distance(s.layout.caretAt(s.page, s.start, end = false))
        return when {
            toEnd != null && (toStart == null || toEnd <= toStart) -> false
            toStart != null -> true
            else -> null
        }
    }

    Column(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val handle = handleAt(toPage(down.position))
                    if (handle != null) {
                        down.consume()
                        selection = selectionNow?.copy(settled = false)
                        drag(down.id) { change ->
                            onSelectDrag(toPage(change.position), handle)
                            change.consume()
                        }
                        selection = selectionNow?.copy(settled = true)
                        return@awaitEachGesture
                    }

                    var upAt = down.position
                    var dragAt = down.position   // 判定成拖动的那一下移到了哪儿：快速一甩可能这一下之后就松手，drag 不会再报位置
                    val press = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull Press.TAP
                            if (change.changedToUp()) {
                                upAt = change.position
                                return@withTimeoutOrNull Press.TAP
                            }
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                dragAt = change.position
                                return@withTimeoutOrNull Press.DRAG
                            }
                        }
                        @Suppress("UNREACHABLE_CODE") Press.TAP
                    } ?: Press.LONG

                    when (press) {
                        Press.TAP -> onTap(upAt, size.width, toPage(upAt))
                        Press.DRAG -> {
                            // 横向滑动翻页（右滑 = 上一页）。有选区时这一滑只是取消选区
                            var last = dragAt
                            drag(down.id) { change -> last = change.position }
                            val dx = last.x - down.position.x
                            val dy = last.y - down.position.y
                            when {
                                selectionNow != null -> selection = null
                                abs(dx) < abs(dy) -> {}
                                dx <= -SwipeThreshold.toPx() -> turn(forward = true)
                                dx >= SwipeThreshold.toPx() -> turn(forward = false)
                            }
                        }
                        Press.LONG -> {
                            if (onLongPress(toPage(down.position))) {
                                drag(down.id) { change ->
                                    onSelectDrag(toPage(change.position), handle = null)
                                    change.consume()
                                }
                            } else {
                                waitForUpOrCancellation()
                            }
                            selection = selectionNow?.copy(settled = true)
                        }
                    }
                }
            },
    ) {
        PageHeader(page?.title)
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(margins)
                .onSizeChanged { vm.setTextViewport(it.width, it.height, density.density, density.density * density.fontScale) },
        ) {
            val p = page
            val s = selection?.takeIf { p != null && it.layout === p.layout && it.page == p.page }
            // 两层：下层划线底色 + 选区把手，上层只画字（见 ChapterLayout.drawMarks：同一层里先画色块会让加粗的字变细）
            Canvas(Modifier.fillMaxSize()) {
                if (p == null) return@Canvas
                val document = ready.text.documentOf(p.chapter)
                val marks = shownHighlights.filter { it.document == document }.map { Mark(it.startOffset, it.endOffset, highlightColor(it.color)) } +
                    listOfNotNull(s?.takeIf { it.existing == null }?.let { Mark(it.start, it.end, SELECTION_COLOR) })
                drawIntoCanvas { p.layout.drawMarks(it.nativeCanvas, p.page, marks) }
                if (s != null && s.settled && s.existing == null) {
                    p.layout.caretAt(p.page, s.start, end = false)?.let { drawHandle(it, atStart = true) }
                    p.layout.caretAt(p.page, s.end, end = true)?.let { drawHandle(it, atStart = false) }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                p?.let { drawIntoCanvas { c -> it.layout.draw(c.nativeCanvas, it.page, style.emboldenEm) } }
            }
        }
        PageFooter(page?.let { PageInfo(it.bookPage, it.bookPages) })
    }

    selection?.takeIf { it.settled }?.let { s ->
        SelectionBar(s, ready.text.documentOf(s.layout.chapter), vm, margins) { edit ->
            pendingEdit = edit.display
            edit.commit()
            selection = null
        }
    }

    when (val c = chrome) {
        Chrome.Hidden -> {}
        is Chrome.Bars -> Bars(
            title = ready.book.title,
            author = ready.book.author,
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
            val items = remember(shownHighlights) {
                shownHighlights.mapNotNull { h -> ready.text.chapterOf(h.document)?.let { HighlightEntry(h, it, ready.text.titleOf(it)) } }
                    .sortedWith(compareBy({ it.chapter }, { it.highlight.startOffset }))
            }
            val current = remember(entries) {
                val here = page ?: return@remember -1
                currentTocIndex(entries.orEmpty(), tocPosition(here.chapter, here.offset)) { entry ->
                    val url = entry.link.url()
                    val chapter = url.removeFragment().path?.let(ready.text::chapterOf) ?: return@currentTocIndex null
                    // 只有当前章排过版、知道锚点在哪儿；别的章算章首就够分先后
                    val offset = if (chapter == here.chapter) url.fragment?.let { here.layout.content.anchors[it] } ?: 0 else 0
                    tocPosition(chapter, offset)
                }
            }
            TocScreen(
                entries = entries,
                current = current,
                onBack = { chrome = Chrome.Bars() },
                onPick = { link ->
                    chrome = Chrome.Hidden
                    val url = link.url()
                    url.removeFragment().path?.let(ready.text::chapterOf)?.let { chapter ->
                        ready.text.go(chapter, url.fragment?.let { Spot.Anchor(it) } ?: Spot.Offset(0))
                    }
                },
                highlights = items,
                onPickHighlight = { item ->
                    chrome = Chrome.Hidden
                    ready.text.go(item.chapter, Spot.Offset(item.highlight.startOffset))
                },
            )
        }
    }
}

/** 点了颜色条之后要做的事：[display] 是库里回来之前先怎么画，[commit] 是真正写库。 */
private class HighlightEdit(val display: (List<Highlight>) -> List<Highlight>, val commit: () -> Unit)

/** 选区松手后的颜色条：放在选区下方，下面放不下就放上方；水平居中。 */
@Composable
private fun SelectionBar(s: Selection, document: String, vm: ReaderHostViewModel, margins: PaddingValues, onEdit: (HighlightEdit) -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val y = with(density) {
            val originY = HeaderHeight.toPx() + margins.calculateTopPadding().toPx()
            val top = s.layout.caretAt(s.page, s.start, end = false)?.top ?: 0f
            val bottom = s.layout.caretAt(s.page, s.end, end = true)?.bottom ?: 0f
            val bar = HighlightBarHeight.toPx()
            val gap = BarGap.toPx()
            val below = originY + bottom + gap
            if (below + bar <= constraints.maxHeight - HeaderHeight.toPx()) below   // 页脚和页眉一样高
            else (originY + top - gap - bar).coerceAtLeast(0f)
        }
        val existing = s.existing
        HighlightBar(
            current = existing?.color,
            modifier = Modifier.align(Alignment.TopCenter).offset { IntOffset(0, y.roundToInt()) },
            onColor = { color ->
                onEdit(
                    if (existing != null) {
                        HighlightEdit({ list -> list.map { if (it.id == existing.id) it.copy(color = color) else it } }) {
                            vm.recolorHighlight(existing, color)
                        }
                    } else {
                        val draft = Highlight(bookId = "", document = document, startOffset = s.start, endOffset = s.end, color = color, text = "", createdAt = 0)
                        HighlightEdit({ list -> list + draft }) {
                            vm.addHighlight(s.layout.chapter, s.start, s.end, color, s.layout.content.text)
                        }
                    },
                )
            },
            onDelete = existing?.let { h ->
                {
                    onEdit(HighlightEdit({ list -> list.filter { it.id != h.id } }) { vm.deleteHighlight(h) })
                }
            },
        )
    }
}
