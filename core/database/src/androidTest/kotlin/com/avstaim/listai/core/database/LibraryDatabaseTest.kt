package com.avstaim.listai.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryDatabaseTest {
    @Test fun allCoverPosesAndUtf16SemanticAnchorsSurviveDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "poses-${UUID.randomUUID()}.db"
        var database = LibraryDatabase.open(context, name)
        try {
            CoverPosition.entries.forEachIndexed { index, pose ->
                val location = BookLocation(BlockId("блок-😀"), 17 + index, 9123L + index)
                val progress = ReadingProgress(location, pose, index / 3f)
                database.library().insert(BookEntity(pose.name, "Book", "Author", "book-$index.book", null,
                    42, totalCharacters = 10000, progress = StoredProgress.from(progress)))
            }
            database.close()
            database = LibraryDatabase.open(context, name)
            val rows = database.library().observeBooks().first()
            CoverPosition.entries.forEachIndexed { index, pose ->
                val restored = rows.single { it.id == pose.name }.progress.toModel()
                assertEquals(pose, restored.cover)
                assertEquals(BookLocation(BlockId("блок-😀"), 17 + index, 9123L + index), restored.location)
                assertEquals(index / 3f, restored.fraction)
            }
        } finally { database.close(); context.deleteDatabase(name) }
        Unit
    }

    @Test fun preferencesClearOnlyDeletedSelectionAndKeepOneTimeInitialization() = runBlocking {
        val preferences = AppPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        preferences.markInitialized()
        preferences.selectBook("second")
        preferences.clearBookIfSelected("first")
        assertEquals("second", preferences.lastBook.first())
        preferences.clearBookIfSelected("second")
        assertNull(preferences.lastBook.first())
        assertTrue(preferences.initialized.first())
    }
}
