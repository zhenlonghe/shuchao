package dev.zhenlong.reader

import android.app.Application
import android.net.Uri
import dev.zhenlong.reader.data.AppDatabase
import dev.zhenlong.reader.data.Book
import dev.zhenlong.reader.data.Library
import dev.zhenlong.reader.data.Prefs
import dev.zhenlong.reader.data.RECENT_COUNT
import dev.zhenlong.reader.data.Settings
import coil.imageLoader
import dev.zhenlong.reader.scan.LibraryScanner
import dev.zhenlong.reader.ui.coverRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
     * 最近读过的几本（书架首屏第一行）。null = 还没开始，书架先用 [Library.recent]。
     * 和 [library] 不同，这条是常驻订阅：library 在阅读时停掉、回书架时首帧是旧值，
     * 如果最近一行也跟着它走，回到书架会先画旧顺序再换新顺序，墨水屏刷两次。
     * 每翻一页只多一次 LIMIT 4 的小查询；书的顺序 / 封面没变就不往下发。
     * 等书库加载完才开始查：冷启动时不和书库抢 CPU，首帧用书库里算好的那份就够了。
     */
    lateinit var recent: StateFlow<List<Book>?>
        private set

    override fun onCreate() {
        super.onCreate()
        // 进程一起来就开库、读偏好，和 Activity / Compose 初始化并行（冷启动 < 1s，SPEC §9）
        // WhileSubscribed：阅读时书架不在前台，翻页写进度不会触发整库重排；
        // 先挂一个一次性订阅把加载提前到此刻，5s 内书架会接上
        library = combine(db.dao().observeBooks(), db.dao().observeSeries(), ::Library)
            .onEach(::preloadFirstScreen)
            .flowOn(Dispatchers.Default)
            .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), null)
        appScope.launch { library.first { it != null } }
        recent = flow {
            library.first { it != null }
            emitAll(db.dao().observeRecent(RECENT_COUNT))
        }
            // 键里带 progress：同一本书接着读、顺序没变时，封面下的「读到 N%」也要跟着变
            .distinctUntilChangedBy { books -> books.map { listOf(it.id, it.title, it.coverPath, it.progress) } }
            .onEach { books -> preloadCovers(books.mapNotNull { it.coverPath }) }
            .flowOn(Dispatchers.Default)
            .stateIn(appScope, SharingStarted.Eagerly, null)
        settings = prefs.settings.flowOn(Dispatchers.IO).stateIn(appScope, SharingStarted.Eagerly, null)
    }

    /**
     * 书架首屏的封面先进内存缓存，再把书库交给界面：首帧就是带封面的完整书架，
     * 而不是先出文字、下一帧再补图（墨水屏上是两次刷新）。
     */
    private suspend fun preloadFirstScreen(library: Library) {
        // 读过书时首屏第一行是最近阅读，书架只露出前 8 本
        val shown = if (library.recent.isEmpty()) FIRST_SCREEN_ITEMS else FIRST_SCREEN_ITEMS - RECENT_COUNT
        preloadCovers(library.recent.mapNotNull { it.coverPath } + library.top.take(shown).mapNotNull { it.coverPath })
    }

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
