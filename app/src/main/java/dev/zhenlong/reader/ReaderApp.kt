package dev.zhenlong.reader

import android.app.Application
import android.net.Uri
import dev.zhenlong.reader.data.AppDatabase
import dev.zhenlong.reader.data.HighlightExport
import dev.zhenlong.reader.data.Library
import dev.zhenlong.reader.data.Prefs
import dev.zhenlong.reader.data.ReadState
import dev.zhenlong.reader.data.arrangeByRecent
import dev.zhenlong.reader.data.Settings
import coil.imageLoader
import dev.zhenlong.reader.scan.LibraryScanner
import dev.zhenlong.reader.scan.ScanState
import dev.zhenlong.reader.ui.coverRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FIRST_SCREEN_ITEMS = 12   // 书架一屏 4×3

class ReaderApp : Application() {
    val db by lazy { AppDatabase.open(this) }
    val prefs by lazy { Prefs(this) }
    val scanner by lazy { LibraryScanner(this, db.dao()) }

    // 扫描跟随进程而不是某个页面；不是 Service，进程没了就停（SPEC §2.4）
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** null = 尚未加载完。 */
    lateinit var library: StateFlow<Library?>
        private set
    lateinit var settings: StateFlow<Settings?>
        private set
    /**
     * 读过的书（id → 阅读状态）：书架的顺序和进度条看它。null = 还没开始，书架先用 [Library.reads]。
     * 和 [library] 不同，这条是常驻订阅：library 在阅读时停掉、回书架时首帧是旧值，
     * 如果顺序也跟着它走，回到书架会先画旧顺序再换新顺序，墨水屏刷两次。
     * 每次存进度只多一次只取四列的小查询；等书库加载完才开始，冷启动时不和书库抢 CPU。
     */
    lateinit var reads: StateFlow<Map<String, ReadState>?>
        private set

    override fun onCreate() {
        super.onCreate()
        // 进程一起来就开库、读偏好，和 Activity / Compose 初始化并行（冷启动 < 1s，SPEC §9）
        // WhileSubscribed：阅读时书架不在前台，翻页写进度不会触发整库重排；
        // 先挂一个一次性订阅把加载提前到此刻，5s 内书架会接上
        library = combine(db.dao().observeBooks(), db.dao().observeSeries(), ::Library)
            .onEach { preloadCovers(firstScreenCovers(it, it.reads)) }
            .flowOn(Dispatchers.Default)
            .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), null)
        appScope.launch { library.first { it != null } }
        reads = flow {
            library.first { it != null }
            emitAll(db.dao().observeReadStates())
        }
            .map { states -> states.associateBy { it.id } }
            // 读完回书架时，新排到首屏的封面已在内存里。每存一次进度都会走到这里，首屏没变就跳过
            .onEach { reads ->
                val paths = library.value?.let { firstScreenCovers(it, reads) } ?: return@onEach
                if (paths != preloadedFirstScreen) preloadCovers(paths).also { preloadedFirstScreen = paths }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(appScope, SharingStarted.Eagerly, null)
        settings = prefs.settings.flowOn(Dispatchers.IO).stateIn(appScope, SharingStarted.Eagerly, null)
        // 缩略图的处理方式升级过：这一次启动把全库重扫一遍（≈ 9s，书架上显示「正在扫描」），之后不再触发
        appScope.launch {
            val root = settings.first { it != null }?.libraryRootUri ?: return@launch
            if (withContext(Dispatchers.IO) { scanner.thumbnailsOutdated() }) startScan(Uri.parse(root))
        }
        exportHighlightsOnChange()
    }

    private val exportRequests = MutableStateFlow(0)

    /** 划线文件写不进书库根目录（旧版本只拿了读授权）：设置页提示重新选一次书库文件夹。 */
    val highlightExportBlocked = MutableStateFlow(false)

    /** 授权变了（重新选了书库文件夹）之后再导一次。 */
    fun exportHighlights() {
        exportRequests.value++
    }

    /**
     * 划线一变就把全部划线重写进书库根目录的 [HighlightExport.FILE_NAME]。只订阅 Highlight 表（翻页写进度不触发）；
     * 合并 1.5s 内的连续改动；启动后先等几秒，不和冷启动抢 IO。内容没变就不写（见 [HighlightExport.write]）。
     */
    @OptIn(FlowPreview::class)
    private fun exportHighlightsOnChange() {
        appScope.launch(Dispatchers.IO) {
            delay(EXPORT_STARTUP_DELAY_MS)
            val roots = settings.filterNotNull().map { it.libraryRootUri }.distinctUntilChanged()
            // 扫描中不导：换回含旧划线的书库时，Book 记录要等扫描完才回来，早导会把这些书写成「已不在书库里」。扫完再导一次
            val scanning = scanner.state.map { it is ScanState.Running }.distinctUntilChanged()
            combine(db.dao().observeAllHighlights(), roots, exportRequests, scanning) { highlights, root, _, busy -> Triple(highlights, root, busy) }
                .debounce(EXPORT_DEBOUNCE_MS)
                .collectLatest { (highlights, root, busy) ->
                    if (root == null || busy) return@collectLatest
                    val ids = highlights.map { it.bookId }.distinct()
                    val books = ids.chunked(500).flatMap { db.dao().booksByIds(it) }.associateBy { it.id }
                    val text = HighlightExport.format(highlights, books)
                    // 一条划线都没有、文件也不存在时不新建空文件
                    highlightExportBlocked.value = !HighlightExport.write(this@ReaderApp, Uri.parse(root), text, createIfMissing = highlights.isNotEmpty())
                }
        }
    }

    private var preloadedFirstScreen: List<String>? = null   // 只在 reads 那条流（Dispatchers.Default 上串行）里读写

    /**
     * 书架首屏的封面：先进内存缓存，再把书库交给界面，首帧就是带封面的完整书架，
     * 而不是先出文字、下一帧再补图（墨水屏上是两次刷新）。
     */
    private fun firstScreenCovers(library: Library, reads: Map<String, ReadState>): List<String> =
        arrangeByRecent(library.top, reads).take(FIRST_SCREEN_ITEMS).mapNotNull { it.coverPath }

    private suspend fun preloadCovers(paths: List<String>) = coroutineScope {
        paths.map { path -> async { imageLoader.execute(coverRequest(this@ReaderApp, path)) } }.awaitAll()
    }

    /** 需要比页面活得久的写操作（保存进度）。 */
    fun launch(block: suspend () -> Unit) {
        appScope.launch(Dispatchers.IO) { block() }
    }

    fun startScan(root: Uri) {
        appScope.launch { scanner.scan(root) }
    }
}

private const val EXPORT_STARTUP_DELAY_MS = 3_000L
private const val EXPORT_DEBOUNCE_MS = 1_500L
