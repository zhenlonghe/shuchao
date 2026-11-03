package dev.zhenlong.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 整屏分页的共用件：列表类界面一律不滚动（SPEC §2.6），上下滑或点页脚左 / 右半翻屏，瞬切。 */
internal val PagerFooterHeight = 44.dp
private val SwipeThreshold = 48.dp

/** [onTurn]：+1 下一屏，-1 上一屏。 */
internal fun Modifier.verticalPageSwipe(vararg keys: Any?, onTurn: (Int) -> Unit): Modifier =
    pointerInput(*keys) {
        var dragged = 0f
        detectVerticalDragGestures(
            onDragStart = { dragged = 0f },
            onDragEnd = {
                if (dragged <= -SwipeThreshold.toPx()) onTurn(1)
                else if (dragged >= SwipeThreshold.toPx()) onTurn(-1)
            },
        ) { _, delta -> dragged += delta }
    }

/** [label]：页码前的一小段上下文（书架在一套里时放套名）。只有一屏时不显示页码，也没有点击区。 */
@Composable
internal fun PagerFooter(page: Int, pageCount: Int, label: String? = null, height: Dp = PagerFooterHeight, onTurn: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        if (pageCount > 1) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().clickable { onTurn(-1) })
                Box(Modifier.weight(1f).fillMaxHeight().clickable { onTurn(1) })
            }
        }
        val text = listOfNotNull(label, "${page + 1} / $pageCount".takeIf { pageCount > 1 }).joinToString("　")
        if (text.isNotEmpty()) {
            Text(
                text,
                Modifier.padding(horizontal = ScreenMargin),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}

/**
 * 右边距里的滚动提示条（用户 2026-11-03，取代底部「1 / 2」；书架、目录共用）：一道 [EdgeGray] 细轨，黑色滑块长 = 轨长 / 屏数，位置 = 第几屏。
 * [modifier] 定位置和高度：通常是一条 [ScreenMargin] 宽、和内容上下对齐的竖条。整条边距可点：点滑块上方上一屏、下方下一屏。不画箭头：上下滑本来就能翻。
 */
@Composable
internal fun PageScrollbar(page: Int, pageCount: Int, modifier: Modifier, onTurn: (Int) -> Unit) {
    BoxWithConstraints(modifier) {
        val thumb = (maxHeight / pageCount).coerceAtLeast(ScrollThumbMin)
        val top = (maxHeight - thumb) * (page.toFloat() / (pageCount - 1))
        Box(
            Modifier.fillMaxSize().pointerInput(page, pageCount) {
                detectTapGestures { at -> onTurn(if (at.y < (top + thumb / 2).toPx()) -1 else 1) }
            },
        ) {
            Box(Modifier.align(Alignment.TopCenter).width(OnePx).fillMaxHeight().background(EdgeGray))
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = top).width(ScrollThumbWidth).height(thumb)
                    .background(Color.Black, RoundedCornerShape(50)),
            )
        }
    }
}

private val ScrollThumbWidth = 3.dp
private val ScrollThumbMin = 24.dp
