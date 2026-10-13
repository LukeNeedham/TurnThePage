package com.avstaim.listai

import android.content.Context
import android.content.ContextWrapper
import android.app.LocaleManager
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.app.LocaleManagerCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.database.AppPreferences
import com.avstaim.listai.core.database.LibraryDatabase
import com.avstaim.listai.core.files.LibraryRepository
import com.avstaim.listai.core.files.PrivateBookStorage
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.CoverPosition
import com.avstaim.listai.core.model.ReadingProgress
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledBookImportTest {
    @Test fun interruptedImportRetriesTheReservedEditionAndKeepsProgressAndBookmarks() = runBlocking {
        fixture { context, repository, preferences ->
            // Simulate process death after Room publication, before the installation marker.
            preferences.claimBundledBookLanguage("de")
            val id = repository.import("sample.fb2") {
                context.resources.openRawResource(bundledBookResource("de"))
            }
            val opened = repository.open(id)
            val location = BookLocation(opened.document.blocks.first().id, 0, 0)
            val progress = ReadingProgress(location, CoverPosition.OPEN, 0.2f)
            repository.saveProgress(id, progress)
            repository.addBookmark(id, location, "A saved place")
            assertFalse(preferences.initialized.first())

            // A different system language on the next launch must not import another edition.
            importBundledReadingBookOnce(context, repository, preferences, Locale.FRENCH)
            assertEquals(id, repository.books.first().single().id)
            assertEquals(progress, repository.books.first().single().progress)
            assertEquals(location, repository.bookmarks(id).first().single().location)
            assertEquals("de", preferences.bundledBookLanguage.first())
            assertTrue(preferences.initialized.first())
            assertEquals(id.value, preferences.lastBook.first())

            importBundledReadingBookOnce(context, repository, preferences, Locale.ENGLISH)
            assertEquals(id, repository.books.first().single().id)
            repository.delete(id)
            importBundledReadingBookOnce(context, repository, preferences, Locale.forLanguageTag("es"))
            assertTrue("A deleted sample must stay deleted", repository.books.first().isEmpty())
        }
    }

    @Test fun freshUnsupportedSystemLanguageImportsEnglishOnceAndExistingInstallIsUntouched() = runBlocking {
        fixture { context, repository, preferences ->
            importBundledReadingBookOnce(context, repository, preferences, Locale.JAPANESE)
            val sample = repository.books.first().single()
            assertEquals("The Seventh Light", sample.title)
            assertEquals("en", repository.open(sample.id).document.metadata.language)
            importBundledReadingBookOnce(context, repository, preferences, Locale.forLanguageTag("pt-BR"))
            assertEquals(sample.id, repository.books.first().single().id)
        }
        fixture { context, repository, preferences ->
            // The legacy initialized flag must keep an existing installation, including an empty shelf.
            preferences.markInitialized()
            preferences.selectBook("previous-selection")
            importBundledReadingBookOnce(context, repository, preferences, Locale.ITALIAN)
            assertTrue(repository.books.first().isEmpty())
            assertEquals("previous-selection", preferences.lastBook.first())
            assertNull(preferences.bundledBookLanguage.first())
        }
    }

    @Test fun defaultEditionFollowsTheSystemEvenWhenApplicationResourcesUseAnotherLanguage() = runBlocking {
        fixture { context, repository, preferences ->
            val systemLanguage = bundledBookLanguage(LocaleManagerCompat.getSystemLocales(context)[0] ?: Locale.ROOT)
            val override = if (systemLanguage == "ru") Locale.GERMAN else Locale.forLanguageTag("ru")
            val configured = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(override))
            })
            assertNotEquals(systemLanguage, bundledBookLanguage(configured.resources.configuration.locales[0]))
            if (Build.VERSION.SDK_INT >= 33) {
                // Persist a real per-app override; a local configuration alone cannot prove isolation.
                val manager = context.getSystemService(LocaleManager::class.java)
                val original = manager.applicationLocales
                try {
                    manager.applicationLocales = LocaleList(override)
                    assertEquals(override, manager.applicationLocales[0])
                    importBundledReadingBookOnce(context, repository, preferences)
                } finally { manager.applicationLocales = original }
            } else {
                importBundledReadingBookOnce(configured, repository, preferences)
            }
            val sample = repository.books.first().single()
            assertEquals(systemLanguage, repository.open(sample.id).document.metadata.language)
        }
    }

    @Test fun tutorialRestoresDeletedReservedSampleWithoutDuplicatesOrUnrelatedProgressChanges() = runBlocking {
        fixture { context, repository, preferences ->
            preferences.claimBundledBookLanguage("ru")
            val other = repository.import("another.fb2") {
                context.resources.openRawResource(bundledBookResource("fr"))
            }
            val location = BookLocation(repository.open(other).document.blocks.first().id, 0, 0)
            val retained = ReadingProgress(location, CoverPosition.OPEN, .37f)
            repository.saveProgress(other, retained)
            repository.addBookmark(other, location, "Keep this place")
            val sample = ensureBundledReadingBook(context, repository, preferences)
            assertEquals("Седьмой огонь", repository.open(sample).book.title)
            assertEquals(sample, ensureBundledReadingBook(context, repository, preferences))
            assertEquals(2, repository.books.first().size)
            repository.delete(sample)
            assertEquals(sample, ensureBundledReadingBook(context, repository, preferences))
            assertEquals(2, repository.books.first().size)
            assertEquals(retained, repository.open(other).book.progress)
            assertEquals(location, repository.bookmarks(other).first().single().location)
        }
    }

    private suspend fun fixture(block: suspend (Context, LibraryRepository, AppPreferences) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "bundled-import-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        val name = "bundled-import-${UUID.randomUUID()}.db"
        val database = LibraryDatabase.open(base, name)
        val preferencesJob = SupervisorJob()
        try {
            val preferences = AppPreferences(PreferenceDataStoreFactory.create(
                scope = CoroutineScope(preferencesJob + Dispatchers.IO),
                produceFile = { File(root, "app_state.preferences_pb") },
            ))
            val repository = LibraryRepository(context, database.library(),
                PrivateBookStorage(File(root, "books")), preferences)
            repository.initialize()
            block(context, repository, preferences)
        } finally {
            preferencesJob.cancelAndJoin()
            database.close()
            base.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
