package com.avstaim.listai.core.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.model.ReaderPinchMode
import com.avstaim.listai.core.model.ReaderPreferences
import com.avstaim.listai.core.model.ReaderScreenTimeoutMode
import com.avstaim.listai.core.model.ReaderTheme
import com.avstaim.listai.core.model.ShelfZoom
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
            assertEquals(ShelfZoom.MEDIUM, preferences.shelfZoom.first())
            preferences.markInitialized()
            preferences.selectBook("source-😀")
            preferences.completeReaderOnboarding()
            preferences.claimBundledBookLanguage("it")
            val system = ReaderPreferences(25f, ReaderTheme.DARK, showClock = false, pinchMode = ReaderPinchMode.TEMPORARY_ZOOM,
                screenTimeoutMode = ReaderScreenTimeoutMode.SYSTEM)
            preferences.setReaderPreferences(system)
            preferences.setShelfZoom(ShelfZoom.SMALL)
            job.cancelAndJoin()
            job = SupervisorJob()
            preferences = open()
            assertEquals(system, preferences.reader.first())
            assertEquals(ShelfZoom.SMALL, preferences.shelfZoom.first())
            assertEquals("source-😀", preferences.lastBook.first())
            assertTrue(preferences.initialized.first())
            assertTrue(preferences.readerOnboardingCompleted.first())
            assertEquals("it", preferences.bundledBookLanguage.first())
            val always = ReaderPreferences(16f, ReaderTheme.SYSTEM, pinchMode = ReaderPinchMode.DISABLED,
                screenTimeoutMode = ReaderScreenTimeoutMode.ALWAYS_ON)
            preferences.setReaderPreferences(always)
            preferences.selectBook(null)
            job.cancelAndJoin()
            job = SupervisorJob()
            preferences = open()
            assertEquals(always, preferences.reader.first())
            assertEquals(ShelfZoom.SMALL, preferences.shelfZoom.first())
            assertNull(preferences.lastBook.first())
            assertTrue(preferences.initialized.first())
            assertTrue(preferences.readerOnboardingCompleted.first())
            assertEquals("it", preferences.bundledBookLanguage.first())
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
                it[stringPreferencesKey("reader_pinch_mode")] = "UNKNOWN_FUTURE_VALUE"
                it[stringPreferencesKey("last_book")] = "original"
                it[stringPreferencesKey("shelf_zoom")] = "UNKNOWN_FUTURE_VALUE"
                it[stringPreferencesKey("reader_screen_timeout_mode")] = "UNKNOWN_FUTURE_VALUE"
                it[booleanPreferencesKey("initialized")] = true
                it[booleanPreferencesKey("reader_onboarding_completed")] = true
                it[stringPreferencesKey("bundled_book_language")] = "fr"
            }
            val preferences = AppPreferences(store)
            assertEquals(ReaderPreferences(), preferences.reader.first())
            assertEquals(ShelfZoom.MEDIUM, preferences.shelfZoom.first())
            assertEquals("original", preferences.lastBook.first())
            assertTrue(preferences.initialized.first())
            assertTrue(preferences.readerOnboardingCompleted.first())
            assertEquals("fr", preferences.bundledBookLanguage.first())
        } finally { job.cancelAndJoin(); file.delete() }
    }

    @Test fun legacyReaderSettingsGainScreenDefaultsWithoutResettingExistingState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "legacy-timeout-${UUID.randomUUID()}.preferences_pb")
        var job = SupervisorJob()
        fun open() = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        var store = open()
        try {
            // Existing installations have the original reader keys and no timeout keys.
            store.edit {
                it[floatPreferencesKey("reader_font_size_sp")] = 21f
                it[stringPreferencesKey("reader_theme")] = ReaderTheme.DARK.name
                it[booleanPreferencesKey("reader_show_clock")] = false
                it[stringPreferencesKey("last_book")] = "legacy-book"
                it[booleanPreferencesKey("initialized")] = true
                it[booleanPreferencesKey("reader_onboarding_completed")] = true
                it[stringPreferencesKey("bundled_book_language")] = "es"
            }
            job.cancelAndJoin()
            job = SupervisorJob()
            store = open()
            val preferences = AppPreferences(store)
            assertEquals(ReaderPreferences(21f, ReaderTheme.DARK, showClock = false), preferences.reader.first())
            assertEquals("legacy-book", preferences.lastBook.first())
            assertTrue(preferences.initialized.first())
            assertTrue(preferences.readerOnboardingCompleted.first())
            assertEquals("es", preferences.bundledBookLanguage.first())
        } finally { job.cancelAndJoin(); file.delete() }
    }

    @Test fun unknownScreenModeRecoversAfterARealDataStoreReopenWithoutResettingOtherState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "invalid-timeout-${UUID.randomUUID()}.preferences_pb")
        var job = SupervisorJob()
        fun open() = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        var store = open()
        val original = ReaderPreferences(22f, ReaderTheme.DARK, showClock = false,
            screenTimeoutMode = ReaderScreenTimeoutMode.SYSTEM)
        try {
            var preferences = AppPreferences(store)
            preferences.setReaderPreferences(original)
            preferences.selectBook("retain-selected-book")
            preferences.markInitialized()
            preferences.completeReaderOnboarding()
            preferences.claimBundledBookLanguage("de")
            preferences.setShelfZoom(ShelfZoom.LARGE)
            store.edit {
                it[stringPreferencesKey("reader_screen_timeout_mode")] = "UNKNOWN_FUTURE_MODE"
            }
            job.cancelAndJoin()
            job = SupervisorJob()
            store = open()
            preferences = AppPreferences(store)
            assertEquals(original.copy(screenTimeoutMode = ReaderScreenTimeoutMode.ALWAYS_ON),
                preferences.reader.first())
            assertEquals("retain-selected-book", preferences.lastBook.first())
            assertTrue(preferences.initialized.first())
            assertTrue(preferences.readerOnboardingCompleted.first())
            assertEquals("de", preferences.bundledBookLanguage.first())
            assertEquals(ShelfZoom.LARGE, preferences.shelfZoom.first())
        } finally { job.cancelAndJoin(); file.delete() }
    }
}
