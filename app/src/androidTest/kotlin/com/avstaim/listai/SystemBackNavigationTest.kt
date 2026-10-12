package com.avstaim.listai

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.database.AppPreferences
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity navigation and Android KEYCODE_BACK, including incoming document launches. */
@RunWith(AndroidJUnit4::class)
class SystemBackNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var controller: LibraryController
    private var fixtureId = BookId("system-back-not-imported")
    private lateinit var fixtureXml: String
    private var originalId: BookId? = null
    private val imported = mutableListOf<BookId>()
    private val sourceFiles = mutableListOf<File>()
    private val sourceUris = mutableListOf<Uri>()

    @Before fun prepareRealLibrary() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { controller = (it.application as ListaiApplication).controller }
        compose.waitUntil(30_000) { !controller.busy.value && !controller.importing.value }
        originalId = controller.selection.value?.opened?.book?.id
        compose.runOnUiThread {
            controller.completeReaderOnboarding()
            controller.returnToShelf()
        }
        awaitShelf()
        compose.waitUntil(10_000) { controller.readerOnboardingCompleted.value == true }
        fixtureXml = fixtureXml("System Back ${UUID.randomUUID()}")
        fixtureId = runBlocking { controller.repository.import("system-back.fb2") { fixtureXml.byteInputStream() } }
        imported += fixtureId
        compose.waitUntil(10_000) { controller.books.value.any { it.id == fixtureId } }
    }

    @After fun restoreLibrary() {
        if (::controller.isInitialized) {
            compose.runOnUiThread { controller.returnToShelf() }
            runBlocking { imported.forEach { controller.repository.delete(it) } }
            sourceUris.forEach { context.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            sourceFiles.forEach(File::delete)
            originalId?.let { id ->
                compose.runOnUiThread { controller.open(id) }
                compose.waitUntil(30_000) { !controller.busy.value && controller.selection.value?.opened?.book?.id == id }
            }
        }
        if (::scenario.isInitialized) scenario.close()
    }

    @Test fun backFromReadingReturnsToTheLibraryAndRecreationKeepsTheBookShelved() {
        openFromShelf(sourceLocation())
        systemBack()
        awaitShelf()
        assertForeground()
        compose.waitUntil(10_000) { runBlocking { AppPreferences(context).lastBook.first() } == null }
        scenario.recreate()
        awaitShelf()
        assertForeground()
        compose.onNodeWithTag("library_book_${fixtureId.value}").assertIsDisplayed()
    }

    @Test fun backClosesTheMenuAndReplayedGuideBeforeLeavingReading() {
        openFromShelf(sourceLocation())
        val source = sourceCheckpoint()
        compose.onNodeWithTag("reader_top_settings").performClick()
        compose.onNodeWithTag("reader_paper_insert").assertIsDisplayed()
        systemBack()
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
        assertReadingAt(source)

        compose.onNodeWithTag("reader_top_settings").performClick()
        compose.onNodeWithTag("reader_insert_guide").performScrollTo().performClick()
        compose.onNodeWithTag("reader_onboarding").assertIsDisplayed()
        systemBack()
        compose.onNodeWithTag("reader_onboarding").assertDoesNotExist()
        assertReadingAt(source)
        systemBack()
        awaitShelf()
        assertForeground()
    }

    @Test fun backDismissesTheIllustrationAndRetainsTheReadingPlace() {
        val imageLocation = location { it is BookBlock.Image }
        openFromShelf(imageLocation)
        compose.waitUntil(30_000) { config()?.getOrElse(ReaderImageBounds) { emptyList() }?.isNotEmpty() == true }
        val source = sourceCheckpoint()
        val bounds = requireNotNull(config())[ReaderImageBounds].first()
        book().performTouchInput { click(bounds.center) }
        compose.onNodeWithTag("reader_image_viewer").assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("reader_image_viewer").fetchSemanticsNodes().singleOrNull()?.config
                ?.getOrElse(ReaderImageTransition) { 0f } == 1f
        }
        systemBack()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader_image_viewer").fetchSemanticsNodes().isEmpty() }
        assertReadingAt(source)
        systemBack()
        awaitShelf()
        assertForeground()
    }

    @Test fun backAfterRecreatingANoteVisitReturnsToTheCallBeforeReturningToTheLibrary() {
        openFromShelf(sourceLocation())
        compose.waitUntil(30_000) { config()?.getOrElse(ReaderNoteLinks) { emptyList() }?.isNotEmpty() == true }
        val source = requireNotNull(config())[ReaderSemanticLocation]
        val noteConfig = requireNotNull(config())
        val index = noteConfig[ReaderNoteLinkTargets].indexOfFirst { it.endsWith("|inline") }
        assertTrue("The fixture has a visible real note call", index >= 0)
        val origin = noteConfig[ReaderNoteLinkTargets][index].split('|')[1]
        book().performTouchInput { click(noteConfig[ReaderNoteLinks][index].center) }
        awaitNote()
        assertEquals(source, requireNotNull(config())[ReaderSemanticLocation])
        val visit = requireNotNull(config())[ReaderNoteVisit]
        scenario.recreate()
        awaitNote()
        assertEquals(visit, requireNotNull(config())[ReaderNoteVisit])
        systemBack()
        compose.waitUntil(30_000) {
            resting() && config()?.getOrElse(ReaderNotesLocation) { "" } == "" &&
                config()?.getOrElse(ReaderSemanticLocation) { "" } == origin
        }
        assertSelectedFixture()
        assertForeground()
        systemBack()
        awaitShelf()
        assertForeground()
    }

    @Test fun coldActionViewAndLaterIncomingDeliveryBothReturnToTheLibrary() {
        saveAt(sourceLocation())
        val directory = File(context.cacheDir, "imports").also { it.mkdirs() }
        val source = File(directory, "system-back-${UUID.randomUUID()}.fb2").also { it.writeText(fixtureXml) }
        sourceFiles += source
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.debug.imports", source)
        sourceUris += uri
        context.grantUriPermission(context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val incoming = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/x-fictionbook+xml")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        // A fresh Activity really consumes ACTION_VIEW in onCreate; this is not an onNewIntent shortcut.
        scenario.close()
        // ActivityScenario's invoker adds CLEAR_TASK to its Intent in place. Keep the
        // later external delivery separate, so it really exercises onNewIntent.
        scenario = ActivityScenario.launch(Intent(incoming))
        awaitReading()
        assertSelectedFixture()
        systemBack()
        awaitShelf()
        assertForeground()

        lateinit var launchIdentity: Intent
        lateinit var receivingActivity: MainActivity
        scenario.onActivity { activity ->
            launchIdentity = Intent(activity.intent)
            receivingActivity = activity
            assertEquals("A later document delivery must not clear the running task", 0,
                incoming.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
            activity.startActivity(incoming)
        }
        awaitReading()
        assertSelectedFixture()
        scenario.onActivity {
            assertSame("A later document is delivered to the running Activity", receivingActivity, it)
            assertTrue("A later delivery retains the launch Intent identity", launchIdentity.filterEquals(it.intent))
        }
        systemBack()
        awaitShelf()
        scenario.recreate()
        awaitShelf()
        assertForeground()
        assertEquals("Repeated incoming copies deduplicate", 1,
            runBlocking { controller.repository.books.first().count { it.id == fixtureId } })
        assertTrue("Returning to the library preserves the supplied original", source.isFile)
    }

    @Test fun backDuringPendingExternalOpeningCancelsItsLateSelectionWithoutExiting() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val copy = executor.submit<BookId> {
            runBlocking {
                controller.repository.import("back-copy-gate.fb2") {
                    object : ByteArrayInputStream(fixtureXml("Back copy gate ${UUID.randomUUID()}").toByteArray()) {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            entered.countDown()
                            check(release.await(20, TimeUnit.SECONDS)) { "Test did not release the pending copy" }
                            return super.read(buffer, offset, length)
                        }
                    }
                }
            }
        }
        try {
            assertTrue("The repository lock is held before opening", entered.await(10, TimeUnit.SECONDS))
            compose.runOnUiThread { controller.open(fixtureId) }
            compose.waitUntil(10_000) { controller.busy.value && controller.selection.value == null }
            systemBack()
            assertForeground()
            assertNull(controller.selection.value)
            release.countDown()
            imported += copy.get(20, TimeUnit.SECONDS)
            compose.waitUntil(30_000) { !controller.busy.value }
            awaitShelf()
            compose.waitUntil(10_000) { runBlocking { AppPreferences(context).lastBook.first() } == null }
            assertForeground()
            assertNull("Completing the old parse must not reopen the shelved book", controller.selection.value)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun startupRestoreHonorsShelvingBeforeItsLastBookCanBeParsed() {
        val preferences = AppPreferences(context)
        runBlocking { preferences.selectBook(fixtureId.value) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val copy = executor.submit<BookId> {
            runBlocking {
                controller.repository.import("startup-copy-gate.fb2") {
                    object : ByteArrayInputStream(fixtureXml("Startup gate ${UUID.randomUUID()}").toByteArray()) {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            entered.countDown()
                            check(release.await(20, TimeUnit.SECONDS)) { "Test did not release initialization" }
                            return super.read(buffer, offset, length)
                        }
                    }
                }
            }
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            lateinit var startup: LibraryController
            compose.runOnUiThread { startup = LibraryController(context, controller.repository, preferences) }
            // Initialization and the fixture copy share the real repository lock. A subsequent
            // main-loop turn cancels the restore deterministically, without timing a cold process.
            compose.runOnIdle {
                assertTrue(startup.busy.value)
                assertNull(startup.selection.value)
                startup.returnToShelf()
            }
            release.countDown()
            imported += copy.get(20, TimeUnit.SECONDS)
            compose.waitUntil(30_000) { !startup.busy.value }
            compose.waitUntil(10_000) { runBlocking { preferences.lastBook.first() } == null }
            assertNull("Initialization must not publish the last book after the shelving intent", startup.selection.value)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun backDuringIncomingImportKeepsItsPrivateCopyButSuppressesLateAutoOpen() {
        val directory = File(context.cacheDir, "imports").also { it.mkdirs() }
        val source = File(directory, "import-back-${UUID.randomUUID()}.fb2").also { it.writeText(fixtureXml) }
        sourceFiles += source
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.debug.imports", source)
        sourceUris += uri
        context.grantUriPermission(context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val copy = executor.submit<BookId> {
            runBlocking {
                controller.repository.import("incoming-copy-gate.fb2") {
                    object : ByteArrayInputStream(fixtureXml("Incoming gate ${UUID.randomUUID()}").toByteArray()) {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            entered.countDown()
                            check(release.await(20, TimeUnit.SECONDS)) { "Test did not release the incoming copy" }
                            return super.read(buffer, offset, length)
                        }
                    }
                }
            }
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            compose.runOnUiThread { controller.import(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            compose.waitUntil(10_000) { controller.importing.value }
            val importTitle = context.getString(R.string.library_import_title)
            compose.onNodeWithText(importTitle).assertIsDisplayed()
            systemBack()
            compose.onNodeWithText(importTitle).assertDoesNotExist()
            assertForeground()
            assertTrue("Shelving leaves the safe private-copy operation running", controller.importing.value)
            assertNull(controller.selection.value)
            scenario.recreate()
            compose.onNodeWithText(importTitle).assertDoesNotExist()
            assertForeground()
            release.countDown()
            imported += copy.get(20, TimeUnit.SECONDS)
            compose.waitUntil(30_000) { !controller.importing.value && !controller.busy.value }
            awaitShelf()
            compose.waitUntil(10_000) { runBlocking { AppPreferences(context).lastBook.first() } == null }
            assertNull("Finishing the incoming copy must not reopen the reader", controller.selection.value)
            assertNull("A completed background single import does not show a cancellation summary", controller.importSummary.value)
            assertEquals(1, runBlocking { controller.repository.books.first().count { it.id == fixtureId } })
            assertTrue("The external original remains untouched", source.isFile)
            assertForeground()
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun systemBack() {
        compose.waitForIdle()
        // Send the real key through Android's input service. Never invoke the app dispatcher directly.
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
        ).use { it.readBytes() }
        compose.waitForIdle()
    }

    private fun assertForeground() = scenario.onActivity {
        assertFalse("Back must leave the Activity running while a book can return to its library", it.isFinishing)
        assertTrue(it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    private fun assertSelectedFixture() = assertEquals(fixtureId, controller.selection.value?.opened?.book?.id)
    private fun sourceCheckpoint() = requireNotNull(controller.selection.value).initialProgress
    private fun assertReadingAt(source: ReadingProgress) {
        awaitReading()
        assertSelectedFixture()
        assertEquals(source, sourceCheckpoint())
        assertForeground()
    }

    private fun sourceLocation() = location { it.plainText().startsWith("A system back call") }
    private fun location(predicate: (BookBlock) -> Boolean): BookLocation = runBlocking {
        val document = controller.repository.open(fixtureId).document
        val block = document.blocks.first(predicate)
        val offset = document.blocks.takeWhile { it.id != block.id }.sumOf { it.plainText().length + 1L }
        BookLocation(block.id, 0, offset)
    }
    private fun saveAt(location: BookLocation) = runBlocking {
        controller.repository.saveProgress(fixtureId, ReadingProgress(location, CoverPosition.OPEN))
    }
    private fun openFromShelf(location: BookLocation) {
        saveAt(location)
        val tag = "library_book_${fixtureId.value}"
        if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
        }
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
        awaitReading()
        assertSelectedFixture()
    }
    private fun awaitShelf() = compose.waitUntil(30_000) {
        controller.selection.value == null && phase() == ShelfReaderPhase.SHELF.name
    }
    private fun awaitReading() = compose.waitUntil(45_000) {
        controller.selection.value?.opened?.book?.id == fixtureId &&
            phase() == ShelfReaderPhase.READING.name && resting()
    }
    private fun awaitNote() = compose.waitUntil(45_000) {
        resting() && config()?.getOrElse(ReaderNotesLocation) { "" }?.startsWith("back-note|") == true
    }
    private fun phase() = compose.onAllNodesWithTag("shelf_reader_transition").fetchSemanticsNodes()
        .singleOrNull()?.config?.getOrElse(ShelfTransitionPhase) { "" }
    private fun book() = compose.onNodeWithTag("book_view_demo")
    private fun config(): SemanticsConfiguration? = compose.onAllNodesWithTag("book_view_demo")
        .fetchSemanticsNodes().singleOrNull()?.config
    private fun resting() = config()?.let {
        it.getOrElse(ReaderPaginationComplete) { false } && !it.getOrElse(ReaderJumpInProgress) { true } &&
            it.getOrElse(ReaderTurnProgress) { 1f } == 0f
    } == true

    private fun fixtureXml(title: String): String {
        val image = Bitmap.createBitmap(240, 140, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF4C708D.toInt()) }
        val imageBytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        image.recycle()
        val encoded = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        return """<?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
            <description><title-info><book-title>$title</book-title><lang>en</lang></title-info></description>
            <body><section><image l:href="#back-image"/>
            <p id="back-source">A system back call<a l:href="#back-note" type="note">[1]</a> returns here.
            ${"Reading keeps the source place through menus and Android navigation. ".repeat(120)}</p>
            </section></body><body name="notes"><section id="back-note"><title><p>1</p></title>
            <p>${"A complete note has a durable call to return to before leaving the book. ".repeat(90)}</p>
            </section></body><binary id="back-image" content-type="image/png">$encoded</binary></FictionBook>""".trimIndent()
    }
}
