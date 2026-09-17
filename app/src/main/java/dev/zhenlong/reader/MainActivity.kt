package dev.zhenlong.reader

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.zhenlong.reader.reader.ReaderScreen
import dev.zhenlong.reader.ui.InkTheme
import dev.zhenlong.reader.ui.SettingsScreen
import dev.zhenlong.reader.ui.ShelfScreen

/** FragmentActivity：Readium 的 EPUB 渲染器是个 Fragment。 */
class MainActivity : FragmentActivity() {

    /** 阅读器在前台时接管音量键 / 翻页键；参数 true = 下一页。 */
    var pageKeyHandler: ((forward: Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 不恢复旧状态：进程被杀后 FragmentManager 会在书还没重新打开时就去重建 Readium 的 Fragment 而崩溃。
        // 代价只是回到书架；阅读位置存在库里，不受影响。
        super.onCreate(null)
        // 内容始终铺满整个窗口、自己让开系统栏和键盘：进出阅读器时状态栏的隐现只动顶上那一条，
        // 不会让整页重新布局；键盘弹出时窗口也不缩小（窗口是透明的，缩小会漏出桌面）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            InkTheme {
                val nav = rememberNavController()
                NavHost(
                    navController = nav,
                    startDestination = "shelf",
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    composable("shelf") {
                        ShelfScreen(
                            onOpenBook = { nav.navigate("reader/${it.id}") },
                            onOpenSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                    composable("reader/{bookId}") { ReaderScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }

    // 在分发入口拦：焦点在 WebView 上，等到 onKeyDown 时音量已经被系统调了
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handler = pageKeyHandler ?: return super.dispatchKeyEvent(event)
        val forward = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> true
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT -> false
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) handler(forward)
        return true
    }
}
