package dev.zhenlong.reader

import android.app.Application
import android.net.Uri
import dev.zhenlong.reader.data.AppDatabase
import dev.zhenlong.reader.data.Library
import dev.zhenlong.reader.data.Prefs
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
        settings = prefs.settings.flowOn(Dispatchers.IO).stateIn(appScope, SharingStarted.Eagerly, null)
    }

    /**
     * 书架首屏的封面先进内存缓存，再把书库交给界面：首帧就是带封面的完整书架，
     * 而不是先出文字、下一帧再补图（墨水屏上是两次刷新）。
     */
    private suspend fun preloadFirstScreen(library: Library) = coroutineScope {
        library.top.take(FIRST_SCREEN_ITEMS).mapNotNull { it.coverPath }
            .map { path -> async { imageLoader.execute(coverRequest(this@ReaderApp, path)) } }
            .awaitAll()
    }

    /** 需要比页面活得久的写操作（保存进度）。 */
    fun launch(block: suspend () -> Unit) {
        appScope.launch(Dispatchers.IO) { block() }
    }

    fun startScan(root: Uri) {
        appScope.launch { scanner.scan(root) }
    }
}
