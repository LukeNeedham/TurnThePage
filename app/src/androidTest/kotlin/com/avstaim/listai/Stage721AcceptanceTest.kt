package com.avstaim.listai

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the printed controls and inspect the actual leaf while Compose's clock is paused. */
@RunWith(AndroidJUnit4::class)
class Stage721AcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun eachPrintedPageNumberOpensFindPlaceAndCancellingKeepsTheCheckpoint() {
        for (mode in listOf(ReaderPresentationMode.SINGLE, ReaderPresentationMode.SPREAD)) {
            val fixture = showReader(mode)
            val before = checkpoint(fixture)
            val numbers = book().fetchSemanticsNode().config[ReaderPageNumberBounds]
            assertEquals(if (mode == ReaderPresentationMode.SPREAD) 2 else 1, numbers.size)
            numbers.forEach { number ->
                book().performTouchInput { click(number.center) }
                compose.onNodeWithTag("reader_progress_percent").assertIsDisplayed()
                    .performTextClearance()
                compose.onNodeWithTag("reader_progress_percent").performTextInput("87")
                assertEquals("Previewing from either printed footer must preserve the source", before,
                    checkpoint(fixture))
                compose.onNodeWithTag("reader_progress_cancel").performScrollTo().performClick()
                compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
                awaitImeHidden()
                assertEquals(before, checkpoint(fixture))
                assertFalse(jumping())
            }
            // The page number shortcut must not replace the existing centre gesture.
            book().performTouchInput { click(center) }
            compose.onNodeWithTag("reader_insert_settings").assertIsDisplayed()
            compose.onNodeWithTag("reader_progress_percent").assertDoesNotExist()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals(before, checkpoint(fixture))
        }
    }

    @Test fun contentsEntryReturnAndChapterLinksCurlInTheirPhysicalDirections() {
        for (mode in listOf(ReaderPresentationMode.SINGLE, ReaderPresentationMode.SPREAD)) {
            val fixture = showReader(mode)
            val original = checkpoint(fixture)
            assertJump(fixture, snapshot(fixture), "backward", original.location, toc = true) {
                topAction("contents")
            }
            val contents = snapshot(fixture)
            openMenuFromNumber()
            assertJump(fixture, contents, "forward", original.location, toc = false) {
                compose.onNodeWithTag("reader_insert_return_from_toc").performScrollTo().performClick()
            }
            assertJump(fixture, snapshot(fixture), "backward", original.location, toc = true) {
                topAction("contents")
            }
            val node = book().fetchSemanticsNode()
            val target = fixture.anchor(12, 23)
            val index = node.config[ReaderContentsTargets].indexOf(target.encoded())
            assertTrue("The physical contents must expose the requested chapter", index >= 0)
            val row = node.config[ReaderContentsRows][index]
            assertJump(fixture, snapshot(fixture), "forward", target, toc = false) {
                // Whitespace belongs to the same full printed row as the chapter title.
                book().performTouchInput { click(Offset(row.right - 2f, row.bottom - 2f)) }
            }
        }
    }

    @Test fun bookmarksCurlToMiddleFinalAndEarlierSourceAnchorsBeforeCommitting() {
        val fixture = showReader(ReaderPresentationMode.SPREAD)
        for ((id, direction) in listOf("middle" to "forward", "last" to "forward", "early" to "backward")) {
            val bookmark = fixture.bookmarks.single { it.id == id }
            val before = snapshot(fixture)
            prepareBookmark(id)
            assertJump(fixture, before, direction, bookmark.location, toc = false) {
                compose.onNodeWithTag("bookmark_jump_$id").performClick()
            }
        }
    }

    @Test fun aBookmarkWithinTheSameSpreadStillCurlsAndCommitsItsExactCharacter() {
        val fixture = showReader(ReaderPresentationMode.SPREAD)
        val before = snapshot(fixture)
        val footers = book().fetchSemanticsNode().config[ReaderVisibleFooters]
        prepareBookmark("nearby")
        assertJump(fixture, before, "forward", fixture.anchor(6, 18), toc = false) {
            compose.onNodeWithTag("bookmark_jump_nearby").performClick()
        }
        assertEquals("A nearby exact anchor stays on the same printed spread", footers,
            book().fetchSemanticsNode().config[ReaderVisibleFooters])
    }

    @Test fun closingDuringAJumpKeepsTheSourceAndDoesNotReplayTheDestination() {
        val fixture = showReader(ReaderPresentationMode.SINGLE)
        val before = snapshot(fixture)
        compose.mainClock.autoAdvance = false
        try {
            topAction("contents")
            awaitRenderedCurl("backward")
            assertUncommitted(fixture, before)
            topAction("close")
            advanceUntil { fixture.shelfRequests.get() == 1 && !jumping() }
            compose.mainClock.advanceTimeBy(1_000)
            assertUncommitted(fixture, before)
            compose.runOnUiThread { fixture.coverTransition.value = null }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        waitForBook()
        assertUncommitted(fixture, before)
        assertFalse("Cancelling the shelf transition must not replay the old jump", jumping())
    }

    @Test fun reflowDuringAJumpCancelsTheUncommittedDestination() {
        val fixture = showReader(ReaderPresentationMode.SINGLE)
        val before = snapshot(fixture)
        compose.mainClock.autoAdvance = false
        try {
            topAction("contents")
            awaitRenderedCurl("backward")
            assertUncommitted(fixture, before)
            compose.runOnUiThread { fixture.mode.value = ReaderPresentationMode.SPREAD }
            advanceUntil {
                val node = bookNodeOrNull()
                node != null && node.config.getOrElse(ReaderPaginationComplete) { false } &&
                    node.config[ReaderLayoutIdentity] != before.layout && !jumping()
            }
            compose.mainClock.advanceTimeBy(1_000)
            assertUncommitted(fixture, before)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        waitForBook()
        assertUncommitted(fixture, before)
    }

    private fun assertJump(
        fixture: Fixture,
        before: SourceSnapshot,
        direction: String,
        target: BookLocation?,
        toc: Boolean,
        trigger: () -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        try {
            trigger()
            awaitRenderedCurl(direction)
            assertUncommitted(fixture, before)
            val frame = book().fetchSemanticsNode().config
            assertEquals("Navigation must bend a paper leaf", "page", frame[ReaderTurnSubject])
            val sourceText = frame[ReaderTurnSourceText]
            assertTrue("The curl must retain an actual source page", sourceText.isNotBlank())
            val returnsPreviousLeaf = fixture.mode.value == ReaderPresentationMode.SINGLE && direction == "backward"
            if (!returnsPreviousLeaf) {
                assertTrue("The turning pixels must belong to a page visible before the request",
                    before.text.contains(sourceText))
            }
            assertEquals("A jump must reuse the live pagination layout", before.layout, frame[ReaderLayoutIdentity])
            assertTrue("A remount must not reset the surface render count", frame[ReaderSurfaceRenders] >= before.renders)
            assertTrue("The surface cache must remain within 64 MiB during a jump",
                frame[ReaderCacheBytes] <= 64L * 1024 * 1024)
            assertTrue("The intermediate frame must visibly deform the original book",
                changedFraction(before.image, captureBook("${before.captureId}-mid-$direction")) > .002)
            advanceUntil {
                val progress = checkpoint(fixture)
                !jumping() && turnProgress() == 0f && progress.location == target &&
                    (progress.tocEntryIndex != null) == toc
            }
            assertEquals(target?.encoded().orEmpty(), semanticLocation())
            assertEquals(toc, contentsLocation().isNotEmpty())
            if (returnsPreviousLeaf) {
                val destinationText = book().fetchSemanticsNode().config[SemanticsProperties.Text]
                    .joinToString("\n\n") { it.text }
                assertTrue("A returning SINGLE leaf carries the destination ink before that anchor is committed",
                    destinationText.contains(sourceText))
            }
            assertTrue("The completed destination must keep the surface cache within 64 MiB",
                book().fetchSemanticsNode().config[ReaderCacheBytes] <= 64L * 1024 * 1024)
            captureBook("${before.captureId}-destination")
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
    }

    private fun assertUncommitted(fixture: Fixture, before: SourceSnapshot) {
        assertEquals("The semantic source must remain committed until the leaf finishes",
            before.progress.location?.encoded(), semanticLocation())
        assertEquals("The persistence callback must not see an unfinished destination",
            before.progress, checkpoint(fixture))
        assertTrue("No transient destination may escape through the persistence callback",
            fixture.commits.drop(before.commitCount).all { it == before.progress })
    }

    private fun awaitRenderedCurl(direction: String) {
        advanceUntil {
            val config = bookNodeOrNull()?.config
            config != null && config.getOrElse(ReaderJumpInProgress) { false } &&
                config.getOrElse(ReaderTurnDirection) { "" } == direction &&
                config.getOrElse(ReaderTurnProgress) { 0f } in .05f.. .98f &&
                compose.onAllNodesWithTag("reader_paper_insert").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(turnProgress() > 0f && turnProgress() < 1f)
    }

    private fun advanceUntil(predicate: () -> Boolean) = compose.waitUntil(30_000) {
        // Rasterization uses real IO time. Advance one frame only, then inspect installed nodes;
        // waiting for intent alone can capture the frame before any mesh exists.
        compose.mainClock.advanceTimeByFrame()
        predicate()
    }

    private fun prepareBookmark(id: String) {
        topAction("bookmarks")
        compose.onNodeWithTag("bookmark_jump_$id").performScrollTo().assertIsDisplayed()
    }

    private fun openMenuFromNumber() {
        val number = book().fetchSemanticsNode().config[ReaderPageNumberBounds].first()
        book().performTouchInput { click(number.center) }
        compose.onNodeWithTag("reader_progress_percent").assertIsDisplayed()
        compose.onNodeWithTag("reader_insert_back").performClick()
        compose.onNodeWithTag("reader_insert_return_from_toc").performScrollTo().assertIsDisplayed()
    }

    private fun showReader(mode: ReaderPresentationMode): Fixture {
        val fixture = Fixture(mode)
        compose.runOnUiThread {
            compose.activity.setContent {
                ListaiTheme {
                    ReaderScreen(fixture.document, presentationMode = fixture.mode.value,
                        initialProgress = fixture.initial, bookmarks = fixture.bookmarks,
                        onProgressChanged = { fixture.commits.add(it) },
                        transitionCoverProgress = fixture.coverTransition.value,
                        onReturnToShelf = {
                            fixture.shelfRequests.incrementAndGet()
                            fixture.coverTransition.value = .65f
                        })
                }
            }
        }
        waitForBook()
        dismissIme()
        compose.onNodeWithTag("reader_top_close").assertIsDisplayed()
        return fixture
    }

    private fun snapshot(fixture: Fixture): SourceSnapshot {
        val config = book().fetchSemanticsNode().config
        val captureId = "${fixture.document.id.value}-${++fixture.captureNumber}"
        return SourceSnapshot(checkpoint(fixture), fixture.commits.size,
            config[ReaderLayoutIdentity], config[ReaderSurfaceRenders],
            config[SemanticsProperties.Text].joinToString("\n\n") { it.text },
            captureBook("$captureId-source"), captureId)
    }

    private fun captureBook(name: String): ImageBitmap {
        compose.waitForIdle()
        if (Build.VERSION.SDK_INT >= 29) {
            val submitted = CountDownLatch(1)
            compose.runOnUiThread {
                val view = compose.activity.window.decorView
                view.viewTreeObserver.registerFrameCommitCallback { submitted.countDown() }
                view.invalidate()
            }
            assertTrue("The Android renderer must submit the paused Compose frame before capture",
                submitted.await(5, TimeUnit.SECONDS))
        }
        val bounds = book().fetchSemanticsNode().boundsInWindow
        // Read the composed display directly: PixelCopy's window callback can time out on
        // the software GPU emulator even when its already-presented frame is available.
        val screen = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "The emulator must supply real compositor pixels for curl acceptance"
        }
        val left = bounds.left.roundToInt().coerceIn(0, screen.width - 1)
        val top = bounds.top.roundToInt().coerceIn(0, screen.height - 1)
        val right = bounds.right.roundToInt().coerceIn(left + 1, screen.width)
        val bottom = bounds.bottom.roundToInt().coerceIn(top + 1, screen.height)
        val cropped = Bitmap.createBitmap(screen, left, top, right - left, bottom - top)
        val directory = requireNotNull(compose.activity.getExternalFilesDir("stage721")).also { it.mkdirs() }
        File(directory, "$name.png").outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val config = book().fetchSemanticsNode().config
        File(directory, "$name.txt").writeText(buildString {
            appendLine("location=${config[ReaderSemanticLocation]}")
            appendLine("contents=${config[ReaderContentsLocation]}")
            appendLine("direction=${config[ReaderTurnDirection]}")
            appendLine("progress=${config[ReaderTurnProgress]}")
            appendLine("subject=${config[ReaderTurnSubject]}")
            appendLine("jump=${config[ReaderJumpInProgress]}")
            appendLine("cacheBytes=${config[ReaderCacheBytes]}")
            appendLine("layout=${config[ReaderLayoutIdentity]}")
            appendLine("sourceText=${config[ReaderTurnSourceText]}")
        })
        return cropped.asImageBitmap()
    }

    private fun dismissIme() {
        compose.runOnUiThread {
            WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        awaitImeHidden()
    }

    private fun awaitImeHidden() {
        val requestedAt = SystemClock.uptimeMillis()
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) != true && SystemClock.uptimeMillis() - requestedAt >= 400
        }
        compose.waitForIdle()
    }

    private fun waitForBook() {
        compose.waitUntil(30_000) {
            bookNodeOrNull()?.config?.getOrElse(ReaderPaginationComplete) { false } == true
        }
        compose.waitForIdle()
    }

    private fun book() = compose.onNodeWithTag(BOOK)
    private fun bookNodeOrNull() = compose.onAllNodesWithTag(BOOK).fetchSemanticsNodes().singleOrNull()
    private fun topAction(action: String) = compose.onNodeWithTag("reader_top_$action")
        .performTouchInput { click(center) }
    private fun checkpoint(fixture: Fixture) = fixture.commits.last()
    private fun semanticLocation() = book().fetchSemanticsNode().config[ReaderSemanticLocation]
    private fun contentsLocation() = book().fetchSemanticsNode().config[ReaderContentsLocation]
    private fun jumping() = bookNodeOrNull()?.config?.getOrElse(ReaderJumpInProgress) { false } == true
    private fun turnProgress() = book().fetchSemanticsNode().config.getOrElse(ReaderTurnProgress) { 0f }
    private fun BookLocation.encoded() = "${blockId.value}:$charOffset:$fallbackGlobalOffset"

    private fun changedFraction(before: ImageBitmap, during: ImageBitmap): Double {
        assertEquals(before.width, during.width)
        assertEquals(before.height, during.height)
        val source = before.toPixelMap()
        val target = during.toPixelMap()
        var changed = 0
        var sampled = 0
        for (y in 0 until before.height step 5) for (x in 0 until before.width step 5) {
            val a = source[x, y]
            val b = target[x, y]
            if (abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue) > .1f) changed++
            sampled++
        }
        return changed.toDouble() / sampled.coerceAtLeast(1)
    }

    private data class SourceSnapshot(val progress: ReadingProgress, val commitCount: Int,
        val layout: String, val renders: Long, val text: String, val image: ImageBitmap, val captureId: String)

    private class Fixture(presentation: ReaderPresentationMode) {
        val mode = mutableStateOf(presentation)
        val coverTransition = mutableStateOf<Float?>(null)
        val shelfRequests = AtomicInteger()
        val commits = CopyOnWriteArrayList<ReadingProgress>()
        var captureNumber = 0
        private val blocks = List(18) { index -> BookBlock.Paragraph(BlockId("leaf-$index"),
            listOf(InlineNode.Text("Chapter $index. Ink from the original source stays on this leaf. ".repeat(24)))) }
        fun anchor(index: Int, character: Int = 0) = BookLocation(blocks[index].id, character,
            blocks.take(index).sumOf { it.plainText().length + 1L } + character)
        private val id = BookId("stage721-${fixtureIds.incrementAndGet()}")
        val document = BookDocument(id, BookMetadata("An Actual Turning Leaf", listOf("Reader QA"), "en"),
            blocks, tableOfContents = listOf(BookTocEntry("The first chapter", anchor(0)),
                BookTocEntry("A later chapter", anchor(12, 23))))
        val initial = ReadingProgress(anchor(6, 17), CoverPosition.OPEN, 1f / 3f)
        val bookmarks = listOf("middle" to anchor(12, 23),
            "last" to anchor(17, blocks.last().plainText().length),
            "early" to anchor(2, 31), "nearby" to anchor(6, 18)).map { (name, location) ->
                Bookmark(name, id, location, 1L, "Saved source position: $name")
            }
    }

    private companion object {
        const val BOOK = "book_view_demo"
        val fixtureIds = AtomicInteger()
    }
}
