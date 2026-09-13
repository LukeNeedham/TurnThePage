package com.avstaim.listai.core.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.model.ReaderPreferences
import com.avstaim.listai.core.model.ReaderTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderPreferencesTest {
    @Test fun lightIsDefaultAndReaderSettingsSurviveARealDataStoreReopenAlongsideSelection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "settings-${UUID.randomUUID()}.preferences_pb")
        var job = SupervisorJob()
        fun open() = AppPreferences(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file }))
        var preferences = open()
        try {
            assertEquals(ReaderPreferences(18f, ReaderTheme.LIGHT), preferences.reader.first())
            preferences.markInitialized()
            preferences.selectBook("source-😀")
            preferences.setReaderPreferences(ReaderPreferences(25f, ReaderTheme.DARK))
            job.cancelAndJoin()
            job = SupervisorJob()
            preferences = open()
            assertEquals(ReaderPreferences(25f, ReaderTheme.DARK), preferences.reader.first())
            assertEquals("source-😀", preferences.lastBook.first())
            assertTrue(preferences.initialized.first())
            preferences.setReaderPreferences(ReaderPreferences(16f, ReaderTheme.SYSTEM))
            preferences.selectBook(null)
            job.cancelAndJoin()
            job = SupervisorJob()
            preferences = open()
            assertEquals(ReaderPreferences(16f, ReaderTheme.SYSTEM), preferences.reader.first())
            assertNull(preferences.lastBook.first())
            assertTrue(preferences.initialized.first())
        } finally { job.cancelAndJoin(); file.delete() }
    }

    @Test fun invalidStoredSettingsRecoverToLightWithoutClearingTheSelectedBook() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "invalid-settings-${UUID.randomUUID()}.preferences_pb")
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        try {
            store.edit {
                it[floatPreferencesKey("reader_font_size_sp")] = Float.NaN
                it[stringPreferencesKey("reader_theme")] = "UNKNOWN_FUTURE_VALUE"
                it[stringPreferencesKey("last_book")] = "original"
            }
            val preferences = AppPreferences(store)
            assertEquals(ReaderPreferences(), preferences.reader.first())
            assertEquals("original", preferences.lastBook.first())
        } finally { job.cancelAndJoin(); file.delete() }
    }
}
