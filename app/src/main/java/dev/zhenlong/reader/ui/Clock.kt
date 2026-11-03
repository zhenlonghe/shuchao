package dev.zhenlong.reader.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Date

/**
 * 跟着系统的 TIME_TICK 广播每分钟加一：自己不起定时器，页面不在时也不监听（阅读页脚用）。
 * 时间和电量都按它刷新——一分钟最多一次，而且值没变就不重组、不刷屏。
 */
@Composable
internal fun minuteTick(): Int {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                tick++
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_TIME_TICK).apply { addAction(Intent.ACTION_TIME_CHANGED) })
        tick++   // 页面回到前台时立刻更新一次
        onDispose { context.unregisterReceiver(receiver) }
    }
    return tick
}

/** 当前时间，跟随系统的 12 / 24 小时制。 */
@Composable
internal fun currentTime(tick: Int): String {
    val context = LocalContext.current
    val format = remember { DateFormat.getTimeFormat(context) }
    return remember(tick) { format.format(Date()) }
}

/**
 * 电量（0–100）。不听 ACTION_BATTERY_CHANGED（电压 / 温度一变就发，太频繁），跟着时钟每分钟问一次 BatteryManager，
 * 不需要任何权限。
 */
@Composable
internal fun batteryPercent(tick: Int): Int {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(BatteryManager::class.java) }
    return remember(tick) { manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100) }
}
