package com.avstaim.listai

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
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

    @Test fun phoneKeepsThePartEpigraphAndSkipsBlankLeavesBothWays() {
        show(ReaderPresentationMode.SINGLE, dark = false)
        assertPart()
        assertEquals(listOf("PART_TITLE"), config()[ReaderPaperKinds])
        assertEquals("The divider has no printed number", "", config()[ReaderVisibleFooters])
        assertTrue(config()[ReaderPageNumberBounds].isEmpty())
        capture("phone-part-light")
        val original = visibleText()
        turn(forward = true)
        compose.waitUntil(30_000) { resting() && visibleText() != original }
        capture("phone-after-one-turn")
        assertTrue("One turn from the part opens the chapter without a blank stop",
            visibleText().any { it.contains("Глава четвёртая") })
        assertEquals(listOf("TEXT"), config()[ReaderPaperKinds])
        assertFalse(visibleText().joinToString().contains("Семён Ракитин, полевой дневник"))
        capture("phone-following-chapter")
        turn(forward = false)
        awaitText("Карта прилива")
        assertPart()
        assertEquals("Walking backwards restores the complete original divider", original, visibleText())
        turn(forward = false)
        compose.waitUntil(30_000) { resting() && visibleText() != original }
        assertTrue("No recto-alignment blank is shown before the part either", visibleText().any(String::isNotBlank))
        assertFalse(config()[ReaderPaperKinds].contains("BLANK"))
        capture("phone-before-part")
        turn(forward = true)
        awaitText("Карта прилива")
        assertEquals(original, visibleText())
    }

    @Test fun foldPartAndNextChapterAreRectoAndContentsCanReachTheSameLeaf() {
        show(ReaderPresentationMode.SPREAD, dark = true)
        assertPart()
        assertEquals("The divider is the right leaf of a spread", 2, visibleText().size)
        assertTrue(visibleText().last().contains("Часть вторая"))
        assertEquals("PART_TITLE", config()[ReaderPaperKinds].last())
        val controls = controlBounds()
        assertControlsOnSpread()
        val original = visibleText()
        capture("spread-part-dark")
        turn(forward = true)
        awaitText("Глава четвёртая")
        assertEquals(2, visibleText().size)
        assertTrue("The part title's verso is blank", visibleText().first().isBlank())
        assertTrue("The following chapter starts on the right", visibleText().last().contains("Глава четвёртая"))
        assertEquals(listOf("BLANK", "TEXT"), config()[ReaderPaperKinds])
        assertEquals("An empty verso cannot relocate printed controls, clock or bookmark", controls, controlBounds())
        assertControlsOnSpread()
        val printedNumber = Regex("[0-9]+").find(config()[ReaderVisibleFooters])?.value?.toInt()
        assertNotNull(printedNumber)
        assertEquals("A physical right-hand chapter has an odd folio", 1, requireNotNull(printedNumber) % 2)
        assertEquals("Only the chapter prints a folio", 1, config()[ReaderPageNumberBounds].size)
        capture("spread-blank-and-chapter-dark")
        val chapterText = visibleText()
        turn(forward = true)
        compose.waitUntil(30_000) { resting() && visibleText() != chapterText }
        assertEquals("The next ordinary spread uses the same controls", controls, controlBounds())
        turn(forward = false)
        awaitText("Глава четвёртая")
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
            assertFalse("Every single-page turn must show source content", config()[ReaderPaperKinds].contains("BLANK"))
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
        val foldedAgainLayout = config()[ReaderLayoutIdentity]
        compose.runOnIdle { mode.value = ReaderPresentationMode.SINGLE }
        compose.waitUntil(30_000) { resting() && config()[ReaderLayoutIdentity] != foldedAgainLayout }
        assertEquals("Folding at the chapter beside a blank verso preserves its source", anchor, progress.get().location)
        assertTrue(visibleText().any { it.contains("Глава четвёртая") })
        assertFalse(config()[ReaderPaperKinds].contains("BLANK"))
        capture("folded-again-chapter-large-type")
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

    private fun controlBounds() = listOf("reader_top_close", "reader_top_contents",
        "reader_top_settings", "reader_top_theme", "reader_top_bookmarks", "reader_clock", "reader_quick_bookmark")
        .filter { it != "reader_clock" || compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
        .associateWith { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }

    private fun assertControlsOnSpread() {
        val paper = config()[ReaderPaperBounds]
        val origin = compose.onNodeWithTag("book_view_demo").fetchSemanticsNode().boundsInRoot.topLeft
        val left = paper.first().translate(origin)
        val right = paper.last().translate(origin)
        val controls = controlBounds()
        val viewport = compose.onNodeWithTag("book_view_demo").fetchSemanticsNode().boundsInRoot
        if (viewport.width / compose.activity.resources.displayMetrics.density >= 800f) {
            assertTrue("A wide spread has room for its clock", "reader_clock" in controls)
        }
        listOf("reader_top_close", "reader_top_contents", "reader_clock").filter { it in controls }.forEach {
            assertTrue("$it stays on the left leaf even when it is blank", left.contains(controls.getValue(it).center))
        }
        assertTrue(controls.getValue("reader_quick_bookmark").center.x < right.left)
    }

    private fun turn(forward: Boolean) {
        compose.waitUntil(30_000) { resting() && (!forward || config().contains(SemanticsActions.OnClick)) }
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
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // API26 can publish Compose semantics and an OnDraw fence before SurfaceFlinger has
        // replaced the preceding blank verso. Wait for native window quiet, then capture pixels.
        automation.waitForIdle(200, 5_000)
        SystemClock.sleep(500)
        var bitmap = requireNotNull(automation.takeScreenshot())
        val verifyChapterPixels = Build.VERSION.SDK_INT < 29 && name == "phone-following-chapter"
        if (verifyChapterPixels) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (!hasChapterInk(bitmap) && SystemClock.uptimeMillis() < deadline) {
                bitmap.recycle()
                SystemClock.sleep(100)
                bitmap = requireNotNull(automation.takeScreenshot())
            }
        }
        val directory = requireNotNull(compose.activity.getExternalFilesDir("rc6-parts")).also { it.mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "$name.txt").writeText("anchor=${progress.get().location}\n" + visibleText().joinToString("\n---\n"))
        val chapterVisible = !verifyChapterPixels || hasChapterInk(bitmap)
        bitmap.recycle()
        if (verifyChapterPixels) {
            assertTrue("Chapter source is current before inspecting its presented frame",
                visibleText().any { it.contains("Глава четвёртая") })
            assertTrue("The native screenshot contains chapter ink, not the preceding blank verso", chapterVisible)
        }
    }

    /** Sample the white page's text area, excluding its edges, controls, clock and footer. */
    private fun hasChapterInk(bitmap: Bitmap): Boolean {
        var ink = 0
        for (y in (bitmap.height * .20f).toInt() until (bitmap.height * .75f).toInt() step 4) {
            for (x in (bitmap.width * .15f).toInt() until (bitmap.width * .85f).toInt() step 4) {
                val color = bitmap.getPixel(x, y)
                if (Color.red(color) < 170 && Color.green(color) < 170 && Color.blue(color) < 170 && ++ink >= 100) return true
            }
        }
        return false
    }
}
