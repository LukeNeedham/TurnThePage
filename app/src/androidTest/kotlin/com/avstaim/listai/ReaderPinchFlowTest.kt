package com.avstaim.listai

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real two-finger text input through production ReaderScreen. */
@RunWith(AndroidJUnit4::class)
class ReaderPinchFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun textPinchesGrowAndShrinkTypeInBothLayoutsWithoutChangingTheSourceAnchor() {
        for ((mode, dark) in listOf(ReaderPresentationMode.SINGLE to false, ReaderPresentationMode.SPREAD to true)) {
            val fixture = textFixture()
            show(fixture, mode, dark)
            val before = checkpoint(fixture)
            val paper = config()[ReaderPaperBounds].last()

            // No intermediate move may publish a font preference or change pagination.
            book().performTouchInput {
                val origin = paper.center
                val start = paper.width * .12f
                down(0, origin - Offset(start, 0f))
                down(1, origin + Offset(start, 0f))
                repeat(8) { frame ->
                    val distance = start * (1f + .35f * (frame + 1) / 8f)
                    advanceEventTime(16)
                    moveTo(0, origin - Offset(distance, 0f))
                    moveTo(1, origin + Offset(distance, 0f))
                }
            }
            assertEquals("Text size is committed only when both fingers are released", 18f, fixture.preferences.value.fontSizeSp, .001f)
            assertTrue(fixture.changes.isEmpty())
            book().performTouchInput { up(0); up(1) }
            compose.waitUntil(30_000) { fixture.preferences.value.fontSizeSp > 18f && resting() }
            val grown = fixture.preferences.value.fontSizeSp
            assertEquals(1, fixture.changes.size)
            assertSource(before, fixture)
            capture("text-${mode.name.lowercase()}-enlarged")

            pinch(book(), config()[ReaderPaperBounds].last(), .24f, .16f)
            compose.waitUntil(30_000) { fixture.preferences.value.fontSizeSp < grown && resting() }
            assertTrue(fixture.preferences.value.fontSizeSp >= 12f)
            assertEquals(2, fixture.changes.size)
            assertSource(before, fixture)
        }
    }

    @Test fun textPinchesRespectBothPreferenceLimitsAndDoNotChangeOtherSettings() {
        val fixture = textFixture()
        show(fixture, ReaderPresentationMode.SINGLE, dark = true)
        val before = checkpoint(fixture)
        val originalPreferences = fixture.preferences.value
        pinch(book(), config()[ReaderPaperBounds].single(), .07f, .30f)
        compose.waitUntil(30_000) { fixture.preferences.value.fontSizeSp == 32f && resting() }
        assertEquals(originalPreferences.copy(fontSizeSp = 32f), fixture.preferences.value)
        assertSource(before, fixture)
        pinch(book(), config()[ReaderPaperBounds].single(), .30f, .05f)
        compose.waitUntil(30_000) { fixture.preferences.value.fontSizeSp == 12f && resting() }
        assertEquals(originalPreferences.copy(fontSizeSp = 12f), fixture.preferences.value)
        assertSource(before, fixture)
    }

    @Test fun twoFingerJitterAndCancellationNeverTurnAPageOrOpenTheMenu() {
        val fixture = textFixture()
        show(fixture, ReaderPresentationMode.SINGLE, dark = false)
        val before = checkpoint(fixture)
        val paper = config()[ReaderPaperBounds].single()
        pinch(book(), paper, .15f, .153f)
        compose.waitUntil(30_000) { resting() }
        assertEquals(18f, fixture.preferences.value.fontSizeSp, .001f)
        assertTrue(fixture.changes.isEmpty())
        assertSource(before, fixture)

        // Begin a genuine page drag, then claim the sequence with a second finger.
        // Dropping one finger and continuing to drag must not resurrect the page turn.
        book().performTouchInput {
            val first = Offset(paper.left + paper.width * .78f, paper.center.y)
            down(0, first)
            advanceEventTime(16)
            moveTo(0, first - Offset(paper.width * .12f, 0f))
            down(1, Offset(paper.left + paper.width * .32f, paper.center.y))
            advanceEventTime(16)
            moveTo(0, Offset(paper.left + paper.width * .82f, paper.center.y))
            moveTo(1, Offset(paper.left + paper.width * .18f, paper.center.y))
            up(1)
            advanceEventTime(16)
            moveTo(0, Offset(paper.left + paper.width * .08f, paper.center.y))
            cancel()
        }
        compose.waitUntil(30_000) { resting() }
        assertEquals(18f, fixture.preferences.value.fontSizeSp, .001f)
        assertTrue("A cancelled pinch never publishes a preference", fixture.changes.isEmpty())
        assertSource(before, fixture)
    }

    private fun show(fixture: Fixture, mode: ReaderPresentationMode, dark: Boolean) {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value && !controller.importing.value }
        fixture.preferences.value = fixture.preferences.value.copy(theme = if (dark) ReaderTheme.DARK else ReaderTheme.LIGHT)
        compose.runOnUiThread {
            compose.activity.setContent {
                ListaiTheme(darkTheme = dark) {
                    ReaderScreen(fixture.document, presentationMode = mode, initialProgress = fixture.initial,
                        onProgressChanged = fixture.progress::set, preferences = fixture.preferences.value,
                        darkTheme = dark, onPreferencesChange = {
                            fixture.changes += it
                            fixture.preferences.value = it
                        }, onReturnToShelf = { fixture.shelfReturns.incrementAndGet() }, onboardingCompleted = true)
                }
            }
        }
        compose.waitUntil(45_000) { resting() }
    }

    private fun pinch(node: SemanticsNodeInteraction, bounds: Rect, startFraction: Float, endFraction: Float) {
        node.performTouchInput {
            val origin = bounds.center
            val start = bounds.width * startFraction
            val end = bounds.width * endFraction
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
    }

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
        assertTrue("Screenshot follows a rendered frame", frame.await(5, TimeUnit.SECONDS))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(200, 5_000)
        // API 26 can expose the previous Activity until its native window transition settles.
        if (Build.VERSION.SDK_INT <= 27) android.os.SystemClock.sleep(500)
        val bitmap = requireNotNull(automation.takeScreenshot())
        val directory = requireNotNull(compose.activity.getExternalFilesDir("reader-pinch")).also { it.mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun book() = compose.onNodeWithTag("book_view_demo")
    private fun config(): SemanticsConfiguration = book().fetchSemanticsNode().config
    private fun resting() = compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().singleOrNull()?.config?.let {
        it.getOrElse(ReaderPaginationComplete) { false } && !it.getOrElse(ReaderJumpInProgress) { true } &&
            it.getOrElse(ReaderTurnProgress) { 1f } == 0f
    } == true
    private data class Checkpoint(val progress: ReadingProgress, val semanticLocation: String)
    private fun checkpoint(fixture: Fixture) = Checkpoint(fixture.progress.get(), config()[ReaderSemanticLocation])
    private fun assertSource(before: Checkpoint, fixture: Fixture) {
        assertEquals("Reflow and magnification keep the semantic source anchor", before.progress.location, fixture.progress.get().location)
        assertEquals(before.semanticLocation, config()[ReaderSemanticLocation])
        assertEquals(before.progress.cover, fixture.progress.get().cover)
        assertEquals(0, fixture.shelfReturns.get())
        compose.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
        assertEquals(0f, config()[ReaderTurnProgress], .001f)
    }

    private fun textFixture(): Fixture {
        val blocks = List(12) { index -> BookBlock.Paragraph(BlockId("pinch-paragraph-$index"),
            listOf(InlineNode.Text("Paragraph $index keeps the same source through text reflow and two-finger reading gestures. ".repeat(40)))) }
        val location = BookLocation(blocks[4].id, 137, blocks.take(4).sumOf { it.plainText().length + 1L } + 137)
        return Fixture(BookDocument(BookId("pinch-${UUID.randomUUID()}"), BookMetadata("Reading zoom"), blocks), location)
    }

    private class Fixture(val document: BookDocument, location: BookLocation) {
        val initial = ReadingProgress(location, CoverPosition.OPEN)
        val progress = AtomicReference(initial)
        val preferences = mutableStateOf(ReaderPreferences(fontSizeSp = 18f, showClock = false))
        val changes = CopyOnWriteArrayList<ReaderPreferences>()
        val shelfReturns = AtomicInteger()
    }
}
