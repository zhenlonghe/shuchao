package dev.zhenlong.reader.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
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

    @Transaction
    suspend fun clearAll() {
        clearBooks()
        clearSeries()
    }
}

@Database(entities = [Book::class, Series::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        /** v2：Book 加 progress 列。只能加列不能重建：Book 里有阅读进度，不能靠重扫找回来。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Book ADD COLUMN progress REAL")
            }
        }

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "library.db").addMigrations(MIGRATION_1_2).build()
    }
}
