package com.avstaim.listai.core.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.model.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Every historical starting version is created with its own SQL, never with current entities. */
@RunWith(AndroidJUnit4::class)
class V12CatalogMigrationTest {
    @Test fun allUpgradeChainsPreserveFilesAnchorsBookmarksAndManualShelfThenReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (version in 1..4) {
            val name = "v12-from-$version-${UUID.randomUUID()}.db"
            val file = context.getDatabasePath(name)
            file.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db -> createLegacy(db, version) }
            var database = LibraryDatabase.open(context, name)
            try {
                val dao = database.library()
                val ids = dao.orderedBooks().map { it.id }
                assertEquals(if (version == 4) listOf("b", "a") else listOf("a", "b"), ids)
                val original = requireNotNull(dao.book("a"))
                assertEquals("a.book", original.fileName)
                assertEquals("a.jpg", original.coverName)
                assertEquals("Глава 😀", original.title)
                assertEquals(0, original.metadataState)
                assertEquals("[]", original.authorNamesJson)
                assertNull(original.progress.titleBlockId)
                assertEquals(ReadingProgress(BookLocation(BlockId("body"), 7, 107), CoverPosition.OPEN, .4f,
                    tocEntryIndex = if (version >= 2) 2 else null, tocCharOffset = if (version >= 2) 4 else 0,
                    annotationLocation = if (version >= 3) BookLocation(BlockId("description"), 9, 29) else null), original.progress.toModel())
                val mark = dao.observeBookmarks("a").first().single()
                assertEquals("a-mark", mark.id)
                assertEquals("Фрагмент 😀", mark.snippet)

                dao.changeShelf { it.createStack("s", "Моя серия", listOf("b", "a")) }
                dao.saveMetadata("a", "[\"Автор, первый\",\"Второй\"]", "Серия", "[\"adventure\"]", 1)
                val progress = original.progress.toModel().copy(annotationLocation = null, tocEntryIndex = null,
                    tocCharOffset = 0, titleLocation = BookLocation(BlockId("title"), 12, 20))
                val stored = StoredProgress.from(progress)
                dao.saveProgress("a", stored.blockId, stored.charOffset, stored.globalOffset, stored.cover,
                    stored.fraction, stored.tocEntryIndex, stored.tocCharOffset, stored.annotationBlockId,
                    stored.annotationCharOffset, stored.annotationGlobalOffset, stored.titleBlockId,
                    stored.titleCharOffset, stored.titleGlobalOffset)
                assertEquals(progress, dao.book("a")!!.progress.toModel())
                assertEquals(setOf("s"), dao.observeCatalog().first().map { it.book.stackId }.toSet())
                database.close()
                database = LibraryDatabase.open(context, name)
                assertEquals(progress, database.library().book("a")!!.progress.toModel())
                assertEquals("Серия", database.library().book("a")!!.series)
                assertEquals(mark, database.library().observeBookmarks("a").first().single())
                assertEquals("Моя серия", database.library().allStacks().single().name)
                assertEquals(listOf("b", "a"), database.library().orderedBooks().sortedBy { it.stackOrder }.map { it.id })
                database.library().markDeleting("a")
                assertEquals(1, database.library().allStacks().size)
                database.library().delete("a")
                assertTrue(database.library().observeBookmarks("a").first().isEmpty())
                database.library().delete("b")
                assertTrue(database.library().allStacks().isEmpty())
            } finally { database.close(); context.deleteDatabase(name) }
        }
    }

    private fun createLegacy(db: SQLiteDatabase, version: Int) {
        db.execSQL("""CREATE TABLE books (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, authors TEXT NOT NULL,
            fileName TEXT NOT NULL, coverName TEXT, importedAt INTEGER NOT NULL, lastOpenedAt INTEGER,
            totalCharacters INTEGER NOT NULL, deleting INTEGER NOT NULL, blockId TEXT, charOffset INTEGER NOT NULL,
            globalOffset INTEGER NOT NULL, cover TEXT NOT NULL, fraction REAL NOT NULL)""")
        db.execSQL("""CREATE TABLE bookmarks (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL,
            blockId TEXT NOT NULL, charOffset INTEGER NOT NULL, globalOffset INTEGER NOT NULL,
            createdAt INTEGER NOT NULL, snippet TEXT NOT NULL,
            FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX index_bookmarks_bookId ON bookmarks(bookId)")
        db.execSQL("CREATE UNIQUE INDEX index_bookmarks_bookId_blockId_charOffset ON bookmarks(bookId, blockId, charOffset)")
        if (version >= 2) {
            db.execSQL("ALTER TABLE books ADD COLUMN tocEntryIndex INTEGER")
            db.execSQL("ALTER TABLE books ADD COLUMN tocCharOffset INTEGER NOT NULL DEFAULT 0")
        }
        if (version >= 3) {
            db.execSQL("ALTER TABLE books ADD COLUMN annotationBlockId TEXT")
            db.execSQL("ALTER TABLE books ADD COLUMN annotationCharOffset INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE books ADD COLUMN annotationGlobalOffset INTEGER NOT NULL DEFAULT 0")
        }
        if (version >= 4) db.execSQL("ALTER TABLE books ADD COLUMN shelfOrder INTEGER NOT NULL DEFAULT 0")
        listOf("a", "b").forEachIndexed { index, id ->
            db.insertOrThrow("books", null, ContentValues().apply {
                put("id", id); put("title", "Глава 😀"); put("authors", "Автор, первый")
                put("fileName", "$id.book"); put("coverName", "$id.jpg"); put("importedAt", 10L + index)
                put("lastOpenedAt", 200L - index); put("totalCharacters", 1000L); put("deleting", 0)
                put("blockId", "body"); put("charOffset", 7); put("globalOffset", 107L)
                put("cover", CoverPosition.OPEN.name); put("fraction", .4f)
                if (version >= 2) { put("tocEntryIndex", 2); put("tocCharOffset", 4) }
                if (version >= 3) {
                    put("annotationBlockId", "description"); put("annotationCharOffset", 9); put("annotationGlobalOffset", 29L)
                }
                if (version >= 4) put("shelfOrder", 1L - index)
            })
        }
        db.execSQL("INSERT INTO bookmarks VALUES ('a-mark','a','body',7,107,123,'Фрагмент 😀')")
        db.version = version
    }
}
