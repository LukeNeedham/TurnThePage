package com.avstaim.listai

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.LibraryBook
import com.avstaim.listai.core.model.ReadingProgress
import com.avstaim.listai.feature.library.LibraryScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V11LibraryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun longPressSharesWithoutOpeningAndImportOffersFilesOrRecursiveFolder() {
        var opened: BookId? = null
        var shared: BookId? = null
        var files = 0
        var folders = 0
        showShelf(onOpen = { opened = it }, onShare = { shared = it },
            onImport = { files++ }, onImportFolder = { folders++ })
        compose.onNodeWithTag("library_book_v11-0").performTouchInput { longClick() }
        compose.onNodeWithTag("library_share").assertIsDisplayed().performClick()
        assertEquals(BookId("v11-0"), shared)
        assertNull(opened)
        compose.onNodeWithTag("library_import").performClick()
        compose.onNodeWithTag("library_import_files").performClick()
        assertEquals(1, files)
        compose.onNodeWithTag("library_import").performClick()
        compose.onNodeWithTag("library_import_folder").performClick()
        assertEquals(1, folders)
    }

    @Test fun longPressDragMovesToAnotherSlotOnceAndKeepsCheckpointUpdatesInPlace() {
        val books = mutableStateOf(fixtures())
        val moves = mutableListOf<Pair<BookId, BookId>>()
        var opened = false
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        compose.runOnUiThread {
            compose.activity.setContent {
                ListaiTheme {
                    LibraryScreen(books.value, false, {}, {}, { opened = true }, {}, onMoveBook = { from, to ->
                        moves += from to to
                        val changed = books.value.toMutableList()
                        val fromIndex = changed.indexOfFirst { it.id == from }
                        val toIndex = changed.indexOfFirst { it.id == to }
                        changed.add(toIndex, changed.removeAt(fromIndex))
                        books.value = changed
                    })
                }
            }
        }
        val source = compose.onNodeWithTag("library_book_v11-0").fetchSemanticsNode().boundsInRoot.center
        val destination = compose.onNodeWithTag("library_book_v11-1").fetchSemanticsNode().boundsInRoot.center
        val origin = compose.onNodeWithTag("library_screen").fetchSemanticsNode().boundsInRoot.topLeft
        compose.onNodeWithTag("library_screen").performTouchInput {
            down(source - origin)
            advanceEventTime(650)
            moveTo(source - origin + Offset(1f, 0f))
            advanceEventTime(50)
            moveTo(destination - origin)
            advanceEventTime(100)
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(BookId("v11-0") to BookId("v11-1")), moves)
        assertFalse(opened)
        assertEquals(BookId("v11-1"), books.value.first().id)
        val movedBounds = compose.onNodeWithTag("library_book_v11-0").fetchSemanticsNode().boundsInRoot
        compose.runOnUiThread {
            books.value = books.value.map { if (it.id == BookId("v11-0")) it.copy(lastOpenedAt = Long.MAX_VALUE) else it }
        }
        assertEquals(movedBounds, compose.onNodeWithTag("library_book_v11-0").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun heldDragScrollsAcrossLazyRowsWithoutLosingTheBook() {
        val books = List(24) { index -> fixtures().first().copy(id = BookId("long-$index"), title = "Book $index") }
        var destination: BookId? = null
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        compose.runOnUiThread {
            compose.activity.setContent {
                ListaiTheme {
                    LibraryScreen(books, false, {}, {}, {}, {}, onMoveBook = { _, target -> destination = target })
                }
            }
        }
        val source = compose.onNodeWithTag("library_book_long-0").fetchSemanticsNode().boundsInRoot.center
        val screen = compose.onNodeWithTag("library_screen").fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        val edge = Offset(source.x - screen.left, screen.height - 100f * density)
        compose.onNodeWithTag("library_screen").performTouchInput {
            down(source - screen.topLeft)
            advanceEventTime(650)
            moveTo(source - screen.topLeft + Offset(1f, 0f))
            advanceEventTime(50)
            moveTo(edge)
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("library_book_long-12").fetchSemanticsNodes().any {
                it.boundsInRoot.top < screen.bottom - 100f * density && it.boundsInRoot.bottom > screen.top
            }
        }
        compose.onNodeWithTag("library_dragging_book").assertIsDisplayed()
        compose.onNodeWithTag("library_screen").performTouchInput { up() }
        compose.waitForIdle()
        assertNotNull(destination)
        assertTrue(destination!!.value.removePrefix("long-").toInt() >= 6)
        compose.onNodeWithTag("library_share").assertDoesNotExist()
    }

    @Test fun accessibleBookActionsExposeReorderAndShare() {
        var moved: Pair<BookId, BookId>? = null
        showShelf(onMove = { from, to -> moved = from to to })
        compose.onNodeWithTag("library_book_v11-1").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithTag("library_move_earlier").performClick()
        assertEquals(BookId("v11-1") to BookId("v11-0"), moved)
    }

    private fun showShelf(
        onOpen: (BookId) -> Unit = {},
        onShare: (BookId) -> Unit = {},
        onMove: (BookId, BookId) -> Unit = { _, _ -> },
        onImport: () -> Unit = {},
        onImportFolder: () -> Unit = {},
    ) {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        compose.runOnUiThread {
            compose.activity.setContent {
                ListaiTheme { LibraryScreen(fixtures(), false, onImport, {}, onOpen, {},
                    onMoveBook = onMove, onShare = onShare, onImportFolder = onImportFolder) }
            }
        }
    }

    private fun fixtures() = List(4) { index ->
        LibraryBook(BookId("v11-$index"), "Book $index", "Author", "fixture.fb2", null,
            4L - index, null, 1000, ReadingProgress())
    }
}
