package com.avstaim.listai

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.LibraryBook
import com.avstaim.listai.core.model.ReadingProgress
import com.avstaim.listai.feature.library.LibraryScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShelfInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun readerOwnedShelfCannotScrollButShelfInteractionCanResume() {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val enabled = mutableStateOf(false)
        val books = List(24) { index ->
            LibraryBook(BookId("scroll-$index"), "Shelf book $index", "Author", "fixture.fb2", null,
                24L - index, null, 1000, ReadingProgress())
        }
        compose.runOnUiThread {
            compose.activity.setContent {
                ListaiTheme {
                    LibraryScreen(books, false, {}, {}, {}, {}, interactionEnabled = enabled.value)
                }
            }
        }
        val first = compose.onNodeWithTag("library_book_scroll-0")
        val originalSlot = first.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("library_shelf").performTouchInput { swipeUp() }
        assertEquals("An inactive shelf must retain the selected book's return slot",
            originalSlot, first.fetchSemanticsNode().boundsInRoot)

        compose.runOnUiThread { enabled.value = true }
        compose.onNodeWithTag("library_shelf").performTouchInput { swipeUp() }
        first.assertIsNotDisplayed()
    }
}
