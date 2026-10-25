package dev.zhenlong.reader

import android.app.Application
import android.net.Uri
import dev.zhenlong.reader.data.AppDatabase
import dev.zhenlong.reader.data.Library
import dev.zhenlong.reader.data.Prefs
import dev.zhenlong.reader.data.ReadState
import dev.zhenlong.reader.data.arrangeByRecent
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
