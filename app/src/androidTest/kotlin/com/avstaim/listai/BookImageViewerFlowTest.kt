package com.avstaim.listai

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise image hit regions from the actual raster and preserve the book's source checkpoint. */
@RunWith(AndroidJUnit4::class)
class BookImageViewerFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val progress = AtomicReference(ReadingProgress())
    private val shelfReturns = AtomicInteger()

    @Test fun lightSingleIllustrationOpensDoubleTapZoomsAndPrintedCloseRestoresTheSameLeaf() {
        show(ReaderPresentationMode.SINGLE, dark = false, language = "ru")
        val before = checkpoint()
        openImage(0)
        capture("single-light-ru-fit")
        assertEquals(1f, zoom(), .001f)
        image().performTouchInput {
            click(center)
            advanceEventTime(100)
            click(center)
        }
        compose.waitUntil(10_000) { zoom() > 1.1f }
        panImage()
        assertEquals("Image gestures do not write a new reading position", before.progress, progress.get())
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
        capture("single-light-ru-zoom")
        image().performTouchInput {
            click(center)
            advanceEventTime(100)
            click(center)
        }
        compose.waitUntil(10_000) { zoom() == 1f }
        assertEquals("Double tap resets a zoomed and panned image to its fitted size", 1f, zoom(), .001f)
        assertEquals(before.progress, progress.get())
        compose.onNodeWithTag("reader_image_close").performClick()
        awaitClosed(before)
    }

    @Test fun darkSinglePinchAndPanStayInTheImageAndAndroidBackReturnsToTheSamePlace() {
        show(ReaderPresentationMode.SINGLE, dark = true, language = "en")
        val before = checkpoint()
        openImage(0)
        image().performTouchInput {
            val start = width * .08f
            val end = width * .24f
            val origin = center
            down(0, origin - Offset(start, 0f))
            down(1, origin + Offset(start, 0f))
            repeat(8) { frame ->
                val distance = start + (end - start) * (frame + 1) / 8f
                advanceEventTime(16)
                moveTo(0, origin - Offset(distance, 0f))
                moveTo(1, origin + Offset(distance, 0f))
            }
            up(0)
            up(1)
        }
        compose.waitUntil(10_000) { zoom() > 1.1f }
        panImage()
        assertEquals(before.progress, progress.get())
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
        capture("single-dark-en-pinch")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitClosed(before)
        assertEquals("Back dismisses the illustration before returning to the shelf", 0, shelfReturns.get())
    }

    @Test fun spreadHitRegionsOpenTheCorrectImageFromBothPhysicalLeaves() {
        for ((dark, language) in listOf(true to "en", false to "ru")) {
            show(ReaderPresentationMode.SPREAD, dark, language)
            val before = checkpoint()
            val papers = config()[ReaderPaperBounds]
            assertEquals("The fixture requests two physical leaves", 2, papers.size)
            val regions = config()[ReaderImageBounds]
            val ids = config()[ReaderImageIds]
            assertEquals(regions.size, ids.size)
            val leftIndex = regions.indexOfFirst { papers.first().contains(it.center) }
            val rightIndex = regions.indexOfFirst { papers.last().contains(it.center) }
            assertTrue("A real illustration must be visible on the left leaf", leftIndex >= 0)
            assertTrue("A real illustration must be visible on the right leaf", rightIndex >= 0)
            assertNotEquals("The two leaves deliberately contain different binaries", ids[leftIndex], ids[rightIndex])
            capture("spread-${language}-book")
            for ((side, index) in listOf("left" to leftIndex, "right" to rightIndex)) {
                openImage(index)
                assertEquals("$side leaf must resolve its own source image", ids[index],
                    compose.onNodeWithTag("reader_image_viewer").fetchSemanticsNode().config[ReaderImageId])
                assertEquals("Each image opens fitted to the viewport", 1f, zoom(), .001f)
                capture("spread-${language}-$side-fit")
                compose.onNodeWithTag("reader_image_close").performClick()
                awaitClosed(before)
            }
        }
    }

    @Test fun aSwipeStartingOnAnIllustrationStillTurnsTheLeafAndBlankPaperStillOpensTheMenu() {
        show(ReaderPresentationMode.SINGLE, dark = false, language = "ru")
        val before = checkpoint()
        val regions = config()[ReaderImageBounds]
        val node = book().fetchSemanticsNode()
        val outside = listOf(.62f, .68f, .72f).map { fraction ->
            Offset(node.size.width * .5f, node.size.height * fraction)
        }.firstOrNull { point -> regions.none { it.contains(point) } }
        assertNotNull("The landscape illustration leaves central blank paper for the menu", outside)
        book().performTouchInput { click(requireNotNull(outside)) }
        compose.onNodeWithTag("reader_paper_insert").assertIsDisplayed()
        compose.onNodeWithTag("reader_image_viewer").assertDoesNotExist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
        assertCheckpoint(before)

        compose.waitUntil(30_000) { resting() && config().contains(SemanticsActions.OnClick) }
        val imageBounds = config()[ReaderImageBounds].first()
        val paper = config()[ReaderPaperBounds].single()
        val start = Offset(imageBounds.left + imageBounds.width * .90f, imageBounds.center.y)
        val end = Offset(paper.left + paper.width * .08f, imageBounds.center.y)
        assertTrue("The ordinary page swipe starts inside the illustration", imageBounds.contains(start))
        // The page physics commits at .45 of the leaf width. Starting at image centre can
        // produce an intentional cancelled turn, so cross that threshold from inside its right side.
        assertTrue("The gesture travels beyond the page-turn completion threshold", start.x - end.x > paper.width * .5f)
        book().performTouchInput {
            swipe(start, end, 400)
        }
        compose.waitUntil(30_000) { resting() && config()[ReaderSemanticLocation] != before.location }
        assertNotEquals("Dragging an image in the page commits an ordinary book turn", before.progress.location,
            progress.get().location)
        compose.onNodeWithTag("reader_image_viewer").assertDoesNotExist()
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
        capture("single-image-swipe-next-leaf")
    }

    @Test fun tappingAnIllustrationAnimatesFromItsPaperAndClosingAnimatesBackBeforeRemovingTheViewer() {
        show(ReaderPresentationMode.SPREAD, dark = true, language = "en")
        val before = checkpoint()
        val region = config()[ReaderImageBounds].last()
        compose.mainClock.autoAdvance = false
        try {
            book().performTouchInput { click(region.center) }
            compose.mainClock.advanceTimeBy(80)
            compose.onNodeWithTag("reader_image_viewer").assertIsDisplayed()
            assertTrue("Opening interpolates from the book into the viewer", transition() > 0f && transition() < 1f)
            assertEquals(before.progress, progress.get())
            capture("spread-image-opening")
            compose.mainClock.advanceTimeBy(700)
            assertEquals("Opening settles at the fitted fullscreen image", 1f, transition(), .001f)
            compose.onNodeWithTag("reader_image_close").performClick()
            compose.mainClock.advanceTimeBy(80)
            compose.onNodeWithTag("reader_image_viewer").assertIsDisplayed()
            assertTrue("Closing keeps the image mounted while it returns to its source", transition() > 0f && transition() < 1f)
            assertEquals(before.progress, progress.get())
            capture("spread-image-closing")
            compose.mainClock.advanceTimeBy(700)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        awaitClosed(before)
    }

    @Test fun holdingAnIllustrationShowsFullscreenUntilReleaseWithoutMovingTheBook() {
        show(ReaderPresentationMode.SINGLE, dark = false, language = "ru")
        val before = checkpoint()
        val region = config()[ReaderImageBounds].first()
        val point = region.center + book().fetchSemanticsNode().boundsInRoot.topLeft
        val imageId = config()[ReaderImageIds].first()
        // Use the persistent root for both events: the book's semantics are hidden by the overlay.
        compose.onRoot().performTouchInput { down(point) }
        try {
            compose.onRoot().performTouchInput {
                advanceEventTime(650)
                moveTo(point)
            }
            compose.mainClock.advanceTimeBy(650)
            compose.waitUntil(10_000) { viewerHeld() }
            assertEquals(imageId, viewerConfig()[ReaderImageId])
            assertEquals(before.progress, progress.get())
            compose.onNodeWithTag("reader_image_close").assertDoesNotExist()
            compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
            capture("single-page-image-held")
        } finally {
            compose.onRoot().performTouchInput { up() }
        }
        awaitClosed(before)
    }

    @Test fun holdingTheFullscreenImageTemporarilyMagnifiesAndRestoresItsPreviousZoomAndPan() {
        show(ReaderPresentationMode.SINGLE, dark = true, language = "en")
        val before = checkpoint()
        openImage(0)
        image().performTouchInput {
            click(center)
            advanceEventTime(100)
            click(center)
        }
        compose.waitUntil(10_000) { zoom() > 1.1f }
        panImage()
        val oldZoom = zoom()
        val oldOffset = image().fetchSemanticsNode().config[ReaderImageOffset]
        image().performTouchInput { down(center) }
        try {
            image().performTouchInput {
                advanceEventTime(650)
                moveTo(center)
            }
            compose.mainClock.advanceTimeBy(650)
            compose.waitUntil(10_000) { viewerHeld() && zoom() > oldZoom + .1f }
            assertEquals("Temporary inspection does not alter reading position", before.progress, progress.get())
            compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
            capture("fullscreen-image-temporary-hold")
        } finally {
            image().performTouchInput { up() }
        }
        compose.waitUntil(10_000) { !viewerHeld() && kotlin.math.abs(zoom() - oldZoom) < .001f }
        val restoredOffset = image().fetchSemanticsNode().config[ReaderImageOffset]
        assertEquals("Releasing returns to the exact horizontal pan", oldOffset.x, restoredOffset.x, .01f)
        assertEquals("Releasing returns to the exact vertical pan", oldOffset.y, restoredOffset.y, .01f)
        compose.onNodeWithTag("reader_image_viewer").assertIsDisplayed()
        compose.onNodeWithTag("reader_image_close").performClick()
        awaitClosed(before)
    }

    @Test fun cancellingAHeldPageImageDismissesThePreviewAndAnExtraPointerPreventsAHold() {
        show(ReaderPresentationMode.SINGLE, dark = false, language = "en")
        val before = checkpoint()
        val point = config()[ReaderImageBounds].first().center + book().fetchSemanticsNode().boundsInRoot.topLeft
        compose.onRoot().performTouchInput { down(point) }
        try {
            compose.onRoot().performTouchInput {
                advanceEventTime(650)
                moveTo(point)
            }
            compose.mainClock.advanceTimeBy(650)
            compose.waitUntil(10_000) { viewerHeld() }
        } finally {
            compose.onRoot().performTouchInput { cancel() }
        }
        awaitClosed(before)

        compose.onRoot().performTouchInput {
            down(0, point)
            down(1, point + Offset(30f, 0f))
            advanceEventTime(650)
            moveTo(0, point)
            moveTo(1, point + Offset(30f, 0f))
        }
        try {
            compose.mainClock.advanceTimeBy(650)
            compose.onNodeWithTag("reader_image_viewer").assertDoesNotExist()
        } finally {
            compose.onRoot().performTouchInput { up(0); up(1) }
        }
        compose.waitUntil(30_000) { resting() }
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
        assertCheckpoint(before)
    }

    private fun show(presentation: ReaderPresentationMode, dark: Boolean, language: String) {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value && !controller.importing.value }
        val fixture = Fixture(language)
        val initial = ReadingProgress(location = fixture.location(1))
        progress.set(initial)
        shelfReturns.set(0)
        val configuration = Configuration(compose.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        val context = compose.activity.createConfigurationContext(configuration)
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalConfiguration provides configuration, LocalContext provides context) {
                    ListaiTheme(darkTheme = dark) {
                        ReaderScreen(fixture.document, presentationMode = presentation, darkTheme = dark,
                            preferences = ReaderPreferences(theme = if (dark) ReaderTheme.DARK else ReaderTheme.LIGHT),
                            initialProgress = initial, onProgressChanged = progress::set,
                            onReturnToShelf = { shelfReturns.incrementAndGet() }, onboardingCompleted = true)
                    }
                }
            }
        }
        compose.waitUntil(45_000) { resting() && config()[ReaderImageBounds].isNotEmpty() }
    }

    private fun openImage(index: Int) {
        val bookSize = book().fetchSemanticsNode().size
        val id = config()[ReaderImageIds][index]
        val region = config()[ReaderImageBounds][index]
        assertTrue("The hit region must lie on rendered paper", config()[ReaderPaperBounds].any { it.contains(region.center) })
        book().performTouchInput { click(region.center) }
        compose.onNodeWithTag("reader_image_viewer").assertIsDisplayed()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("reader_image_content").fetchSemanticsNodes().singleOrNull()?.config?.let {
                it.getOrElse(SemanticsActions.CustomActions) { emptyList() }.isNotEmpty()
            } == true && transition() == 1f
        }
        val viewerSize = compose.onNodeWithTag("reader_image_viewer").fetchSemanticsNode().size
        assertTrue("The viewer covers the full reader width", viewerSize.width >= bookSize.width * .98f)
        assertTrue("The viewer covers the full reader height", viewerSize.height >= bookSize.height * .98f)
        assertEquals(id, compose.onNodeWithTag("reader_image_viewer").fetchSemanticsNode().config[ReaderImageId])
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
    }

    private fun panImage() {
        val before = zoom()
        image().performTouchInput {
            swipe(Offset(width * .4f, height * .5f), Offset(width * .65f, height * .6f), 300)
        }
        assertEquals("One-finger pan retains the current image zoom", before, zoom(), .001f)
        compose.onNodeWithTag("reader_image_viewer").assertIsDisplayed()
    }

    private fun awaitClosed(before: Checkpoint) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reader_image_viewer").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("reader_image_viewer").assertDoesNotExist()
        compose.waitUntil(30_000) { resting() }
        assertCheckpoint(before)
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
    }

    private fun assertCheckpoint(before: Checkpoint) {
        assertEquals("Opening and closing the image keeps the complete reading checkpoint", before.progress, progress.get())
        assertEquals("The same semantic source remains selected", before.location, config()[ReaderSemanticLocation])
        assertEquals("The same paper is visibly rendered after dismissing the image", before.text, visibleText())
        assertEquals(before.footers, config()[ReaderVisibleFooters])
        assertEquals(before.imageIds, config()[ReaderImageIds])
        assertEquals(0, shelfReturns.get())
    }

    private data class Checkpoint(val progress: ReadingProgress, val location: String, val text: List<String>,
        val footers: String, val imageIds: List<String>)
    private fun checkpoint() = Checkpoint(progress.get(), config()[ReaderSemanticLocation], visibleText(),
        config()[ReaderVisibleFooters], config()[ReaderImageIds])
    private fun book() = compose.onNodeWithTag("book_view_demo")
    private fun config() = book().fetchSemanticsNode().config
    private fun image() = compose.onNodeWithTag("reader_image_content")
    private fun zoom() = image().fetchSemanticsNode().config[ReaderImageZoom]
    private fun viewerConfig() = compose.onNodeWithTag("reader_image_viewer").fetchSemanticsNode().config
    private fun transition() = viewerConfig()[ReaderImageTransition]
    private fun viewerHeld() = compose.onAllNodesWithTag("reader_image_viewer").fetchSemanticsNodes().singleOrNull()?.config
        ?.getOrElse(ReaderImageHeld) { false } == true
    private fun visibleText() = config()[SemanticsProperties.Text].map { it.text }
    private fun resting() = compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().singleOrNull()?.config?.let {
        it.getOrElse(ReaderPaginationComplete) { false } && !it.getOrElse(ReaderJumpInProgress) { true } &&
            it.getOrElse(ReaderTurnProgress) { 1f } == 0f
    } == true

    private fun capture(name: String) {
        compose.waitForIdle()
        val frame = CountDownLatch(1)
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            if (Build.VERSION.SDK_INT >= 29) view.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
            else view.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    view.post { view.viewTreeObserver.removeOnDrawListener(this) }
                    view.postOnAnimation { view.postOnAnimation { frame.countDown() } }
                }
            })
            view.invalidate()
        }
        assertTrue("Capture follows a rendered native frame", frame.await(5, TimeUnit.SECONDS))
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = requireNotNull(compose.activity.getExternalFilesDir("book-image-viewer")).also { it.mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private class Fixture(language: String) {
        private val images = List(6) { index ->
            val bitmap = Bitmap.createBitmap(1200, 700, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (index % 2 == 0) 0xFFE6ECF1.toInt() else 0xFF405975.toInt())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF829EB9.toInt() }
            canvas.drawCircle(850f, 190f, 110f + index * 8f, paint)
            paint.color = 0xFF17354F.toInt()
            canvas.drawRect(0f, 490f, 1200f, 700f, paint)
            paint.color = 0xFFC8D7E5.toInt()
            canvas.drawOval(90f, 410f, 1100f, 630f, paint)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            BookImage(ImageId("illustration-$index"), "image/png", BookBinaryContent { bytes })
        }
        private val blocks = images.mapIndexed { index, image ->
            BookBlock.Image(BlockId("image-block-$index"), image.id,
                if (language == "ru") "Иллюстрация ${index + 1}: озеро и холодное небо" else "Illustration ${index + 1}: lake and cold sky")
        }
        val document = BookDocument(BookId("book-image-viewer-${ids.incrementAndGet()}"),
            BookMetadata(if (language == "ru") "Атлас тихих озёр" else "Atlas of quiet lakes", listOf("Reader QA"), language),
            blocks, images = images, pageBreakBefore = blocks.drop(1).mapTo(mutableSetOf()) { it.id })
        fun location(index: Int) = BookLocation(blocks[index].id, 0, blocks.take(index).sumOf { it.plainText().length }.toLong())
        companion object { val ids = AtomicInteger() }
    }
}
