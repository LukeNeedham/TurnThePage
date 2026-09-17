package com.avstaim.listai.core.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.CoverPosition
import com.avstaim.listai.core.model.ReadingProgress
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production Room builder from the frozen version-2 schema to RC1. */
@RunWith(AndroidJUnit4::class)
class Rc1MigrationTest {
    @Test
    fun v2ToV3KeepsBooksProgressBookmarksAndNewAnnotationAnchorAcrossReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "rc1-v2-upgrade-${UUID.randomUUID()}.db"
        createVersion2Database(context, name)
        var database = LibraryDatabase.open(context, name)
        try {
            // The production flow intentionally orders by lastOpenedAt. Make the
            // fixture assertions deterministic without coupling this migration
            // test to that presentation order.
            val books = database.library().observeBooks().first().sortedBy { it.id }
            assertEquals(2, books.size)
            books.forEachIndexed { index, row ->
                assertEquals("v2-book-$index", row.id)
                assertEquals("V2 book $index", row.title)
                assertEquals("Author $index", row.authors)
                assertEquals(10_000L + index, row.importedAt)
                assertEquals(20_000L + index, row.lastOpenedAt)
                assertEquals(80_000L, row.totalCharacters)
                assertEquals(
                    ReadingProgress(
                        location = BookLocation(BlockId("source-$index"), 100 + index, 3_000L + index),
                        cover = CoverPosition.entries[index],
                        fraction = .2f + index / 10f,
                        tocEntryIndex = 4 + index,
                        tocCharOffset = 9 + index,
                    ),
                    row.progress.toModel(),
                )
                val bookmark = database.library().observeBookmarks(row.id).first().single()
                assertEquals("v2-bookmark-$index", bookmark.id)
                assertEquals(BookLocation(BlockId("source-$index"), 100 + index, 3_000L + index), bookmark.toModel().location)
                assertEquals("Saved from v2", bookmark.snippet)
            }

            // A v2 row must receive a nullable annotation column, not a replacement source anchor.
            assertNull(books.first().progress.toModel().annotationLocation)
            val source = requireNotNull(books.first().progress.toModel().location)
            val migrated = ReadingProgress(
                location = source,
                cover = CoverPosition.FRONT,
                fraction = .75f,
                tocEntryIndex = 12,
                tocCharOffset = 31,
                annotationLocation = BookLocation(BlockId("annotation-😀"), 27, 9_876L),
            )
            val annotation = requireNotNull(migrated.annotationLocation)
            database.library().saveProgress(
                id = books.first().id,
                blockId = source.blockId.value,
                charOffset = source.charOffset,
                globalOffset = source.fallbackGlobalOffset,
                cover = migrated.cover.name,
                fraction = migrated.fraction,
                tocEntryIndex = migrated.tocEntryIndex,
                tocCharOffset = migrated.tocCharOffset,
                annotationBlockId = annotation.blockId.value,
                annotationCharOffset = annotation.charOffset,
                annotationGlobalOffset = annotation.fallbackGlobalOffset,
            )
            database.close()
            database = LibraryDatabase.open(context, name)

            assertEquals(migrated, database.library().book(books.first().id)!!.progress.toModel())
            assertNotNull(database.library().observeBookmarks(books.first().id).first().single())
            assertEquals(2, database.library().allBooks().size)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
        Unit
    }

    private fun createVersion2Database(context: android.content.Context, name: String) {
        val path = context.getDatabasePath(name)
        check(path.parentFile!!.isDirectory || path.parentFile!!.mkdirs())
        SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
            // Frozen v2 SQL: this is intentionally not built from the current Room entities.
            database.execSQL("""CREATE TABLE books (
                id TEXT NOT NULL, title TEXT NOT NULL, authors TEXT NOT NULL,
                fileName TEXT NOT NULL, coverName TEXT, importedAt INTEGER NOT NULL,
                lastOpenedAt INTEGER, totalCharacters INTEGER NOT NULL, deleting INTEGER NOT NULL,
                blockId TEXT, charOffset INTEGER NOT NULL, globalOffset INTEGER NOT NULL,
                cover TEXT NOT NULL, fraction REAL NOT NULL,
                tocEntryIndex INTEGER, tocCharOffset INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(id))""")
            database.execSQL("""CREATE TABLE bookmarks (
                id TEXT NOT NULL, bookId TEXT NOT NULL, blockId TEXT NOT NULL,
                charOffset INTEGER NOT NULL, globalOffset INTEGER NOT NULL,
                createdAt INTEGER NOT NULL, snippet TEXT NOT NULL, PRIMARY KEY(id),
                FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
            database.execSQL("CREATE INDEX index_bookmarks_bookId ON bookmarks(bookId)")
            database.execSQL("CREATE UNIQUE INDEX index_bookmarks_bookId_blockId_charOffset ON bookmarks(bookId, blockId, charOffset)")
            database.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            database.execSQL("INSERT INTO room_master_table (id,identity_hash) VALUES(42, '46399c8eab0d8ac04617d22e438cdc2d')")
            CoverPosition.entries.take(2).forEachIndexed { index, pose ->
                database.execSQL(
                    "INSERT INTO books VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf(
                        "v2-book-$index", "V2 book $index", "Author $index", "v2-$index.book", null,
                        10_000L + index, 20_000L + index, 80_000L, 0,
                        "source-$index", 100 + index, 3_000L + index, pose.name, .2f + index / 10f,
                        4 + index, 9 + index,
                    ),
                )
                database.execSQL(
                    "INSERT INTO bookmarks VALUES(?,?,?,?,?,?,?)",
                    arrayOf("v2-bookmark-$index", "v2-book-$index", "source-$index", 100 + index,
                        3_000L + index, 30_000L + index, "Saved from v2"),
                )
            }
            // Explicitly freeze the input at schema version 2 so Room must run 2 -> 3.
            database.version = 2
        }
    }
}
