package com.avstaim.listai

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real raster/gesture integration: a note is ink on a leaf, never a popup. */
@RunWith(AndroidJUnit4::class)
class Stage8AcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun inlineAndBottomCallsCurlAndPrintedReturnKeepsExactSourceInBothLayouts() {
        for (mode in listOf(ReaderPresentationMode.SINGLE, ReaderPresentationMode.SPREAD)) {
            val fixture = showReader(mode)
            for (kind in listOf("inline", "footer")) {
                val link = noteLink("short-1", kind)
                val source = fixture.commits.last().location
                capture("${mode.name}-$kind-source")
                curlToNote(fixture, link, source)
                val targets = config()[ReaderNoteReturnTargets]
                val index = targets.indexOf("short-1")
                assertTrue("A printed return mark must accompany the full note", index >= 0)
                val bounds = config()[ReaderNoteReturns][index]
                curlBack(fixture, link.origin) {
                    book().performTouchInput { click(bounds.center) }
                }
                assertEquals("Return must point at the call, not merely its page start",
                    link.origin, fixture.commits.last().location?.encoded())
            }
        }
    }

    @Test fun longNoteUsesTheSameCurlAndAndroidBackReturnsToItsActualCall() {
        val fixture = showReader(ReaderPresentationMode.SINGLE, dark = true)
        val link = noteLink("long", "footer")
        capture("dark-long-preview")
        curlToNote(fixture, link, fixture.commits.last().location)
        assertTrue(config()[ReaderNotesLocation].startsWith("long|"))
        assertTrue("Full note pages must have a return path", config()[ReaderNoteReturns].isNotEmpty())
        capture("dark-long-full")
        curlBack(fixture, link.origin) {
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        }
        assertEquals(0, fixture.shelfRequests.get())
    }

    @Test fun reflowWhileReadingANoteRetainsTheVisitAndSourceCheckpoint() {
        val fixture = showReader(ReaderPresentationMode.SINGLE)
        val link = noteLink("long", "inline")
        val source = fixture.commits.last().location
        curlToNote(fixture, link, source)
        val visit = config()[ReaderNoteVisit]
        val layout = config()[ReaderLayoutIdentity]
        compose.runOnUiThread { fixture.mode.value = ReaderPresentationMode.SPREAD }
        compose.waitUntil(30_000) {
            val node = bookNode() ?: return@waitUntil false
            node.config[ReaderLayoutIdentity] != layout &&
                node.config[ReaderPaginationComplete] && node.config[ReaderNotesLocation].startsWith("long|")
        }
        assertEquals(visit, config()[ReaderNoteVisit])
        assertEquals(source, fixture.commits.last().location)
        capture("note-reflow-spread")
        curlBack(fixture, link.origin) {
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        }
    }

    @Test fun stoppingDuringNoteCurlCancelsItsUncommittedVisitAndNextJumpStillWorks() {
        val fixture = showReader(ReaderPresentationMode.SINGLE)
        val call = noteLink("long", "footer")
        val checkpoint = fixture.commits.last()
        val commitCount = fixture.commits.size
        val source = config()[ReaderSemanticLocation]
        var stopped = false
        compose.mainClock.autoAdvance = false
        try {
            book().performTouchInput { click(call.bounds.center) }
            awaitCurl("forward")
            assertEquals(checkpoint, fixture.commits.last())
            assertEquals("", config()[ReaderNotesLocation])
            assertEquals("", config()[ReaderNoteVisit])

            // ActivityScenario delivers the real ON_PAUSE/ON_STOP pair, as in ReaderDemoTest.
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            stopped = true
            assertEquals(checkpoint, fixture.commits.last())
            assertTrue(fixture.commits.drop(commitCount).all { it == checkpoint })
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            stopped = false
            advanceUntil {
                val state = bookNode()?.config ?: return@advanceUntil false
                !state[ReaderJumpInProgress] && state[ReaderTurnProgress] == 0f &&
                    state[ReaderSemanticLocation] == source && state[ReaderNotesLocation].isEmpty()
            }
            // Late texture completions or the old settle must not replay after ON_START.
            compose.mainClock.advanceTimeBy(1_000)
            assertEquals(source, config()[ReaderSemanticLocation])
            assertEquals("", config()[ReaderNotesLocation])
            assertEquals("", config()[ReaderNoteVisit])
            assertFalse(jumping())
            assertTrue(fixture.commits.drop(commitCount).all { it == checkpoint })
            assertEquals(0, fixture.shelfRequests.get())
        } finally {
            if (stopped) compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        val resumedCall = noteLink("long", "footer")
        curlToNote(fixture, resumedCall, checkpoint.location)
        curlBack(fixture, resumedCall.origin) {
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        }
    }

    private fun curlToNote(fixture: Fixture, link: Link, checkpoint: BookLocation?) {
        compose.mainClock.autoAdvance = false
        try {
            book().performTouchInput { click(link.bounds.center) }
            awaitCurl("forward")
            assertEquals(checkpoint, fixture.commits.last().location)
            assertEquals("page", config()[ReaderTurnSubject])
            assertTrue(config()[ReaderTurnSourceText].isNotBlank())
            assertTrue(config()[ReaderCacheBytes] <= 64L * 1024 * 1024)
            capture("note-mid-forward")
            advanceUntil {
                !jumping() && config()[ReaderTurnProgress] == 0f &&
                    config()[ReaderNotesLocation].startsWith("${link.noteId}|")
            }
            assertEquals(checkpoint, fixture.commits.last().location)
            assertEquals("${link.noteId}|${link.origin}", config()[ReaderNoteVisit])
            assertFalse(config()[ReaderVisibleFooters].contains('%'))
            capture("note-full-${link.noteId}")
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
    }

    private fun curlBack(fixture: Fixture, origin: String, trigger: () -> Unit) {
        compose.mainClock.autoAdvance = false
        try {
            trigger()
            awaitCurl("backward")
            capture("note-mid-return")
            advanceUntil {
                !jumping() && config()[ReaderTurnProgress] == 0f &&
                    config()[ReaderNotesLocation].isEmpty() && fixture.commits.last().location?.encoded() == origin
            }
            assertEquals("", config()[ReaderNoteVisit])
            assertTrue(config()[ReaderCacheBytes] <= 64L * 1024 * 1024)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
    }

    private fun awaitCurl(direction: String) = advanceUntil {
        val current = bookNode()?.config
        current != null && current[ReaderJumpInProgress] &&
            current[ReaderTurnDirection] == direction && current[ReaderTurnProgress] in .05f.. .98f
    }

    private fun advanceUntil(predicate: () -> Boolean) = compose.waitUntil(30_000) {
        compose.mainClock.advanceTimeByFrame()
        predicate()
    }

    private fun noteLink(noteId: String, kind: String): Link {
        val data = config()
        val index = data[ReaderNoteLinkTargets].indexOfFirst {
            it.startsWith("$noteId|") && it.endsWith("|$kind")
        }
        assertTrue("Expected $kind call for $noteId: ${data[ReaderNoteLinkTargets]}", index >= 0)
        val target = data[ReaderNoteLinkTargets][index].split('|')
        return Link(target[0], target[1], data[ReaderNoteLinks][index])
    }

    private fun showReader(mode: ReaderPresentationMode, dark: Boolean = false): Fixture {
        val fixture = Fixture(mode)
        compose.runOnUiThread {
            compose.activity.setContent {
                ListaiTheme {
                    ReaderScreen(fixture.document, presentationMode = fixture.mode.value,
                        darkTheme = dark, initialProgress = fixture.initial,
                        onProgressChanged = { fixture.commits.add(it) },
                        onReturnToShelf = { fixture.shelfRequests.incrementAndGet() })
                }
            }
        }
        compose.waitUntil(30_000) {
            bookNode()?.config?.getOrElse(ReaderPaginationComplete) { false } == true
        }
        compose.waitForIdle()
        return fixture
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
            assertTrue("Rendered frame must reach the Android compositor", frame.await(5, TimeUnit.SECONDS))
        }
        val screen = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val bounds = book().fetchSemanticsNode().boundsInWindow
        val left = bounds.left.roundToInt().coerceIn(0, screen.width - 1)
        val top = bounds.top.roundToInt().coerceIn(0, screen.height - 1)
        val right = bounds.right.roundToInt().coerceIn(left + 1, screen.width)
        val bottom = bounds.bottom.roundToInt().coerceIn(top + 1, screen.height)
        val cropped = Bitmap.createBitmap(screen, left, top, right - left, bottom - top)
        val directory = requireNotNull(compose.activity.getExternalFilesDir("stage8")).also { it.mkdirs() }
        File(directory, "${captures.incrementAndGet()}-$name.png").outputStream().use {
            cropped.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        cropped.recycle()
        screen.recycle()
    }

    private fun book() = compose.onNodeWithTag("book_view_demo")
    private fun bookNode() = compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().singleOrNull()
    private fun config() = book().fetchSemanticsNode().config
    private fun jumping() = bookNode()?.config?.getOrElse(ReaderJumpInProgress) { false } == true
    private fun BookLocation.encoded() = "${blockId.value}:$charOffset:$fallbackGlobalOffset"
    private data class Link(val noteId: String, val origin: String, val bounds: Rect)

    private class Fixture(mode: ReaderPresentationMode) {
        val mode = mutableStateOf(mode)
        val commits = CopyOnWriteArrayList<ReadingProgress>()
        val shelfRequests = AtomicInteger()
        private fun call(id: String, mark: String) = InlineNode.Link(LinkTarget.Note(NoteId(id)),
            listOf(InlineNode.Text(mark)))
        private val first = BookBlock.Paragraph(BlockId("source"), listOf(
            InlineNode.Text("A paper book and its notes "), call("short-1", "[1]"),
            InlineNode.Text(". Another call "), call("short-2", "[2]"),
            InlineNode.Text(". A longer explanation "), call("long", "[3]"),
            InlineNode.Text(". The main text continues without losing its place. ".repeat(18))))
        private val blocks = listOf(first) + List(8) { index -> BookBlock.Paragraph(BlockId("body-$index"),
            listOf(InlineNode.Text("The story continues on a physical page, with quiet ink and ample margins. ".repeat(10)))) }
        val initial = ReadingProgress(BookLocation(first.id, 0, 0))
        val document = BookDocument(BookId("stage8-${ids.incrementAndGet()}"),
            BookMetadata("Notes on a Paper Book", listOf("Reader QA"), "en"), blocks,
            tableOfContents = listOf(BookTocEntry("The story", initial.location!!)),
            notes = listOf(
                BookNote(NoteId("short-1"), listOf(BookBlock.Paragraph(BlockId("note-a"),
                    listOf(InlineNode.Text("A short explanation printed at the foot of the leaf."))))),
                BookNote(NoteId("short-2"), listOf(BookBlock.Paragraph(BlockId("note-b"),
                    listOf(InlineNode.Emphasis(listOf(InlineNode.Text("Two notes can share one page."))))))),
                BookNote(NoteId("long"), listOf(BookBlock.Paragraph(BlockId("note-c"),
                    listOf(InlineNode.Text("This is a complete long note. Its continuation belongs to the end of the book, " +
                        "not to a popup. The source remains bookmarked while this explanation is read. ".repeat(100)))))),
            ))
    }

    private companion object {
        val ids = AtomicInteger()
        val captures = AtomicInteger()
    }
}
