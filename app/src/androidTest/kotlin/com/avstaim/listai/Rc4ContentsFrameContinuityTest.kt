package com.avstaim.listai

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A supplementary TOC changes physical page indices, but must never remove the current book. */
@RunWith(AndroidJUnit4::class)
class Rc4ContentsFrameContinuityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val progress = AtomicReference(ReadingProgress(BookLocation(BlockId("text-8"), 0, 0)))
    private val observations = mutableListOf<String>()
    private val captured = mutableSetOf<String>()
    private var paperSamples = emptyList<Pair<Offset, Int>>()
    private var bookmarkBounds: Rect? = null
    private var artifactPrefix = ""
    private var frame = 0

    @Test fun openingAndLeavingContentsNeverExposesAnEmptyReader() =
        exerciseContents(ReaderPresentationMode.SINGLE, dark = false, returnWithBack = false)

    @Test fun graphiteSpreadKeepsItsPaperWhenReturningWithSystemBack() =
        exerciseContents(ReaderPresentationMode.SPREAD, dark = true, returnWithBack = true)

    private fun exerciseContents(mode: ReaderPresentationMode, dark: Boolean, returnWithBack: Boolean) {
        artifactPrefix = mode.name
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val blocks = List(30) { index -> BookBlock.Paragraph(BlockId("text-$index"),
            listOf(InlineNode.Text("A printed page must stay visible while its contents are prepared. ".repeat(14)))) }
        fun anchorAt(index: Int) = BookLocation(blocks[index].id, 0,
            blocks.take(index).sumOf { it.plainText().length + 1L })
        progress.set(ReadingProgress(anchorAt(8)))
        val document = BookDocument(BookId("rc4-contents-frame-continuity"),
            BookMetadata("Contents continuity", language = "en"), blocks,
            tableOfContents = (0..28 step 4).map {
                BookTocEntry("Chapter ${it / 4 + 1}", anchorAt(it))
            })
        val initial = progress.get()
        compose.runOnUiThread { compose.activity.setContent { ListaiTheme(darkTheme = dark) {
            ReaderScreen(document, presentationMode = mode, darkTheme = dark,
                preferences = ReaderPreferences(theme = if (dark) ReaderTheme.DARK else ReaderTheme.LIGHT),
                initialProgress = initial, onProgressChanged = progress::set, onboardingCompleted = true)
        } } }
        compose.waitUntil(30_000) { node()?.config?.getOrElse(ReaderPaginationComplete) { false } == true }
        compose.waitForIdle()
        val book = requireNotNull(node())
        bookmarkBounds = compose.onAllNodesWithTag("reader_quick_bookmark").fetchSemanticsNodes()
            .singleOrNull()?.boundsInRoot
        val baseline = captureFrame()
        paperSamples = book.config[ReaderPaperBounds].map { bounds ->
            val point = Offset(bounds.right - bounds.width * .035f, bounds.center.y) + book.boundsInRoot.topLeft
            point to baseline.getPixel(point.x.toInt(), point.y.toInt())
        }
        baseline.recycle()
        assertTrue("The fixture exposes paper to compare against the loading frames", paperSamples.isNotEmpty())
        val anchor = progress.get().location
        try {
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("reader_top_contents").performClick()
            inspectUntil("enter") { progress.get().tocEntryIndex != null }
            assertTrue("Entering contents preserves $anchor, got ${progress.get().location}", progress.get().location == anchor)

            if (returnWithBack) {
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            } else {
                compose.mainClock.autoAdvance = true
                compose.onNodeWithTag("reader_top_settings").performClick()
                compose.onNodeWithTag("reader_insert_return_from_toc").performScrollTo().assertIsDisplayed()
                compose.mainClock.autoAdvance = false
                compose.onNodeWithTag("reader_insert_return_from_toc").performClick()
            }
            inspectUntil("return") { progress.get().tocEntryIndex == null }
            assertTrue("Returning restores $anchor, got ${progress.get().location}", progress.get().location == anchor)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            val restored = progress.get().location
            compose.onNodeWithTag("book_view_demo").performTouchInput { click(Offset(width * .88f, height * .52f)) }
            compose.waitUntil(30_000) { progress.get().location != restored &&
                node()?.config?.getOrElse(ReaderTurnProgress) { 1f } == 0f }
            assertTrue("Supplementary contents stay outside ordinary page turns", progress.get().tocEntryIndex == null)
        } finally {
            compose.mainClock.autoAdvance = true
            File(directory(), "contents-$mode-frame-report.txt").writeText(observations.joinToString("\n"))
        }
    }

    private fun inspectUntil(label: String, destination: () -> Boolean) {
        var idleFrames = 0
        compose.waitUntil(30_000) {
            compose.mainClock.advanceTimeByFrame()
            val state = node()?.config
            val report = "frame=${frame++} $label book=${state != null} " +
                "jump=${state?.getOrElse(ReaderJumpInProgress) { false }} " +
                "turn=${state?.getOrElse(ReaderTurnProgress) { 0f }} " +
                "contents=${state?.getOrElse(ReaderContentsLocation) { "" }}"
            observations += report
            val atRest = destination() && state?.getOrElse(ReaderJumpInProgress) { true } == false &&
                state.getOrElse(ReaderTurnProgress) { 1f } == 0f
            val retained = state?.getOrElse(ReaderPaginationComplete) { true } == false &&
                state.getOrElse(ReaderTurnProgress) { 1f } == 0f
            if (label == "enter" && retained && bookmarkBounds != null) {
                val retainedBookmark = compose.onNodeWithTag("reader_quick_bookmark").fetchSemanticsNode()
                assertTrue("The retained bookmark stays on the paper edge", retainedBookmark.boundsInRoot == bookmarkBounds)
            }
            if (state == null || frame == 1 || (retained && captured.add("$label-retained")) ||
                (state.getOrElse(ReaderTurnProgress) { 0f } in .4f.. .6f && captured.add("$label-curl")) ||
                (atRest && captured.add("$label-rest"))) {
                val bitmap = captureFrame()
                File(directory(), "contents-$artifactPrefix-$label-frame-$frame.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                if (retained || atRest) paperSamples.forEach { (point, expected) ->
                    val actual = bitmap.getPixel(point.x.toInt(), point.y.toInt())
                    val difference = listOf(16, 8, 0).sumOf { shift ->
                        kotlin.math.abs((actual shr shift and 255) - (expected shr shift and 255)) }
                    assertTrue("Prepared paper was replaced by the backdrop ($difference): $report", difference < 24)
                }
                bitmap.recycle()
            }
            assertTrue("The prepared book disappeared during contents navigation: $report", state != null)
            if (atRest) idleFrames++ else idleFrames = 0
            idleFrames >= 3
        }
    }

    private fun captureFrame(): Bitmap {
        repeat(3) { attempt ->
            try { return compose.onRoot().captureToImage().asAndroidBitmap() }
            catch (error: RuntimeException) {
                // Retry only a busy emulator's PixelCopy timeout, at the same held frame.
                if (error.javaClass.name != "androidx.compose.ui.test.android.PixelCopyException" ||
                    error.message != "PixelCopy failed with result 2!" || attempt == 2) throw error
            }
        }
        error("Unreachable capture result")
    }

    private fun directory() = requireNotNull(compose.activity.getExternalFilesDir("rc4-contents-frames")).also { it.mkdirs() }
    private fun node() = compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().singleOrNull()
}
