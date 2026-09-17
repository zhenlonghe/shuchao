package dev.zhenlong.reader.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * TEXT / MANGA 是自动判定的结果，同时也是阅读模式；WEB 只会出现在 kindOverride 里：
 * 用户手动选的「原版」模式——用 Readium（WebView）还原出版商的版式，慢，留给版式复杂的书。
 */
enum class BookKind { TEXT, MANGA, WEB }

@Entity
data class Series(
    @PrimaryKey val id: String,       // 文件夹 URI 的 hash
    val folderUri: String,
    val name: String,
    val coverPath: String?,           // 缓存缩略图本地路径
)

@Entity(indices = [Index("seriesId")])
data class Book(
    @PrimaryKey val id: String,       // 文件 URI 的 hash
    val fileUri: String,
    val seriesId: String?,            // null = 根目录单本
    val title: String,
    val author: String?,
    val fileName: String,
    val kind: BookKind,
    val kindOverride: BookKind?,      // 用户手动切换
    val coverPath: String?,
    val lastModified: Long,
    val size: Long,
    val locatorJson: String?,         // TEXT 进度
    val pageIndex: Int?,              // MANGA 进度
    val pageCount: Int?,              // MANGA 总页
    val lastOpenedAt: Long?,
)
