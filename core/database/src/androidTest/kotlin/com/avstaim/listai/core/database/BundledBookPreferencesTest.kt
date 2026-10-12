package com.avstaim.listai.core.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.model.ReaderPreferences
import com.avstaim.listai.core.model.ReaderTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledBookPreferencesTest {
    @Test fun languageReservationSurvivesInterruptedImportAndReopenWithoutChangingReaderState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "bundled-book-${UUID.randomUUID()}.preferences_pb")
        var job = SupervisorJob()
        fun open() = AppPreferences(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file }))
        var preferences = open()
        try {
            val reader = ReaderPreferences(23f, ReaderTheme.DARK)
            preferences.selectBook("user-book")
            preferences.setReaderPreferences(reader)
            assertEquals("de", preferences.claimBundledBookLanguage("de"))
            assertFalse(preferences.initialized.first())
            job.cancelAndJoin()
            job = SupervisorJob()
            preferences = open()
            assertEquals("de", preferences.bundledBookLanguage.first())
            assertEquals("de", preferences.claimBundledBookLanguage("fr"))
            assertEquals("user-book", preferences.lastBook.first())
            assertEquals(reader, preferences.reader.first())
            preferences.completeBundledBookImport("german-sample")
            job.cancelAndJoin()
            job = SupervisorJob()
            preferences = open()
            assertTrue(preferences.initialized.first())
            assertEquals("german-sample", preferences.lastBook.first())
            assertEquals("de", preferences.claimBundledBookLanguage("en"))
            assertEquals(reader, preferences.reader.first())
            preferences.clearBookIfSelected("german-sample")
            assertTrue("Deletion must not reset one-time initialization", preferences.initialized.first())
        } finally {
            job.cancelAndJoin()
            file.delete()
        }
    }
}
