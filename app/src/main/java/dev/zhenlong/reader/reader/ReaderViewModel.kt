package dev.zhenlong.reader.reader

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.zhenlong.reader.ReaderApp
import dev.zhenlong.reader.data.Book
import dev.zhenlong.reader.data.BookKind
import dev.zhenlong.reader.data.ReaderStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref

sealed interface ReaderState {
    data object Loading : ReaderState
    data object Failed : ReaderState
    class Ready(
        val book: Book,
        val publication: Publication,
        val initialLocator: Locator?,
        val pages: PageEstimator,
    ) : ReaderState
}

class ReaderViewModel(app: Application, savedState: SavedStateHandle) : AndroidViewModel(app) {
    private val reader = app as ReaderApp
    private val dao = reader.db.dao()
    private val bookId: String = checkNotNull(savedState["bookId"])

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state

    val style: StateFlow<ReaderStyle> = reader.settings.filterNotNull().map { it.readerStyle }
        .stateIn(viewModelScope, SharingStarted.Eagerly, reader.settings.value?.readerStyle ?: ReaderStyle())

    private val fontContainer = FontContainer(reader.contentResolver)
    private val _fonts = MutableStateFlow<List<UserFont>>(emptyList())
    val fonts: StateFlow<List<UserFont>> = _fonts

    /** 最近一次的位置：换字体文件夹要重建渲染器，从这里接着读。 */
    var lastLocator: Locator? = null
        private set

    private val createdAt = SystemClock.uptimeMillis()
    private var firstPageLogged = false
    private var pendingLocator: Locator? = null
    private var saveJob: Job? = null

    init {
        viewModelScope.launch { _state.value = open() }
    }

    private suspend fun open(): ReaderState = withContext(Dispatchers.IO) {
        val book = dao.book(bookId) ?: return@withContext ReaderState.Failed
        try {
            loadFonts(reader.settings.filterNotNull().first().fontsFolderUri)
            val opened = openPublication(reader, book.fileUri, extra = fontContainer)
            val publication = opened.publication
            val zip = opened.zip
            val locator = book.locatorJson?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }

            ReaderState.Ready(book, publication, locator, PageEstimator(publication.readingOrder.map { zip.sizeOf(it.url()) }))
        } catch (e: Exception) {
            Log.w(TAG, "cannot open ${book.fileName}", e)
            ReaderState.Failed
        }
    }

    private fun loadFonts(folderUri: String?) {
        val fonts = folderUri?.let { listUserFonts(reader, Uri.parse(it)) }.orEmpty()
        fontContainer.fonts = fonts
        _fonts.value = fonts
    }

    /** 用户在字体面板里选了字体文件夹（SAF 授权，一次即可）。 */
    fun setFontsFolder(uri: Uri) {
        val resolver = reader.contentResolver
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        reader.settings.value?.fontsFolderUri?.let(Uri::parse)?.takeIf { it != uri }?.let {
            runCatching { resolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        viewModelScope.launch {
            reader.prefs.setFontsFolderUri(uri.toString())
            withContext(Dispatchers.IO) { loadFonts(uri.toString()) }
        }
    }

    /** logcat「first page」= 点开书到首页出现的耗时（SPEC §9：< 1.5s）。 */
    fun onFirstPage() {
        if (firstPageLogged) return
        firstPageLogged = true
        Log.i(TAG, "first page in ${SystemClock.uptimeMillis() - createdAt}ms")
    }

    /** 每次翻页调用；防抖 1s 落库（SPEC §5.4）。 */
    fun onLocator(locator: Locator) {
        lastLocator = locator
        pendingLocator = locator
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            flush()
        }
    }

    /** 退出 / 切后台时强制写入。走进程级 scope，页面销毁也写得完。 */
    fun flush() {
        val locator = pendingLocator ?: return
        pendingLocator = null
        saveJob?.cancel()
        val json = locator.toJSON().toString()
        val chapter = (_state.value as? ReaderState.Ready)?.publication?.readingOrder?.indexOfFirstWithHref(locator.href)
        reader.launch { dao.saveLocator(bookId, json, chapter, System.currentTimeMillis()) }
    }

    fun setStyle(style: ReaderStyle) {
        viewModelScope.launch { reader.prefs.setReaderStyle(BookKind.TEXT, style) }
    }

    override fun onCleared() {
        flush()
        (_state.value as? ReaderState.Ready)?.publication?.close()
    }

    private companion object {
        const val TAG = "Reader"
        const val SAVE_DEBOUNCE_MS = 1_000L
    }
}
