package com.avstaim.listai.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.avstaim.listai.core.model.*
import kotlinx.coroutines.flow.Flow

data class StoredProgress(
    val blockId: String? = null,
    val charOffset: Int = 0,
    val globalOffset: Long = 0,
    val cover: String = CoverPosition.OPEN.name,
    val fraction: Float = 0f,
    val tocEntryIndex: Int? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val tocCharOffset: Int = 0,
) {
    fun toModel() = ReadingProgress(blockId?.let { BookLocation(BlockId(it), charOffset, globalOffset) },
        CoverPosition.entries.firstOrNull { it.name == cover } ?: CoverPosition.OPEN, fraction,
        tocEntryIndex, tocCharOffset)

    companion object {
        fun from(value: ReadingProgress) = StoredProgress(value.location?.blockId?.value,
            value.location?.charOffset ?: 0, value.location?.fallbackGlobalOffset ?: 0,
            value.cover.name, value.fraction, value.tocEntryIndex, value.tocCharOffset)
    }
}

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val authors: String,
    val fileName: String,
    val coverName: String?,
    val importedAt: Long,
    val lastOpenedAt: Long? = null,
    val totalCharacters: Long,
    @Embedded val progress: StoredProgress = StoredProgress(),
    val deleting: Boolean = false,
)

@Entity(tableName = "bookmarks", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId"), Index(value = ["bookId", "blockId", "charOffset"], unique = true)])
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val blockId: String,
    val charOffset: Int,
    val globalOffset: Long,
    val createdAt: Long,
    val snippet: String,
) {
    fun toModel() = Bookmark(id, BookId(bookId), BookLocation(BlockId(blockId), charOffset, globalOffset), createdAt, snippet)
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM books WHERE deleting = 0 ORDER BY lastOpenedAt DESC, importedAt DESC, id ASC")
    fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books")
    suspend fun allBooks(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun book(id: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: BookEntity): Long

    @Query("UPDATE books SET blockId = :blockId, charOffset = :charOffset, globalOffset = :globalOffset, cover = :cover, fraction = :fraction, tocEntryIndex = :tocEntryIndex, tocCharOffset = :tocCharOffset WHERE id = :id AND deleting = 0")
    suspend fun saveProgress(id: String, blockId: String?, charOffset: Int, globalOffset: Long, cover: String, fraction: Float,
        tocEntryIndex: Int? = null, tocCharOffset: Int = 0)

    @Query("UPDATE books SET lastOpenedAt = :time WHERE id = :id AND deleting = 0")
    suspend fun markOpened(id: String, time: Long)

    @Query("UPDATE books SET deleting = 1 WHERE id = :id")
    suspend fun markDeleting(id: String)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC, id ASC")
    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId AND id = :id")
    suspend fun deleteBookmark(bookId: String, id: String)
}

@Database(entities = [BookEntity::class, BookmarkEntity::class], version = 2, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN tocEntryIndex INTEGER")
                db.execSQL("ALTER TABLE books ADD COLUMN tocCharOffset INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun open(context: Context, name: String = "library.db"): LibraryDatabase =
            Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2).build()
    }
}
