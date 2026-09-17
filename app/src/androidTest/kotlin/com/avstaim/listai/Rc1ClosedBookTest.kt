package com.avstaim.listai

import android.graphics.Bitmap
import android.os.Build
import android.view.Choreographer
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.adaptive.DevicePosture
import com.avstaim.listai.core.adaptive.FoldBounds
import com.avstaim.listai.core.adaptive.FoldOrientation
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real ReaderScreen supplies desk controls outside the scaled physical cover. */
@RunWith(AndroidJUnit4::class)
class Rc1ClosedBookTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun bothClosedFacesKeepTheirActionsOutsideTheBoardAndEmptyDeskReturnsToShelf() {
        for (mode in modes) for (face in faces) {
            val fixture = showReader(mode, face)
            assertPortraitRestingCover(fixture)
            assertControlsOutsideCover(fixture)
            capture("${mode.name}-${face.name}-closed")
            val before = fixture.progress.get()
            val host = compose.onNodeWithTag(HOST).fetchSemanticsNode().boundsInRoot
            val cover = requireNotNull(fixture.bounds.get())
            val freePoint = Offset(if (cover.left > host.width - cover.right) cover.left / 2f
                else (cover.right + host.width) / 2f, cover.center.y)
            assertFalse(cover.contains(freePoint))
            compose.onNodeWithTag(HOST).performTouchInput { click(freePoint) }
            compose.waitUntil(5_000) { fixture.shelfRequests.get() == 1 }
            assertEquals(before, fixture.progress.get())
            compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
        }
    }

    @Test fun controlsDisappearDuringCoverDragAndReturnOnlyAfterTheClosedBookSettles() {
        for (mode in modes) for (face in faces) {
            val fixture = showReader(mode, face)
            val openingForward = face == CoverPosition.FRONT
            val closedBounds = requireNotNull(fixture.bounds.get())
            compose.mainClock.autoAdvance = false
            try {
                val from = Offset(closedBounds.left + closedBounds.width * if (openingForward) .9f else .1f,
                    closedBounds.center.y)
                val to = from + Offset(closedBounds.width * if (openingForward) -.65f else .65f, 0f)
                dragAndHold(from, to)
                awaitMovingCover()
                assertClosedControlsAbsent()
                assertEquals(face, fixture.progress.get().cover)
                capture("${mode.name}-${face.name}-opening")
                compose.onNodeWithTag(HOST).performTouchInput { up() }
                advanceUntil { fixture.progress.get().cover !in faces && resting() }
            } finally { compose.mainClock.autoAdvance = true }
            compose.waitForIdle()
            assertClosedControlsAbsent()
            if (face == CoverPosition.FRONT) {
                // Single opens onto its annotated rigid inside; spread shows the same board
                // opposite contents. Both are readable paper and retain the five margin actions.
                listOf("close", "contents", "settings", "theme", "bookmarks").forEach {
                    compose.onNodeWithTag("reader_top_$it").assertIsDisplayed()
                }
                if (mode == ReaderPresentationMode.SINGLE) {
                    assertNotNull(fixture.progress.get().annotationLocation)
                    assertEquals("", bookConfig()[ReaderVisibleFooters])
                    assertTrue(bookConfig()[ReaderPageNumberBounds].isEmpty())
                }
            }

            val host = compose.onNodeWithTag(HOST).fetchSemanticsNode().boundsInRoot
            val closingForward = !openingForward
            compose.mainClock.autoAdvance = false
            try {
                val from = Offset(host.width * if (closingForward) .9f else .1f, host.height * .5f)
                val to = from + Offset(host.width * if (closingForward) -.65f else .65f, 0f)
                dragAndHold(from, to)
                awaitMovingCover()
                assertClosedControlsAbsent()
                capture("${mode.name}-${face.name}-closing")
                compose.onNodeWithTag(HOST).performTouchInput { up() }
                advanceUntil { fixture.progress.get().cover == face && resting() }
                // The geometry first settles, then the closed book moves back on the desk.
                advanceUntil { controlsPresent() }
            } finally { compose.mainClock.autoAdvance = true }
            compose.waitForIdle()
            assertPortraitRestingCover(fixture)
            assertControlsOutsideCover(fixture)
            assertEquals(0, fixture.shelfRequests.get())
            capture("${mode.name}-${face.name}-settled")
        }
    }

    @Test fun centeredFoldKeepsAutomaticSpreadWhileTheClosedBookUsesItsVisualRestingScale() {
        val fixture = Fixture(ReaderPresentationMode.AUTOMATIC, CoverPosition.FRONT)
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.fillMaxSize()) {
                        ListaiTheme(darkTheme = false) {
                            ReaderScreen(
                                fixture.document,
                                Modifier.requiredSize(680.dp, 760.dp).testTag(HOST),
                                devicePosture = DevicePosture.Flat(
                                    foldBounds = FoldBounds(339, 0, 341, 760),
                                    orientation = FoldOrientation.VERTICAL,
                                    separating = true,
                                    occluding = true,
                                ),
                                presentationMode = ReaderPresentationMode.AUTOMATIC,
                                initialProgress = fixture.progress.get(),
                                onProgressChanged = { fixture.progress.set(it) },
                                onboardingCompleted = true,
                                onBookReady = { fixture.bounds.set(it) },
                            )
                        }
                    }
                }
            }
        }
        compose.waitUntil(30_000) {
            bookNode()?.config?.getOrElse(ReaderPaginationComplete) { false } == true &&
                fixture.bounds.get() != null && controlsPresent()
        }
        assertTrue(bookConfig()[ReaderLayoutIdentity].contains("pageMode=SPREAD"))
        assertPortraitRestingCover(fixture)
    }

    private fun dragAndHold(from: Offset, to: Offset) {
        compose.onNodeWithTag(HOST).performTouchInput {
            down(from)
            moveTo(to, delayMillis = 500)
        }
    }

    private fun awaitMovingCover() = advanceUntil {
        val config = bookNode()?.config ?: return@advanceUntil false
        config[ReaderTurnProgress] in .05f.. .98f && config[ReaderTurnSubject].endsWith("cover") && !controlsPresent()
    }

    private fun assertControlsOutsideCover(fixture: Fixture) {
        val cover = requireNotNull(fixture.bounds.get())
        val origin = compose.onNodeWithTag(HOST).fetchSemanticsNode().boundsInRoot.topLeft
        controlTags.forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.translate(-origin)
            assertFalse("$tag overlaps the real ${fixture.face}/${fixture.mode} cover: button=$bounds cover=$cover",
                bounds.overlaps(cover))
        }
    }

    private fun assertPortraitRestingCover(fixture: Fixture) {
        val cover = requireNotNull(fixture.bounds.get())
        val host = compose.onNodeWithTag(HOST).fetchSemanticsNode().boundsInRoot
        assertTrue("Closed ${fixture.face}/${fixture.mode} cover must stay portrait: $cover",
            cover.width / cover.height <= .70f)
        assertTrue("Closed ${fixture.face}/${fixture.mode} cover must leave visible desk margins: cover=$cover host=$host",
            cover.width < host.width * .92f && cover.height < host.height * .94f)
    }

    private fun assertClosedControlsAbsent() = controlTags.forEach {
        compose.onNodeWithTag(it).assertDoesNotExist()
    }

    private fun showReader(mode: ReaderPresentationMode, face: CoverPosition): Fixture {
        val fixture = Fixture(mode, face)
        compose.runOnUiThread {
            compose.activity.setContent {
                key(fixture.document.id.value) {
                    ListaiTheme(darkTheme = face == CoverPosition.BACK) {
                        ReaderScreen(fixture.document, Modifier.testTag(HOST), presentationMode = mode,
                            initialProgress = fixture.progress.get(), onProgressChanged = { fixture.progress.set(it) },
                            darkTheme = face == CoverPosition.BACK, onboardingCompleted = true,
                            onBookReady = { fixture.bounds.set(it) },
                            onReturnToShelf = { fixture.shelfRequests.incrementAndGet() })
                    }
                }
            }
        }
        compose.waitUntil(30_000) {
            bookNode()?.config?.getOrElse(ReaderPaginationComplete) { false } == true &&
                controlsPresent() && fixture.bounds.get() != null && resting()
        }
        compose.waitForIdle()
        return fixture
    }

    private fun controlsPresent() = controlTags.all { compose.onAllNodesWithTag(it).fetchSemanticsNodes().size == 1 }
    private fun bookNode() = compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().singleOrNull()
    private fun bookConfig() = requireNotNull(bookNode()).config
    private fun resting() = bookNode()?.config?.let {
        it.getOrElse(ReaderTurnProgress) { 1f } == 0f && !it.getOrElse(ReaderJumpInProgress) { true }
    } == true
    private fun advanceUntil(predicate: () -> Boolean) = compose.waitUntil(30_000) {
        compose.mainClock.advanceTimeByFrame()
        predicate()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        if (Build.VERSION.SDK_INT >= 29) {
            val frame = CountDownLatch(1)
            compose.runOnUiThread {
                compose.activity.window.decorView.apply {
                    viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
                    invalidate()
                }
            }
            assertTrue(frame.await(5, TimeUnit.SECONDS))
        } else {
            // API 26 has no frame-commit callback. Allow the Compose invalidation to
            // reach SurfaceFlinger before taking an OS screenshot with the clock frozen.
            val frame = CountDownLatch(1)
            compose.runOnUiThread {
                compose.activity.window.decorView.invalidate()
                Choreographer.getInstance().postFrameCallback {
                    Choreographer.getInstance().postFrameCallback { frame.countDown() }
                }
            }
            assertTrue(frame.await(5, TimeUnit.SECONDS))
        }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = requireNotNull(compose.activity.getExternalFilesDir("rc1-closed-book")).also { it.mkdirs() }
        File(directory, "${captures.incrementAndGet()}-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private class Fixture(val mode: ReaderPresentationMode, val face: CoverPosition) {
        val bounds = AtomicReference<Rect?>()
        val shelfRequests = AtomicInteger()
        private val paragraph = BookBlock.Paragraph(BlockId("source"), listOf(InlineNode.Text(
            "The book keeps its physical cover and its exact reading place. ".repeat(40))))
        val progress = AtomicReference(ReadingProgress(BookLocation(paragraph.id, 0, 0), face,
            fraction = if (face == CoverPosition.BACK) 1f else 0f))
        val document = BookDocument(BookId("rc1-closed-${ids.incrementAndGet()}"), BookMetadata(
            "The Quiet Library", authors = listOf("Reader QA"), annotation =
                "A quiet story about letters left between the pages, and the people who find them."),
            listOf(paragraph), tableOfContents = listOf(BookTocEntry("The story", BookLocation(paragraph.id, 0, 0))))
    }

    private companion object {
        const val HOST = "rc1_closed_reader_host"
        val ids = AtomicInteger()
        val captures = AtomicInteger()
        val modes = listOf(ReaderPresentationMode.AUTOMATIC, ReaderPresentationMode.SINGLE,
            ReaderPresentationMode.SPREAD)
        val faces = listOf(CoverPosition.FRONT, CoverPosition.BACK)
        val controlTags = listOf("closed_book_shelf", "closed_book_settings", "closed_book_contents", "closed_book_bookmarks")
    }
}
