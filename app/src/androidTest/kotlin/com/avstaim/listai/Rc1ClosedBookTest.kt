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
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
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

/** The real ReaderScreen keeps a closed hardback at one native leaf and adapts its paper controls. */
@RunWith(AndroidJUnit4::class)
class Rc1ClosedBookTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun nativeClosedFacesUseFreeSideForVerticalActionsAndEmptyDeskReturnsToShelf() {
        for (mode in modes) for (face in faces) {
            val fixture = showReader(mode, face)
            assertNativeLeafSize(fixture)
            assertClosedControlPolicy(fixture)
            capture("${mode.name}-${face.name}-closed")
            val before = fixture.progress.get()
            freeDeskPoint(fixture)?.let { freePoint ->
                compose.onNodeWithTag(HOST).performTouchInput { click(freePoint) }
                compose.waitUntil(5_000) { fixture.shelfRequests.get() == 1 }
            }
            assertEquals(before, fixture.progress.get())
            compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
        }
    }

    @Test fun controlsDisappearDuringCoverDragAndReturnOnlyAfterTheClosedBookSettles() {
        for (mode in modes) for (face in faces) {
            val fixture = showReader(mode, face)
            // System-level screenshots are diagnostics, not assertions. One representative
            // sequence is enough; capturing all 18 frames exhausts the headless emulator's
            // graphics transport before the actual geometry checks can finish.
            val captureSequence = mode == ReaderPresentationMode.AUTOMATIC &&
                face == CoverPosition.FRONT
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
                if (captureSequence) capture("${mode.name}-${face.name}-opening")
                compose.onNodeWithTag(HOST).performTouchInput { up() }
                advanceUntil { fixture.progress.get().cover !in faces && resting() }
            } finally { compose.mainClock.autoAdvance = true }
            compose.waitForIdle()
            assertClosedControlsAbsent()
            assertSameLeafSize(closedBounds, requireNotNull(fixture.bounds.get()), mode, face)
            if (face == CoverPosition.FRONT) {
                // The front boundary remains readable paper and retains the five in-page actions.
                listOf("close", "contents", "settings", "theme", "bookmarks").forEach {
                    compose.onNodeWithTag("reader_top_$it").assertIsDisplayed()
                }
                if (pageMode() == "SINGLE") {
                    // Narrow mode prints the FB2 annotation on a separate white paper leaf.
                    assertNotNull(fixture.progress.get().annotationLocation)
                    assertEquals("", bookConfig()[ReaderVisibleFooters])
                    assertTrue(bookConfig()[ReaderPageNumberBounds].isEmpty())
                    assertTrue("The single front annotation leaf must expose the FB2 description",
                        bookConfig()[SemanticsProperties.Text].any {
                            it.text.contains("A quiet story about letters left between the pages")
                        })
                    assertSingleAnnotationForwardCurlUsesPaper(fixture)
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
                if (captureSequence) capture("${mode.name}-${face.name}-closing")
                compose.onNodeWithTag(HOST).performTouchInput { up() }
                advanceUntil { fixture.progress.get().cover == face && resting() }
                advanceUntil { fixture.bounds.get() != null && resting() }
            } finally { compose.mainClock.autoAdvance = true }
            compose.waitForIdle()
            assertSameLeafSize(closedBounds, requireNotNull(fixture.bounds.get()), mode, face)
            assertNativeLeafSize(fixture)
            assertClosedControlPolicy(fixture)
            assertEquals(0, fixture.shelfRequests.get())
            if (captureSequence) capture("${mode.name}-${face.name}-settled")
        }
    }

    @Test fun centeredFoldKeepsAutomaticSpreadAndTheClosedCoverAtNativeLeafSize() {
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
                fixture.bounds.get() != null && resting()
        }
        assertTrue(bookConfig()[ReaderLayoutIdentity].contains("pageMode=SPREAD"))
        assertNativeLeafSize(fixture)
        assertClosedControlPolicy(fixture)
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

    private fun assertSingleAnnotationForwardCurlUsesPaper(fixture: Fixture) {
        val paper = bookConfig()[ReaderPaperBounds].single()
        val firstAnchor = requireNotNull(fixture.progress.get().annotationLocation)
        compose.mainClock.autoAdvance = false
        try {
            val from = Offset(paper.right - paper.width * .08f, paper.top + paper.height * .58f)
            val to = from - Offset(paper.width * .30f, 0f)
            dragAndHold(from, to)
            advanceUntil {
                val config = bookNode()?.config ?: return@advanceUntil false
                config[ReaderTurnDirection] == "forward" &&
                    config[ReaderTurnProgress] in .05f.. .80f
            }
            val frame = bookConfig()
            assertEquals("The description must turn as a flexible paper leaf", "page",
                frame[ReaderTurnSubject])
            assertTrue("The turning source must be the FB2 description, not the front cover",
                frame[ReaderTurnSourceText].contains("A quiet story about letters left between the pages"))

            val pixels = compose.onNodeWithTag(HOST).captureToImage().toPixelMap()
            var coverBlue = 0
            var sampled = 0
            val left = paper.left.toInt().coerceIn(0, pixels.width - 1)
            val right = paper.right.toInt().coerceIn(left + 1, pixels.width)
            val top = (paper.top + paper.height * .16f).toInt().coerceIn(0, pixels.height - 1)
            val bottom = (paper.bottom - paper.height * .08f).toInt().coerceIn(top + 1, pixels.height)
            for (y in top until bottom step 4) for (x in left until right step 4) {
                val color = pixels.buffer[pixels.bufferOffset + y * pixels.stride + x]
                val red = color shr 16 and 0xFF
                val green = color shr 8 and 0xFF
                val blue = color and 0xFF
                if (blue - red >= 15 && blue - green >= 8 && red < 140 && green < 150 && blue < 180) {
                    coverBlue++
                }
                sampled++
            }
            assertTrue("The annotation verso must reveal target paper, not the external blue front cover",
                coverBlue.toFloat() / sampled.coerceAtLeast(1) < .04f)

            compose.onNodeWithTag(HOST).performTouchInput { cancel() }
            advanceUntil { resting() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertEquals("A cancelled probe must keep the exact first annotation anchor", firstAnchor,
            fixture.progress.get().annotationLocation)
    }

    private fun assertClosedControlPolicy(fixture: Fixture) {
        val cover = requireNotNull(fixture.bounds.get())
        val hostNode = compose.onNodeWithTag(HOST).fetchSemanticsNode()
        val origin = hostNode.boundsInRoot.topLeft
        val pageMode = pageMode()
        val expectedTags = expectedControlTags(fixture.face)
        val unexpectedTags = allControlTags - expectedTags.toSet()
        unexpectedTags.forEach { tag -> compose.onNodeWithTag(tag).assertDoesNotExist() }
        val controls = expectedTags.map { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.translate(-origin)
        }
        controls.zipWithNext().forEach { (upper, lower) ->
            assertTrue("Closed-book actions must form a vertical stack: $upper then $lower",
                upper.bottom <= lower.top + 1f)
        }
        val centres = controls.map { it.center }
        assertTrue("Closed-book actions must share one side column: $controls",
            centres.maxOf { it.x } - centres.minOf { it.x } <= controls.maxOf { it.width } * .2f)
        if (pageMode == "SINGLE") {
            controls.forEachIndexed { index, bounds ->
                assertTrue("${expectedTags[index]} must be drawn over the closed ${fixture.face} cover in SINGLE: button=$bounds cover=$cover",
                    bounds.overlaps(cover))
                assertTrue("${expectedTags[index]} must stay inside the closed ${fixture.face} cover in SINGLE: button=$bounds cover=$cover",
                    bounds.left >= cover.left - 2f && bounds.top >= cover.top - 2f &&
                        bounds.right <= cover.right + 2f && bounds.bottom <= cover.bottom + 2f)
            }
            assertTrue("SINGLE actions must be a left-aligned cover stack: controls=$controls cover=$cover",
                controls.all { it.center.x < cover.center.x })
            assertTrue("A single native leaf should consume the viewport instead of shrinking for controls: cover=$cover host=${hostNode.boundsInRoot}",
                cover.width >= hostNode.boundsInRoot.width * .9f)
            return
        }
        assertEquals("SPREAD", pageMode)
        controls.forEachIndexed { index, bounds ->
            assertFalse("${expectedTags[index]} overlaps the real ${fixture.face}/${fixture.mode} cover: button=$bounds cover=$cover",
                bounds.overlaps(cover))
        }
        if (cover.center.x >= hostNode.boundsInRoot.width / 2f) {
            assertTrue("A right-hand cover must leave all actions on its left: controls=$controls cover=$cover",
                controls.all { it.right <= cover.left + 1f })
        } else {
            assertTrue("A left-hand cover must leave all actions on its right: controls=$controls cover=$cover",
                controls.all { it.left >= cover.right - 1f })
        }
    }

    private fun assertNativeLeafSize(fixture: Fixture) {
        val cover = requireNotNull(fixture.bounds.get())
        val host = compose.onNodeWithTag(HOST).fetchSemanticsNode().boundsInRoot
        val identity = bookConfig()[ReaderLayoutIdentity]
        val expectedWidth = requireNotNull(Regex("pageWidthPx=(\\d+)").find(identity)).groupValues[1].toFloat()
        val expectedHeight = requireNotNull(Regex("pageHeightPx=(\\d+)").find(identity)).groupValues[1].toFloat()
        assertEquals("Closed ${fixture.face}/${fixture.mode} width must equal one native leaf: $identity",
            expectedWidth, cover.width, 1.1f)
        assertEquals("Closed ${fixture.face}/${fixture.mode} height must equal one native leaf: $identity",
            expectedHeight, cover.height, 1.1f)
        assertTrue("Native cover must remain inside the reader host: cover=$cover host=$host",
            cover.left >= -1f && cover.top >= -1f && cover.right <= host.width + 1f && cover.bottom <= host.height + 1f)
    }

    private fun assertSameLeafSize(before: Rect, after: Rect, mode: ReaderPresentationMode,
        face: CoverPosition) {
        assertEquals("Opening/closing $face in $mode must not change leaf width", before.width, after.width, 1.1f)
        assertEquals("Opening/closing $face in $mode must not change leaf height", before.height, after.height, 1.1f)
    }

    private fun freeDeskPoint(fixture: Fixture): Offset? {
        val hostNode = compose.onNodeWithTag(HOST).fetchSemanticsNode()
        val host = hostNode.boundsInRoot
        val cover = requireNotNull(fixture.bounds.get())
        val leftSpace = cover.left
        val rightSpace = host.width - cover.right
        val density = compose.activity.resources.displayMetrics.density
        if (maxOf(leftSpace, rightSpace) < 72f * density) return null
        val x = if (leftSpace >= rightSpace) cover.left / 2f else (cover.right + host.width) / 2f
        val origin = host.topLeft
        val controls = allControlTags.flatMap { tag ->
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().map { it.boundsInRoot.translate(-origin) }
        }
        return listOf(host.height * .08f, host.height * .92f, host.height * .5f)
            .map { Offset(x, it) }
            .firstOrNull { point -> !cover.contains(point) && controls.none { it.contains(point) } }
    }

    private fun assertClosedControlsAbsent() = allControlTags.forEach {
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
                fixture.bounds.get() != null && resting()
        }
        compose.waitForIdle()
        return fixture
    }

    private fun controlsPresent() = allControlTags.any {
        compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty()
    }
    private fun expectedControlTags(face: CoverPosition) = if (face == CoverPosition.FRONT) {
        frontControlTags
    } else {
        frontControlTags - "closed_book_description"
    }
    private fun pageMode(): String = Regex("pageMode=(SINGLE|SPREAD)")
        .find(bookConfig()[ReaderLayoutIdentity])?.groupValues?.get(1)
        ?: throw AssertionError("Layout identity has no page mode: ${bookConfig()[ReaderLayoutIdentity]}")
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
        val frontControlTags = listOf(
            "closed_book_read",
            "closed_book_description",
            "closed_book_contents",
            "closed_book_bookmarks",
            "closed_book_settings",
            "closed_book_shelf",
        )
        val allControlTags = frontControlTags
    }
}
