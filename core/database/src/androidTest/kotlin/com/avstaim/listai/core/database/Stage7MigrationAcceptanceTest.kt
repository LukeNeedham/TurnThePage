package com.avstaim.listai.core.database

import android.content.Context
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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opens an actual stage-6 database, not a database created with the current entities. */
@RunWith(AndroidJUnit4::class)
class Stage7MigrationAcceptanceTest {
    @Test
    fun upgradePreservesEveryCoverPoseAndBookmarksThenPersistsContentsAnchor() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "stage7-upgrade-${UUID.randomUUID()}.db"
        createStage6Database(context, name)
        var database = LibraryDatabase.open(context, name)
        try {
            val restored = database.library().observeBooks().first()
            assertEquals(CoverPosition.entries.size, restored.size)
            CoverPosition.entries.forEachIndexed { index, pose ->
                val row = restored.single { it.id == pose.name }
                assertEquals("Книга $index — Ω", row.title)
                assertEquals("Автор 😀", row.authors)
                assertEquals("legacy-$index.book", row.fileName)
                assertEquals("legacy-$index.cover.jpg", row.coverName)
                assertEquals(1_234L, row.importedAt)
                assertEquals(9_000L + index, row.lastOpenedAt)
                assertEquals(50_000L, row.totalCharacters)
                assertEquals(oldCheckpoint(index, pose), row.progress.toModel())
                assertNull(row.progress.toModel().tocEntryIndex)
                assertEquals(0, row.progress.toModel().tocCharOffset)
                val bookmark = database.library().observeBookmarks(pose.name).first().single()
                assertEquals("bookmark-$index", bookmark.id)
                assertEquals("Фрагмент 😀 с исходной страницы", bookmark.snippet)
                assertEquals(oldCheckpoint(index, pose).location, bookmark.toModel().location)
                assertEquals(8_000L + index, bookmark.createdAt)
            }

            val original = requireNotNull(restored.single { it.id == CoverPosition.OPEN.name }.progress.toModel().location)
            val inContents = ReadingProgress(original, CoverPosition.OPEN, .25f,
                tocEntryIndex = 37, tocCharOffset = 23,
                annotationLocation = BookLocation(BlockId("frontmatter-😀"), 11, 777L))
            database.library().saveProgress(CoverPosition.OPEN.name, original.blockId.value,
                original.charOffset, original.fallbackGlobalOffset, inContents.cover.name,
                inContents.fraction, inContents.tocEntryIndex, inContents.tocCharOffset,
                inContents.annotationLocation?.blockId?.value,
                inContents.annotationLocation?.charOffset ?: 0,
                inContents.annotationLocation?.fallbackGlobalOffset ?: 0)
            database.close()
            database = LibraryDatabase.open(context, name)
            assertEquals(inContents, database.library().book(CoverPosition.OPEN.name)!!.progress.toModel())

            // The migration must also retain uniqueness and the foreign-key deletion behavior.
            database.library().insertBookmark(BookmarkEntity("duplicate", CoverPosition.OPEN.name,
                original.blockId.value, original.charOffset, original.fallbackGlobalOffset, 99_000, "Duplicate"))
            assertEquals(1, database.library().observeBookmarks(CoverPosition.OPEN.name).first().size)
            database.library().delete(CoverPosition.OPEN.name)
            assertTrue(database.library().observeBookmarks(CoverPosition.OPEN.name).first().isEmpty())
            assertEquals(CoverPosition.entries.size - 1, database.library().observeBooks().first().size)
            assertEquals(1, database.library().observeBookmarks(CoverPosition.BACK.name).first().size)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
        Unit
    }

    private fun oldCheckpoint(index: Int, pose: CoverPosition) = ReadingProgress(
        BookLocation(BlockId("исходный-блок-😀-$index"), 21 + index, 12_345L + index), pose, index / 3f,
    )

    private fun createStage6Database(context: Context, name: String) {
        val path = context.getDatabasePath(name)
        check(path.parentFile!!.isDirectory || path.parentFile!!.mkdirs())
        SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
            // Frozen SQL and identity from the committed-to-source schema version 1 export.
            database.execSQL("""CREATE TABLE IF NOT EXISTS books (
                id TEXT NOT NULL, title TEXT NOT NULL, authors TEXT NOT NULL,
                fileName TEXT NOT NULL, coverName TEXT, importedAt INTEGER NOT NULL,
                lastOpenedAt INTEGER, totalCharacters INTEGER NOT NULL, deleting INTEGER NOT NULL,
                blockId TEXT, charOffset INTEGER NOT NULL, globalOffset INTEGER NOT NULL,
                cover TEXT NOT NULL, fraction REAL NOT NULL, PRIMARY KEY(id))""")
            database.execSQL("""CREATE TABLE IF NOT EXISTS bookmarks (
                id TEXT NOT NULL, bookId TEXT NOT NULL, blockId TEXT NOT NULL,
                charOffset INTEGER NOT NULL, globalOffset INTEGER NOT NULL,
                createdAt INTEGER NOT NULL, snippet TEXT NOT NULL, PRIMARY KEY(id),
                FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
            database.execSQL("CREATE INDEX index_bookmarks_bookId ON bookmarks(bookId)")
            database.execSQL("CREATE UNIQUE INDEX index_bookmarks_bookId_blockId_charOffset ON bookmarks(bookId, blockId, charOffset)")
            database.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            database.execSQL("INSERT INTO room_master_table (id,identity_hash) VALUES(42, '6a7363dc7af00be8fc960b5791cd4f80')")
            CoverPosition.entries.forEachIndexed { index, pose ->
                val checkpoint = oldCheckpoint(index, pose)
                val location = requireNotNull(checkpoint.location)
                database.execSQL("INSERT INTO books VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(
                    pose.name, "Книга $index — Ω", "Автор 😀", "legacy-$index.book",
                    "legacy-$index.cover.jpg", 1_234L, 9_000L + index, 50_000L, 0,
                    location.blockId.value, location.charOffset, location.fallbackGlobalOffset,
                    pose.name, checkpoint.fraction,
                ))
                database.execSQL("INSERT INTO bookmarks VALUES(?,?,?,?,?,?,?)", arrayOf(
                    "bookmark-$index", pose.name, location.blockId.value, location.charOffset,
                    location.fallbackGlobalOffset, 8_000L + index, "Фрагмент 😀 с исходной страницы",
                ))
            }
            database.version = 1
        }
    }
}
