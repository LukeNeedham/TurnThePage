package com.avstaim.listai

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.feature.reader.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real FB2 importer, application navigation, Activity state, and shelf persistence. */
@RunWith(AndroidJUnit4::class)
class Stage8RestorationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun importedNotesSurviveActivityRecreationAndManualContentsReturnWithoutLeakingToShelf() {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val name = "Note restoration ${System.nanoTime()}"
        val sourceFile = File(compose.activity.cacheDir, "stage8-restoration.fb2")
        sourceFile.writeText("""<?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
            <description><title-info><book-title>$name</book-title><lang>en</lang></title-info></description>
            <body><section id="chapter"><title><p>The paper book</p></title>
            <p id="source">A word<a l:href="#note" type="note">[1]</a> and its explanation.
            ${"The source story keeps its own place while the reader studies a note. ".repeat(70)}</p>
            </section></body><body name="notes"><section id="note"><title><p>1</p></title>
            <p>${"The complete note retains all its sentences and a printed path back to the source. ".repeat(90)}</p>
            </section></body></FictionBook>""".trimIndent())
        try {
            compose.runOnUiThread { controller.import(Uri.fromFile(sourceFile)) }
            compose.waitUntil(45_000) {
                controller.selection.value?.opened?.document?.metadata?.title == name &&
                    node()?.config?.getOrElse(ReaderNoteLinks) { emptyList() }?.isNotEmpty() == true
            }
            awaitIdle()
            val bookId = requireNotNull(controller.selection.value).opened.book.id
            val source = config()[ReaderSemanticLocation]
            val index = config()[ReaderNoteLinkTargets].indexOfFirst { it.endsWith("|footer") }
            assertTrue(index >= 0)
            val origin = config()[ReaderNoteLinkTargets][index].split('|')[1]
            val call = config()[ReaderNoteLinks][index]
            book().performTouchInput { click(call.center) }
            awaitNotes()
            val visit = config()[ReaderNoteVisit]
            assertTrue(visit.endsWith("|$origin"))
            assertEquals(source, config()[ReaderSemanticLocation])
            capture("imported-notes")

            compose.activityRule.scenario.recreate()
            awaitNotes()
            assertEquals(visit, config()[ReaderNoteVisit])
            assertEquals(source, config()[ReaderSemanticLocation])
            capture("notes-after-activity-recreate")
            back()
            awaitSource(origin)

            // A direct TOC visit has no synthetic call. Android Back must use its first real call.
            top("contents")
            compose.waitUntil(30_000) {
                node()?.config?.getOrElse(ReaderContentsLocation) { "" }?.isNotEmpty() == true && !jumping()
            }
            awaitIdle()
            val notesRow = config()[ReaderContentsRows].last()
            book().performTouchInput { click(notesRow.center) }
            awaitNotes()
            assertEquals("", config()[ReaderNoteVisit])
            back()
            awaitSource(origin)

            val footer = config()[ReaderNoteLinkTargets].indexOfFirst { it.endsWith("|footer") }
            val footerBounds = config()[ReaderNoteLinks][footer]
            book().performTouchInput { click(footerBounds.center) }
            awaitNotes()
            top("close")
            compose.waitUntil(30_000) { controller.selection.value == null && node() == null }
            compose.runOnUiThread { controller.open(bookId) }
            awaitSource(origin)
            assertEquals("", config()[ReaderNoteVisit])
            assertTrue(config()[ReaderCacheBytes] <= 64L * 1024 * 1024)
            capture("source-after-shelf-reopen")
        } finally { sourceFile.delete() }
    }

    private fun awaitNotes() {
        try {
            compose.waitUntil(45_000) {
                node()?.config?.getOrElse(ReaderNotesLocation) { "" }?.isNotEmpty() == true && !jumping()
            }
        } catch (failure: Exception) {
            val current = node()?.config
            capture("notes-wait-failure")
            throw AssertionError("Notes missing: source=${current?.getOrElse(ReaderSemanticLocation) { "" }} " +
                "notes=${current?.getOrElse(ReaderNotesLocation) { "" }} " +
                "visit=${current?.getOrElse(ReaderNoteVisit) { "" }} " +
                "complete=${current?.getOrElse(ReaderPaginationComplete) { false }} " +
                "jump=${current?.getOrElse(ReaderJumpInProgress) { false }}", failure)
        }
        awaitIdle()
    }
    private fun awaitSource(expected: String) {
        compose.waitUntil(45_000) {
            node()?.config?.getOrElse(ReaderSemanticLocation) { "" } == expected &&
                node()?.config?.getOrElse(ReaderNotesLocation) { "" } == "" && !jumping()
        }
        awaitIdle()
    }
    private fun awaitIdle() { compose.waitForIdle() }
    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    private fun top(action: String) = compose.onNodeWithTag("reader_top_$action").performTouchInput { click(center) }
    private fun book() = compose.onNodeWithTag("book_view_demo")
    private fun node() = compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().singleOrNull()
    private fun config() = book().fetchSemanticsNode().config
    private fun jumping() = node()?.config?.getOrElse(ReaderJumpInProgress) { false } == true
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = requireNotNull(compose.activity.getExternalFilesDir("stage8-restoration")).also { it.mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
