package com.avstaim.listai

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.feature.reader.ReaderPaginationComplete
import com.avstaim.listai.feature.reader.ReaderJumpInProgress
import com.avstaim.listai.feature.reader.ReaderSemanticLocation
import com.avstaim.listai.core.model.BookId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun incomingBookSupportsBookmarkJumpAndReaderRemountWithoutLosingProgress() {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val originalId = controller.selection.value?.opened?.book?.id
        val launchIdentity = Intent(compose.activity.intent)
        // Instrumentation executes under the target UID on API 36; its own APK's
        // cache directory is intentionally not writable by this process.
        val providerContext = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(providerContext.cacheDir, "imports").also { it.mkdirs() }
        val source = File(directory, "test-${UUID.randomUUID()}.fb2")
        source.writeText("""<?xml version="1.0" encoding="UTF-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info>
            <book-title>Imported ${source.name}</book-title><lang>en</lang></title-info></description>
            <body><section><p>${"A quiet reading journey through many pages. ".repeat(1500)}</p></section></body></FictionBook>""".trimIndent())
        val uri = FileProvider.getUriForFile(providerContext, "com.avstaim.listai.debug.imports", source)
        var importedId: BookId? = null
        try {
            providerContext.grantUriPermission(compose.activity.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            compose.runOnUiThread {
                compose.activity.startActivity(Intent(compose.activity, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW).setDataAndType(uri, "application/x-fictionbook+xml")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            compose.waitUntil(30_000) { controller.selection.value?.opened?.book?.title?.contains(source.name) == true }
            assertTrue("Incoming book delivery must preserve the Activity lifecycle identity",
                launchIdentity.filterEquals(compose.activity.intent))
            val id = requireNotNull(controller.selection.value).opened.book.id
            importedId = id
            waitForBook()
            compose.onNodeWithTag("closed_book_read").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("reader_quick_bookmark").fetchSemanticsNodes().isNotEmpty() &&
                    !compose.onNodeWithTag("book_view_demo").fetchSemanticsNode().config
                        .getOrElse(ReaderJumpInProgress) { true }
            }
            val initial = location()
            turnForward()
            compose.waitUntil(10_000) { location() != initial }
            val anchor = location()

            compose.onNodeWithTag("reader_quick_bookmark").performClick()
            var bookmarkId: String? = null
            compose.waitUntil(10_000) {
                bookmarkId = runBlocking { controller.repository.bookmarks(id).first().firstOrNull()?.id }
                bookmarkId != null
            }
            turnForward()
            compose.waitUntil(10_000) { location() != anchor }
            val advanced = location()

            compose.onNodeWithTag("book_view_demo").performTouchInput { click(center) }
            compose.onNodeWithTag("reader_return_to_shelf").performClick()
            compose.onNodeWithTag("library_book_${id.value}").performClick()
            waitForBook()
            assertEquals(advanced, location())
            openBookmarks()
            val liveSession = requireNotNull(controller.selection.value).session
            compose.onNodeWithTag("bookmark_jump_$bookmarkId").performClick()
            compose.waitUntil(10_000) {
                location() == anchor && !compose.onNodeWithTag("book_view_demo")
                    .fetchSemanticsNode().config.getOrElse(ReaderJumpInProgress) { false }
            }
            assertEquals(anchor, location())
            assertEquals("A printed bookmark turns the page without remounting the reader",
                liveSession, controller.selection.value?.session)

            // An explicitly replaced controller session still rejects genuinely stale callbacks.
            val obsolete = requireNotNull(controller.selection.value)
            val bookmark = runBlocking { controller.repository.bookmarks(id).first().single() }
            compose.runOnUiThread { controller.jump(bookmark) }
            compose.waitUntil(10_000) { controller.selection.value?.session != obsolete.session }
            waitForBook()
            compose.runOnUiThread {
                controller.progressChanged(id, obsolete.session,
                    obsolete.initialProgress.copy(location = null, fraction = 0f))
            }
            assertEquals(anchor, location())
            assertEquals(bookmark.location, controller.selection.value?.initialProgress?.location)
            compose.activityRule.scenario.recreate()
            waitForBook()
            assertEquals(anchor, location())

            openBookmarks()
            compose.onNodeWithTag("bookmark_delete_$bookmarkId").performClick()
            compose.waitUntil(10_000) { runBlocking { controller.repository.bookmarks(id).first().isEmpty() } }
            compose.onNodeWithTag("bookmarks_close").performClick()
            compose.runOnUiThread { controller.delete(id) }
            compose.waitUntil(10_000) { runBlocking { controller.repository.books.first().none { it.id == id } } }
            assertTrue(source.isFile)
        } finally {
            importedId?.let { id ->
                compose.runOnUiThread { controller.delete(id) }
                compose.waitUntil(10_000) { runBlocking { controller.repository.books.first().none { it.id == id } } }
            }
            providerContext.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            source.delete()
            if (originalId != null) {
                compose.runOnUiThread { controller.open(originalId) }
                compose.waitUntil(30_000) { controller.selection.value?.opened?.book?.id == originalId && !controller.busy.value }
            }
        }
    }

    private fun waitForBook() = compose.waitUntil(30_000) {
        compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().any {
            it.config.getOrElse(ReaderPaginationComplete) { false }
        }
    }

    private fun openBookmarks() {
        compose.onNodeWithTag("book_view_demo").performTouchInput { click(center) }
        compose.onNodeWithTag("reader_bookmarks").performClick()
    }

    private fun turnForward() {
        val book = compose.onNodeWithTag("book_view_demo")
        compose.waitUntil(10_000) { book.fetchSemanticsNode().config.contains(SemanticsActions.OnClick) }
        book.performTouchInput { click(Offset(width * .86f, height * .54f)) }
    }

    private fun location() = compose.onNodeWithTag("book_view_demo").fetchSemanticsNode().config[ReaderSemanticLocation]
}
