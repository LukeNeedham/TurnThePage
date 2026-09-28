package com.avstaim.listai

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** A valid EPUB can contain only a marked title page: it must not acquire a second copy. */
@RunWith(AndroidJUnit4::class)
class V12NativeTitleOnlyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun nativeTitleOnlyRestoresReturnsFromContentsAndClosesWithoutAnExtraMainLeaf() {
        val title = BookBlock.Heading(BlockId("native-title"), 1, listOf(InlineNode.Text("A Quiet Title")))
        val author = BookBlock.Paragraph(BlockId("native-author"), listOf(InlineNode.Text("Author One")))
        val document = BookDocument(BookId("native-title-only-ui"), BookMetadata("A Quiet Title", listOf("Author One")),
            listOf(title, author), titlePageBlockIds = listOf(title.id, author.id))
        val legacy = BookLocation(title.id, 2, 2)
        val progress = AtomicReference(ReadingProgress(legacy))
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        compose.runOnUiThread {
            compose.activity.setContent {
                ListaiTheme {
                    ReaderScreen(document, initialProgress = ReadingProgress(legacy), onProgressChanged = progress::set,
                        presentationMode = ReaderPresentationMode.SINGLE, onboardingCompleted = true)
                }
            }
        }
        compose.waitUntil(30_000) { resting() && progress.get().titleLocation == legacy }
        compose.onNodeWithTag("reader_quick_bookmark").assertDoesNotExist()
        compose.onNodeWithTag("reader_top_contents").performClick()
        compose.waitUntil(30_000) { resting() && progress.get().tocEntryIndex != null }
        compose.onNodeWithTag("book_view_demo").performTouchInput { click(center) }
        compose.onNodeWithTag("reader_paper_insert").assertIsDisplayed()
        compose.onNodeWithTag("reader_insert_settings").assertIsDisplayed()
        compose.onNodeWithTag("reader_progress_percent").assertDoesNotExist()
        compose.onNodeWithTag("reader_insert_return_from_toc").performScrollTo().performClick()
        compose.waitUntil(30_000) { resting() && progress.get().titleLocation == legacy && progress.get().tocEntryIndex == null }
        assertEquals(legacy, progress.get().location)
        turn(forward = true)
        compose.waitUntil(30_000) { resting() && progress.get().cover == CoverPosition.BACK }
        assertEquals(legacy, progress.get().location)
        turn(forward = false)
        compose.waitUntil(30_000) { resting() && progress.get().titleLocation?.blockId == title.id }
        assertEquals(legacy, progress.get().location)
        assertNull(progress.get().annotationLocation)
        assertNull(progress.get().tocEntryIndex)
    }

    private fun resting(): Boolean = compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().singleOrNull()?.config?.let {
        it.getOrElse(ReaderPaginationComplete) { false } && !it.getOrElse(ReaderJumpInProgress) { true } &&
            it.getOrElse(ReaderTurnProgress) { 1f } == 0f
    } == true

    private fun turn(forward: Boolean) {
        compose.waitUntil(30_000) { resting() }
        val book = compose.onNodeWithTag("book_view_demo")
        if (forward) {
            compose.waitUntil(10_000) { book.fetchSemanticsNode().config.contains(SemanticsActions.OnClick) }
            book.performTouchInput { click(Offset(width * .87f, height * .52f)) }
        } else {
            // ClosedBack has no forward click: its only turn action reopens the rigid cover.
            compose.waitUntil(10_000) { book.fetchSemanticsNode().config
                .getOrElse(SemanticsActions.CustomActions) { emptyList() }.size == 1 }
            val backward = book.fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
            compose.runOnIdle { assertTrue(backward.action()) }
        }
    }
}
