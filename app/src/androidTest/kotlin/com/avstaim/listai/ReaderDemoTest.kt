package com.avstaim.listai

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
        val controller = (composeRule.activity.application as ListaiApplication).controller
        composeRule.waitUntil(30_000) { !controller.busy.value }
        if (controller.selection.value == null) {
            val id = runBlocking { controller.repository.books.first().first().id }
            composeRule.runOnUiThread { controller.open(id) }
            composeRule.waitUntil(30_000) { !controller.busy.value && controller.selection.value != null }
        }
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
            startFraction = Offset(0.68f, 0.56f),
            endFraction = Offset(0.16f, 0.55f),
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
            startFraction = Offset(0.32f, 0.44f),
            endFraction = Offset(0.84f, 0.45f),
        )
        composeRule.waitUntil(timeoutMillis = 3_000) { pageState() == initialState }
    }

    @Test
    fun fullscreenAndAwakeFollowTheVisibleBookAcrossShelfAndActivityLifecycle() {
        waitForPaginatedBook()
        composeRule.onNodeWithTag(APP_BOTTOM_NAVIGATION_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(READER_FULLSCREEN_BUTTON_TAG).assertDoesNotExist()
        composeRule.waitUntil(3_000) { allSystemBarsAreHidden() && screenIsKeptAwake() }
        val controller = (composeRule.activity.application as ListaiApplication).controller
        val id = requireNotNull(controller.selection.value).opened.book.id
        val anchor = semanticLocation()
        composeRule.onNodeWithTag(BOOK_TAG).performTouchInput { click(center) }
        composeRule.onNodeWithTag("reader_paper_insert").assertIsDisplayed()
        assertTrue(screenIsKeptAwake())
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("reader_paper_insert").assertDoesNotExist()
        assertEquals(anchor, semanticLocation())

        composeRule.runOnUiThread { composeRule.activity.onWindowFocusChanged(false) }
        assertSystemBarsStayHidden()
        composeRule.runOnUiThread { composeRule.activity.onWindowFocusChanged(true) }
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(!screenIsKeptAwake())
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitUntil(3_000) { allSystemBarsAreHidden() && screenIsKeptAwake() }
        composeRule.activityRule.scenario.recreate()
        waitForPaginatedBook()
        assertEquals(anchor, semanticLocation())
        composeRule.waitUntil(3_000) { allSystemBarsAreHidden() && screenIsKeptAwake() }

        composeRule.onNodeWithTag(BOOK_TAG).performTouchInput { click(center) }
        composeRule.onNodeWithTag("reader_return_to_shelf").performClick()
        composeRule.onNodeWithTag("library_screen").assertIsDisplayed()
        composeRule.waitUntil(3_000) { allSystemBarsAreHidden() && !screenIsKeptAwake() }
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag("library_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("library_book_${id.value}").performClick()
        waitForPaginatedBook()
        assertEquals(anchor, semanticLocation())
    }

    private fun screenIsKeptAwake(): Boolean =
        composeRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    @Test
    fun parsedBookRestoresSemanticLocationAcrossRotationAndBack() {
        waitForPaginatedBook()
        val initial = semanticLocation()
        composeRule.waitUntil(10_000) {
            composeRule.onNodeWithTag(BOOK_TAG).fetchSemanticsNode().config.contains(SemanticsActions.OnClick)
        }
        composeRule.onNodeWithTag(BOOK_TAG).performTouchInput { click(Offset(width * .86f, height * .54f)) }
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
            book.performTouchInput { click(Offset(width * .86f, height * .54f)) }
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
            startFraction = Offset(0.32f, 0.52f),
            endFraction = Offset(0.84f, 0.52f),
        )
        composeRule.waitUntil(timeoutMillis = 3_000) { pageState() != firstPageState }
        val frontCoverState = pageState()

        book.dragSlowly(
            startFraction = Offset(0.68f, 0.52f),
            endFraction = Offset(0.16f, 0.52f),
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
            startFraction = Offset(0.68f, 0.52f),
            endFraction = Offset(0.16f, 0.52f),
        )
        composeRule.waitUntil(timeoutMillis = 3_000) { pageState() != lastPageState }
        val backCoverState = pageState()

        book.performTouchInput { click(Offset(width * 0.72f, height * 0.52f)) }
        composeRule.waitForIdle()
        assertEquals(backCoverState, pageState())

        book.dragSlowly(
            startFraction = Offset(0.32f, 0.52f),
            endFraction = Offset(0.84f, 0.52f),
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
            // Uniform motion supplies a real velocity trace. A lone move followed by
            // a stationary up can fit a decelerating/outward terminal velocity.
            for (sample in 1..4) {
                moveTo(grab - Offset(width * .04f * sample, sample.toFloat()), delayMillis = 8)
            }
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
        waitForPaginatedBook()
        val initialState = semanticLocation()
        composeRule.waitUntil(10_000) {
            composeRule.onNodeWithTag(BOOK_TAG).fetchSemanticsNode().config.contains(SemanticsActions.OnClick)
        }
        composeRule.onNodeWithTag(BOOK_TAG).performTouchInput {
            click(Offset(width * 0.75f, height * 0.58f))
        }
        composeRule.waitUntil(timeoutMillis = 3_000) { semanticLocation() != initialState }
        val positionBeforeResize = semanticLocation()

        requestOrientation(
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            expectedOrientation = Configuration.ORIENTATION_LANDSCAPE,
        )
        waitForPaginatedBook()
        assertEquals(positionBeforeResize, semanticLocation())

        requestOrientation(
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            expectedOrientation = Configuration.ORIENTATION_PORTRAIT,
        )
        waitForPaginatedBook()
        assertEquals(positionBeforeResize, semanticLocation())
    }

    // Fullscreen leaves nearly fill the node. Complete single-page drags travel 52%
    // of its width, crossing the existing 45%-of-leaf distance threshold without a flick.
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
