package dev.zhenlong.reader.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zhenlong.reader.R
import dev.zhenlong.reader.data.Highlight
import dev.zhenlong.reader.ui.BarIcon
import dev.zhenlong.reader.ui.BarIconInset
import dev.zhenlong.reader.ui.PageScrollbar
import dev.zhenlong.reader.ui.ScreenMargin
import dev.zhenlong.reader.ui.verticalPageSwipe
import org.readium.r2.shared.publication.Link

// 行高是下限：一屏放得下几行定了之后，余下的高度均分给每一行，列表正好铺到页脚，不在底下空一截
// 行高 ≈ 2.5 倍字号：像书里的目录页那样一屏读得下一大段；整行可点，竖向 42dp 够手指
private val RowHeight = 42.dp
private val HighlightRowHeight = 84.dp
private val ListTopGap = 8.dp   // 顶栏和第一行之间多一点，列表自成一组
private val ListBottomGap = 8.dp
private val Indent = 20.dp
private val SecondaryGray = Color(0xFF5A5A5A)

/** 划线列表里的一条：[chapter] 是阅读顺序里的章节序号（排序、跳转用）。 */
internal class HighlightEntry(val highlight: Highlight, val chapter: Int, val chapterTitle: String?)

/**
 * 目录：纯文本、缩进表示层级、整屏分页。[highlights] 不为 null 时（原生文字模式）顶上多一个「划线」页签，
 * 按书中顺序列出摘录，点了跳过去。[current] 是当前读到的那一项（[currentTocIndex]）：打开时翻到它所在的那一屏，字加粗。
 */
@Composable
internal fun TocScreen(
    entries: List<TocEntry>?,
    onBack: () -> Unit,
    onPick: (Link) -> Unit,
    current: Int = -1,
    highlights: List<HighlightEntry>? = null,
    onPickHighlight: (HighlightEntry) -> Unit = {},
) {
    var showHighlights by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = ScreenMargin - BarIconInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarIcon(R.drawable.ic_back, "返回", onBack)
            if (highlights == null) {
                Text("目录", fontSize = 18.sp)
            } else {
                // 两个页签，当前的加下划线（和工具条的模式切换一个说法）
                Tab("目录", !showHighlights) { showHighlights = false }
                Tab("划线", showHighlights) { showHighlights = true }
            }
        }
        if (highlights != null && showHighlights) {
            HighlightList(highlights, onPickHighlight)
            return@Column
        }
        if (entries == null) return@Column   // 还在解析
        if (entries.isEmpty()) {
            Empty("这本书没有目录")
            return@Column
        }
        PagedList(entries.size, RowHeight, initialIndex = current) { i, height ->
            val entry = entries[i]
            Box(
                Modifier.fillMaxWidth().height(height).clickable { onPick(entry.link) }
                    .padding(start = ScreenMargin + Indent * entry.depth, end = ScreenMargin),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    entry.link.title.orEmpty(),
                    fontSize = 17.sp,
                    fontWeight = if (i == current) FontWeight.Bold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Tab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxHeight().clickable(onClick = onClick).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 18.sp, textDecoration = if (selected) TextDecoration.Underline else null)
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 16.sp, textAlign = TextAlign.Center, lineHeight = 26.sp)
    }
}

/** 划线：左边一道划线的颜色，右边两行摘录、下面一行灰色章名。 */
@Composable
private fun HighlightList(items: List<HighlightEntry>, onPick: (HighlightEntry) -> Unit) {
    if (items.isEmpty()) {
        Empty("还没有划线\n长按正文开始划线")
        return
    }
    PagedList(items.size, HighlightRowHeight) { i, height ->
        val item = items[i]
        Row(
            Modifier.fillMaxWidth().height(height).clickable { onPick(item) }.padding(horizontal = ScreenMargin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).height(64.dp).background(Color(highlightColor(item.highlight.color))))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.highlight.text.replace('\n', ' '), fontSize = 16.sp, lineHeight = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.chapterTitle?.let {
                    Text(it, Modifier.padding(top = 6.dp), fontSize = 13.sp, color = SecondaryGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 整屏分页的定高列表：上下滑或点右侧滚动提示条翻屏（SPEC §2.6：列表不滚动）。 */
@Composable
private fun PagedList(count: Int, minRowHeight: Dp, initialIndex: Int = 0, row: @Composable (index: Int, height: Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val listHeight = maxHeight - ListTopGap - ListBottomGap
        val perPage = (listHeight / minRowHeight).toInt().coerceAtLeast(1)
        val rowHeight = listHeight / perPage
        val pageCount = (count + perPage - 1) / perPage
        var page by remember(count, perPage) { mutableIntStateOf(initialIndex.coerceAtLeast(0) / perPage) }
        val current = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val turn = { delta: Int -> page = (current + delta).coerceIn(0, pageCount - 1) }

        // 页数靠右边距里的滚动提示条（和书架同一个），不再占底部一条页脚
        Box(Modifier.fillMaxSize()) {
            // key 要带上 current：手势协程里的 turn 捕获的是建立时的 current，只带 pageCount 的话永远从第 1 屏算起
            Column(Modifier.fillMaxSize().verticalPageSwipe(current, pageCount) { turn(it) }.padding(top = ListTopGap)) {
                for (i in current * perPage until minOf(count, (current + 1) * perPage)) row(i, rowHeight)
            }
            if (pageCount > 1) {
                PageScrollbar(
                    current, pageCount,
                    Modifier.align(Alignment.TopEnd).padding(top = ListTopGap, bottom = ListBottomGap).width(ScreenMargin).fillMaxHeight(),
                ) { turn(it) }
            }
        }
    }
}

/**
 * 当前读到的目录项：位置不超过 [here] 的最靠后的一项；同一位置有好几项时取靠前的（章名，而不是它下面的第一节——
 * 原版 / 漫画模式不知道锚点在章内哪儿，同一章的各节都算在章首）。[position] 把一项换算成 [tocPosition]，
 * 不在阅读顺序里的给 null。一项都不够格（还在第一项之前）就是 -1。
 */
internal fun currentTocIndex(entries: List<TocEntry>, here: Long, position: (TocEntry) -> Long?): Int {
    var best = -1
    var bestAt = Long.MIN_VALUE
    entries.forEachIndexed { i, entry ->
        val at = position(entry) ?: return@forEachIndexed
        if (at <= here && at > bestAt) { best = i; bestAt = at }
    }
    return best
}

/** 阅读顺序里的一个位置：先比章节序号，再比章内字符偏移。 */
internal fun tocPosition(chapter: Int, offset: Int = 0): Long = (chapter.toLong() shl 32) or offset.toLong()
