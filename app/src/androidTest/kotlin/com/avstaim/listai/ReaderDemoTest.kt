package com.avstaim.listai

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.BookMetadata
import com.avstaim.listai.core.model.InlineNode
import com.avstaim.listai.feature.reader.ReaderDemoScreen
import com.avstaim.listai.feature.reader.ReaderScreen
import com.avstaim.listai.feature.reader.ReaderPresentationMode
import com.avstaim.listai.feature.reader.ReaderSemanticLocation
import com.avstaim.listai.feature.reader.ReaderPaginationComplete
import com.avstaim.listai.feature.reader.ReaderLayoutIdentity
import com.avstaim.listai.feature.reader.ReaderCacheBytes
import com.avstaim.listai.feature.reader.ReaderSurfaceRenders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ReaderDemoTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun usePortraitSinglePagePresentation() {
        requestOrientation(
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            expectedOrientation = Configuration.ORIENTATION_PORTRAIT,
        )
    }

    @Test
    fun pageBodyDragCanCancelAndCompleteInBothDirections() {
        setReaderContent(ReaderPresentationMode.SINGLE)
        val book = composeRule.onNodeWithTag(BOOK_TAG).assertIsDisplayed()
        val initialState = pageState()

        book.dragSlowly(
            startFraction = Offset(0.52f, 0.28f),
            endFraction = Offset(0.48f, 0.275f),
        )
        composeRule.waitForIdle()
        assertEquals(initialState, pageState())

        book.dragSlowly(
            startFraction = Offset(0.58f, 0.56f),
            endFraction = Offset(0.22f, 0.55f),
        )
        composeRule.waitUntil(timeoutMillis = 3_000) { pageState() != initialState }
        val nextState = pageState()
        assertNotEquals(initialState, nextState)

        book.dragSlowly(
            startFraction = Offset(0.48f, 0.54f),
            endFraction = Offset(0.52f, 0.535f),
        )
        composeRule.waitForIdle()
        assertEquals(nextState, pageState())

        book.dragSlowly(
            startFraction = Offset(0.42f, 0.44f),
            endFraction = Offset(0.78f, 0.45f),
        )
        composeRule.waitUntil(timeoutMillis = 3_000) { pageState() == initialState }
    }

    @Test
    fun fullscreenButtonHidesApplicationAndSystemChromeAndExitRestoresThem() {
        composeRule.onNodeWithTag(APP_BOTTOM_NAVIGATION_TAG).assertIsDisplayed()
        waitForPaginatedBook()
        val initialPosition = pageState()
        composeRule.onNodeWithTag(BOOK_TAG).performTouchInput {
            click(Offset(width * 0.58f, height * 0.54f))
        }
        composeRule.waitUntil(timeoutMillis = 3_000) { pageState() != initialPosition }
        val positionBeforeFullscreen = semanticLocation()
        val layoutBeforeFullscreen = layoutIdentity()

        composeRule.onNodeWithTag(READER_FULLSCREEN_BUTTON_TAG).performClick()

        composeRule.onNodeWithTag(APP_BOTTOM_NAVIGATION_TAG).assertDoesNotExist()
        composeRule.waitUntil(timeoutMillis = 3_000) { allSystemBarsAreHidden() }
        waitForPaginatedBook()
        assertEquals(positionBeforeFullscreen, semanticLocation())
        assertNotEquals(layoutBeforeFullscreen, layoutIdentity())

        // A transient focus loss must not turn immersive mode off. This reproduces the
        // window lifecycle path used by system overlays and some OEM navigation shells.
        composeRule.runOnUiThread {
            composeRule.activity.onWindowFocusChanged(false)
        }
        assertSystemBarsStayHidden()
        composeRule.runOnUiThread {
            composeRule.activity.onWindowFocusChanged(true)
        }
        composeRule.waitUntil(timeoutMillis = 3_000) { allSystemBarsAreHidden() }

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag(APP_BOTTOM_NAVIGATION_TAG).assertDoesNotExist()
        composeRule.waitUntil(timeoutMillis = 3_000) { allSystemBarsAreHidden() }
        waitForPaginatedBook()
        assertEquals(positionBeforeFullscreen, semanticLocation())

        composeRule.onNodeWithTag(READER_FULLSCREEN_BUTTON_TAG).performClick()

        composeRule.onNodeWithTag(APP_BOTTOM_NAVIGATION_TAG).assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 3_000) { allSystemBarsAreVisible() }
        waitForPaginatedBook()
        assertEquals(positionBeforeFullscreen, semanticLocation())
    }

    @Test
    fun parsedBookRestoresSemanticLocationAcrossRotationAndBack() {
        waitForPaginatedBook()
        val initial = semanticLocation()
        composeRule.onNodeWithTag(BOOK_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) { semanticLocation() != initial }
        val anchor = semanticLocation()
        val portraitKey = layoutIdentity()
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        waitForPaginatedBook()
        assertEquals(anchor, semanticLocation())
        assertNotEquals(portraitKey, layoutIdentity())
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        waitForPaginatedBook()
        assertEquals(anchor, semanticLocation())
    }

    @Test
    fun realBitmapPagesStayPinnedDuringDragAndLongWalkRespectsMemoryBudget() {
        val document = BookDocument(BookId("cache-walk"), BookMetadata("Проверка кэша", language = "ru"),
            List(60) { index -> BookBlock.Paragraph(BlockId("p$index"), listOf(InlineNode.Text(
                "Запись $index. Белые страницы хранят историю дороги через лес. ".repeat(30)))) })
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                ListaiTheme { ReaderScreen(document, presentationMode = ReaderPresentationMode.SINGLE) }
            }
        }
        waitForPaginatedBook()
        val book = composeRule.onNodeWithTag(BOOK_TAG)
        val initial = semanticLocation()
        book.performTouchInput {
            down(Offset(width * .58f, height * .52f))
            advanceEventTime(SLOW_EVENT_DELAY_MS)
            moveTo(Offset(width * .38f, height * .52f), delayMillis = SLOW_EVENT_DELAY_MS)
        }
        composeRule.waitForIdle()
        val pinned = book.fetchSemanticsNode().config[ReaderSurfaceRenders]
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        assertEquals(pinned, book.fetchSemanticsNode().config[ReaderSurfaceRenders])
        assertEquals(initial, semanticLocation())
        book.performTouchInput { cancel() }
        composeRule.waitForIdle()

        repeat(20) {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                book.fetchSemanticsNode().config.contains(SemanticsActions.OnClick)
            }
            val before = semanticLocation()
            book.performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) { semanticLocation() != before }
            assertTrue(book.fetchSemanticsNode().config[ReaderCacheBytes] <= 64L * 1024 * 1024)
            assertTrue(book.fetchSemanticsNode().config[SemanticsProperties.Text].any { it.text.isNotBlank() })
        }
        repeat(20) {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                book.fetchSemanticsNode().config[SemanticsActions.CustomActions].size == 2
            }
            val before = semanticLocation()
            book.performTouchInput { click(Offset(width * .25f, height * .5f)) }
            composeRule.waitUntil(timeoutMillis = 10_000) { semanticLocation() != before }
        }
        assertEquals(initial, semanticLocation())
    }

    private fun waitForPaginatedBook() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(BOOK_TAG).fetchSemanticsNodes().any {
                it.config.getOrElse(ReaderPaginationComplete) { false }
            }
        }
    }

    private fun semanticLocation() = composeRule.onNodeWithTag(BOOK_TAG).fetchSemanticsNode().config[ReaderSemanticLocation]
    private fun layoutIdentity() = composeRule.onNodeWithTag(BOOK_TAG).fetchSemanticsNode().config[ReaderLayoutIdentity]

    @Test
    fun diagnosticsScreenParsesAndDisplaysBundledFb2() {
        composeRule.onNodeWithTag(APP_DIAGNOSTICS_NAVIGATION_TAG).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(STAGE3_DIAGNOSTICS_READY_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(STAGE3_DIAGNOSTICS_READY_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(STAGE3_DIAGNOSTICS_ERROR_TAG).assertDoesNotExist()
    }

    @Test
    fun firstMovementFromPageCenterShowsCurlWithoutCommittingPage() {
        setReaderContent(ReaderPresentationMode.SINGLE)
        val book = composeRule.onNodeWithTag(BOOK_TAG).assertIsDisplayed()
        val initialState = pageState()
        val before = book.captureToImage()

        book.performTouchInput {
            val grab = Offset(width * 0.50f, height * 0.52f)
            down(grab)
        }
        composeRule.waitForIdle()
        val afterDown = book.captureToImage()
        assertTrue(
            "Touch-down in the page body must not make the sheet jump",
            pixelDifference(before, afterDown).changedFraction < MAX_TOUCH_DOWN_DIFFERENCE,
        )

        book.performTouchInput {
            val grab = Offset(width * 0.50f, height * 0.52f)
            advanceEventTime(SLOW_EVENT_DELAY_MS)
            moveTo(
                position = grab - Offset(width * 0.04f, 0f),
                delayMillis = SLOW_EVENT_DELAY_MS,
            )
        }
        composeRule.waitForIdle()
        val during = book.captureToImage()
        assertEquals(
            "Dragging must not commit the logical page before release",
            initialState,
            pageState(),
        )

        book.performTouchInput { cancel() }
        composeRule.waitForIdle()
        assertEquals(initialState, pageState())

        val difference = pixelDifference(before, during)
        assertTrue(
            "A drag started in the page body must produce a visible curl",
            difference.changedFraction > MIN_VISIBLE_DIFFERENCE,
        )
    }

    @Test
    fun pageBodyTapsTurnForwardAndBackward() {
        setReaderContent(ReaderPresentationMode.SINGLE)
        val initialState = pageState()
        val book = composeRule.onNodeWithTag(BOOK_TAG)

        book.performTouchInput {
            click(Offset(width * 0.58f, height * 0.54f))
        }
        composeRule.waitUntil(timeoutMillis = 3_000) { pageState() != initialState }
        val nextState = pageState()

        book.performTouchInput {
            click(Offset(width * 0.42f, height * 0.46f))
        }
        composeRule.waitUntil(timeoutMillis = 3_000) { pageState() == initialState }
        assertNotEquals(initialState, nextState)
    }

    @Test
    fun firstPageClosesIntoFrontCoverAndCanBeReopened() {
        setReaderContent(ReaderPresentationMode.SINGLE)
        val book = composeRule.onNodeWithTag(BOOK_TAG).assertIsDisplayed()
        val firstPageState = pageState()

        book.dragSlowly(
            startFraction = Offset(0.42f, 0.52f),
            endFraction = Offset(0.78f, 0.52f),
        )
        composeRule.waitUntil(timeoutMillis = 3_000) { pageState() != firstPageState }
        val frontCoverState = pageState()

        book.dragSlowly(
            startFraction = Offset(0.58f, 0.52f),
            endFraction = Offset(0.22f, 0.52f),
        )
        composeRule.waitUntil(timeoutMillis = 3_000) { pageState() == firstPageState }
        assertNotEquals(firstPageState, frontCoverState)
    }

    @Test
    fun lastPageClosesIntoBackCoverTapIsReservedAndBackwardSwipeReopens() {
        setReaderContent(
            presentationMode = ReaderPresentationMode.SINGLE,
            initialPageIndex = 31,
        )
        val book = composeRule.onNodeWithTag(BOOK_TAG).assertIsDisplayed()
        val lastPageState = pageState()

        book.dragSlowly(
            startFraction = Offset(0.58f, 0.52f),
            endFraction = Offset(0.22f, 0.52f),
        )
        composeRule.waitUntil(timeoutMillis = 3_000) { pageState() != lastPageState }
        val backCoverState = pageState()

        book.performTouchInput { click(Offset(width * 0.72f, height * 0.52f)) }
        composeRule.waitForIdle()
        assertEquals(backCoverState, pageState())

        book.dragSlowly(
            startFraction = Offset(0.42f, 0.52f),
            endFraction = Offset(0.78f, 0.52f),
        )
        composeRule.waitUntil(timeoutMillis = 3_000) { pageState() == lastPageState }
    }

    @Test
    fun shortFastFlickFromInnerPageBodyCompletesWithoutCrossingDistanceThreshold() {
        setReaderContent(ReaderPresentationMode.SINGLE)
        val initialState = pageState()
        composeRule.onNodeWithTag(BOOK_TAG).performTouchInput {
            val grab = Offset(width * 0.40f, height * 0.54f)
            down(grab)
            advanceEventTime(16)
            moveTo(grab - Offset(width * 0.16f, 4f))
            advanceEventTime(16)
            up()
        }

        composeRule.waitUntil(timeoutMillis = 3_000) { pageState() != initialState }
    }

    @Test
    fun spreadUsesRightLeafForForwardAndLeftLeafForBackward() {
        setReaderContent(ReaderPresentationMode.SPREAD)

        val book = composeRule.onNodeWithTag(BOOK_TAG).assertIsDisplayed()
        val initialState = pageState()

        book.dragSlowly(
            startFraction = Offset(0.76f, 0.44f),
            endFraction = Offset(0.72f, 0.44f),
        )
        composeRule.waitForIdle()
        assertEquals(initialState, pageState())

        book.dragSlowly(
            startFraction = Offset(0.76f, 0.56f),
            endFraction = Offset(0.22f, 0.55f),
        )
        composeRule.waitUntil(timeoutMillis = 3_000) { pageState() != initialState }
        val nextSpreadState = pageState()

        book.dragSlowly(
            startFraction = Offset(0.24f, 0.44f),
            endFraction = Offset(0.28f, 0.44f),
        )
        composeRule.waitForIdle()
        assertEquals(nextSpreadState, pageState())

        book.dragSlowly(
            startFraction = Offset(0.24f, 0.56f),
            endFraction = Offset(0.78f, 0.55f),
        )
        composeRule.waitUntil(timeoutMillis = 3_000) { pageState() == initialState }
    }

    @Test
    fun semanticPagePositionSurvivesPortraitLandscapePortraitResize() {
        val initialState = pageState()
        composeRule.onNodeWithTag(BOOK_TAG).performTouchInput {
            click(Offset(width * 0.75f, height * 0.58f))
        }
        composeRule.waitUntil(timeoutMillis = 3_000) { pageState() != initialState }
        val positionBeforeResize = pageState()

        requestOrientation(
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            expectedOrientation = Configuration.ORIENTATION_LANDSCAPE,
        )
        assertEquals(positionBeforeResize, pageState())

        requestOrientation(
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            expectedOrientation = Configuration.ORIENTATION_PORTRAIT,
        )
        assertEquals(positionBeforeResize, pageState())
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.dragSlowly(
        startFraction: Offset,
        endFraction: Offset,
    ) {
        performTouchInput {
            val start = Offset(width * startFraction.x, height * startFraction.y)
            val end = Offset(width * endFraction.x, height * endFraction.y)
            down(start)
            advanceEventTime(SLOW_EVENT_DELAY_MS)
            moveTo(end, delayMillis = SLOW_EVENT_DELAY_MS)
            advanceEventTime(SLOW_EVENT_DELAY_MS)
            up()
        }
    }

    private fun requestOrientation(requestedOrientation: Int, expectedOrientation: Int) {
        composeRule.activity.requestedOrientation = requestedOrientation
        composeRule.waitUntil(timeoutMillis = ORIENTATION_TIMEOUT_MS) {
            composeRule.activity.resources.configuration.orientation == expectedOrientation &&
                runCatching {
                    composeRule.onNodeWithTag(BOOK_TAG).fetchSemanticsNode()
                }.isSuccess
        }
        composeRule.waitForIdle()
    }

    private fun setReaderContent(
        presentationMode: ReaderPresentationMode,
        initialPageIndex: Int = 0,
    ) {
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                ListaiTheme {
                    ReaderDemoScreen(
                        presentationMode = presentationMode,
                        initialPageIndex = initialPageIndex,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun pageState(): String = composeRule
        .onNodeWithTag(BOOK_TAG)
        .fetchSemanticsNode()
        .config[SemanticsProperties.StateDescription]

    private fun allSystemBarsAreHidden(): Boolean {
        val activity = composeRule.activity
        val insets = ViewCompat.getRootWindowInsets(activity.window.decorView) ?: return false
        return !insets.isVisible(WindowInsetsCompat.Type.statusBars()) &&
            !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
    }

    private fun allSystemBarsAreVisible(): Boolean {
        val activity = composeRule.activity
        val insets = ViewCompat.getRootWindowInsets(activity.window.decorView) ?: return false
        return insets.isVisible(WindowInsetsCompat.Type.statusBars()) &&
            insets.isVisible(WindowInsetsCompat.Type.navigationBars())
    }

    private fun assertSystemBarsStayHidden(durationMillis: Long = 750L) {
        val deadline = SystemClock.uptimeMillis() + durationMillis
        while (SystemClock.uptimeMillis() < deadline) {
            assertTrue(
                "A transient focus loss must not reveal status or navigation bars",
                allSystemBarsAreHidden(),
            )
            SystemClock.sleep(50L)
        }
    }

    private fun pixelDifference(before: ImageBitmap, after: ImageBitmap): PixelDifference {
        require(before.width == after.width && before.height == after.height)
        val beforePixels = before.toPixelMap()
        val afterPixels = after.toPixelMap()
        var changed = 0
        var sampled = 0

        for (y in 0 until before.height step PIXEL_SAMPLE_STEP) {
            for (x in 0 until before.width step PIXEL_SAMPLE_STEP) {
                sampled += 1
                val beforeIndex = beforePixels.bufferOffset + y * beforePixels.stride + x
                val afterIndex = afterPixels.bufferOffset + y * afterPixels.stride + x
                if (
                    rgbDistance(
                        beforePixels.buffer[beforeIndex],
                        afterPixels.buffer[afterIndex],
                    ) >= RGB_DELTA
                ) {
                    changed += 1
                }
            }
        }

        return PixelDifference(
            changedFraction = changed.toFloat() / sampled.coerceAtLeast(1),
        )
    }

    private fun rgbDistance(first: Int, second: Int): Int =
        abs((first shr 16 and 0xFF) - (second shr 16 and 0xFF)) +
            abs((first shr 8 and 0xFF) - (second shr 8 and 0xFF)) +
            abs((first and 0xFF) - (second and 0xFF))

    private data class PixelDifference(
        val changedFraction: Float,
    )

    private companion object {
        const val BOOK_TAG = "book_view_demo"
        const val APP_BOTTOM_NAVIGATION_TAG = "app_bottom_navigation"
        const val READER_FULLSCREEN_BUTTON_TAG = "reader_fullscreen_button"
        const val SLOW_EVENT_DELAY_MS = 700L
        const val ORIENTATION_TIMEOUT_MS = 8_000L
        const val PIXEL_SAMPLE_STEP = 2
        const val RGB_DELTA = 24
        const val MIN_VISIBLE_DIFFERENCE = 0.0002f
        const val MAX_TOUCH_DOWN_DIFFERENCE = 0.0001f
    }
}
