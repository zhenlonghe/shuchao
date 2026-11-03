package dev.zhenlong.reader.reader

import android.icu.text.BreakIterator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zhenlong.reader.data.Highlight
import dev.zhenlong.reader.reader.text.Caret
import dev.zhenlong.reader.reader.text.ChapterLayout
import dev.zhenlong.reader.ui.OnePx

/**
 * 划线的四种颜色：黄 / 青 / 紫 / 绿（参考 Kobo 彩屏上的观感：土黄、灰青、淡紫）。
 * Kaleido 彩膜会把颜色压淡很多，屏幕上看到的大约只剩帧缓冲里一半的饱和度，所以这里取得比想要的观感浓得多
 * （2026-11-02 第一版用的粉彩色，用户反馈真机上「略淡」）。库里存的是序号，改色值不用迁移。
 */
internal val HighlightColors = intArrayOf(
    0xFFF2C21B.toInt(),   // 黄
    0xFF4FB0CF.toInt(),   // 青
    0xFFA683D6.toInt(),   // 紫
    0xFF86C24F.toInt(),   // 绿
)

internal fun highlightColor(index: Int) = HighlightColors[index.coerceIn(0, HighlightColors.size - 1)]

/** 正在选的选区的底色：中灰，和四种划线色都分得开。 */
internal const val SELECTION_COLOR = 0xFFC4C4C4.toInt()

/**
 * 正在选 / 刚选好的一段。只在一页之内（选区不跨页）。
 * [anchor] = 长按选中的那个词，拖动从它往两边扩；[existing] = 长按的是已有的划线（只能改色 / 删除，不能拖）；
 * [settled] = 已松手：显示颜色条和两端的把手。
 */
internal data class Selection(
    val layout: ChapterLayout,
    val page: Int,
    val anchor: IntRange,
    val start: Int,
    val end: Int,
    val existing: Highlight? = null,
    val settled: Boolean = false,
)

/** 选字的单位：长按按词（ICU 的分词，中文按词典切成词）；拖动时汉字逐字、其余按词。 */
internal class WordFinder {
    private val iterator = BreakIterator.getWordInstance()
    private var text: CharSequence? = null

    /** [offset] 处那个字所在的词；按在空白 / 图片上 = null。 */
    fun wordAt(t: CharSequence, offset: Int): IntRange? {
        if (offset !in t.indices) return null
        val c = t[offset]
        if (c.isWhitespace() || c == '￼' || c == '​') return null
        if (t !== text) {
            iterator.setText(t)
            text = t
        }
        val end = iterator.following(offset).let { if (it == BreakIterator.DONE) t.length else it }
        val start = iterator.preceding(offset + 1).let { if (it == BreakIterator.DONE) 0 else it }
        return start until end
    }

    fun unitAt(t: CharSequence, offset: Int): IntRange {
        val at = offset.coerceIn(0, (t.length - 1).coerceAtLeast(0))
        if (at in t.indices && Character.isIdeographic(t[at].code)) return at..at
        return wordAt(t, at) ?: at..at
    }
}

private val BarHeight = 52.dp
internal val HighlightBarHeight = BarHeight

/**
 * 松手后弹出的颜色条：四个色点；长按的是已有划线时，当前颜色的点加粗圈、末尾多一个「删除」。
 * 白底 1px 黑框，不画阴影（墨水屏上阴影是一片脏灰）。
 */
@Composable
internal fun HighlightBar(current: Int?, modifier: Modifier, onColor: (Int) -> Unit, onDelete: (() -> Unit)?) {
    Row(
        modifier.height(BarHeight).background(Color.White).border(OnePx, Color.Black).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HighlightColors.forEachIndexed { i, argb ->
            Box(Modifier.size(48.dp).clickable { onColor(i) }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(28.dp)) {
                    val r = size.minDimension / 2
                    drawCircle(Color(argb), r)
                    val ring = if (i == current) 3.dp.toPx() else 1.dp.toPx()
                    drawCircle(Color.Black, r - ring / 2, style = Stroke(ring))
                }
            }
        }
        if (onDelete != null) {
            Box(Modifier.padding(horizontal = 4.dp).width(OnePx).height(24.dp).background(Color.Black))
            Box(Modifier.fillMaxHeight().clickable(onClick = onDelete).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text("删除", fontSize = 15.sp)
            }
        }
    }
}

/** 选区两端的把手：一道竖线，起点的圆点在上、终点的在下（一眼分得出哪头是哪头，也好按）。 */
internal fun DrawScope.drawHandle(caret: Caret, atStart: Boolean) {
    val stroke = 1.5.dp.toPx()
    val r = 5.dp.toPx()
    drawLine(Color.Black, Offset(caret.x, caret.top), Offset(caret.x, caret.bottom), strokeWidth = stroke)
    drawCircle(Color.Black, r, Offset(caret.x, if (atStart) caret.top - r else caret.bottom + r))
}
