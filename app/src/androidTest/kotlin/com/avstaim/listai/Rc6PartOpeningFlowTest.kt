package com.avstaim.listai

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regression for the exact part/epigraph in the user's Seventh Light screenshots. */
@RunWith(AndroidJUnit4::class)
class Rc6PartOpeningFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val progress = AtomicReference(ReadingProgress())
    private val mode = mutableStateOf(ReaderPresentationMode.SINGLE)
    private val preferences = mutableStateOf(ReaderPreferences())
    private lateinit var document: BookDocument
    private lateinit var part: BookLocation
    private lateinit var chapter: BookLocation

    @Test fun phoneKeepsThePartEpigraphTogetherAndTurnsItsBlankVersoBothWays() {
        show(ReaderPresentationMode.SINGLE, dark = false)
        assertPart()
        assertEquals(listOf("PART_TITLE"), config()[ReaderPaperKinds])
        assertEquals("The divider has no printed number", "", config()[ReaderVisibleFooters])
        assertTrue(config()[ReaderPageNumberBounds].isEmpty())
        capture("phone-part-light")
        val original = visibleText()
        turn(forward = true)
        compose.waitUntil(30_000) { resting() && visibleText() != original }
        assertTrue("The reverse of the part title is a real blank leaf", visibleText().all(String::isBlank))
        assertEquals(listOf("BLANK"), config()[ReaderPaperKinds])
        assertEquals("", config()[ReaderVisibleFooters])
        assertTrue(config()[ReaderPageNumberBounds].isEmpty())
        capture("phone-blank-verso")
        turn(forward = true)
        awaitText("Глава четвёртая")
        assertFalse(visibleText().joinToString().contains("Семён Ракитин, полевой дневник"))
        capture("phone-following-chapter")
        turn(forward = false)
        compose.waitUntil(30_000) { resting() && visibleText().all(String::isBlank) }
        turn(forward = false)
        awaitText("Карта прилива")
        assertPart()
        assertEquals("Walking backwards restores the complete original divider", original, visibleText())
    }

    @Test fun foldPartAndNextChapterAreRectoAndContentsCanReachTheSameLeaf() {
        show(ReaderPresentationMode.SPREAD, dark = true)
        assertPart()
        assertEquals("The divider is the right leaf of a spread", 2, visibleText().size)
        assertTrue(visibleText().last().contains("Часть вторая"))
        assertEquals("PART_TITLE", config()[ReaderPaperKinds].last())
        val original = visibleText()
        capture("spread-part-dark")
        turn(forward = true)
        awaitText("Глава четвёртая")
        assertEquals(2, visibleText().size)
        assertTrue("The part title's verso is blank", visibleText().first().isBlank())
        assertTrue("The following chapter starts on the right", visibleText().last().contains("Глава четвёртая"))
        assertEquals(listOf("BLANK", "TEXT"), config()[ReaderPaperKinds])
        val printedNumber = Regex("[0-9]+").find(config()[ReaderVisibleFooters])?.value?.toInt()
        assertNotNull(printedNumber)
        assertEquals("A physical right-hand chapter has an odd folio", 1, requireNotNull(printedNumber) % 2)
        assertEquals("Only the chapter prints a folio", 1, config()[ReaderPageNumberBounds].size)
        capture("spread-blank-and-chapter-dark")
        val chapterAnchor = progress.get().location
        assertNotNull(chapterAnchor)
        assertEquals(chapter.blockId, chapterAnchor?.blockId)
        val identity = config()[ReaderLayoutIdentity]
        compose.onNodeWithTag("reader_top_contents").performClick()
        compose.waitUntil(30_000) { resting() && progress.get().tocEntryIndex != null }
        var turns = 0
        while (part.encoded() !in config()[ReaderContentsTargets]) {
            assertTrue("The part exists in the permanent printed contents", turns++ < 10)
            val before = visibleText()
            turn(forward = true)
            compose.waitUntil(30_000) { resting() && visibleText() != before }
        }
        val rowIndex = config()[ReaderContentsTargets].indexOf(part.encoded())
        val row = config()[ReaderContentsRows][rowIndex]
        compose.onNodeWithTag("book_view_demo").performTouchInput { click(row.center) }
        compose.waitUntil(30_000) { resting() && progress.get().tocEntryIndex == null }
        assertPart()
        assertEquals(part, progress.get().location)
        assertEquals("TOC links use the same physical leaves", original, visibleText())
        assertEquals("TOC does not rebuild pagination", identity, config()[ReaderLayoutIdentity])
        capture("spread-part-from-contents")
    }

    @Test fun reflowPreservesThePartAndTheChapterAfterItsBlankVerso() {
        show(ReaderPresentationMode.SPREAD, dark = true)
        assertPart()
        val initial = progress.get().location
        val oldLayout = config()[ReaderLayoutIdentity]
        compose.runOnIdle {
            mode.value = ReaderPresentationMode.SINGLE
            preferences.value = preferences.value.copy(fontSizeSp = 24f)
        }
        compose.waitUntil(30_000) { resting() && config()[ReaderLayoutIdentity] != oldLayout }
        assertEquals("Reflow preserves the part source offset", initial, progress.get().location)
        assertPart()
        capture("phone-part-large-type-dark")
        // Reach the next chapter through actual leaves; no chapter anchor is substituted by the test.
        var turns = 0
        while (visibleText().none { it.contains("Глава четвёртая") }) {
            assertTrue("The following chapter remains reachable", turns++ < 6)
            val before = visibleText()
            turn(forward = true)
            compose.waitUntil(30_000) { resting() && visibleText() != before }
        }
        val anchor = progress.get().location
        val singleLayout = config()[ReaderLayoutIdentity]
        compose.runOnIdle { mode.value = ReaderPresentationMode.SPREAD }
        compose.waitUntil(30_000) { resting() && config()[ReaderLayoutIdentity] != singleLayout }
        assertEquals("Blank-leaf handling cannot move the chapter anchor back to the part", anchor, progress.get().location)
        assertTrue(visibleText().last().contains("Глава четвёртая"))
        // A forced spread on a narrow phone at 24sp can need a continuation for the
        // epigraph. It must preserve that source instead of deleting it to force a blank.
        assertTrue("The left leaf is the part's blank verso or an explicit epigraph continuation",
            config()[ReaderPaperKinds].first() in listOf("BLANK", "PART_CONTINUATION"))
        capture("reflow-chapter-large-type")
    }

    private fun show(presentation: ReaderPresentationMode, dark: Boolean) {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        document = runBlocking {
            compose.activity.resources.openRawResource(R.raw.stage4_reading_sample).use {
                Fb2BookParser().parse(it, "seventh-light.fb2")
            }
        }
        part = document.locationsBySourceId.getValue("part-two")
        chapter = document.locationsBySourceId.getValue("chapter-four")
        mode.value = presentation
        preferences.value = ReaderPreferences(theme = if (dark) ReaderTheme.DARK else ReaderTheme.LIGHT)
        progress.set(ReadingProgress(location = part))
        val initial = progress.get()
        compose.runOnUiThread { compose.activity.setContent {
            ListaiTheme(darkTheme = dark) {
                ReaderScreen(document, presentationMode = mode.value, darkTheme = dark,
                    preferences = preferences.value, initialProgress = initial,
                    onProgressChanged = progress::set, onboardingCompleted = true)
            }
        } }
        compose.waitUntil(45_000) { resting() }
    }

    private fun assertPart() {
        val text = visibleText().last()
        for (fragment in listOf("Часть вторая", "Карта прилива", "На сухой карте глубины неподвижны.",
                "На живой они дышат.", "Семён Ракитин, полевой дневник")) {
            assertTrue("The complete part composition must contain '$fragment': $text", text.contains(fragment))
        }
        assertFalse(text.contains("Глава четвёртая"))
        assertFalse(text.contains("Вторая половина оставалась труднее"))
    }

    private fun turn(forward: Boolean) {
        compose.waitUntil(30_000) { resting() }
        compose.onNodeWithTag("book_view_demo").performTouchInput {
            swipe(Offset(width * if (forward) .92f else .08f, height * .68f),
                Offset(width * if (forward) .08f else .92f, height * .68f), durationMillis = 400)
        }
    }

    private fun awaitText(text: String) = compose.waitUntil(30_000) {
        resting() && visibleText().any { it.contains(text) }
    }
    private fun config() = compose.onNodeWithTag("book_view_demo").fetchSemanticsNode().config
    private fun visibleText() = config()[SemanticsProperties.Text].map { it.text }
    private fun resting() = compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().singleOrNull()?.config?.let {
        it.getOrElse(ReaderPaginationComplete) { false } && !it.getOrElse(ReaderJumpInProgress) { true } &&
            it.getOrElse(ReaderTurnProgress) { 1f } == 0f
    } == true
    private fun BookLocation.encoded() = "${blockId.value}:$charOffset:$fallbackGlobalOffset"

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
        assertTrue("A new native frame is presented before capture", frame.await(5, TimeUnit.SECONDS))
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = requireNotNull(compose.activity.getExternalFilesDir("rc6-parts")).also { it.mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "$name.txt").writeText("anchor=${progress.get().location}\n" + visibleText().joinToString("\n---\n"))
        bitmap.recycle()
    }
}
