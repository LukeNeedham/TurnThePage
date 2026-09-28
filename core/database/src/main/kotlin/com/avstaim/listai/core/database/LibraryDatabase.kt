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
import androidx.room.Transaction
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
    val annotationBlockId: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val annotationCharOffset: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val annotationGlobalOffset: Long = 0,
    val titleBlockId: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val titleCharOffset: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val titleGlobalOffset: Long = 0,
) {
    fun toModel() = ReadingProgress(blockId?.let { BookLocation(BlockId(it), charOffset, globalOffset) },
        CoverPosition.entries.firstOrNull { it.name == cover } ?: CoverPosition.OPEN, fraction,
        tocEntryIndex, tocCharOffset,
        annotationBlockId?.let { BookLocation(BlockId(it), annotationCharOffset, annotationGlobalOffset) },
        titleBlockId?.let { BookLocation(BlockId(it), titleCharOffset, titleGlobalOffset) })

    companion object {
        fun from(value: ReadingProgress) = StoredProgress(value.location?.blockId?.value,
            value.location?.charOffset ?: 0, value.location?.fallbackGlobalOffset ?: 0,
            value.cover.name, value.fraction, value.tocEntryIndex, value.tocCharOffset,
            value.annotationLocation?.blockId?.value, value.annotationLocation?.charOffset ?: 0,
            value.annotationLocation?.fallbackGlobalOffset ?: 0,
            value.titleLocation?.blockId?.value, value.titleLocation?.charOffset ?: 0,
            value.titleLocation?.fallbackGlobalOffset ?: 0)
    }
}

@Entity(tableName = "books", foreignKeys = [ForeignKey(entity = StackEntity::class,
    parentColumns = ["id"], childColumns = ["stackId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("stackId")])
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
    @androidx.room.ColumnInfo(defaultValue = "0") val shelfOrder: Long = 0,
    val stackId: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val stackOrder: Long = 0,
    @androidx.room.ColumnInfo(defaultValue = "'[]'") val authorNamesJson: String = "[]",
    val series: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "'[]'") val genresJson: String = "[]",
    /** 0 pending, 1 complete (including absent metadata), 2 unreadable; cancellation stays pending. */
    @androidx.room.ColumnInfo(defaultValue = "0") val metadataState: Int = 0,
)

@Entity(tableName = "book_stacks")
data class StackEntity(@PrimaryKey val id: String, val name: String, val shelfOrder: Long)

/** A single SQL snapshot avoids combining separately emitted book and stack flows. */
data class CatalogBookRow(
    @Embedded val book: BookEntity,
    val stackName: String?,
    val stackShelfOrder: Long?,
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
    @Query("SELECT * FROM books WHERE deleting = 0 ORDER BY shelfOrder ASC, importedAt DESC, id ASC")
    fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT books.*, book_stacks.name AS stackName, book_stacks.shelfOrder AS stackShelfOrder FROM books LEFT JOIN book_stacks ON books.stackId = book_stacks.id WHERE books.deleting = 0 ORDER BY books.shelfOrder ASC, books.importedAt DESC, books.id ASC")
    fun observeCatalog(): Flow<List<CatalogBookRow>>

    @Query("SELECT * FROM book_stacks ORDER BY shelfOrder, id")
    suspend fun allStacks(): List<StackEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStack(stack: StackEntity): Long

    @Query("UPDATE book_stacks SET name = :name, shelfOrder = :order WHERE id = :id")
    suspend fun updateStack(id: String, name: String, order: Long)

    @Query("UPDATE books SET stackId = :stackId, stackOrder = :stackOrder, shelfOrder = :shelfOrder WHERE id = :id AND deleting = 0")
    suspend fun setPlacement(id: String, stackId: String?, stackOrder: Long, shelfOrder: Long)

    @Query("DELETE FROM book_stacks WHERE NOT EXISTS (SELECT 1 FROM books WHERE books.stackId = book_stacks.id AND books.deleting = 0)")
    suspend fun deleteEmptyStacks()

    @Query("SELECT MIN(position) FROM (SELECT shelfOrder AS position FROM books WHERE deleting = 0 AND stackId IS NULL UNION ALL SELECT shelfOrder AS position FROM book_stacks)")
    suspend fun firstShelfPosition(): Long?

    @Query("SELECT * FROM books WHERE deleting = 0 AND metadataState = 0 ORDER BY importedAt, id LIMIT :limit")
    suspend fun pendingMetadata(limit: Int): List<BookEntity>

    @Query("UPDATE books SET authorNamesJson = :authors, series = :series, genresJson = :genres, metadataState = :state WHERE id = :id AND deleting = 0")
    suspend fun saveMetadata(id: String, authors: String, series: String?, genres: String, state: Int)

    @Query("UPDATE books SET metadataState = 2 WHERE id = :id AND deleting = 0 AND metadataState = 0")
    suspend fun markMetadataUnreadable(id: String)

    @Transaction
    suspend fun changeShelf(transform: (ShelfTopology) -> ShelfTopology): ShelfTopology {
        val books = orderedBooks()
        val stacks = allStacks()
        val root = books.filter { it.stackId == null }.map { Triple(ShelfTopology.bookKey(it.id), it.shelfOrder, it.importedAt) } +
            stacks.map { Triple(ShelfTopology.stackKey(it.id), it.shelfOrder, Long.MIN_VALUE) }
        val before = ShelfTopology(
            root.sortedWith(compareBy<Triple<String, Long, Long>> { it.second }.thenByDescending { it.third }.thenBy { it.first }).map { it.first },
            stacks.map { stack -> ShelfStack(stack.id, stack.name,
                books.filter { it.stackId == stack.id }.sortedWith(compareBy<BookEntity> { it.stackOrder }.thenBy { it.id }).map { it.id }) },
            books.mapTo(mutableSetOf()) { it.id },
        ).normalized()
        val after = transform(before).normalized()
        require(after.books == before.books) { "Shelf mutations cannot add or delete books" }
        if (after == before) return before
        val positions = after.shelf.withIndex().associate { it.value to it.index.toLong() }
        after.stacks.forEach { stack ->
            val position = requireNotNull(positions[ShelfTopology.stackKey(stack.id)])
            insertStack(StackEntity(stack.id, stack.name, position))
            updateStack(stack.id, stack.name, position)
            stack.bookIds.forEachIndexed { index, id -> setPlacement(id, stack.id, index.toLong(), position) }
        }
        after.shelf.forEachIndexed { index, key ->
            if (key.startsWith("book:")) setPlacement(key.removePrefix("book:"), null, 0, index.toLong())
        }
        deleteEmptyStacks()
        return before
    }

    @Query("SELECT * FROM books WHERE deleting = 0 ORDER BY shelfOrder ASC, importedAt DESC, id ASC")
    suspend fun orderedBooks(): List<BookEntity>

    @Query("UPDATE books SET shelfOrder = :position WHERE id = :id AND deleting = 0")
    suspend fun setShelfOrder(id: String, position: Long)

    @Transaction
    suspend fun moveBook(id: String, targetId: String) {
        changeShelf { topology ->
            val stack = topology.stacks.firstOrNull { id in it.bookIds && targetId in it.bookIds }
            if (stack != null) topology.moveStackBook(stack.id, id, targetId)
            else topology.moveShelfItem(ShelfTopology.bookKey(id), ShelfTopology.bookKey(targetId))
        }
    }

    @Query("SELECT * FROM books")
    suspend fun allBooks(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun book(id: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: BookEntity): Long

    @Query("UPDATE books SET blockId = :blockId, charOffset = :charOffset, globalOffset = :globalOffset, cover = :cover, fraction = :fraction, tocEntryIndex = :tocEntryIndex, tocCharOffset = :tocCharOffset, annotationBlockId = :annotationBlockId, annotationCharOffset = :annotationCharOffset, annotationGlobalOffset = :annotationGlobalOffset, titleBlockId = :titleBlockId, titleCharOffset = :titleCharOffset, titleGlobalOffset = :titleGlobalOffset WHERE id = :id AND deleting = 0")
    suspend fun saveProgress(id: String, blockId: String?, charOffset: Int, globalOffset: Long, cover: String, fraction: Float,
        tocEntryIndex: Int? = null, tocCharOffset: Int = 0,
        annotationBlockId: String? = null, annotationCharOffset: Int = 0, annotationGlobalOffset: Long = 0,
        titleBlockId: String? = null, titleCharOffset: Int = 0, titleGlobalOffset: Long = 0)

    @Query("UPDATE books SET lastOpenedAt = :time WHERE id = :id AND deleting = 0")
    suspend fun markOpened(id: String, time: Long)

    @Query("UPDATE books SET deleting = 1 WHERE id = :id")
    suspend fun markBookDeleting(id: String)

    @Transaction
    suspend fun markDeleting(id: String) { markBookDeleting(id); deleteEmptyStacks() }

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteRow(id: String)

    @Transaction
    suspend fun delete(id: String) { deleteRow(id); deleteEmptyStacks() }

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC, id ASC")
    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId AND id = :id")
    suspend fun deleteBookmark(bookId: String, id: String)
}

@Database(entities = [BookEntity::class, BookmarkEntity::class, StackEntity::class], version = 5, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN tocEntryIndex INTEGER")
                db.execSQL("ALTER TABLE books ADD COLUMN tocCharOffset INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN annotationBlockId TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN annotationCharOffset INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN annotationGlobalOffset INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN shelfOrder INTEGER NOT NULL DEFAULT 0")
                // Freeze the existing visible shelf order without changing reading state.
                val ids = mutableListOf<String>()
                db.query("SELECT id FROM books ORDER BY lastOpenedAt DESC, importedAt DESC, id ASC").use { cursor ->
                    while (cursor.moveToNext()) ids += cursor.getString(0)
                }
                ids.forEachIndexed { index, id ->
                    db.execSQL("UPDATE books SET shelfOrder = ? WHERE id = ?", arrayOf<Any>(index, id))
                }
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS book_stacks (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, shelfOrder INTEGER NOT NULL)")
                db.execSQL("ALTER TABLE books ADD COLUMN stackId TEXT REFERENCES book_stacks(id) ON DELETE SET NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_books_stackId ON books(stackId)")
                db.execSQL("ALTER TABLE books ADD COLUMN stackOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN authorNamesJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE books ADD COLUMN series TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN genresJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE books ADD COLUMN metadataState INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN titleBlockId TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN titleCharOffset INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN titleGlobalOffset INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun open(context: Context, name: String = "library.db"): LibraryDatabase =
            Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
}
