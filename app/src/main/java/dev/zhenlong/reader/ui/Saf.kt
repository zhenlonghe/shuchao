package dev.zhenlong.reader.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher

/** 有些精简 ROM 没有系统文件选择器（DocumentsUI），SAF 一启动就抛异常：提示一句，别闪退。 */
fun <I> ManagedActivityResultLauncher<I, *>.launchOrToast(context: Context, input: I) {
    try {
        launch(input)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "这台设备没有系统文件选择器，无法选择文件夹", Toast.LENGTH_LONG).show()
    }
}
