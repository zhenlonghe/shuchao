package dev.zhenlong.reader.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Date

/** 跟着系统的 TIME_TICK 广播每分钟变一次：自己不起定时器，页面不在时也不监听（阅读页脚用）。 */
@Composable
internal fun currentTime(): String {
    val context = LocalContext.current
    val format = remember { DateFormat.getTimeFormat(context) }   // 跟随系统的 12 / 24 小时制
    var now by remember { mutableStateOf(format.format(Date())) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                now = format.format(Date())
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_TIME_TICK).apply { addAction(Intent.ACTION_TIME_CHANGED) })
        now = format.format(Date())
        onDispose { context.unregisterReceiver(receiver) }
    }
    return now
}
