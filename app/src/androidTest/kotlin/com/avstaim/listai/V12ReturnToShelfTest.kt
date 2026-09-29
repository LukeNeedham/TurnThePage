package com.avstaim.listai

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.library.LibraryScreen
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V12ReturnToShelfTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun hiddenShelfPositionsTheBookAfterViewportAndColumnChanges() {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val books = List(60) { index -> LibraryBook(BookId("return-$index"), "Book $index", "Author",
            "fixture.fb2", null, index.toLong(), null, 1000, ReadingProgress()) }
        val width = mutableStateOf(340.dp)
        val returning = mutableStateOf<BookId?>(null)
        val positioned = AtomicReference<Pair<BookId, Rect?>?>()
        val target = books[56].id
        compose.runOnUiThread {
            compose.activity.setContent {
                ListaiTheme {
                    Box(Modifier.width(width.value).fillMaxHeight()) {
                        LibraryScreen(books, false, {}, {}, {}, {}, interactionEnabled = false,
                            returningBookId = returning.value,
                            onReturnBookPositioned = { id, rect -> positioned.set(id to rect) })
                    }
                }
            }
        }
        compose.onNodeWithTag("library_book_${target.value}").assertDoesNotExist()
        compose.runOnUiThread { width.value = 260.dp; returning.value = target }
        compose.waitUntil(10_000) { positioned.get()?.first == target && positioned.get()?.second != null }
        val slot = compose.onNodeWithTag("library_book_${target.value}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(slot, requireNotNull(positioned.get()).second)
        val shelf = compose.onNodeWithTag("library_shelf").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("library_import").fetchSemanticsNode().boundsInRoot
        assertTrue(slot.top >= shelf.top)
        assertTrue("The returning cover stays above the floating controls", slot.bottom <= controls.top)
    }

    @Test fun acknowledgedVisibleBookIsRepositionedWhenNewColumnsMoveItsRowOffscreen() {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val books = List(30) { index -> LibraryBook(BookId("resize-$index"), "Book $index", "Author",
            "fixture.fb2", null, index.toLong(), null, 1000, ReadingProgress()) }
        val width = mutableStateOf(340.dp)
        val zoom = mutableStateOf(ShelfZoom.SMALL)
        val returning = mutableStateOf<BookId?>(null)
        val positions = CopyOnWriteArrayList<Pair<BookId, Rect?>>()
        val target = books[4].id
        compose.runOnUiThread { compose.activity.setContent { ListaiTheme {
            Box(Modifier.width(width.value).fillMaxHeight()) {
                LibraryScreen(books, false, {}, {}, {}, {}, interactionEnabled = false, shelfZoom = zoom.value,
                    returningBookId = returning.value, onReturnBookPositioned = { id, rect -> positions += id to rect })
            }
        } } }
        // Before resizing, this is a visible second-row book with a valid acknowledged slot.
        val oldSlot = compose.onNodeWithTag("library_book_${target.value}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.runOnUiThread { returning.value = target }
        compose.waitUntil(10_000) { positions.lastOrNull()?.second == oldSlot }
        val beforeResize = positions.size
        compose.runOnUiThread { width.value = 260.dp; zoom.value = ShelfZoom.LARGE }
        compose.waitUntil(10_000) {
            val changed = positions.drop(beforeResize)
            changed.any { it.second == null } && changed.lastOrNull()?.second?.let { it.width > oldSlot.width * 1.5f } == true
        }
        val newSlot = compose.onNodeWithTag("library_book_${target.value}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(newSlot, positions.last().second)
        assertTrue(newSlot.top >= compose.onNodeWithTag("library_shelf").fetchSemanticsNode().boundsInRoot.top)
        assertTrue(newSlot.bottom <= compose.onNodeWithTag("library_import").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun externallyOpenedBookReturnsAfterClosingAndLoweringViewportChanges() {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val original = controller.selection.value?.opened?.book?.id
        val originalZoom = controller.shelfZoom.value
        val imported = mutableListOf<BookId>()
        val width = mutableStateOf(340.dp)
        try {
            compose.runOnUiThread { controller.returnToShelf() }
            compose.waitUntil(10_000) { controller.selection.value == null }
            compose.runOnUiThread { controller.setShelfZoom(ShelfZoom.MEDIUM) }
            compose.waitUntil(10_000) { controller.shelfZoom.value == ShelfZoom.MEDIUM }
            compose.runOnUiThread { compose.activity.setContent { ListaiTheme {
                Box(Modifier.width(width.value).fillMaxHeight()) { ListaiApp() }
            } } }
            val prefix = UUID.randomUUID().toString()
            runBlocking {
                repeat(24) { index -> imported += controller.repository.import("return-$prefix-$index.fb2") {
                    """<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info>
                        <book-title>Return $prefix $index</book-title></title-info></description>
                        <body><section><p>A return to the real shelf slot, book $index.</p></section></body></FictionBook>""".byteInputStream()
                } }
            }
            val target = imported.first()
            compose.waitUntil(10_000) { controller.books.value.any { it.id == imported.last() } }
            compose.onNodeWithTag("library_book_${target.value}").assertDoesNotExist()
            for (resizePhase in listOf(ShelfReaderPhase.CLOSING, ShelfReaderPhase.LOWERING)) {
                compose.runOnUiThread { controller.open(target) }
                compose.waitUntil(30_000) { phase() == ShelfReaderPhase.READING.name }
                compose.mainClock.autoAdvance = false
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                advanceToPhase(resizePhase)
                if (resizePhase == ShelfReaderPhase.LOWERING) compose.mainClock.advanceTimeBy(64)
                compose.runOnUiThread { width.value = if (resizePhase == ShelfReaderPhase.CLOSING) 260.dp else 340.dp }
                compose.mainClock.autoAdvance = true
                compose.waitUntil(10_000) { controller.selection.value == null && phase() == ShelfReaderPhase.SHELF.name }
                val slot = compose.onNodeWithTag("library_book_${target.value}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val shelf = compose.onNodeWithTag("library_shelf").fetchSemanticsNode().boundsInRoot
                assertTrue(slot.top >= shelf.top)
                assertTrue(slot.bottom <= compose.onNodeWithTag("library_import").fetchSemanticsNode().boundsInRoot.top)
            }
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { controller.returnToShelf() }
            runBlocking { imported.forEach { controller.repository.delete(it) } }
            compose.runOnUiThread { controller.setShelfZoom(originalZoom) }
            compose.waitUntil(10_000) { controller.shelfZoom.value == originalZoom }
            if (original != null) compose.runOnUiThread { controller.open(original) }
        }
    }

    private fun phase() = compose.onNodeWithTag("shelf_reader_transition").fetchSemanticsNode().config[ShelfTransitionPhase]

    private fun advanceToPhase(expected: ShelfReaderPhase) = compose.waitUntil(30_000) {
        compose.mainClock.advanceTimeByFrame()
        phase() == expected.name && (expected != ShelfReaderPhase.LOWERING ||
            compose.onAllNodesWithTag("shelf_flying_book").fetchSemanticsNodes().isNotEmpty())
    }
}
