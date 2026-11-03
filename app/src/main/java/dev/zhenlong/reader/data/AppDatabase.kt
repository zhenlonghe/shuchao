package dev.zhenlong.reader.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM Book")
    fun observeBooks(): Flow<List<Book>>

    @Query("SELECT * FROM Series")
    fun observeSeries(): Flow<List<Series>>

    @Query("SELECT * FROM Book")
    suspend fun allBooks(): List<Book>

    /** 读过的书：书架按它排序、画进度条。lastOpenedAt 每翻一页都会更新，所以就是「最近读过」的顺序。 */
    @Query("SELECT id, seriesId, lastOpenedAt, progress FROM Book WHERE lastOpenedAt IS NOT NULL")
    fun observeReadStates(): Flow<List<ReadState>>

    @Query("SELECT * FROM Book WHERE id = :id")
    suspend fun book(id: String): Book?

    /** 两种阅读器都同时写 locatorJson 和 pageIndex（章节序号）：切换模式后能从差不多的位置接着读。 */
    // progress 传 null = 这次算不出来，保留上次的值
    @Query("UPDATE Book SET locatorJson = :locatorJson, pageIndex = :pageIndex, lastOpenedAt = :now, progress = COALESCE(:progress, progress) WHERE id = :id")
    suspend fun saveLocator(id: String, locatorJson: String, pageIndex: Int?, now: Long, progress: Float?)

    @Query("UPDATE Book SET pageIndex = :pageIndex, pageCount = :pageCount, locatorJson = :locatorJson, lastOpenedAt = :now, progress = COALESCE(:progress, progress) WHERE id = :id")
    suspend fun saveMangaProgress(id: String, pageIndex: Int, pageCount: Int, locatorJson: String, now: Long, progress: Float?)

    @Query("UPDATE Book SET kind = :kind WHERE id = :id")
    suspend fun saveKind(id: String, kind: BookKind)

    @Query("UPDATE Book SET kindOverride = :kind WHERE id = :id")
    suspend fun saveKindOverride(id: String, kind: BookKind?)

    @Upsert
    suspend fun upsertBooks(books: List<Book>)

    @Upsert
    suspend fun upsertSeries(series: List<Series>)

    @Query("DELETE FROM Book WHERE id IN (:ids)")
    suspend fun deleteBooks(ids: List<String>)

    @Query("DELETE FROM Series")
    suspend fun clearSeries()

    @Query("DELETE FROM Book")
    suspend fun clearBooks()

    /** 扫描收尾：删掉消失的书，整表替换 Series（Series 无用户状态，可整体重建）。 */
    @Transaction
    suspend fun finishScan(removedBookIds: List<String>, series: List<Series>) {
        removedBookIds.chunked(500).forEach { deleteBooks(it) }
        clearSeries()
        upsertSeries(series)
    }

    @Query("SELECT * FROM Highlight WHERE bookId = :bookId ORDER BY id")   // 定序：后划的画在上面、长按先命中它
    fun observeHighlights(bookId: String): Flow<List<Highlight>>

    /** 全部划线：导出到书库根目录的文本文件用（[dev.zhenlong.reader.data.HighlightExport]）。只订阅这一张表，翻页写进度不会触发。 */
    @Query("SELECT * FROM Highlight ORDER BY createdAt")
    fun observeAllHighlights(): Flow<List<Highlight>>

    @Query("SELECT * FROM Book WHERE id IN (:ids)")
    suspend fun booksByIds(ids: List<String>): List<Book>

    @Insert
    suspend fun insertHighlight(highlight: Highlight): Long

    @Query("UPDATE Highlight SET color = :color WHERE id = :id")
    suspend fun recolorHighlight(id: Long, color: Int)

    @Query("DELETE FROM Highlight WHERE id = :id")
    suspend fun deleteHighlight(id: Long)

    // 不随书一起删：书从书库里消失（换了目录、暂时拔了卡）再回来时 id 不变，划线还在
    @Transaction
    suspend fun clearAll() {
        clearBooks()
        clearSeries()
    }
}

@Database(entities = [Book::class, Series::class, Highlight::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        /** v2：Book 加 progress 列。只能加列不能重建：Book 里有阅读进度，不能靠重扫找回来。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Book ADD COLUMN progress REAL")
            }
        }

        /** v3：划线表。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `Highlight` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` TEXT NOT NULL, " +
                        "`document` TEXT NOT NULL, `startOffset` INTEGER NOT NULL, `endOffset` INTEGER NOT NULL, `color` INTEGER NOT NULL, " +
                        "`text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_Highlight_bookId` ON `Highlight` (`bookId`)")
            }
        }

        /** v4：划线记下所在章的标题（导出文本用）。 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Highlight ADD COLUMN chapterTitle TEXT")
            }
        }

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "library.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}
