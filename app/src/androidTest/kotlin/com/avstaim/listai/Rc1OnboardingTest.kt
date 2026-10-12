package com.avstaim.listai

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real reader gestures and saved Compose state; disk persistence is covered in core/database. */
@RunWith(AndroidJUnit4::class)
class Rc1OnboardingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun externalReplayOpensEitherCoverAndAnnotationBoundaryAtTheSavedSourceAnchor() {
        for (pose in listOf(CoverPosition.FRONT, CoverPosition.BACK, CoverPosition.BOUNDARY, CoverPosition.OPEN)) {
            val fixture = ReaderFixture(completed = true, opening = .5f, cover = pose,
                annotation = pose == CoverPosition.OPEN)
            install(fixture)
            waitForBook()
            compose.runOnUiThread { fixture.replay.value = 1L }
            compose.onNodeWithTag("reader_onboarding").assertDoesNotExist()
            compose.runOnUiThread { fixture.opening.value = null }
            awaitStep(1)
            waitForBook()
            assertEquals("Opening $pose must retain the source place", fixture.initial.location, fixture.progress.value.location)
            assertEquals(CoverPosition.OPEN, fixture.progress.value.cover)
            assertNull(fixture.progress.value.annotationLocation)
            assertNull(fixture.progress.value.tocEntryIndex)
            if (pose == CoverPosition.OPEN) {
                // After the external request has opened the source, a user may visit the
                // contents during the guide. Restoring after rotation must retain that visit,
                // not consume the old request again and jump back into the main text.
                compose.onNodeWithTag("reader_top_contents").performClick()
                compose.waitUntil(30_000) {
                    book().fetchSemanticsNode().config.getOrElse(ReaderContentsLocation) { "" }.isNotEmpty() &&
                        !book().fetchSemanticsNode().config.getOrElse(ReaderJumpInProgress) { true }
                }
                lateinit var saved: Map<String, List<Any?>>
                compose.runOnIdle {
                    saved = fixture.registry.value.performSave()
                    fixture.emitted.value = false
                }
                compose.waitForIdle()
                compose.runOnUiThread {
                    fixture.registry.value = SaveableStateRegistry(saved) { true }
                    fixture.emitted.value = true
                }
                awaitStep(1)
                waitForBook()
                compose.mainClock.advanceTimeBy(2_000)
                assertTrue("Restoration must not dispatch a handled shelf replay again",
                    book().fetchSemanticsNode().config.getOrElse(ReaderContentsLocation) { "" }.isNotEmpty())
                assertEquals(fixture.initial.location, fixture.progress.value.location)
            }
            compose.onNodeWithTag("reader_onboarding_skip").performClick()
            compose.waitUntil(5_000) { fixture.completions == 1 }
            val opened = semanticLocation()
            book().performTouchInput {
                swipe(Offset(width * .77f, height * .5f), Offset(width * .23f, height * .5f), 850L)
            }
            waitForBook()
            assertNotEquals(opened, semanticLocation())
            val turned = semanticLocation()
            compose.mainClock.advanceTimeBy(2_000)
            assertEquals("A consumed replay must not jump back again", turned, semanticLocation())
        }
    }

    @Test fun shelfReplayIsConsumedByOneBookAndStillAllowsAnotherReplayWithinThatReader() {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val original = controller.selection.value
        val fixtures = mutableListOf<BookId>()
        try {
            repeat(2) { fixtures += importReplayBook(controller) }
            compose.runOnUiThread {
                controller.completeReaderOnboarding()
                controller.returnToShelf()
            }
            awaitShelf()
            compose.waitUntil(10_000) { controller.readerOnboardingCompleted.value == true }
            compose.onNodeWithTag("library_settings").performClick()
            compose.onNodeWithTag("reader_show_hints").performScrollTo().performClick()
            awaitStep(1)
            val firstBook = requireNotNull(controller.selection.value).opened.book.id
            compose.onNodeWithTag("reader_onboarding_skip").performClick()
            compose.onNodeWithTag("reader_onboarding").assertDoesNotExist()

            // Consuming the shelf request must not collide with the local replay sequence.
            book().performTouchInput { click(center) }
            compose.onNodeWithTag("reader_insert_settings").performClick()
            compose.onNodeWithTag("reader_show_hints").performScrollTo().performClick()
            awaitStep(1)
            compose.onNodeWithTag("reader_onboarding_skip").performClick()
            compose.onNodeWithTag("reader_onboarding").assertDoesNotExist()
            compose.waitForIdle()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            awaitShelf()

            val secondBook = fixtures.first { it != firstBook }
            val secondBookTag = "library_book_${secondBook.value}"
            if (compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(secondBookTag))
            }
            compose.onNodeWithTag(secondBookTag).performClick()
            compose.waitUntil(30_000) {
                controller.selection.value?.opened?.book?.id == secondBook &&
                    transitionPhase() == ShelfReaderPhase.READING.name
            }
            waitForBook()
            compose.mainClock.advanceTimeBy(2_000)
            compose.onNodeWithTag("reader_onboarding").assertDoesNotExist()
        } finally {
            compose.runOnUiThread {
                fixtures.forEach(controller::delete)
                original?.let { controller.open(it.opened.book.id) } ?: controller.returnToShelf()
            }
            compose.waitUntil(30_000) {
                !controller.busy.value && controller.selection.value?.opened?.book?.id == original?.opened?.book?.id &&
                    runBlocking { controller.repository.books.first().none { it.id in fixtures } }
            }
        }
    }

    @Test fun firstOpeningWaitsForPreferenceAndFlightAndVisualDemoNeverChangesReadingPlace() {
        val fixture = ReaderFixture(completed = null, opening = .5f)
        install(fixture)
        waitForBook()
        compose.onNodeWithTag("reader_onboarding").assertDoesNotExist()
        compose.runOnUiThread { fixture.completed.value = false }
        compose.onNodeWithTag("reader_onboarding").assertDoesNotExist()
        compose.runOnUiThread { fixture.opening.value = null }
        awaitStep(1)
        val initial = fixture.progress.value
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        assertEquals(initial, fixture.progress.value)

        compose.onNodeWithTag("reader_onboarding_skip").performClick()
        compose.onNodeWithTag("reader_onboarding").assertDoesNotExist()
        compose.waitUntil(5_000) { fixture.completions == 1 }
        // Simulate a delayed completion write: the old false value must not restart the guide.
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("reader_onboarding").assertDoesNotExist()
        assertEquals(initial, fixture.progress.value)
        compose.runOnUiThread {
            fixture.completed.value = true
            fixture.instance.value++
        }
        waitForBook()
        compose.onNodeWithTag("reader_onboarding").assertDoesNotExist()
    }

    @Test fun realGesturesAdvanceTheFirstFiveStepsAndInformationContinuesOnlyWithNext() {
        val fixture = ReaderFixture(completed = false)
        install(fixture)
        awaitStep(1)
        book().performTouchInput { click(center) }
        compose.onNodeWithTag("reader_paper_insert").assertIsDisplayed()
        awaitStep(2)
        waitForBook()
        val original = semanticLocation()

        book().performTouchInput {
            swipe(Offset(width * .77f, height * .5f), Offset(width * .23f, height * .5f), 850L)
        }
        awaitStep(3)
        waitForBook()
        assertNotEquals(original, semanticLocation())
        val next = semanticLocation()
        book().performTouchInput {
            swipe(Offset(width * .23f, height * .5f), Offset(width * .77f, height * .5f), 850L)
        }
        awaitStep(4)
        waitForBook()
        assertNotEquals(next, semanticLocation())

        book().performTouchInput { click(Offset(width * .08f, height * .5f)) }
        awaitStep(5)
        waitForBook()
        book().performTouchInput { click(Offset(width * .92f, height * .5f)) }
        awaitStep(ReaderOnboardingStep.BOOKMARKS.ordinal + 1)
        waitForBook()
        assertEquals("The fifth gesture must leave the information guide open", 0, fixture.completions)

        // Information steps leave normal reading gestures available, without treating
        // another real page turn as completion of their instructions.
        val beforeOrdinaryTurn = semanticLocation()
        book().performTouchInput {
            swipe(Offset(width * .77f, height * .5f), Offset(width * .23f, height * .5f), 850L)
        }
        waitForBook()
        awaitStep(ReaderOnboardingStep.BOOKMARKS.ordinal + 1)
        assertNotEquals(beforeOrdinaryTurn, semanticLocation())
        assertEquals(0, fixture.completions)

        val source = fixture.progress.value.location
        val displayedSource = semanticLocation()
        val informationSteps = ReaderOnboardingStep.entries.drop(ReaderOnboardingStep.BOOKMARKS.ordinal)
        assertEquals(7, informationSteps.size)
        informationSteps.forEachIndexed { index, step ->
            awaitStep(step.ordinal + 1)
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
            assertEquals("Showing $step must retain the source place", source, fixture.progress.value.location)
            assertEquals(displayedSource, semanticLocation())
            compose.onNodeWithTag("reader_onboarding_next").performClick()
            waitForBook()
            assertEquals("Next on $step must retain the source place", source, fixture.progress.value.location)
            assertEquals(displayedSource, semanticLocation())
            if (index < informationSteps.lastIndex) assertEquals(0, fixture.completions)
        }
        compose.waitUntil(5_000) { fixture.completions == 1 }
        compose.onNodeWithTag("reader_onboarding").assertDoesNotExist()
        assertEquals(0, fixture.shelfReturns)
    }

    @Test fun unfinishedGuideRestoresGestureAndInformationStepsAndBothReplayLinksRemainAvailable() {
        val fixture = ReaderFixture(completed = false)
        install(fixture)
        awaitStep(1)
        compose.onNodeWithTag("reader_onboarding_next").performClick()
        compose.onNodeWithTag("reader_onboarding_next").performClick()
        awaitStep(3)
        lateinit var saved: Map<String, List<Any?>>
        compose.runOnIdle {
            saved = fixture.registry.value.performSave()
            fixture.emitted.value = false
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            fixture.registry.value = SaveableStateRegistry(saved) { true }
            fixture.emitted.value = true
        }
        awaitStep(3)
        waitForBook()
        val source = fixture.progress.value.location
        val displayedSource = semanticLocation()
        repeat(ReaderOnboardingStep.STACKS.ordinal - 2) {
            compose.onNodeWithTag("reader_onboarding_next").performClick()
        }
        awaitStep(ReaderOnboardingStep.STACKS.ordinal + 1)
        assertEquals(source, fixture.progress.value.location)
        assertEquals(displayedSource, semanticLocation())
        compose.runOnIdle {
            saved = fixture.registry.value.performSave()
            fixture.emitted.value = false
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            fixture.registry.value = SaveableStateRegistry(saved) { true }
            fixture.emitted.value = true
        }
        awaitStep(ReaderOnboardingStep.STACKS.ordinal + 1)
        waitForBook()
        assertEquals("Restoring an information step must retain the source place", source, fixture.progress.value.location)
        assertEquals(displayedSource, semanticLocation())
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { fixture.completions == 1 }
        compose.onNodeWithTag("reader_onboarding").assertDoesNotExist()
        assertEquals(0, fixture.shelfReturns)
        compose.runOnUiThread { fixture.completed.value = true }

        waitForBook()
        book().performTouchInput { click(center) }
        compose.onNodeWithTag("reader_insert_settings").performClick()
        compose.onNodeWithTag("reader_show_hints").performScrollTo().performClick()
        awaitStep(1)
        assertTrue(fixture.completed.value == true)
        assertEquals(source, fixture.progress.value.location)
        assertEquals(displayedSource, semanticLocation())
        compose.onNodeWithTag("reader_onboarding_skip").performClick()
        compose.waitUntil(5_000) { fixture.completions == 2 }
        compose.onNodeWithTag("reader_onboarding").assertDoesNotExist()

        // The same full guide is reachable directly from the main paper insert.
        waitForBook()
        book().performTouchInput { click(center) }
        compose.onNodeWithTag("reader_paper_insert").assertIsDisplayed()
        compose.onNodeWithTag("reader_insert_guide").performScrollTo().assertIsDisplayed().performClick()
        awaitStep(1)
        assertTrue(fixture.completed.value == true)
        assertEquals("Menu replay must retain the source place", source, fixture.progress.value.location)
        assertEquals(displayedSource, semanticLocation())
        compose.onNodeWithTag("reader_onboarding_skip").performClick()
        compose.waitUntil(5_000) { fixture.completions == 3 }
        compose.onNodeWithTag("reader_onboarding").assertDoesNotExist()
        assertEquals(0, fixture.shelfReturns)
    }

    private fun install(fixture: ReaderFixture) {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalSaveableStateRegistry provides fixture.registry.value) {
                    if (fixture.emitted.value) key(fixture, fixture.instance.value) {
                        ListaiTheme {
                            ReaderScreen(fixture.document, presentationMode = ReaderPresentationMode.SINGLE,
                                initialProgress = fixture.initial,
                                onProgressChanged = { fixture.progress.value = it },
                                onboardingCompleted = fixture.completed.value,
                                onboardingReplayRequest = fixture.replay.value,
                                onOnboardingComplete = { fixture.completions++ },
                                onReturnToShelf = { fixture.shelfReturns++ },
                                transitionCoverProgress = fixture.opening.value)
                        }
                    }
                }
            }
        }
    }

    private fun awaitStep(step: Int) {
        val expected = compose.activity.getString(com.avstaim.listai.feature.reader.R.string.reader_onboarding_counter,
            step, ReaderOnboardingStep.entries.size)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("reader_onboarding_step").fetchSemanticsNodes().any {
                it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                    .any { text -> text.text == expected }
            }
        }
        compose.onNodeWithTag("reader_onboarding_step").assertTextEquals(expected)
    }

    private fun waitForBook() = compose.waitUntil(30_000) {
        compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().any {
            it.config.getOrElse(ReaderPaginationComplete) { false } &&
                !it.config.getOrElse(ReaderJumpInProgress) { true } &&
                it.config.getOrElse(ReaderTurnProgress) { 1f } == 0f
        }
    }

    private fun book() = compose.onNodeWithTag("book_view_demo")
    private fun semanticLocation() = book().fetchSemanticsNode().config[ReaderSemanticLocation]
    private fun transitionPhase() = compose.onAllNodesWithTag("shelf_reader_transition")
        .fetchSemanticsNodes().singleOrNull()?.config?.getOrElse(ShelfTransitionPhase) { "" }
    private fun awaitShelf() = compose.waitUntil(30_000) { transitionPhase() == ShelfReaderPhase.SHELF.name }

    private fun importReplayBook(controller: LibraryController): BookId {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><book-title>Guide replay ${UUID.randomUUID()}</book-title><lang>en</lang></title-info></description><body><section><p>${"The guide is requested for one reading session. ".repeat(160)}</p></section></body></FictionBook>"""
        return runBlocking { controller.repository.import("guide-replay.fb2") { xml.byteInputStream() } }
    }

    private class ReaderFixture(completed: Boolean?, opening: Float? = null,
        cover: CoverPosition = CoverPosition.OPEN, annotation: Boolean = false) {
        val completed = mutableStateOf(completed)
        val opening = mutableStateOf(opening)
        val replay = mutableStateOf(0L)
        val instance = mutableStateOf(0)
        val emitted = mutableStateOf(true)
        val registry = mutableStateOf(SaveableStateRegistry(null) { true })
        var completions = 0
        var shelfReturns = 0
        val document = BookDocument(BookId("onboarding-fixture"), BookMetadata("Reading gestures",
            annotationBlocks = if (annotation) listOf(BookBlock.Paragraph(BlockId("guide-annotation"),
                listOf(InlineNode.Text("The inside cover has its own introduction. ".repeat(80))))) else emptyList()),
            List(12) { index -> BookBlock.Paragraph(BlockId("gesture-paragraph-$index"),
                listOf(InlineNode.Text("Page $index. The quiet book keeps the exact place of a reader. ".repeat(55)))) })
        val initial = ReadingProgress(BookLocation(document.blocks[4].id, 0,
            document.blocks.take(4).sumOf { it.plainText().length + 1L }), cover,
            annotationLocation = if (annotation) BookLocation(BlockId("guide-annotation"), 0, 0) else null)
        val progress = mutableStateOf(initial)
    }
}
