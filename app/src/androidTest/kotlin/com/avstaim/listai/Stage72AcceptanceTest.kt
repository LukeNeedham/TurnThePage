package com.avstaim.listai

import androidx.activity.compose.setContent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Paper controls must keep their physical bounds and their actual touch behavior. */
@RunWith(AndroidJUnit4::class)
class Stage72AcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun fourActionsOccupyThePaperInSinglePageAndSpread() {
        for (mode in listOf(ReaderPresentationMode.SINGLE, ReaderPresentationMode.SPREAD)) {
            showReader(mode = mode)
            val book = compose.onNodeWithTag(BOOK).fetchSemanticsNode()
            val paper = book.config[ReaderPaperBounds].map { it.translate(book.boundsInRoot.topLeft) }
            assertTrue("An open book exposes its paper bounds", paper.isNotEmpty())
            val rootWindowOffset = book.boundsInWindow.topLeft - book.boundsInRoot.topLeft
            val cutouts = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.displayCutout?.boundingRects.orEmpty().map {
                    Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
                        .translate(-rootWindowOffset)
                }
            val controls = ACTIONS.map { action ->
                val bounds = compose.onNodeWithTag("reader_top_$action").assertIsDisplayed()
                    .fetchSemanticsNode().boundsInRoot
                assertTrue("The $action action must fit completely inside a physical page: $bounds / $paper",
                    paper.any { page -> contains(page, bounds) })
                cutouts.forEach { cutout ->
                    assertFalse("The $action action intersects a camera cutout", bounds.overlaps(cutout))
                }
                bounds
            }
            controls.forEachIndexed { index, bounds ->
                controls.drop(index + 1).forEach { assertFalse("Paper action targets overlap", bounds.overlaps(it)) }
            }
            compose.onNodeWithTag("reader_top_search").assertDoesNotExist()
            compose.onNodeWithTag("reader_top_font").assertDoesNotExist()
            compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
        }
    }

    @Test fun closedCoversHaveNoPrintedPaperActions() {
        for (face in listOf(CoverPosition.FRONT, CoverPosition.BACK)) {
            showReader(cover = face)
            ACTIONS.forEach { compose.onNodeWithTag("reader_top_$it").assertDoesNotExist() }
            compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
        }
    }

    @Test fun restoredBackBoundaryInSinglePageStillOffersActionsOnItsLastPrintedPage() {
        val preferences = mutableStateOf(ReaderPreferences())
        showReader(mode = ReaderPresentationMode.SINGLE, cover = CoverPosition.BOUNDARY,
            preferences = preferences)
        ACTIONS.forEach { compose.onNodeWithTag("reader_top_$it").assertIsDisplayed() }
        val anchor = location()
        touchAction("theme")
        compose.waitUntil(10_000) { preferences.value.theme == ReaderTheme.DARK }
        waitForBook()
        assertEquals(anchor, location())
        val number = compose.onNodeWithTag(BOOK).fetchSemanticsNode()
            .config[com.avstaim.listai.feature.reader.ReaderPageNumberBounds].single()
        compose.onNodeWithTag(BOOK).performTouchInput { click(number.center) }
        compose.onNodeWithTag("reader_progress_percent").assertIsDisplayed()
        assertEquals(anchor, location())
    }

    @Test fun touchingPaperActionsDoesNotAlsoTurnTheUnderlyingPage() {
        val preferences = mutableStateOf(ReaderPreferences())
        val shelves = AtomicInteger()
        showReader(preferences = preferences, onShelf = { shelves.incrementAndGet() })
        val anchor = location()

        touchAction("theme")
        compose.waitUntil(10_000) { preferences.value.theme == ReaderTheme.DARK }
        waitForBook()
        assertEquals("A theme tap must not also turn the leaf", anchor, location())
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()

        touchAction("bookmarks")
        compose.onNodeWithTag("reader_paper_insert").assertIsDisplayed()
        assertEquals(anchor, location())
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
        assertEquals(anchor, location())

        touchAction("contents")
        waitForBook()
        compose.waitUntil(10_000) { contentsLocation().isNotBlank() }
        assertEquals("Opening supplementary contents preserves the source anchor", anchor, location())
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()

        touchAction("close")
        compose.waitUntil(3_000) { shelves.get() == 1 }
        assertEquals("Returning to the shelf must not also change the source anchor", anchor, location())
    }

    @Test fun theWhitespaceOfAPhysicalContentsRowNavigatesToItsExactSourceAnchor() {
        val target = showReader(mode = ReaderPresentationMode.SINGLE)
        val before = location()
        touchAction("contents")
        waitForBook()
        compose.waitUntil(10_000) { contentsLocation().isNotBlank() }
        assertEquals(before, location())

        val book = compose.onNodeWithTag(BOOK)
        val node = book.fetchSemanticsNode()
        val expected = "${target.blockId.value}:${target.charOffset}:${target.fallbackGlobalOffset}"
        val rowIndex = node.config[ReaderContentsTargets].indexOf(expected)
        assertTrue("The physical contents exposes its later chapter link", rowIndex >= 0)
        val row = node.config[ReaderContentsRows][rowIndex]
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("A one-line contents row has a spacious 48 dp touch region: $row",
            row.height >= 48f * density - 1f)
        // The bottom-right padding is outside the title glyphs, but is part of the printed row.
        book.performTouchInput { click(Offset(row.right - 2f * density, row.bottom - 2f * density)) }
        waitForBook()
        compose.waitUntil(10_000) { contentsLocation().isEmpty() }
        assertEquals(expected, location())
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
    }

    @Test fun closingTheRigidCoverDoesNotPaintPaperActionsOverTheBoard() {
        showReader(mode = ReaderPresentationMode.SINGLE)
        touchAction("contents")
        waitForBook()
        compose.waitUntil(10_000) { contentsLocation().isNotEmpty() }
        val anchor = location()
        val book = compose.onNodeWithTag(BOOK)
        book.performTouchInput {
            down(Offset(width * .15f, height * .52f))
            moveTo(Offset(width * .62f, height * .52f), delayMillis = 200)
        }
        compose.waitForIdle()
        ACTIONS.forEach { compose.onNodeWithTag("reader_top_$it").assertDoesNotExist() }
        assertEquals(anchor, location())
        // Bring the board back with a real reversing gesture, then release without a flick.
        book.performTouchInput {
            moveTo(Offset(width * .15f, height * .52f), delayMillis = 500)
            advanceEventTime(500)
            up()
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("reader_top_close").fetchSemanticsNodes().isNotEmpty() }
        ACTIONS.forEach { compose.onNodeWithTag("reader_top_$it").assertIsDisplayed() }
        assertEquals(anchor, location())
    }

    private fun showReader(
        mode: ReaderPresentationMode = ReaderPresentationMode.SPREAD,
        cover: CoverPosition = CoverPosition.OPEN,
        preferences: MutableState<ReaderPreferences> = mutableStateOf(ReaderPreferences()),
        onShelf: () -> Unit = {},
    ): BookLocation {
        val paragraphs = List(18) { index -> BookBlock.Paragraph(BlockId("paper-$index"),
            listOf(InlineNode.Text("Chapter $index. The story continues on the white paper. ".repeat(24)))) }
        fun anchor(index: Int) = BookLocation(paragraphs[index].id, 0,
            paragraphs.take(index).sumOf { it.plainText().length + 1L })
        val document = BookDocument(BookId("stage72-${mode.name}-${cover.name}"),
            BookMetadata("A Book on Paper", listOf("Reader QA"), "en"), paragraphs,
            tableOfContents = listOf(BookTocEntry("The first chapter", anchor(0)),
                BookTocEntry("A later chapter", anchor(12))))
        compose.runOnUiThread {
            compose.activity.setContent {
                ListaiTheme(darkTheme = preferences.value.theme == ReaderTheme.DARK) {
                    ReaderScreen(document, presentationMode = mode,
                        initialProgress = ReadingProgress(anchor(6), cover, 1f / 3f),
                        preferences = preferences.value,
                        darkTheme = preferences.value.theme == ReaderTheme.DARK,
                        onPreferencesChange = { preferences.value = it }, onReturnToShelf = onShelf)
                }
            }
        }
        waitForBook()
        if (cover == CoverPosition.OPEN) compose.onNodeWithTag("reader_top_close").assertIsDisplayed()
        return anchor(12)
    }

    private fun waitForBook() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag(BOOK).fetchSemanticsNodes().any {
                it.config.getOrElse(ReaderPaginationComplete) { false }
            }
        }
        compose.waitForIdle()
    }

    private fun touchAction(action: String) = compose.onNodeWithTag("reader_top_$action")
        .performTouchInput { click(center) }

    private fun location() = compose.onNodeWithTag(BOOK).fetchSemanticsNode().config[ReaderSemanticLocation]

    private fun contentsLocation() = compose.onNodeWithTag(BOOK).fetchSemanticsNode()
        .config.getOrElse(ReaderContentsLocation) { "" }

    private fun contains(outer: Rect, inner: Rect): Boolean =
        inner.left >= outer.left - 1f && inner.top >= outer.top - 1f &&
            inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f

    private companion object {
        const val BOOK = "book_view_demo"
        val ACTIONS = listOf("close", "contents", "theme", "bookmarks")
    }
}
