package dev.zhenlong.reader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zhenlong.reader.ReaderApp
import dev.zhenlong.reader.data.Book
import dev.zhenlong.reader.data.Library
import dev.zhenlong.reader.data.Settings
import dev.zhenlong.reader.data.ShelfItem
import dev.zhenlong.reader.scan.ScanState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class ShelfUi(
    val hasLibraryRoot: Boolean,
    val scan: ScanState,
    val seriesName: String?,      // 非 null = 正在一套里
    val query: String?,           // 非 null = 搜索中
    val items: List<ShelfItem>,
    val page: Int,
)

class ShelfViewModel(app: Application) : AndroidViewModel(app) {
    private val reader = app as ReaderApp

    private data class Nav(
        val seriesId: String? = null,
        val query: String? = null,
        val topPage: Int = 0,
        val seriesPage: Int = 0,
        val searchPage: Int = 0,
    )

    private val nav = MutableStateFlow(Nav())

    /** null = 首次加载未完成，界面保持空白，避免闪一下空状态。 */
    val ui: StateFlow<ShelfUi?> = combine(
        reader.library.filterNotNull(),
        nav,
        reader.settings.filterNotNull(),
        reader.scanner.state,
        ::buildUi,
    ).flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        // 冷启动时数据通常已由 ReaderApp 提前备好：同步给出初值，首帧直接就是书架，省掉一次空白帧
        initialValue = reader.library.value?.let { lib ->
            reader.settings.value?.let { buildUi(lib, nav.value, it, reader.scanner.state.value) }
        },
    )

    private fun buildUi(lib: Library, nav: Nav, settings: Settings, scan: ScanState): ShelfUi {
        val seriesId = nav.seriesId?.takeIf { it in lib.bySeries }
        val (items, page) = when {
            !nav.query.isNullOrBlank() -> search(lib.all, nav.query) to nav.searchPage
            seriesId != null -> lib.bySeries.getValue(seriesId) to nav.seriesPage
            else -> lib.top to nav.topPage
        }
        return ShelfUi(settings.libraryRootUri != null, scan, seriesId?.let(lib.seriesNames::get), nav.query, items, page)
    }

    fun openSeries(id: String) = nav.update { it.copy(seriesId = id, seriesPage = 0) }
    fun closeSeries() = nav.update { it.copy(seriesId = null) }
    fun startSearch() = nav.update { it.copy(query = "", searchPage = 0) }
    fun setQuery(q: String) = nav.update { it.copy(query = q, searchPage = 0) }
    fun closeSearch() = nav.update { it.copy(query = null) }

    fun setPage(page: Int) = nav.update {
        when {
            !it.query.isNullOrBlank() -> it.copy(searchPage = page)
            it.seriesId != null -> it.copy(seriesPage = page)
            else -> it.copy(topPage = page)
        }
    }

    /** 书名 / 作者 / 文件名；按空白分词，每个词都要命中，不分大小写。查询为空时不过滤（显示原书架）。 */
    private fun search(all: List<Book>, query: String): List<ShelfItem> {
        val tokens = query.trim().lowercase().split(Regex("\\s+"))
        return all.filter { b ->
            val haystack = "${b.title}\n${b.author.orEmpty()}\n${b.fileName}".lowercase()
            tokens.all { it in haystack }
        }.map { ShelfItem.Single(it) }
    }
}
