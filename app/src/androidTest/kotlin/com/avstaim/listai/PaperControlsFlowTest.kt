package com.avstaim.listai

import android.provider.Settings
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.ReaderPreferences
import com.avstaim.listai.core.model.ReaderScreenTimeoutMode
import com.avstaim.listai.core.model.ReaderTheme
import com.avstaim.listai.feature.reader.ReaderContentsLocation
import com.avstaim.listai.feature.reader.ReaderJumpInProgress
import com.avstaim.listai.feature.reader.ReaderNotesLocation
import com.avstaim.listai.feature.reader.ReaderPaginationComplete
import com.avstaim.listai.feature.reader.ReaderSemanticLocation
import com.avstaim.listai.feature.reader.ReaderTurnProgress
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaperControlsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun screenSleepChoiceIsSharedPersistsAndFollowsActivityLifecycle() {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val previousSelection = controller.selection.value
        val originalPreferences = controller.readerPreferences.value
        val systemTimeout = Settings.System.getInt(compose.activity.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
        var fixtureId: BookId? = null
        fun keptAwake() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        fun chooseMode(mode: ReaderScreenTimeoutMode) {
            val tag = "reader_screen_timeout_${mode.name.lowercase()}"
            compose.onNodeWithTag(tag).performScrollTo()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
            compose.waitUntil(10_000) { controller.readerPreferences.value.screenTimeoutMode == mode }
            compose.onNodeWithTag(tag).assertIsSelected()
        }
        try {
            val id = importFixture(controller).also { fixtureId = it }
            compose.runOnUiThread {
                controller.setReaderPreferences(ReaderPreferences())
                controller.completeReaderOnboarding()
                controller.open(id)
            }
            compose.waitUntil(30_000) {
                !controller.busy.value && controller.selection.value?.opened?.book?.id == id &&
                    controller.readerPreferences.value == ReaderPreferences()
            }
            waitForBook()
            compose.onNodeWithTag("closed_book_read").performClick()
            compose.waitUntil(30_000) { controller.selection.value?.initialProgress?.cover == com.avstaim.listai.core.model.CoverPosition.OPEN }
            waitForBook()
            val startingAnchor = controller.selection.value?.initialProgress?.location
            compose.onNodeWithTag("book_view_demo").performTouchInput {
                click(Offset(width * .86f, height * .54f))
            }
            compose.waitUntil(30_000) { controller.selection.value?.initialProgress?.location != startingAnchor }
            waitForBook()
            val anchor = requireNotNull(controller.selection.value).initialProgress.location
            compose.waitUntil(10_000) { keptAwake() }
            openInsert()
            compose.onNodeWithTag("reader_insert_settings").performClick()
            compose.onNodeWithTag("reader_screen_timeout_always_on").assertIsSelected()
            chooseMode(ReaderScreenTimeoutMode.SYSTEM)
            compose.onNodeWithTag("reader_screen_timeout_always_on").assertIsNotSelected()
            compose.waitUntil(10_000) { !keptAwake() }
            compose.onNodeWithTag("bookmarks_close").performScrollTo().performClick()
            compose.activityRule.scenario.recreate()
            waitForBook()
            assertEquals(ReaderScreenTimeoutMode.SYSTEM, controller.readerPreferences.value.screenTimeoutMode)
            assertFalse(keptAwake())
            assertEquals(anchor, controller.selection.value?.initialProgress?.location)
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse(keptAwake())
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitForBook()
            assertFalse(keptAwake())

            openInsert()
            compose.onNodeWithTag("reader_insert_settings").performClick()
            chooseMode(ReaderScreenTimeoutMode.ALWAYS_ON)
            compose.waitUntil(10_000) { keptAwake() }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse(keptAwake())
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { keptAwake() }
            compose.onNodeWithTag("bookmarks_close").performScrollTo().performClick()
            openInsert()
            compose.onNodeWithTag("reader_return_to_shelf").performScrollTo().performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithTag("shelf_reader_transition").fetchSemanticsNodes().any {
                    it.config.getOrElse(ShelfTransitionPhase) { "" } == ShelfReaderPhase.SHELF.name
                }
            }
            assertFalse(keptAwake())
            compose.onNodeWithTag("library_settings").performClick()
            compose.onNodeWithTag("reader_screen_timeout_always_on").assertIsSelected()
            chooseMode(ReaderScreenTimeoutMode.SYSTEM)
            compose.onNodeWithTag("settings_close").performClick()
            compose.onNodeWithTag("library_book_${id.value}").performClick()
            waitForBook()
            assertFalse(keptAwake())
            assertEquals(systemTimeout, Settings.System.getInt(compose.activity.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT))
        } finally {
            compose.runOnUiThread {
                controller.setReaderPreferences(originalPreferences)
                fixtureId?.let(controller::delete)
                previousSelection?.let { controller.open(it.opened.book.id) } ?: controller.returnToShelf()
            }
            compose.waitUntil(30_000) {
                !controller.busy.value && controller.readerPreferences.value == originalPreferences &&
                    controller.selection.value?.opened?.book?.id == previousSelection?.opened?.book?.id &&
                    runBlocking { controller.repository.books.first().none { it.id == fixtureId } }
            }
        }
    }

    @Test fun settingsPersistExactRulerCanCancelAndContentsCanReturnToTheOriginalAnchor() {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val previousSelection = controller.selection.value
        val originalPreferences = controller.readerPreferences.value
        val fixturePreferences = ReaderPreferences()
        var fixtureId: BookId? = null
        try {
            // Controls do not depend on the book left open by a previous test, its
            // checkpoint, or the user's saved font size. Exercise the real repository
            // and Activity with a fresh, bounded book containing actual chapter entries.
            val id = importFixture(controller).also { fixtureId = it }
            compose.runOnUiThread {
                controller.setReaderPreferences(fixturePreferences)
                controller.completeReaderOnboarding()
                controller.open(id)
            }
            compose.waitUntil(30_000) {
                controller.selection.value?.opened?.book?.id == id && !controller.busy.value &&
                    controller.readerPreferences.value == fixturePreferences
            }
            waitForBook()
            compose.onNodeWithTag("closed_book_read").performClick()
            compose.waitUntil(30_000) {
                controller.selection.value?.initialProgress?.cover == com.avstaim.listai.core.model.CoverPosition.OPEN
            }
            waitForBook()
            val original = requireNotNull(controller.selection.value)
            assertEquals(3, original.opened.document.tableOfContents.size)
            openInsert()
            compose.onNodeWithTag("reader_insert_settings").performClick()
            compose.onNodeWithTag("reader_font_larger").performClick()
            val expectedSize = fixturePreferences.fontSizeSp + 1
            compose.waitUntil(10_000) { controller.readerPreferences.value.fontSizeSp == expectedSize }
            compose.onNodeWithTag("reader_theme_dark").performScrollTo().performClick()
            compose.waitUntil(10_000) { controller.readerPreferences.value.theme == ReaderTheme.DARK }
            compose.onNodeWithTag("bookmarks_close").performScrollTo().performClick()
            compose.activityRule.scenario.recreate()
            waitForBook()
            assertEquals(expectedSize, controller.readerPreferences.value.fontSizeSp)
            assertEquals(ReaderTheme.DARK, controller.readerPreferences.value.theme)
            assertEquals(original.initialProgress.location, controller.selection.value?.initialProgress?.location)

            val beforePreview = requireNotNull(controller.selection.value).initialProgress
            openInsert()
            compose.onNodeWithTag("reader_insert_progress").performClick()
            val percent = compose.onNodeWithTag("reader_progress_percent")
            percent.performTextClearance()
            percent.performTextInput("7")
            percent.performTextInput("3.5")
            percent.assertTextEquals("73.5")
            assertEquals(beforePreview.location, controller.selection.value?.initialProgress?.location)
            compose.onNodeWithTag("reader_progress_cancel").performScrollTo().performClick()
            assertEquals(beforePreview.location, controller.selection.value?.initialProgress?.location)

            openInsert()
            compose.onNodeWithTag("reader_insert_progress").performClick()
            compose.onNodeWithTag("reader_progress_percent").performTextClearance()
            compose.onNodeWithTag("reader_progress_percent").performTextInput("101")
            compose.onNodeWithTag("reader_progress_confirm").assertIsNotEnabled()
            compose.onNodeWithTag("reader_progress_percent").performTextClearance()
            compose.onNodeWithTag("reader_progress_percent").performTextInput("73.5")
            compose.onNodeWithTag("reader_progress_confirm").performScrollTo().performClick()
            waitForBook()
            compose.waitUntil(10_000) { controller.selection.value?.initialProgress?.fraction?.let { it in .73f.. .74f } == true }
            val jumped = requireNotNull(controller.selection.value).initialProgress.location
            assertNotEquals(beforePreview.location, jumped)

            openInsert()
            compose.onNodeWithTag("reader_insert_toc").performScrollTo().assertIsDisplayed().performClick()
            awaitContents(controller, expected = true)
            assertEquals(jumped, controller.selection.value?.initialProgress?.location)
            compose.activityRule.scenario.recreate()
            waitForBook()
            assertNotNull(controller.selection.value?.initialProgress?.tocEntryIndex)
            assertTrue(bookConfig().getOrElse(ReaderContentsLocation) { "" }.isNotEmpty())
            openInsert()
            compose.onNodeWithTag("reader_insert_return_from_toc").performScrollTo().assertIsDisplayed().performClick()
            awaitContents(controller, expected = false)
            assertNull(controller.selection.value?.initialProgress?.tocEntryIndex)
            assertEquals(jumped, controller.selection.value?.initialProgress?.location)
        } finally {
            compose.runOnUiThread {
                controller.setReaderPreferences(originalPreferences)
                fixtureId?.let(controller::delete)
                previousSelection?.let { controller.open(it.opened.book.id) } ?: controller.returnToShelf()
            }
            compose.waitUntil(30_000) {
                !controller.busy.value && controller.readerPreferences.value == originalPreferences &&
                    controller.selection.value?.opened?.book?.id == previousSelection?.opened?.book?.id &&
                    runBlocking { controller.repository.books.first().none { it.id == fixtureId } }
            }
        }
    }

    private fun openInsert() {
        waitForBook()
        compose.onNodeWithTag("book_view_demo").performTouchInput { click(center) }
        compose.onNodeWithTag("reader_paper_insert").assertIsDisplayed()
    }

    private fun waitForBook() = compose.waitUntil(30_000) {
        // Pagination finishes before the shelf flight. Its input shield must also settle.
        compose.onAllNodesWithTag("shelf_reader_transition").fetchSemanticsNodes().any {
            it.config.getOrElse(ShelfTransitionPhase) { "" } == ShelfReaderPhase.READING.name
        } && compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().any {
            it.config.getOrElse(ReaderPaginationComplete) { false } &&
                !it.config.getOrElse(ReaderJumpInProgress) { true } &&
                it.config.getOrElse(ReaderTurnProgress) { 1f } == 0f
        }
    }

    private fun awaitContents(controller: LibraryController, expected: Boolean) {
        try {
            compose.waitUntil(30_000) {
                val state = compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().singleOrNull()?.config
                state != null && (controller.selection.value?.initialProgress?.tocEntryIndex != null) == expected &&
                    state.getOrElse(ReaderContentsLocation) { "" }.isNotEmpty() == expected &&
                    !state.getOrElse(ReaderJumpInProgress) { true } &&
                    state.getOrElse(ReaderTurnProgress) { 1f } == 0f
            }
        } catch (failure: Exception) {
            val state = compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().singleOrNull()?.config
            throw AssertionError("Contents navigation did not finish (expected=$expected): " +
                "book=${controller.selection.value?.opened?.book?.title} " +
                "checkpoint=${controller.selection.value?.initialProgress} " +
                "source=${state?.getOrElse(ReaderSemanticLocation) { "" }} " +
                "contents=${state?.getOrElse(ReaderContentsLocation) { "" }} " +
                "notes=${state?.getOrElse(ReaderNotesLocation) { "" }} " +
                "jump=${state?.getOrElse(ReaderJumpInProgress) { false }} " +
                "turn=${state?.getOrElse(ReaderTurnProgress) { 0f }}", failure)
        }
    }

    private fun bookConfig() = compose.onNodeWithTag("book_view_demo").fetchSemanticsNode().config

    private fun importFixture(controller: LibraryController): BookId {
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><book-title>Paper controls ${UUID.randomUUID()}</book-title><lang>en</lang></title-info></description><body>""")
            repeat(3) { chapter ->
                append("<section id=\"chapter-$chapter\"><title><p>Chapter ${chapter + 1}</p></title>")
                repeat(12) { paragraph ->
                    append("<p id=\"paragraph-$chapter-$paragraph\">")
                    append("Chapter ${chapter + 1}, paragraph $paragraph. The printed story retains an exact place through settings, chapter visits, and returns. ".repeat(5))
                    append("</p>")
                }
                append("</section>")
            }
            append("</body></FictionBook>")
        }
        return runBlocking { controller.repository.import("paper-controls.fb2") { xml.byteInputStream() } }
    }
}
