package com.avstaim.listai

import android.graphics.Bitmap
import android.os.SystemClock
import android.os.Build
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.library.LibraryScreen
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual device frames for the approved prototype, including the real drag-created dialog. */
@RunWith(AndroidJUnit4::class)
class Rc5ShelfPrototypeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var dark by mutableStateOf(false)
    private var fontScale by mutableStateOf(1f)
    private var composedTheme = 0

    @After fun cleanup() {
        compose.mainClock.autoAdvance = true
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.runOnUiThread { compose.activity.setContent {} }
    }

    @Test fun shelfStackAndDragCreationFollowTheApprovedComposition() {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val titles = listOf("Таинственный остров", "Дети капитана Гранта", "Вокруг света за 80 дней",
            "Двадцать тысяч лье", "Пятнадцатилетний капитан", "Путешествие к центру Земли",
            "Человек-амфибия", "Морской волк", "Белый Клык")
        val books = titles.mapIndexed { i, title -> LibraryBook(BookId("prototype-$i"), title,
            when { i < 6 -> "Жюль Верн"; i == 6 -> "Александр Беляев"; else -> "Джек Лондон" },
            "$i.fb2", null, 1, null, 1000, ReadingProgress(), stackId = if (i < 6) "verne" else null,
            authorNames = listOf(if (i < 6) "Жюль Верн" else if (i == 6) "Александр Беляев" else "Джек Лондон")) }
        val catalog = LibraryCatalog(books, listOf(LibraryStack("verne", "Жюль Верн", books.take(6).map { it.id })),
            listOf(LibraryShelfItem.Stack("verne")) + books.drop(6).map { LibraryShelfItem.Book(it.id) })
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                ListaiTheme(darkTheme = dark) {
                    val theme = MaterialTheme.colorScheme.background.toArgb()
                    SideEffect { composedTheme = theme }
                    LibraryScreen(books, false, {}, {}, {}, {}, catalog = catalog)
                }
            }
        } }
        compose.waitForIdle()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        books.drop(6).forEach { book ->
            compose.onNodeWithText(book.title).assertDoesNotExist()
            compose.onNodeWithContentDescription(book.title, substring = true).assertExists()
        }
        val buttons = listOf("library_search", "library_settings", "library_import").map {
            compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        assertTrue("Three independent square controls", buttons.all { kotlin.math.abs(it.width - it.height) < 1f })
        val light = capture("01-shelf-light")
        compose.runOnIdle { dark = true }
        val night = capture("01-shelf-dark", differentFrom = light)
        assertNotEquals("The compositor must show the actual new cabinet theme", light, night)
        compose.runOnIdle { dark = false }
        compose.onNodeWithTag("library_stack_verne").performClick()
        compose.onNodeWithTag("library_stack_add_books").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("library_settings").assertDoesNotExist()
        capture("02-open-stack")
        compose.onNodeWithTag("library_back").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithTag("library_stack_menu").assertDoesNotExist()
        capture("02-returned-to-shelf")
        val screen = compose.onNodeWithTag("library_screen").fetchSemanticsNode().boundsInRoot
        val source = compose.onNodeWithTag("library_book_prototype-8").fetchSemanticsNode().boundsInRoot.center
        val target = compose.onNodeWithTag("library_book_prototype-7").fetchSemanticsNode().boundsInRoot.center
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("library_screen").performTouchInput { down(source - screen.topLeft) }
            SystemClock.sleep(650)
            compose.mainClock.advanceTimeBy(650)
            repeat(12) { step ->
                val point = source + (target - source) * ((step + 1) / 12f)
                compose.onNodeWithTag("library_screen").performTouchInput { moveTo(point - screen.topLeft, delayMillis = 32) }
                compose.mainClock.advanceTimeBy(32)
                compose.waitForIdle()
            }
            SystemClock.sleep(650)
            compose.mainClock.advanceTimeBy(650)
            compose.waitForIdle()
            compose.onNodeWithTag("library_drop_hint").assertIsDisplayed()
            compose.onNodeWithTag("library_screen").performTouchInput { up() }
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithTag("library_stack_name").assertIsDisplayed().assertTextContains("Джек Лондон")
        compose.onNodeWithTag("library_name_confirm").assertIsDisplayed()
        capture("03-create-stack")
        // The compact dialog must remain usable after the keyboard reduces its height.
        compose.runOnIdle { fontScale = 1.5f }
        compose.onNodeWithTag("library_stack_name").performTouchInput { click(center) }
        compose.waitUntil(10_000) {
            automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
        compose.onNodeWithTag("library_stack_name").performTextReplacement("Джек Лондон — любимые книги")
        compose.onNodeWithTag("library_name_confirm").performScrollTo().assertIsDisplayed()
        capture("04-create-stack-large-type-keyboard")
        val confirm = compose.onNodeWithTag("library_name_confirm").fetchSemanticsNode().boundsInWindow
        val dialog = automation.windows.first { it.isFocused && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val dialogBounds = android.graphics.Rect().also(dialog::getBoundsInScreen)
        val imeTop = automation.windows.first { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .let { android.graphics.Rect().also(it::getBoundsInScreen).top }
        val pointX = confirm.center.x + dialogBounds.left
        val pointY = confirm.center.y + dialogBounds.top
        assertTrue("The real confirmation target must be above the keyboard", confirm.bottom + dialogBounds.top < imeTop)
        val downTime = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, pointX, pointY, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            automation.injectInputEvent(event, true)
            event.recycle()
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("library_stack_name").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("library_stack_name").assertDoesNotExist()
        // Preserve the separate picker/IME cancellation regression as native evidence.
        compose.onNodeWithTag("library_stack_verne").performClick()
        compose.onNodeWithTag("library_stack_add_books").performClick()
        compose.onNodeWithTag("library_picker_search").performClick().performTextReplacement("Лондон")
        compose.waitUntil(10_000) { automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
        capture("05-add-picker-keyboard")
        val cancelBounds = compose.onNodeWithText(compose.activity.getString(com.avstaim.listai.feature.library.R.string.library_cancel))
            .fetchSemanticsNode().boundsInWindow
        val pickerWindow = automation.windows.first { it.isFocused && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val pickerBounds = android.graphics.Rect().also(pickerWindow::getBoundsInScreen)
        val cancelTime = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(cancelTime, SystemClock.uptimeMillis(), action,
                cancelBounds.center.x + pickerBounds.left, cancelBounds.center.y + pickerBounds.top, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            automation.injectInputEvent(event, true)
            event.recycle()
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("library_book_picker").fetchSemanticsNodes().isEmpty() }
        capture("06-after-picker-cancel")
    }

    private fun capture(name: String, differentFrom: Int? = null): Int {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200, 5_000)
        // Dialog/IME alpha transitions run in WindowManager, outside Compose's clock.
        // Finish that short native transition before judging opacity or keyboard clearance.
        SystemClock.sleep(500)
        val until = SystemClock.uptimeMillis() + 5_000
        var bitmap: Bitmap
        var corner: Int
        do {
            submittedDraw()
            bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            corner = bitmap.getPixel(bitmap.width / 20, bitmap.height / 10)
            if (differentFrom == null || corner != differentFrom || SystemClock.uptimeMillis() >= until) break
            bitmap.recycle()
            SystemClock.sleep(50)
        } while (true)
        val directory = requireNotNull(compose.activity.getExternalFilesDir("rc5-shelf-prototype")).also { it.mkdirs() }
        File(directory, "$name.txt").writeText("dark=$dark composedTheme=$composedTheme sampledPixel=$corner")
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return corner
    }

    private fun submittedDraw() {
        val frame = CountDownLatch(1)
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            if (Build.VERSION.SDK_INT >= 29) view.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
            else view.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
                var queued = false
                override fun onDraw() {
                    if (queued) return
                    queued = true
                    view.post {
                        view.viewTreeObserver.removeOnDrawListener(this)
                        view.postOnAnimation { view.postOnAnimation { frame.countDown() } }
                    }
                }
            })
            view.invalidate()
        }
        assertTrue(frame.await(5, TimeUnit.SECONDS))
    }
}
