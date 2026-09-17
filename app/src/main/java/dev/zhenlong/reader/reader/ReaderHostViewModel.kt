package dev.zhenlong.reader.reader

import android.app.Application
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.zhenlong.reader.ReaderApp
import dev.zhenlong.reader.data.Book
import dev.zhenlong.reader.data.BookKind
import dev.zhenlong.reader.data.ReaderFont
import dev.zhenlong.reader.data.ReaderStyle
import dev.zhenlong.reader.reader.text.Spot
import dev.zhenlong.reader.reader.text.TextEngine
import dev.zhenlong.reader.reader.text.TextParams
import dev.zhenlong.reader.scan.EpubMetaParser
import dev.zhenlong.reader.scan.ZipReader
import dev.zhenlong.reader.scan.openZip
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
import org.readium.r2.shared.util.Url

sealed interface HostState {
    data object Loading : HostState
    data object Failed : HostState
    class Ready(val book: Book, val manga: MangaSession, val text: TextEngine) : HostState
}

/**
 * 阅读页的入口：开 ZIP、读阅读顺序、判定文字 / 漫画，决定用哪个渲染器——
 * 漫画直接解码图片（本类负责翻页、预加载）；文字书用原生排版引擎 [TextEngine]；
 * 「原版」模式才交给 Readium（[ReaderViewModel]，用到时才创建）。进度都由本类落库。
 */
class ReaderHostViewModel(app: Application, savedState: SavedStateHandle) : AndroidViewModel(app) {
    private val reader = app as ReaderApp
    private val dao = reader.db.dao()
    private val bookId: String = checkNotNull(savedState["bookId"])
    private val createdAt = SystemClock.uptimeMillis()

    private val _state = MutableStateFlow<HostState>(HostState.Loading)
    val state: StateFlow<HostState> = _state

    /** 打开时自动判定，用户可在工具条里改，改了按书记住。两种模式各用各的一套偏好。 */
    private val _mode = MutableStateFlow(BookKind.TEXT)
    val mode: StateFlow<BookKind> = _mode

    val mangaStyle: StateFlow<ReaderStyle> = reader.settings.filterNotNull().map { it.mangaStyle }
        .stateIn(viewModelScope, SharingStarted.Eagerly, reader.settings.value?.mangaStyle ?: ReaderStyle.MANGA_DEFAULT)

    val textStyle: StateFlow<ReaderStyle> = reader.settings.filterNotNull().map { it.readerStyle }
        .stateIn(viewModelScope, SharingStarted.Eagerly, reader.settings.value?.readerStyle ?: ReaderStyle())

    private val _fonts = MutableStateFlow<List<UserFont>>(emptyList())
    val fonts: StateFlow<List<UserFont>> = _fonts
    private val typefaces = HashMap<String, Typeface>()

    private var zip: ZipReader? = null

    init {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { open() }
            val ready = _state.value as? HostState.Ready ?: return@launch
            applyTextParams()
            // 排版偏好 / 字体列表变了 → 重排，读到的那个字留在原地
            launch { textStyle.collect { applyTextParams() } }
            launch { fonts.collect { applyTextParams() } }
            launch {
                ready.text.page.collect { page ->
                    if (page != null && _mode.value == BookKind.TEXT) {
                        logFirstPage("text")
                        scheduleSave()
                    }
                }
            }
        }
    }

    private fun logFirstPage(engine: String) {
        if (firstPageLogged) return
        firstPageLogged = true
        Log.i(TAG, "first page in ${SystemClock.uptimeMillis() - createdAt}ms ($engine)")
    }

    private suspend fun open(): HostState {
        val book = dao.book(bookId) ?: return HostState.Failed
        return try {
            val zip = openZip(reader, Uri.parse(book.fileUri)).also { this.zip = it }
            val spine = EpubMetaParser.parseSpine(zip)?.takeIf { it.documents.isNotEmpty() } ?: error("no spine")
            val manga = MangaSession(zip, spine.documents)

            val detected = when {
                book.lastOpenedAt != null -> book.kind   // 打开过 = 判定过，不用再抽样读十几个章节
                spine.fixedLayout -> BookKind.MANGA
                MangaDetector.isManga(MangaDetector.sampleIndices(manga.pageCount).mapNotNull { runCatching { manga.sample(it) }.getOrNull() }) ->
                    BookKind.MANGA
                else -> BookKind.TEXT
            }
            if (detected != book.kind) dao.saveKind(bookId, detected)
            _mode.value = book.kindOverride ?: detected

            target = (book.pageIndex ?: chapterOf(book.locatorJson, manga) ?: 0).coerceIn(0, manga.pageCount - 1)

            _fonts.value = reader.settings.filterNotNull().first().fontsFolderUri?.let { listUserFonts(reader, Uri.parse(it)) }.orEmpty()
            val estimator = PageEstimator(spine.documents.map { zip.entries[it]?.size ?: 0L })
            val text = TextEngine(viewModelScope, zip, spine.documents, estimator, target, startSpot(book.locatorJson))
            HostState.Ready(book, manga, text)
        } catch (e: Exception) {
            Log.w(TAG, "cannot open ${book.fileName}", e)
            HostState.Failed
        }
    }

    /** Readium 存的位置 → 第几个章节文档。 */
    private fun chapterOf(locatorJson: String?, manga: MangaSession): Int? = runCatching {
        val href = JSONObject(locatorJson!!).getString("href")
        manga.indexOf(Url(href)?.path ?: href)
    }.getOrNull()

    /** 库里存的位置落在章内哪儿：原生引擎存的有精确的字符偏移，Readium 存的只有章内进度。 */
    private fun startSpot(locatorJson: String?): Spot = runCatching {
        val locations = JSONObject(locatorJson!!).getJSONObject("locations")
        if (locations.has("charOffset")) Spot.Offset(locations.getInt("charOffset")) else Spot.Progression(locations.optDouble("progression", 0.0))
    }.getOrDefault(Spot.Offset(0))

    /** 切换模式，从同一处接着读。[chapterHint] / [progressionHint]：从「原版」（Readium）切出来时它读到的位置。 */
    fun setMode(kind: BookKind, chapterHint: String? = null, progressionHint: Double? = null) {
        val from = _mode.value
        if (kind == from) return
        viewModelScope.launch {
            val ready = _state.value as? HostState.Ready ?: return@launch
            // 这一刻读到第几章、章内多少
            val chapter = when (from) {
                BookKind.MANGA -> _page.value?.index
                BookKind.TEXT -> ready.text.page.value?.chapter
                BookKind.WEB -> chapterHint?.let(ready.manga::indexOf)
            }
            val fraction = when (from) {
                BookKind.TEXT -> ready.text.page.value?.let { it.offset.toDouble() / it.chapterLength.coerceAtLeast(1) }
                BookKind.WEB -> progressionHint
                BookKind.MANGA -> null
            } ?: 0.0
            if (from != BookKind.WEB) saveNow()   // 「原版」一创建就读库里的位置，先落库再切；它自己的进度它自己存
            if (chapter != null) {
                target = chapter
                _page.value = null
                ready.text.go(chapter, Spot.Progression(fraction))
            }
            dao.saveKindOverride(bookId, kind)
            _mode.value = kind
            if (kind == BookKind.MANGA) load()
        }
    }

    // ---- 文字（原生引擎）--------------------------------------------------------------------------

    private var textViewport: Triple<Int, Int, Float>? = null
    private var spToPx = 1f

    /** 界面量出版心的像素尺寸后调用。 */
    fun setTextViewport(width: Int, height: Int, density: Float, spToPx: Float) {
        textViewport = Triple(width, height, density)
        this.spToPx = spToPx
        applyTextParams()
    }

    private fun applyTextParams() {
        val (w, h, density) = textViewport ?: return
        val engine = (_state.value as? HostState.Ready)?.text ?: return
        val style = textStyle.value
        engine.setParams(TextParams(w, h, BASE_TEXT_SP * spToPx * style.fontScale.toFloat(), style.lineHeight.toFloat(), typefaceOf(style), density))
    }

    private fun typefaceOf(style: ReaderStyle): Typeface = when (style.font) {
        ReaderFont.SERIF -> Typeface.SERIF
        ReaderFont.SANS -> Typeface.SANS_SERIF
        ReaderFont.PUBLISHER -> Typeface.DEFAULT
        // 直接映射字体文件：20MB 的字体也是瞬间的事。文件不在了（被删 / 授权被撤）退回默认
        ReaderFont.CUSTOM -> _fonts.value.firstOrNull { it.fileName == style.customFont }?.let { font ->
            typefaces.getOrPut(font.fileName) {
                runCatching {
                    reader.contentResolver.openFileDescriptor(font.uri, "r")!!.use { Typeface.Builder(it.fileDescriptor).build() }
                }.getOrDefault(Typeface.DEFAULT)
            }
        } ?: Typeface.DEFAULT
    }

    fun setTextStyle(style: ReaderStyle) {
        viewModelScope.launch { reader.prefs.setReaderStyle(BookKind.TEXT, style) }
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
            _fonts.value = withContext(Dispatchers.IO) { listUserFonts(reader, uri) }
        }
    }

    // ---- 漫画翻页 -------------------------------------------------------------------------------

    private val _page = MutableStateFlow<MangaPage?>(null)
    val page: StateFlow<MangaPage?> = _page

    private var target = 0
    private var viewport: Pair<Int, Int>? = null
    private val cache = HashMap<Int, MangaPage>()   // 只在主线程碰
    private var loader: Job? = null
    private var saveJob: Job? = null
    private var firstPageLogged = false

    /** 界面量出图片可用的像素尺寸后调用；尺寸变了（改边距）就按新尺寸重新解码。 */
    fun setViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || viewport == width to height) return
        viewport = width to height
        cache.clear()
        load()
    }

    fun turn(forward: Boolean) = go(target + if (forward) 1 else -1)

    fun go(index: Int) {
        val manga = (_state.value as? HostState.Ready)?.manga ?: return
        val clamped = index.coerceIn(0, manga.pageCount - 1)
        if (clamped == target && _page.value?.index == clamped) return
        target = clamped
        load()
    }

    /**
     * 单个循环、始终朝最新的 [target] 干活：先把目标页亮出来（旧页一直留到新页解码完，不闪白），
     * 再解码前后各一页备用（SPEC §5.5：当前页 ±1）。连点几下也只会停在最后的目标上。
     */
    private fun load() {
        if (loader?.isActive == true) return
        val manga = (_state.value as? HostState.Ready)?.manga ?: return
        loader = viewModelScope.launch {
            while (true) {
                val (w, h) = viewport ?: return@launch
                val want = target
                if (_page.value?.index != want || _page.value !== cache[want]) {
                    _page.value = cache[want] ?: decode(manga, want, w, h)
                    logFirstPage("manga")
                    scheduleSave()
                }
                if (target != want) continue
                val next = listOf(want + 1, want - 1).firstOrNull { it in 0 until manga.pageCount && it !in cache }
                if (next == null) break
                decode(manga, next, w, h)
            }
            // 最多留 3 页。不主动 recycle：界面可能还在画旧页，交给 GC
            cache.keys.retainAll { it in target - 1..target + 1 }
        }
    }

    private suspend fun decode(manga: MangaSession, index: Int, w: Int, h: Int): MangaPage {
        val page = withContext(Dispatchers.Default) {
            runCatching { manga.load(index, w, h) }.getOrElse { MangaPage(index, null, "这一页无法显示") }
        }
        if (viewport == w to h) cache[index] = page
        return page
    }

    // ---- 进度 ----------------------------------------------------------------------------------

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            saveNow()
        }
    }

    /** 当前模式下要存的进度：(章节序号, Readium 也认识的位置 JSON, 漫画总页数)。「原版」模式的进度由 [ReaderViewModel] 自己存。 */
    private fun progress(): Triple<Int, String, Int?>? {
        val ready = _state.value as? HostState.Ready ?: return null
        return when (_mode.value) {
            BookKind.MANGA -> _page.value?.index?.let { Triple(it, locatorJson(ready, it, 0.0, null), ready.manga.pageCount) }
            BookKind.TEXT -> ready.text.page.value?.let { p ->
                Triple(p.chapter, locatorJson(ready, p.chapter, p.offset.toDouble() / p.chapterLength.coerceAtLeast(1), p.offset), null)
            }
            BookKind.WEB -> null
        }
    }

    private fun locatorJson(ready: HostState.Ready, chapter: Int, progression: Double, charOffset: Int?): String {
        val document = ready.manga.documents[chapter]
        val locations = JSONObject().put("progression", progression)
        if (charOffset != null) locations.put("charOffset", charOffset)
        return JSONObject()
            .put("href", Url.fromDecodedPath(document)?.toString() ?: document)
            .put("type", "application/xhtml+xml")
            .put("locations", locations)
            .toString()
    }

    private suspend fun save(p: Triple<Int, String, Int?>) {
        val (chapter, json, mangaPages) = p
        val now = System.currentTimeMillis()
        if (mangaPages != null) dao.saveMangaProgress(bookId, chapter, mangaPages, json, now) else dao.saveLocator(bookId, json, chapter, now)
    }

    private suspend fun saveNow() {
        progress()?.let { withContext(Dispatchers.IO) { save(it) } }
    }

    /** 退出 / 切后台时强制写入；走进程级 scope，页面销毁也写得完。 */
    fun flush() {
        saveJob?.cancel()
        progress()?.let { reader.launch { save(it) } }
    }

    fun setMangaStyle(style: ReaderStyle) {
        viewModelScope.launch { reader.prefs.setReaderStyle(BookKind.MANGA, style) }
    }

    /** 漫画的目录不常用：点开时才让 Readium 解析一次。 */
    internal suspend fun loadToc(): List<TocEntry> = withContext(Dispatchers.IO) {
        val book = (_state.value as? HostState.Ready)?.book ?: return@withContext emptyList()
        runCatching {
            val opened = openPublication(reader, book.fileUri)
            try { flattenToc(opened.publication.tableOfContents) } finally { opened.publication.close() }
        }.getOrDefault(emptyList())
    }

    override fun onCleared() {
        flush()
        zip?.close()
    }

    private companion object {
        const val TAG = "Reader"
        const val BASE_TEXT_SP = 16f   // 字号滑块 1.0 档；和之前 Readium 的默认字号一致
        const val SAVE_DEBOUNCE_MS = 1_000L
    }
}
