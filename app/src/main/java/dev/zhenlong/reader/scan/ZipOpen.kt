package dev.zhenlong.reader.scan

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.io.IOException

/** 经 SAF 拿 fd，用 [ZipReader] 随机读。调用方负责 close。 */
fun openZip(context: Context, uri: Uri): ZipReader {
    val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("cannot open $uri")
    return try {
        ZipReader(FileInputStream(pfd.fileDescriptor).channel, pfd)
    } catch (e: Exception) {
        pfd.close()
        throw e
    }
}
