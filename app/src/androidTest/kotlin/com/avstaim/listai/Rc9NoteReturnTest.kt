package com.avstaim.listai

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Return correctness includes the rendered leaf, not just the saved source checkpoint. */
@RunWith(AndroidJUnit4::class)
class Rc9NoteReturnTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val progress = AtomicReference(ReadingProgress())
    private val mode = mutableStateOf(ReaderPresentationMode.SINGLE)
    private val preferences = mutableStateOf(ReaderPreferences())
    private lateinit var fixture: Fixture

    @Test fun printedReturnFromACachedNoteUsesTheLaterCallInTheSameSourceBlock() {
        fixture = Fixture()
        show(ReaderPresentationMode.SINGLE, fixture.early, dark = false)
        val earlyText = visibleText()
        openNote(fixture.early, "inline")
        printedReturn(fixture.early)
        assertEquals(earlyText, visibleText())

        // The same complete note surface is now cached, but this visit starts pages later.
        navigateThroughContents(fixture.late)
        val lateText = visibleText()
        val lateFolios = config()[ReaderVisibleFooters]
        assertNotEquals("Both calls deliberately occupy different physical pages", earlyText, lateText)
        assertExactVisibleCall(fixture.late)
        assertFalse("The first call cannot be a hit target on the later source page",
            config()[ReaderNoteLinkTargets].contains(target(fixture.early, "inline")))
        capture("single-late-source")
        openNote(fixture.late, "footer")
        capture("single-late-full-note")
        printedReturn(fixture.late)
        assertEquals("Return must restore the actual source leaf, not just its saved anchor", lateText, visibleText())
        assertEquals(lateFolios, config()[ReaderVisibleFooters])
        capture("single-late-return")
    }

    @Test fun noteContinuationAndReflowKeepTheLateCallVisibleAfterAndroidBack() {
        fixture = Fixture()
        show(ReaderPresentationMode.SPREAD, fixture.late, dark = true)
        assertExactVisibleCall(fixture.late)
        openNote(fixture.late, "inline")
        val firstNoteLocation = config()[ReaderNotesLocation]
        capture("spread-note-opening")
        turnForward()
        compose.waitUntil(30_000) {
            resting() && config()[ReaderNotesLocation].isNotEmpty() &&
                config()[ReaderNotesLocation] != firstNoteLocation
        }
        assertVisit(fixture.late)
        capture("spread-note-continuation")
        val oldLayout = config()[ReaderLayoutIdentity]
        compose.runOnIdle {
            mode.value = ReaderPresentationMode.SINGLE
            preferences.value = preferences.value.copy(fontSizeSp = 24f)
        }
        compose.waitUntil(45_000) { resting() && config()[ReaderLayoutIdentity] != oldLayout }
        assertVisit(fixture.late)
        assertTrue(config()[ReaderNotesLocation].isNotEmpty())
        capture("single-large-note-continuation")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitReturned(fixture.late)
        assertExactVisibleCall(fixture.late)
        assertTrue("The later sentence must be physically visible after reflow",
            visibleText().any { it.contains("Поздний вызов") })
        capture("single-large-return")
    }

    @Test fun turningBackOntoTheMixedMainAndNotesSpreadKeepsTheExactVisit() {
        // Main-page parity depends on native font metrics and viewport dimensions. Find a real
        // mixed spread using its printed hit regions, rather than assuming a model page number.
        var mixed = false
        for (fontSize in listOf(18f, 20f, 22f, 24f, 17f, 19f)) {
            fixture = Fixture()
            show(ReaderPresentationMode.SPREAD, fixture.late, dark = false, fontSize = fontSize)
            openNote(fixture.late, "inline")
            val leaves = config()[ReaderPaperBounds]
            val returns = config()[ReaderNoteReturns]
            mixed = leaves.size == 2 && returns.any { leaves.last().contains(it.center) } &&
                returns.none { leaves.first().contains(it.center) }
            if (mixed) break
        }
        assertTrue("The test must reach a spread with source text on the left and notes on the right", mixed)
        val openingText = visibleText()
        val checkpoint = progress.get()
        capture("mixed-spread-before-turns")
        turnForward()
        compose.waitUntil(30_000) { resting() && visibleText() != openingText }
        assertVisit(fixture.late)
        val left = config()[ReaderPaperBounds].first()
        book().performTouchInput {
            swipe(Offset(left.left + left.width * .08f, left.top + left.height * .7f),
                Offset(left.left + left.width * .92f, left.top + left.height * .7f), 400)
        }
        compose.waitUntil(30_000) { resting() && visibleText() == openingText }
        capture("mixed-spread-after-turns")
        assertVisit(fixture.late)
        assertEquals("The main-text half of a mixed spread must not replace the visit's checkpoint",
            checkpoint, progress.get())
        printedReturn(fixture.late)
        assertExactVisibleCall(fixture.late)
        capture("mixed-spread-printed-return")
    }

    private fun show(presentation: ReaderPresentationMode, origin: BookLocation, dark: Boolean, fontSize: Float = 18f) {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value && !controller.importing.value }
        mode.value = presentation
        preferences.value = ReaderPreferences(theme = if (dark) ReaderTheme.DARK else ReaderTheme.LIGHT, fontSizeSp = fontSize)
        val initial = ReadingProgress(location = origin)
        progress.set(initial)
        compose.runOnUiThread {
            compose.activity.setContent {
                ListaiTheme(darkTheme = dark) {
                    ReaderScreen(fixture.document, presentationMode = mode.value,
                        preferences = preferences.value, darkTheme = dark, initialProgress = initial,
                        onProgressChanged = progress::set, onboardingCompleted = true)
                }
            }
        }
        compose.waitUntil(45_000) { resting() }
        assertExactVisibleCall(origin)
    }

    private fun openNote(origin: BookLocation, kind: String) {
        val key = target(origin, kind)
        val index = config()[ReaderNoteLinkTargets].indexOf(key)
        assertTrue("Expected the exact $kind call $key in ${config()[ReaderNoteLinkTargets]}", index >= 0)
        val checkpoint = progress.get()
        val bounds = config()[ReaderNoteLinks][index]
        book().performTouchInput { click(bounds.center) }
        compose.waitUntil(45_000) { resting() && config()[ReaderNotesLocation].isNotEmpty() }
        assertVisit(origin)
        assertEquals("Opening a note must preserve the main source checkpoint", checkpoint, progress.get())
        assertTrue("The printed return mark must exist on the actual note page", config()[ReaderNoteReturns].isNotEmpty())
    }

    private fun printedReturn(origin: BookLocation) {
        assertVisit(origin)
        val index = config()[ReaderNoteReturnTargets].indexOf(Fixture.SHARED_NOTE)
        assertTrue("The return mark must belong to this visit", index >= 0)
        val bounds = config()[ReaderNoteReturns][index]
        book().performTouchInput { click(bounds.center) }
        awaitReturned(origin)
    }

    private fun awaitReturned(origin: BookLocation) {
        compose.waitUntil(45_000) {
            resting() && config()[ReaderNotesLocation].isEmpty() &&
                config()[ReaderSemanticLocation] == origin.encoded()
        }
        assertEquals(origin, progress.get().location)
        assertEquals("", config()[ReaderNoteVisit])
        assertExactVisibleCall(origin)
    }

    private fun assertExactVisibleCall(origin: BookLocation) {
        // These regions are derived from the displayed raster's PageLayout/StaticLayout. Unlike
        // ReaderSemanticLocation, a matching region proves this call is on an actually visible leaf.
        val index = config()[ReaderNoteLinkTargets].indexOf(target(origin, "inline"))
        assertTrue("Returned page must contain the clicked source call ${origin.encoded()}; " +
            "visible calls=${config()[ReaderNoteLinkTargets]}, text=${visibleText()}", index >= 0)
        val bounds = config()[ReaderNoteLinks][index]
        assertTrue("The source call must lie inside a visible paper leaf",
            config()[ReaderPaperBounds].any { it.overlaps(bounds) })
    }

    private fun assertVisit(origin: BookLocation) = assertEquals(
        "The visit must retain this occurrence, including its UTF-16 character offset",
        "${Fixture.SHARED_NOTE}|${origin.encoded()}", config()[ReaderNoteVisit])

    private fun navigateThroughContents(origin: BookLocation) {
        compose.onNodeWithTag("reader_top_contents").performClick()
        compose.waitUntil(30_000) { resting() && config()[ReaderContentsLocation].isNotEmpty() }
        val index = config()[ReaderContentsTargets].indexOf(origin.encoded())
        assertTrue("The fixture has a directly selectable late-call entry", index >= 0)
        val bounds = config()[ReaderContentsRows][index]
        book().performTouchInput { click(bounds.center) }
        compose.waitUntil(30_000) {
            resting() && config()[ReaderContentsLocation].isEmpty() &&
                config()[ReaderSemanticLocation] == origin.encoded()
        }
    }

    private fun turnForward() {
        // Pagination may be complete while neighboring page rasters are still preparing.
        // The forward semantics action is published only after those physical surfaces exist.
        compose.waitUntil(30_000) { resting() && config().contains(SemanticsActions.OnClick) }
        val bounds = config()[ReaderPaperBounds].last()
        book().performTouchInput {
            swipe(Offset(bounds.left + bounds.width * .92f, bounds.top + bounds.height * .7f),
                Offset(bounds.left + bounds.width * .08f, bounds.top + bounds.height * .7f), 400)
        }
    }

    private fun target(origin: BookLocation, kind: String) = "${Fixture.SHARED_NOTE}|${origin.encoded()}|$kind"
    private fun BookLocation.encoded() = "${blockId.value}:$charOffset:$fallbackGlobalOffset"
    private fun book() = compose.onNodeWithTag("book_view_demo")
    private fun config() = book().fetchSemanticsNode().config
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
        assertTrue("A screenshot must follow an actual rendered frame", frame.await(5, TimeUnit.SECONDS))
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = requireNotNull(compose.activity.getExternalFilesDir("rc9-note-return")).also { it.mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "$name.txt").writeText("source=${config()[ReaderSemanticLocation]}\n" +
            "visit=${config()[ReaderNoteVisit]}\nnotes=${config()[ReaderNotesLocation]}\n" +
            "calls=${config()[ReaderNoteLinkTargets]}\n" + visibleText().joinToString("\n---\n"))
        bitmap.recycle()
    }

    private class Fixture {
        private fun call(id: String, marker: String) = InlineNode.Link(LinkTarget.Note(NoteId(id)),
            listOf(InlineNode.Text(marker)))
        private val paragraph = BookBlock.Paragraph(BlockId("repeated-source"), listOf(
            InlineNode.Text("Первое короткое примечание "), call("first-note", "[1]"),
            InlineNode.Text(". Ранний вызов 🧭 "), call(SHARED_NOTE, "[2]"),
            InlineNode.Text(". " + "Между двумя вызовами одной сноски идут разные страницы этой истории. ".repeat(75)),
            InlineNode.Text("Поздний вызов 🧭 "), call(SHARED_NOTE, "[2]"),
            InlineNode.Text(". Здесь читатель продолжает книгу после возвращения из примечаний. ".repeat(30))))
        private val body = listOf(paragraph)
        private val notes = listOf(
            BookNote(NoteId("first-note"), listOf(BookBlock.Paragraph(BlockId("first-note-body"),
                listOf(InlineNode.Text("Короткая первая запись делит страницу со следующей сноской."))))),
            BookNote(NoteId(SHARED_NOTE), List(14) { index -> BookBlock.Paragraph(BlockId("shared-note-$index"),
                listOf(InlineNode.Text("Продолжение примечания ${index + 1}. " +
                    "Полный текст пояснения занимает несколько листов. Возврат должен помнить именно вызов читателя. ".repeat(4)))) }))
        private val source = BookDocument(BookId("rc9-note-return-${ids.incrementAndGet()}"),
            BookMetadata("Возврат к вызову сноски", listOf("Reader QA"), "ru"), body, notes = notes)
        val early = source.noteIndex.byId.getValue(NoteId(SHARED_NOTE)).references.first().source
        val late = source.noteIndex.byId.getValue(NoteId(SHARED_NOTE)).references.last().source
        val document = BookDocument(source.id, source.metadata, body, notes = notes,
            tableOfContents = listOf(BookTocEntry("Ранний вызов", early), BookTocEntry("Поздний вызов", late)))
        companion object { const val SHARED_NOTE = "shared-note"; val ids = AtomicInteger() }
    }
}
