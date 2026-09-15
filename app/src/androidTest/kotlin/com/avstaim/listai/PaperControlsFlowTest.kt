package com.avstaim.listai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import com.avstaim.listai.core.model.ReaderTheme
import com.avstaim.listai.feature.reader.ReaderContentsLocation
import com.avstaim.listai.feature.reader.ReaderPaginationComplete
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaperControlsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsPersistExactRulerCanCancelAndContentsCanReturnToTheOriginalAnchor() {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        if (controller.selection.value == null) {
            val id = runBlocking { controller.repository.books.first().first().id }
            compose.runOnUiThread { controller.open(id) }
        }
        waitForBook()
        val original = requireNotNull(controller.selection.value)
        val originalPreferences = controller.readerPreferences.value
        try {
            openInsert()
            compose.onNodeWithTag("reader_insert_settings").performClick()
            compose.onNodeWithTag("reader_font_larger").performClick()
            val expectedSize = (originalPreferences.fontSizeSp + 1).coerceAtMost(32f)
            compose.waitUntil(10_000) { controller.readerPreferences.value.fontSizeSp == expectedSize }
            compose.onNodeWithTag("reader_theme_dark").performClick()
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
            compose.onNodeWithTag("reader_insert_toc").performClick()
            compose.waitUntil(10_000) { controller.selection.value?.initialProgress?.tocEntryIndex != null }
            assertEquals(jumped, controller.selection.value?.initialProgress?.location)
            compose.activityRule.scenario.recreate()
            waitForBook()
            assertNotNull(controller.selection.value?.initialProgress?.tocEntryIndex)
            openInsert()
            compose.onNodeWithTag("reader_insert_return_from_toc").performClick()
            waitForBook()
            compose.waitUntil(10_000) { controller.selection.value?.initialProgress?.tocEntryIndex == null }
            assertNull(controller.selection.value?.initialProgress?.tocEntryIndex)
            assertEquals(jumped, controller.selection.value?.initialProgress?.location)
        } finally {
            compose.runOnUiThread {
                controller.setReaderPreferences(originalPreferences)
                controller.selection.value?.let { current ->
                    controller.progressChanged(current.opened.book.id, current.session, original.initialProgress)
                }
            }
        }
    }

    private fun openInsert() {
        compose.onNodeWithTag("book_view_demo").performTouchInput { click(center) }
        compose.onNodeWithTag("reader_paper_insert").assertIsDisplayed()
    }

    private fun waitForBook() = compose.waitUntil(30_000) {
        compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().any {
            it.config.getOrElse(ReaderPaginationComplete) { false }
        }
    }
}
