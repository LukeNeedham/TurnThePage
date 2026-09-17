package com.avstaim.listai

import android.content.ComponentCallbacks2
import android.os.Debug
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Canvas, Android text rasterization, gestures, and lifecycle; emulator evidence, not an FPS gate. */
@RunWith(AndroidJUnit4::class)
class Stage9ReaderStressTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun hundredTurnsNotesReflowAndBackgroundKeepExactCheckpointsAndBoundedSurfaces() {
        val reports = mutableListOf<String>()
        val started = SystemClock.elapsedRealtime()
        try {
            for (mode in listOf(ReaderPresentationMode.SINGLE, ReaderPresentationMode.SPREAD)) {
                val fixture = Fixture(mode)
                show(fixture)
                val source = config()[ReaderSemanticLocation]
                val initialRasters = config()[ReaderSurfaceRenders]
                var maxCacheBytes = config()[ReaderCacheBytes]
                var maxCachedPages = config()[ReaderCachedPages]
                val initialPssKb = Debug.getPss()
                val modeStarted = SystemClock.elapsedRealtime()
                repeat(25) {
                    turn(forward = true)
                    assertBounds()
                    maxCacheBytes = maxOf(maxCacheBytes, config()[ReaderCacheBytes])
                    maxCachedPages = maxOf(maxCachedPages, config()[ReaderCachedPages])
                }
                val halfwayPssKb = Debug.getPss()
                repeat(25) {
                    turn(forward = false)
                    assertBounds()
                    maxCacheBytes = maxOf(maxCacheBytes, config()[ReaderCacheBytes])
                    maxCachedPages = maxOf(maxCachedPages, config()[ReaderCachedPages])
                }
                assertEquals("The complete physical walk must return to its exact source checkpoint", source,
                    config()[ReaderSemanticLocation])
                val walkRasters = config()[ReaderSurfaceRenders] - initialRasters
                // At most one fresh current/adjacent window per turn, plus prefetch. This
                // catches rerasterization on animation frames without depending on screen size.
                assertTrue("50 turns must not rerasterize on every animation frame: $walkRasters",
                    walkRasters in 1..(50L * 8 + 16))
                assertDragDoesNotRasterizeOrCommit()

                repeat(3) { visit ->
                    val beforeVisit = fixture.commits.last()
                    val links = config()[ReaderNoteLinkTargets]
                    val index = links.indexOfFirst { it.startsWith("long|") && it.endsWith("|footer") }
                    assertTrue("The first leaf must keep its printed note call: $links", index >= 0)
                    val origin = links[index].split('|')[1]
                    val call = config()[ReaderNoteLinks][index]
                    book().performTouchInput { click(call.center) }
                    awaitNotes()
                    val noteVisit = config()[ReaderNoteVisit]
                    assertEquals(beforeVisit, fixture.commits.last())
                    assertTrue(noteVisit.endsWith("|$origin"))
                    assertBounds()

                    if (visit == 2) {
                        val oldLayout = config()[ReaderLayoutIdentity]
                        compose.runOnUiThread {
                            fixture.preferences.value = fixture.preferences.value.copy(fontSizeSp = 20f)
                            fixture.mode.value = if (mode == ReaderPresentationMode.SINGLE)
                                ReaderPresentationMode.SPREAD else ReaderPresentationMode.SINGLE
                        }
                        compose.waitUntil(45_000) {
                            node()?.config?.getOrElse(ReaderLayoutIdentity) { oldLayout } != oldLayout &&
                                node()?.config?.getOrElse(ReaderPaginationComplete) { false } == true
                        }
                        awaitNotes()
                        assertEquals("A reflow retains the call and full-note destination", noteVisit,
                            config()[ReaderNoteVisit])
                        assertEquals(beforeVisit, fixture.commits.last())
                        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                        awaitNotes()
                        assertEquals(noteVisit, config()[ReaderNoteVisit])
                        assertEquals(beforeVisit, fixture.commits.last())
                    }

                    // The actual printed return mark is exercised, including after reflow.
                    val returns = config()[ReaderNoteReturnTargets]
                    val returnIndex = returns.indexOf("long")
                    assertTrue("The full note needs a printed return on its current leaf", returnIndex >= 0)
                    val returnBounds = config()[ReaderNoteReturns][returnIndex]
                    book().performTouchInput { click(returnBounds.center) }
                    compose.waitUntil(45_000) {
                        node()?.config?.getOrElse(ReaderNotesLocation) { "pending" } == "" &&
                            node()?.config?.getOrElse(ReaderSemanticLocation) { "" } == origin && idle()
                    }
                    assertEquals(origin, fixture.commits.last().location?.encoded())
                    assertEquals("", config()[ReaderNoteVisit])
                    assertBounds()
                }

                val beforeTrim = config()[ReaderSemanticLocation]
                val pagesBeforeTrim = config()[ReaderCachedPages]
                @Suppress("DEPRECATION")
                compose.runOnUiThread {
                    compose.activity.application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
                }
                compose.waitUntil(15_000) { config()[ReaderCachedPages] <= 6 }
                assertEquals("Trimming optional rasters cannot move the source", beforeTrim,
                    config()[ReaderSemanticLocation])
                assertBounds()
                reports += "mode=$mode turns=50 noteVisits=3 reflows=1 backgroundCycles=1 " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - modeStarted} walkRasters=$walkRasters " +
                    "maxCacheBytes=$maxCacheBytes maxCachedPages=$maxCachedPages " +
                    "cachedPagesBeforeTrim=$pagesBeforeTrim cachedPagesAfterTrim=${config()[ReaderCachedPages]} " +
                    "initialPssKb=$initialPssKb halfwayPssKb=$halfwayPssKb finalPssKb=${Debug.getPss()} " +
                    "finalCheckpoint=${config()[ReaderSemanticLocation]}"
            }
        } finally {
            compose.mainClock.autoAdvance = true
            val directory = requireNotNull(compose.activity.getExternalFilesDir("stage9")).also { it.mkdirs() }
            File(directory, "reader-stress-report.txt").writeText(reports.joinToString("\n", postfix =
                "\ntotalElapsedMs=${SystemClock.elapsedRealtime() - started}\ncompletedModes=${reports.size}\n"))
        }
    }

    private fun turn(forward: Boolean) {
        compose.waitUntil(15_000) {
            val state = node()?.config ?: return@waitUntil false
            idle() && if (forward) state.contains(SemanticsActions.OnClick)
                else state.getOrElse(SemanticsActions.CustomActions) { emptyList() }.size == 2
        }
        val before = config()[ReaderSemanticLocation]
        book().performTouchInput { click(Offset(width * if (forward) .86f else .14f, height * .52f)) }
        compose.waitUntil(15_000) { config()[ReaderSemanticLocation] != before && idle() }
        assertEquals("The walk stays in source text", "", config()[ReaderNotesLocation])
        assertEquals("The walk stays out of synthetic contents", "", config()[ReaderContentsLocation])
    }

    private fun assertDragDoesNotRasterizeOrCommit() {
        val before = config()[ReaderSemanticLocation]
        book().performTouchInput {
            down(Offset(width * .74f, height * .53f))
            moveTo(Offset(width * .69f, height * .53f), delayMillis = 300)
        }
        compose.waitForIdle()
        val rasters = config()[ReaderSurfaceRenders]
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals("Pinned page pixels are reused throughout a held curl", rasters, config()[ReaderSurfaceRenders])
        assertEquals(before, config()[ReaderSemanticLocation])
        book().performTouchInput { cancel() }
        compose.waitUntil(10_000) { idle() }
        assertEquals(before, config()[ReaderSemanticLocation])
    }

    private fun assertBounds() {
        assertTrue("Page rasters remain within the 64 MiB cache", config()[ReaderCacheBytes] in 1..CACHE_BYTES)
        assertTrue("Published ink must never be blank", config()[SemanticsProperties.Text].any { it.text.isNotBlank() })
        assertTrue(config()[ReaderPaginationComplete])
    }

    private fun show(fixture: Fixture) {
        compose.runOnUiThread {
            compose.activity.setContent {
                ListaiTheme {
                    ReaderScreen(fixture.document, presentationMode = fixture.mode.value,
                        preferences = fixture.preferences.value, initialProgress = fixture.initial,
                        onProgressChanged = { fixture.commits.add(it) })
                }
            }
        }
        compose.waitUntil(45_000) {
            node()?.config?.getOrElse(ReaderPaginationComplete) { false } == true && fixture.commits.isNotEmpty() && idle()
        }
        compose.waitForIdle()
    }

    private fun awaitNotes() {
        compose.waitUntil(45_000) { node()?.config?.getOrElse(ReaderNotesLocation) { "" }?.startsWith("long|") == true && idle() }
        compose.waitForIdle()
    }

    private fun idle(): Boolean = node()?.config?.let {
        !it.getOrElse(ReaderJumpInProgress) { true } && it.getOrElse(ReaderTurnProgress) { 1f } == 0f
    } == true
    private fun book() = compose.onNodeWithTag("book_view_demo")
    private fun node() = compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().singleOrNull()
    private fun config() = book().fetchSemanticsNode().config
    private fun BookLocation.encoded() = "${blockId.value}:$charOffset:$fallbackGlobalOffset"

    private class Fixture(presentation: ReaderPresentationMode) {
        val mode = mutableStateOf(presentation)
        val preferences = mutableStateOf(ReaderPreferences())
        val commits = CopyOnWriteArrayList<ReadingProgress>()
        private val blocks = List(100) { index -> BookBlock.Paragraph(BlockId("source-$index"), buildList {
            add(InlineNode.Text("Stage nine source paragraph $index. "))
            if (index == 0) add(InlineNode.Link(LinkTarget.Note(NoteId("long")), listOf(InlineNode.Text("[1]"))))
            add(InlineNode.Text(" A quiet printed book keeps each sentence in place while its leaves turn. ".repeat(24)))
        }) }
        val initial = ReadingProgress(BookLocation(blocks.first().id, 0, 0))
        val document = BookDocument(BookId("stage9-stress-$presentation"), BookMetadata("Stage nine paper walk",
            listOf("Reader QA"), "en"), blocks, notes = listOf(BookNote(NoteId("long"),
            listOf(BookBlock.Paragraph(BlockId("full-note"), listOf(InlineNode.Text(
                "This complete note keeps all its words and a printed route to its actual source. ".repeat(100))))))))
    }

    private companion object { const val CACHE_BYTES = 64L * 1024 * 1024 }
}
