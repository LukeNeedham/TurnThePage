package com.avstaim.listai

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Independent UI regressions for the eight stage 7.1 corrections. */
@RunWith(AndroidJUnit4::class)
class Stage71AcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun spreadHasOneMenuCentreAndWideOuterTurnZones() {
        showReader()
        val book = compose.onNodeWithTag(BOOK)
        val initial = location()

        // Exactly the spine, not the centre of either individual leaf.
        book.performTouchInput { click(center) }
        compose.onNodeWithTag("reader_paper_insert").assertIsDisplayed()
        assertEquals(initial, location())
        dismissInsert()

        // This was the right leaf's menu centre before 7.1. It must now turn a page.
        book.performTouchInput { click(Offset(width * .75f, height * .52f)) }
        compose.waitUntil(10_000) { location() != initial }
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()

        val advanced = location()
        book.performTouchInput {
            down(Offset(width * .5f, height * .52f))
            advanceEventTime(160)
            moveTo(Offset(width * .43f, height * .52f), delayMillis = 160)
            cancel()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
        assertEquals(advanced, location())
    }

    @Test fun bothClosedFacesReturnFromTheEmptyHalfWithoutChangingTheAnchor() {
        for (face in listOf(CoverPosition.FRONT, CoverPosition.BACK)) {
            val shelved = AtomicInteger()
            val checkpoint = mutableStateOf<ReadingProgress?>(null)
            showReader(face, onProgress = { checkpoint.value = it }, onShelf = { shelved.incrementAndGet() })
            val before = checkpoint.value
            compose.onNodeWithTag(BOOK).performTouchInput {
                click(Offset(width * if (face == CoverPosition.FRONT) .25f else .75f, height * .5f))
            }
            compose.waitUntil(3_000) { shelved.get() == 1 }
            assertEquals(before, checkpoint.value)
            compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
        }
    }

    @Test fun sourceProgressAndFourMarginActionsAreAvailableWithoutOpeningAnInsert() {
        val preferences = mutableStateOf(ReaderPreferences())
        val shelves = AtomicInteger()
        showReader(onShelf = { shelves.incrementAndGet() }, preferences = preferences)
        val anchor = location()
        val footers = compose.onNodeWithTag(BOOK).fetchSemanticsNode().config[ReaderVisibleFooters]
        assertTrue("A completed source leaf keeps its page number and total: $footers", footers.contains("/"))
        assertFalse("Source progress is drawn as dots on paper, without a percentage label: $footers",
            footers.contains("%"))

        listOf("bookmarks", "theme", "contents", "close").forEach {
            val control = compose.onNodeWithTag("reader_top_$it").assertIsDisplayed()
            val bounds = control.fetchSemanticsNode().boundsInRoot
            val cutouts = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.displayCutout?.boundingRects.orEmpty()
            cutouts.forEach { cutout ->
                assertFalse("The $it control overlaps a camera cutout",
                    bounds.overlaps(androidx.compose.ui.geometry.Rect(cutout.left.toFloat(),
                        cutout.top.toFloat(), cutout.right.toFloat(), cutout.bottom.toFloat())))
            }
        }
        compose.onNodeWithTag("reader_top_search").assertDoesNotExist()
        compose.onNodeWithTag("reader_top_font").assertDoesNotExist()
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()

        compose.onNodeWithTag("reader_top_theme").performClick()
        compose.waitUntil(3_000) { preferences.value.theme != ReaderTheme.LIGHT }
        waitForBook()
        assertEquals(anchor, location())
        compose.onNodeWithTag("reader_top_bookmarks").performClick()
        compose.onNodeWithTag("reader_paper_insert").assertIsDisplayed()
        dismissInsert()
        compose.onNodeWithTag("reader_top_contents").performClick()
        waitForBook()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(BOOK).fetchSemanticsNodes().any {
                it.config.getOrElse(ReaderContentsLocation) { "" }.isNotBlank()
            }
        }
        assertEquals(anchor, location())
        compose.onNodeWithTag("reader_top_close").performClick()
        compose.waitUntil(3_000) { shelves.get() == 1 }
    }

    @Test fun selectedBookLiftsOpensClosesAndReturnsToItsShelfSlotWithoutLosingPosition() {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        compose.runOnUiThread { controller.returnToShelf() }
        compose.waitUntil(10_000) { phase() == ShelfReaderPhase.SHELF.name }
        val id = runBlocking { controller.repository.books.first().first().id }
        val sourceBounds = compose.onNodeWithTag("library_book_${id.value}")
            .fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("library_book_${id.value}").performClick()
            advanceToPhase(ShelfReaderPhase.LIFTING)
            val first = compose.onNodeWithTag("shelf_flying_book").fetchSemanticsNode().boundsInRoot
            assertTrue(first.width >= sourceBounds.width - 1f)
            compose.mainClock.advanceTimeBy(180)
            val lifted = compose.onNodeWithTag("shelf_flying_book").fetchSemanticsNode().boundsInRoot
            assertTrue("The selected book must enlarge, not crossfade the whole screen", lifted.width > first.width)
            advanceToPhase(ShelfReaderPhase.OPENING)
            assertTrue(compose.onNodeWithTag("shelf_reader_transition").fetchSemanticsNode()
                .config[ShelfTransitionProgress] < 1f)
            advanceToPhase(ShelfReaderPhase.READING)
            val anchor = location()

            compose.onNodeWithTag("reader_top_close").performClick()
            advanceToPhase(ShelfReaderPhase.CLOSING)
            assertNotNull("Keep the live reading checkpoint through the close animation", controller.selection.value)
            assertEquals(anchor, location())
            advanceToPhase(ShelfReaderPhase.LOWERING)
            compose.mainClock.advanceTimeBy(180)
            val lowering = compose.onNodeWithTag("shelf_flying_book").fetchSemanticsNode().boundsInRoot
            assertTrue(lowering.width > sourceBounds.width)
            advanceToPhase(ShelfReaderPhase.SHELF)
            assertNull(controller.selection.value)
            val returned = compose.onNodeWithTag("library_book_${id.value}").fetchSemanticsNode().boundsInRoot
            assertEquals(sourceBounds, returned)

            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag("library_book_${id.value}").performClick()
            compose.waitUntil(30_000) { phase() == ShelfReaderPhase.READING.name }
            waitForBook()
            assertEquals(anchor, location())
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun closeDuringPageSettleFreezesTheAnchorAndSurvivesActivityRecreation() {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        if (controller.selection.value == null) {
            val id = runBlocking { controller.repository.books.first().first().id }
            compose.runOnUiThread { controller.open(id) }
        }
        compose.waitUntil(30_000) { phase() == ShelfReaderPhase.READING.name }
        waitForBook()
        val selected = requireNotNull(controller.selection.value)
        val before = selected.initialProgress.location
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag(BOOK).performTouchInput {
                click(Offset(width * .86f, height * .52f))
            }
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("reader_top_close").performClick()
            advanceToPhase(ShelfReaderPhase.CLOSING)
            compose.mainClock.advanceTimeBy(120)
            assertEquals("Closing must not let the unfinished turn commit", before,
                controller.selection.value?.initialProgress?.location)
            // The configuration-recreation path must preserve the user's pending shelf intent.
            compose.activityRule.scenario.recreate()
            compose.mainClock.autoAdvance = true
            compose.waitUntil(30_000) { phase() == ShelfReaderPhase.SHELF.name }
            assertNull(controller.selection.value)
            compose.onNodeWithTag("library_book_${selected.opened.book.id.value}").performClick()
            compose.waitUntil(30_000) { phase() == ShelfReaderPhase.READING.name }
            waitForBook()
            assertEquals(before, controller.selection.value?.initialProgress?.location)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun showReader(
        cover: CoverPosition = CoverPosition.OPEN,
        onProgress: (ReadingProgress) -> Unit = {},
        onShelf: () -> Unit = {},
        preferences: androidx.compose.runtime.MutableState<ReaderPreferences> = mutableStateOf(ReaderPreferences()),
    ) {
        val paragraphs = List(24) { index -> BookBlock.Paragraph(BlockId("qa-$index"),
            listOf(InlineNode.Text("Section $index. A white page and a quiet blue binding. ".repeat(24)))) }
        val offset = paragraphs.take(6).sumOf { it.plainText().length + 1L }
        val anchor = BookLocation(paragraphs[6].id, 0, offset)
        val document = BookDocument(BookId("stage71-${cover.name}"),
            BookMetadata("A Book for Stage 7.1", listOf("Reader QA"), "en"), paragraphs,
            tableOfContents = listOf(BookTocEntry("A quiet chapter", anchor)))
        compose.runOnUiThread {
            compose.activity.setContent {
                ListaiTheme(darkTheme = preferences.value.theme == ReaderTheme.DARK) {
                    ReaderScreen(document, presentationMode = ReaderPresentationMode.SPREAD,
                        initialProgress = ReadingProgress(anchor, cover, .25f),
                        preferences = preferences.value,
                        darkTheme = preferences.value.theme == ReaderTheme.DARK,
                        onPreferencesChange = { preferences.value = it },
                        onProgressChanged = onProgress, onReturnToShelf = onShelf)
                }
            }
        }
        waitForBook()
        if (cover == CoverPosition.OPEN) compose.waitUntil(10_000) {
            compose.onNodeWithTag(BOOK).fetchSemanticsNode().config.contains(SemanticsActions.OnClick)
        }
    }

    private fun waitForBook() = compose.waitUntil(30_000) {
        compose.onAllNodesWithTag(BOOK).fetchSemanticsNodes().any {
            it.config.getOrElse(ReaderPaginationComplete) { false }
        }
    }

    private fun dismissInsert() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
    }

    private fun location() = compose.onNodeWithTag(BOOK).fetchSemanticsNode().config[ReaderSemanticLocation]

    private fun phase() = compose.onNodeWithTag("shelf_reader_transition").fetchSemanticsNode()
        .config[ShelfTransitionPhase]

    private fun advanceToPhase(expected: ShelfReaderPhase) = compose.waitUntil(30_000) {
        compose.mainClock.advanceTimeByFrame()
        // State-backed semantics can reflect a new phase before the next composition has
        // installed its nodes and enabled input. A paused-clock test must wait for both.
        phase() == expected.name && when (expected) {
            ShelfReaderPhase.LIFTING, ShelfReaderPhase.LOWERING ->
                compose.onAllNodesWithTag("shelf_flying_book").fetchSemanticsNodes().isNotEmpty()
            ShelfReaderPhase.READING -> compose.onAllNodesWithTag("reader_top_close")
                .fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) }
            ShelfReaderPhase.SHELF -> compose.onAllNodesWithTag("library_import").fetchSemanticsNodes().isNotEmpty()
            else -> true
        }
    }

    private companion object { const val BOOK = "book_view_demo" }
}
