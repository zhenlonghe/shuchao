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
    val progress: Float? = null,      // 全书读到几成（0–1），书架「最近阅读」行显示用；文字书是估算（PageEstimator）
)

/** 读过的一本书的阅读状态（Book 的几列）：书架的排序和进度条只看这个，读书时常驻订阅，比整个书库轻得多。 */
data class ReadState(
    val id: String,
    val seriesId: String?,
    val lastOpenedAt: Long,
    val progress: Float?,
)

/**
 * 划线（只有原生「文字」模式有）。位置是 [dev.zhenlong.reader.reader.text.ChapterParser] 输出里的字符偏移，
 * 和进度的 charOffset 同一个坐标系：改了 ChapterParser 的输出（多 / 少了字）就会错位。[text] 存一份摘录给划线列表用。
 * 按章节文档路径（ZIP 里的条目名）记，不按章节序号：序号只是阅读顺序里的位置。
 */
@Entity(indices = [Index("bookId")])
data class Highlight(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val document: String,
    val startOffset: Int,
    val endOffset: Int,
    val color: Int,                   // HighlightColors 里的序号
    val text: String,
    val createdAt: Long,
    val chapterTitle: String? = null,   // 划线时所在章的目录标题（v4 起），导出的文本文件里用；之前的划线是 null
)
