package dev.zhenlong.reader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 包住 Readium 的 WebView：手指一拖动就把事件截走，WebView 永远收不到 MOVE，也就没有跟手滚动；
 * 抬手时横向位移够 48dp 才算翻页，瞬切（SPEC §5.4）。点按不受影响，照常落到 Readium（链接、点击分区）。
 */
@SuppressLint("ViewConstructor")
class SwipeInterceptLayout(context: Context, private val onSwipe: (forward: Boolean) -> Unit) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val threshold = 48 * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
            }
            MotionEvent.ACTION_MOVE -> if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) return true
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            val dx = ev.x - downX
            if (abs(dx) >= threshold && abs(dx) > abs(ev.y - downY)) onSwipe(dx < 0)
        }
        return true
    }
}
