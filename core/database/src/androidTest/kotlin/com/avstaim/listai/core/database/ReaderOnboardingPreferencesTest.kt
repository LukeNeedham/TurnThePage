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
class ReaderOnboardingPreferencesTest {
    @Test fun completionSurvivesReopenAndDoesNotChangeBookOrReadingSettings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "onboarding-${UUID.randomUUID()}.preferences_pb")
        var job = SupervisorJob()
        fun open() = AppPreferences(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file }))
        var preferences = open()
        try {
            assertFalse(preferences.readerOnboardingCompleted.first())
            preferences.selectBook("currently-reading")
            preferences.setReaderPreferences(ReaderPreferences(22f, ReaderTheme.DARK))
            preferences.completeReaderOnboarding()
            // Completion is idempotent for Skip, Back and replay completion.
            preferences.completeReaderOnboarding()
            job.cancelAndJoin()
            job = SupervisorJob()
            preferences = open()
            assertTrue(preferences.readerOnboardingCompleted.first())
            assertEquals("currently-reading", preferences.lastBook.first())
            assertEquals(ReaderPreferences(22f, ReaderTheme.DARK), preferences.reader.first())
            preferences.selectBook("another-book")
            assertTrue(preferences.readerOnboardingCompleted.first())
        } finally {
            job.cancelAndJoin()
            file.delete()
        }
    }
}
