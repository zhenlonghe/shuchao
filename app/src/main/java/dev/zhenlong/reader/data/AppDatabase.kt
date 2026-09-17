package dev.zhenlong.reader.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM Book")
    fun observeBooks(): Flow<List<Book>>

    @Query("SELECT * FROM Series")
    fun observeSeries(): Flow<List<Series>>

    @Query("SELECT * FROM Book")
    suspend fun allBooks(): List<Book>

    @Query("SELECT * FROM Book WHERE id = :id")
    suspend fun book(id: String): Book?

    /** 两种阅读器都同时写 locatorJson 和 pageIndex（章节序号）：切换模式后能从差不多的位置接着读。 */
    @Query("UPDATE Book SET locatorJson = :locatorJson, pageIndex = :pageIndex, lastOpenedAt = :now WHERE id = :id")
    suspend fun saveLocator(id: String, locatorJson: String, pageIndex: Int?, now: Long)

    @Query("UPDATE Book SET pageIndex = :pageIndex, pageCount = :pageCount, locatorJson = :locatorJson, lastOpenedAt = :now WHERE id = :id")
    suspend fun saveMangaProgress(id: String, pageIndex: Int, pageCount: Int, locatorJson: String, now: Long)

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

@Database(entities = [Book::class, Series::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "library.db").build()
    }
}
