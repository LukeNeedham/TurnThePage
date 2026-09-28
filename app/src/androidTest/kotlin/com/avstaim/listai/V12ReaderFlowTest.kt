package com.avstaim.listai

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
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

/** Exercises the public ReaderScreen with actual pagination, paper turns and printed controls. */
@RunWith(AndroidJUnit4::class)
class V12ReaderFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun firstLeavesFollowDescriptionTitleTextAndContentsReturnsToTitle() {
        val progress = AtomicReference(ReadingProgress(cover = CoverPosition.FRONT))
        showReader(progress)
        compose.onNodeWithTag("closed_book_description").performClick()
        awaitProgress { it.annotationLocation != null }
        assertNull(progress.get().titleLocation)
        assertNull(progress.get().tocEntryIndex)
        compose.onNodeWithTag("reader_quick_bookmark").assertDoesNotExist()
        turnForward()
        awaitProgress { it.titleLocation != null }
        val title = progress.get()
        assertNull(title.annotationLocation)
        assertNull(title.tocEntryIndex)
        compose.onNodeWithTag("reader_quick_bookmark").assertDoesNotExist()
        compose.onNodeWithTag("reader_top_contents").performClick()
        awaitProgress { it.tocEntryIndex != null }
        compose.onNodeWithTag("book_view_demo").performTouchInput { click(center) }
        compose.onNodeWithTag("reader_insert_return_from_toc").performScrollTo().performClick()
        awaitProgress { it.tocEntryIndex == null && it.titleLocation != null }
        assertEquals(title.titleLocation, progress.get().titleLocation)
        assertEquals(title.location, progress.get().location)
        turnForward()
        awaitProgress { it.annotationLocation == null && it.titleLocation == null && it.tocEntryIndex == null }
        assertEquals(BlockId("chapter-1"), progress.get().location?.blockId)
        compose.onNodeWithTag("reader_quick_bookmark").assertIsDisplayed()
    }

    @Test fun clockPreferenceDoesNotChangePaginationOrReadingAnchor() {
        val anchor = BookLocation(BlockId("text-1"), 24, 34)
        val progress = AtomicReference(ReadingProgress(location = anchor))
        val preferences = mutableStateOf(ReaderPreferences(showClock = true))
        showReader(progress, preferences)
        compose.onNodeWithTag("reader_clock", useUnmergedTree = true).assertIsDisplayed()
        val layout = config()[ReaderLayoutIdentity]
        val location = progress.get().location
        compose.onNodeWithTag("reader_top_settings").performClick()
        compose.onNodeWithTag("reader_insert_settings").performClick()
        compose.onNodeWithTag("reader_show_clock").performScrollTo().performClick()
        compose.onNodeWithTag("bookmarks_close").performScrollTo().performClick()
        compose.onNodeWithTag("reader_clock", useUnmergedTree = true).assertDoesNotExist()
        assertFalse(preferences.value.showClock)
        assertEquals(layout, config()[ReaderLayoutIdentity])
        assertEquals(location, progress.get().location)
    }

    @Test fun directContentsVisitCanReturnToClosedFrontCover() {
        val progress = AtomicReference(ReadingProgress(cover = CoverPosition.FRONT))
        showReader(progress)
        compose.onNodeWithTag("closed_book_contents").performClick()
        awaitProgress { it.tocEntryIndex != null }
        compose.onNodeWithTag("book_view_demo").performTouchInput { click(center) }
        compose.onNodeWithTag("reader_insert_return_from_toc").performScrollTo().performClick()
        awaitProgress { it.cover == CoverPosition.FRONT && it.tocEntryIndex == null }
        compose.onNodeWithTag("closed_book_read").assertIsDisplayed()
    }

    private lateinit var latest: AtomicReference<ReadingProgress>

    private fun showReader(progress: AtomicReference<ReadingProgress>,
        preferences: androidx.compose.runtime.MutableState<ReaderPreferences> = mutableStateOf(ReaderPreferences())) {
        latest = progress
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val fixture = fixture()
        val initial = progress.get()
        compose.runOnUiThread {
            compose.activity.setContent {
                ListaiTheme {
                    ReaderScreen(fixture, initialProgress = initial, onProgressChanged = progress::set,
                        preferences = preferences.value, onPreferencesChange = { preferences.value = it },
                        presentationMode = ReaderPresentationMode.SINGLE, onboardingCompleted = true)
                }
            }
        }
        awaitRest()
    }

    private fun awaitProgress(predicate: (ReadingProgress) -> Boolean) {
        compose.waitUntil(30_000) { resting() && predicate(latest.get()) }
    }

    private fun awaitRest() = compose.waitUntil(30_000) { resting() }
    private fun resting(): Boolean = compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().singleOrNull()?.config?.let {
        it.getOrElse(ReaderPaginationComplete) { false } && !it.getOrElse(ReaderJumpInProgress) { true } &&
            it.getOrElse(ReaderTurnProgress) { 1f } == 0f
    } == true
    private fun config() = compose.onNodeWithTag("book_view_demo").fetchSemanticsNode().config
    private fun turnForward() {
        awaitRest()
        compose.waitUntil(10_000) { config().contains(SemanticsActions.OnClick) }
        compose.onNodeWithTag("book_view_demo").performTouchInput { click(Offset(width * .87f, height * .52f)) }
    }

    private fun fixture(): BookDocument {
        val blocks = (1..2).flatMap { number -> listOf(
            BookBlock.Heading(BlockId("chapter-$number"), 1, listOf(InlineNode.Text("Chapter $number"))),
            BookBlock.Paragraph(BlockId("text-$number"), listOf(InlineNode.Text("The quiet page holds the reader's exact place. ".repeat(8))))
        ) }
        return BookDocument(BookId("v12-reader-flow"),
            BookMetadata("A Quiet Book", listOf("Reader One"), "en", annotation = "A brief description on its own white page."),
            blocks, tableOfContents = listOf(BookTocEntry("Chapter 1", BookLocation(BlockId("chapter-1"), 0, 0)),
                BookTocEntry("Chapter 2", BookLocation(BlockId("chapter-2"), 0, 400))),
            chapters = listOf(BookChapter(listOf(BlockId("chapter-1")), 1, "1", 9),
                BookChapter(listOf(BlockId("chapter-2")), 2, "2", 9)))
    }
}
