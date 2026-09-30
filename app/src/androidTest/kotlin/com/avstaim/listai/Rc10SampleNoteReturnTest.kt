package com.avstaim.listai

import android.graphics.Bitmap
import androidx.activity.compose.setContent
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
import com.avstaim.listai.format.fb2.Fb2BookParser
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The exact bundled-book path reported by the reader, including a printed footer tap. */
@RunWith(AndroidJUnit4::class)
class Rc10SampleNoteReturnTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val progress = AtomicReference(ReadingProgress())

    @Test fun phoneFirstChapterLeadingLightsPrintedReturn() = returnFromLeadingLights(ReaderPresentationMode.SINGLE, false)
    @Test fun spreadFirstChapterLeadingLightsPrintedReturn() = returnFromLeadingLights(ReaderPresentationMode.SPREAD, true)

    private fun returnFromLeadingLights(mode: ReaderPresentationMode, dark: Boolean) {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value && !controller.importing.value }
        val document = runBlocking {
            compose.activity.resources.openRawResource(R.raw.stage4_reading_sample).use {
                Fb2BookParser().parse(it, "seventh-light.fb2")
            }
        }
        val noteId = NoteId("note-leading-light")
        val origin = document.noteIndex.entries.single { it.id == noteId }.references.first().source
        val initial = ReadingProgress(cover = CoverPosition.FRONT)
        progress.set(initial)
        compose.runOnUiThread { compose.activity.setContent {
            ListaiTheme(darkTheme = dark) {
                ReaderScreen(document, initialProgress = initial, presentationMode = mode,
                    preferences = ReaderPreferences(theme = if (dark) ReaderTheme.DARK else ReaderTheme.LIGHT),
                    darkTheme = dark, onProgressChanged = progress::set, onboardingCompleted = true)
            }
        } }
        compose.waitUntil(45_000) {
            resting() && compose.onAllNodesWithTag("closed_book_read").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("closed_book_contents").performClick()
        compose.waitUntil(45_000) { resting() && config()[ReaderContentsLocation].isNotEmpty() }
        val chapter = document.locationsBySourceId.getValue("chapter-one").encoded()
        var contentsTurns = 0
        while (chapter !in config()[ReaderContentsTargets]) {
            assertTrue("The first chapter must be reachable through contents", contentsTurns++ < 10)
            turnForward()
        }
        val row = config()[ReaderContentsRows][config()[ReaderContentsTargets].indexOf(chapter)]
        book().performTouchInput { click(row.center) }
        compose.waitUntil(45_000) { resting() && config()[ReaderContentsLocation].isEmpty() }
        capture("${mode.name}-chapter")
        assertTrue("Contents must open the first chapter: ${text()}", text().any { it.contains("Глава первая") })
        val call = "${noteId.value}|${origin.encoded()}|footer"
        var turns = 0
        while (call !in config()[ReaderNoteLinkTargets]) {
            assertTrue("Find the first note by real page turns, never by a synthetic jump", turns++ < 15)
            turnForward()
        }
        val source = text()
        val folios = config()[ReaderVisibleFooters]
        assertTrue(source.any { it.contains("Створные огни", ignoreCase = true) })
        capture("${mode.name}-source")
        val bounds = config()[ReaderNoteLinks][config()[ReaderNoteLinkTargets].indexOf(call)]
        book().performTouchInput { click(bounds.center) }
        compose.waitUntil(45_000) { resting() && config()[ReaderNotesLocation].isNotEmpty() }
        capture("${mode.name}-notes")
        assertEquals("The real note must retain the first-chapter occurrence", "${noteId.value}|${origin.encoded()}", config()[ReaderNoteVisit])
        assertTrue(text().any { it.contains("Створные огни") })
        val returns = config()[ReaderNoteReturns]
        assertTrue("Printed return must be visible", returns.isNotEmpty())
        // A real pointer tap on the printed return, not Android Back or a semantic action.
        // The printed mark is centered on the visible notes leaf. Do not trust the hit
        // region's x: RC9 incorrectly put it on the empty right half of a terminal spread.
        val printedMark = Offset(config()[ReaderPaperBounds].last().center.x, returns.last().center.y)
        book().performTouchInput { click(printedMark) }
        compose.waitUntil(45_000) { resting() && config()[ReaderNotesLocation].isEmpty() }
        capture("${mode.name}-returned")
        assertEquals("Return must preserve the exact source address; visible=${text()}", origin, progress.get().location)
        assertEquals("Return must show the same first-chapter text, never the Epilogue", source, text())
        assertEquals(folios, config()[ReaderVisibleFooters])
        assertTrue(config()[ReaderNoteLinkTargets].contains(call))
    }

    private fun turnForward() {
        compose.waitUntil(30_000) { resting() && config().contains(SemanticsActions.OnClick) }
        val before = text()
        val leaf = config()[ReaderPaperBounds].last()
        book().performTouchInput {
            swipe(Offset(leaf.left + leaf.width * .92f, leaf.top + leaf.height * .68f),
                Offset(leaf.left + leaf.width * .08f, leaf.top + leaf.height * .68f), 400)
        }
        compose.waitUntil(30_000) { resting() && text() != before }
    }
    private fun book() = compose.onNodeWithTag("book_view_demo")
    private fun config() = book().fetchSemanticsNode().config
    private fun text() = config()[SemanticsProperties.Text].map { it.text }
    private fun resting() = compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().singleOrNull()?.config?.let {
        it.getOrElse(ReaderPaginationComplete) { false } && !it.getOrElse(ReaderJumpInProgress) { true } &&
            it.getOrElse(ReaderTurnProgress) { 1f } == 0f
    } == true
    private fun BookLocation.encoded() = "${blockId.value}:$charOffset:$fallbackGlobalOffset"
    private fun capture(name: String) {
        compose.waitForIdle()
        val output = requireNotNull(compose.activity.getExternalFilesDir("rc10-note-return")).also { it.mkdirs() }
        File(output, "$name.txt").writeText("progress=${progress.get()}\n" + book().printToString())
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
