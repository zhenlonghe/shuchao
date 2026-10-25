package dev.zhenlong.reader.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 整屏分页的共用件：列表类界面一律不滚动（SPEC §2.6），上下滑或点页脚左 / 右半翻屏，瞬切。 */
internal val PagerFooterHeight = 44.dp
private val SwipeThreshold = 48.dp

/**
 * 网格分屏：首屏可以比其他屏少放（书架首屏第一行让给「最近阅读」），之后每屏 [perPage] 个。
 * 至少 1 屏。
 */
internal data class GridPages(val itemCount: Int, val perPage: Int, val firstPage: Int = perPage) {
    val count = if (itemCount <= firstPage) 1 else 1 + (itemCount - firstPage + perPage - 1) / perPage

    /** 第 [page] 屏对应的条目下标（可能越过末尾，按空位处理）。 */
    fun range(page: Int): IntRange = when {
        page < 0 -> IntRange.EMPTY
        page == 0 -> 0 until firstPage
        else -> (firstPage + (page - 1) * perPage).let { it until it + perPage }
    }
}

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
internal fun PagerFooter(page: Int, pageCount: Int, label: String? = null, onTurn: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().height(PagerFooterHeight), contentAlignment = Alignment.Center) {
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
