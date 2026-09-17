package dev.zhenlong.reader.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zhenlong.reader.R
import dev.zhenlong.reader.ui.BarIcon
import dev.zhenlong.reader.ui.BarIconInset
import dev.zhenlong.reader.ui.PagerFooter
import dev.zhenlong.reader.ui.PagerFooterHeight
import dev.zhenlong.reader.ui.ScreenMargin
import dev.zhenlong.reader.ui.verticalPageSwipe
import org.readium.r2.shared.publication.Link

private val RowHeight = 48.dp
private val Indent = 16.dp

/** 目录：纯文本、缩进表示层级、整屏分页。 */
@Composable
internal fun TocScreen(entries: List<TocEntry>?, onBack: () -> Unit, onPick: (Link) -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = ScreenMargin - BarIconInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarIcon(R.drawable.ic_back, "返回", onBack)
            Text("目录", fontSize = 18.sp)
        }
        if (entries == null) return@Column   // 还在解析
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("这本书没有目录", fontSize = 16.sp) }
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val perPage = ((maxHeight - PagerFooterHeight) / RowHeight).toInt().coerceAtLeast(1)
            val pageCount = (entries.size + perPage - 1) / perPage
            var page by remember { mutableIntStateOf(0) }
            val turn = { delta: Int -> page = (page + delta).coerceIn(0, pageCount - 1) }

            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().weight(1f).verticalPageSwipe(pageCount) { turn(it) }) {
                    entries.drop(page * perPage).take(perPage).forEach { entry ->
                        Box(
                            Modifier.fillMaxWidth().height(RowHeight).clickable { onPick(entry.link) }
                                .padding(start = ScreenMargin + Indent * entry.depth, end = ScreenMargin),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(entry.link.title.orEmpty(), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                PagerFooter(page, pageCount) { turn(it) }
            }
        }
    }
}
