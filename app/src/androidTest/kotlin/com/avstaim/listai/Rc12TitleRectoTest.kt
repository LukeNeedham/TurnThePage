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
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Rc12TitleRectoTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val mode = mutableStateOf(ReaderPresentationMode.SPREAD)
    private val progress = AtomicReference(ReadingProgress())
    private val title = "Эпизод IV: Новая Надежда"
    private val bodyAnchor = BookLocation(BlockId("body"), 0, 0)

    @Test fun spreadPlacesTitleOnRightWithOnlyOneBlankAndNaturalContentsAfterIt() {
        show(ReaderPresentationMode.SPREAD, description = true, dark = false)
        assertRectoTitle()
        assertEquals(listOf("BLANK", "TEXT"), config()[ReaderPaperKinds])
        assertLeftControls()
        assertEquals("", config()[ReaderVisibleFooters])
        capture("spread-padded-title-light")
        turn(false)
        awaitText("Короткое описание книги")
        assertFalse(text().any { it.contains(title) })
        turn(true)
        awaitText(title)
        assertRectoTitle()
        turn(true)
        compose.waitUntil(30_000) { resting() && progress.get().tocEntryIndex != null }
        assertLeftControls()
        assertFalse(text().any { it.contains(title) })
        capture("spread-contents-after-title")
        turn(false)
        awaitText(title)
        assertRectoTitle()
        assertEquals(bodyAnchor, progress.get().location)
        assertNotNull(progress.get().titleLocation)
        assertNull(progress.get().annotationLocation)
    }

    @Test fun titleAlreadyOnRightDoesNotAcquireAnExtraBlankLeaf() {
        show(ReaderPresentationMode.SPREAD, description = false, dark = true)
        assertEquals(1, text().size)
        assertTrue(text().single().contains(title))
        assertEquals(listOf("TEXT"), config()[ReaderPaperKinds])
        capture("spread-title-already-right-dark")
        turn(true)
        compose.waitUntil(30_000) { resting() && progress.get().tocEntryIndex != null }
        turn(false)
        awaitText(title)
        assertEquals("No blank reverse or new pair was added around the first recto", 1, text().size)
        assertEquals(listOf("TEXT"), config()[ReaderPaperKinds])
        assertEquals(bodyAnchor, progress.get().location)
    }

    @Test fun singleSkipsAllPaddingInBothDirectionsAndReflowKeepsTheTitle() {
        show(ReaderPresentationMode.SINGLE, description = true, dark = true)
        assertEquals(listOf("TEXT"), config()[ReaderPaperKinds])
        capture("single-title-dark")
        turn(false)
        awaitText("Короткое описание книги")
        assertFalse(config()[ReaderPaperKinds].contains("BLANK"))
        turn(true)
        awaitText(title)
        assertEquals(listOf("TEXT"), config()[ReaderPaperKinds])
        turn(true)
        compose.waitUntil(30_000) { resting() && progress.get().tocEntryIndex != null }
        assertFalse(config()[ReaderPaperKinds].contains("BLANK"))
        turn(false)
        awaitText(title)
        val anchor = progress.get().titleLocation
        switchMode(ReaderPresentationMode.SPREAD)
        assertRectoTitle()
        assertEquals(anchor, progress.get().titleLocation)
        capture("unfolded-title-dark")
        switchMode(ReaderPresentationMode.SINGLE)
        assertEquals(anchor, progress.get().titleLocation)
        assertEquals(bodyAnchor, progress.get().location)
        assertEquals(1, text().size)
        assertTrue(text().single().contains(title))
        assertEquals(listOf("TEXT"), config()[ReaderPaperKinds])
        capture("folded-again-title-dark")
    }

    private fun show(presentation: ReaderPresentationMode, description: Boolean, dark: Boolean) {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val heading = BookBlock.Heading(BlockId("chapter"), 1, listOf(InlineNode.Text("Первая глава")))
        val document = BookDocument(BookId("rc12-title"), BookMetadata(title, listOf("Джордж Лукас", "Алан Дин Фостер"),
            "ru", annotation = if (description) "Короткое описание книги." else null),
            listOf(heading, BookBlock.Paragraph(bodyAnchor.blockId, listOf(InlineNode.Text("Текст первой главы. ".repeat(160))))),
            chapters = listOf(BookChapter(listOf(heading.id), 1)))
        mode.value = presentation
        val initial = ReadingProgress(bodyAnchor, titleLocation = BookLocation(BlockId("listai:title:name"), 0, 0))
        progress.set(initial)
        compose.runOnUiThread { compose.activity.setContent {
            ListaiTheme(darkTheme = dark) {
                ReaderScreen(document, presentationMode = mode.value, darkTheme = dark,
                    preferences = ReaderPreferences(theme = if (dark) ReaderTheme.DARK else ReaderTheme.LIGHT),
                    initialProgress = initial, onProgressChanged = progress::set, onboardingCompleted = true)
            }
        } }
        awaitText(title)
    }

    private fun assertRectoTitle() {
        assertEquals(2, text().size)
        assertTrue("Left leaf is empty", text().first().isBlank())
        assertTrue("Book title is printed on the right leaf", text().last().contains(title))
        assertTrue(text().last().contains("Джордж Лукас"))
        assertTrue(text().last().contains("Алан Дин Фостер"))
        assertLeftControls()
    }

    private fun assertLeftControls() {
        val origin = compose.onNodeWithTag("book_view_demo").fetchSemanticsNode().boundsInRoot.topLeft
        val left = config()[ReaderPaperBounds].first().translate(origin)
        listOf("reader_top_close", "reader_top_contents", "reader_clock").forEach { tag ->
            assertTrue("$tag is anchored to the left leaf including the blank facing the title",
                left.contains(compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center))
        }
    }

    private fun switchMode(value: ReaderPresentationMode) {
        val identity = config()[ReaderLayoutIdentity]
        compose.runOnIdle { mode.value = value }
        compose.waitUntil(30_000) { resting() && config()[ReaderLayoutIdentity] != identity }
    }

    private fun turn(forward: Boolean) {
        compose.waitUntil(30_000) { resting() && (!forward || config().contains(SemanticsActions.OnClick)) }
        compose.onNodeWithTag("book_view_demo").performTouchInput {
            swipe(Offset(width * if (forward) .92f else .08f, height * .68f),
                Offset(width * if (forward) .08f else .92f, height * .68f), durationMillis = 400)
        }
    }
    private fun config() = compose.onNodeWithTag("book_view_demo").fetchSemanticsNode().config
    private fun text() = config()[SemanticsProperties.Text].map { it.text }
    private fun awaitText(value: String) = compose.waitUntil(30_000) { resting() && text().any { it.contains(value) } }
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
        assertTrue(frame.await(5, TimeUnit.SECONDS))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(200, 5_000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        val directory = requireNotNull(compose.activity.getExternalFilesDir("rc12-title")).also { it.mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "$name.txt").writeText("progress=${progress.get()}\n" + text().joinToString("\n---\n"))
        bitmap.recycle()
    }
}
