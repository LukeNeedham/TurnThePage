package com.avstaim.listai

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.library.LibraryScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Close real keyboard/search surfaces, rather than only changing the initial query parameter. */
@RunWith(AndroidJUnit4::class)
class V12SearchDismissalTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun clearDoneAndExplicitCloseHaveDifferentEffectsAndCloseDoesNotReopen() {
        showShelf()
        compose.onNodeWithTag("library_search").performTouchInput { click(center) }
        val field = compose.onNodeWithTag("library_search_field")
        field.performTextReplacement("missing title")
        compose.onNodeWithTag("library_search_empty").assertIsDisplayed()
        compose.onNodeWithTag("library_search_clear").performTouchInput { click(center) }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("library_book_north-1").assertIsDisplayed()
        field.performClick().performTextReplacement("Outside")
        awaitKeyboard(true)
        field.performImeAction()
        awaitKeyboard(false)
        field.assertTextContains("Outside")
        field.performClick()
        awaitKeyboard(true)
        compose.onNodeWithTag("library_back").performTouchInput { click(center) }
        awaitKeyboard(false)
        field.assertDoesNotExist()
        // Synchronizing a subsequent frame/up event must not let pull/focus reopen the strip.
        compose.waitForIdle()
        field.assertDoesNotExist()
        compose.onNodeWithTag("library_stack_north").assertIsDisplayed()
    }

    @Test fun backFirstDismissesTheKeyboardThenReturnsToTheSameStack() {
        showShelf(stack = "north")
        compose.onNodeWithTag("library_search").performTouchInput { click(center) }
        compose.onNodeWithTag("library_search_field").performTextReplacement("Outside")
        awaitKeyboard(true)
        Espresso.pressBack()
        awaitKeyboard(false)
        compose.onNodeWithTag("library_search_field").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("library_search_field").assertDoesNotExist()
        compose.onNodeWithTag("library_stack_menu").assertIsDisplayed()
        compose.onNodeWithTag("library_book_north-1").assertIsDisplayed()
        compose.onNodeWithTag("library_book_outside").assertDoesNotExist()
    }

    @Test fun bottomSearchButtonCanCloseRepeatedSearchVisitsWithoutLeavingTheStack() {
        showShelf(stack = "north")
        repeat(2) {
            compose.onNodeWithTag("library_search").performTouchInput { click(center) }
            compose.onNodeWithTag("library_search_field").performTextReplacement("Outside")
            compose.onNodeWithTag("library_search").performTouchInput { click(center) }
            compose.onNodeWithTag("library_search_field").assertDoesNotExist()
            awaitKeyboard(false)
            compose.onNodeWithTag("library_stack_menu").assertIsDisplayed()
            compose.onNodeWithTag("library_book_north-1").assertIsDisplayed()
            compose.onNodeWithTag("library_book_outside").assertDoesNotExist()
        }
    }

    private fun awaitKeyboard(visible: Boolean) = compose.waitUntil(10_000) {
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == visible
    }

    private fun showShelf(stack: String? = null) {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        fun book(id: String, title: String, stackId: String? = null) = LibraryBook(
            BookId(id), title, "An Author", "$id.fb2", null, 1, null, 100,
            ReadingProgress(), authorNames = listOf("An Author"), stackId = stackId)
        val books = listOf(book("north-1", "First North", "north"),
            book("north-2", "Second North", "north"), book("outside", "Outside Book"))
        val catalog = LibraryCatalog(books,
            listOf(LibraryStack("north", "Northern Books", books.take(2).map { it.id })),
            listOf(LibraryShelfItem.Stack("north"), LibraryShelfItem.Book(BookId("outside"))))
        compose.runOnUiThread { compose.activity.setContent { ListaiTheme {
            LibraryScreen(books, false, {}, {}, {}, {}, catalog = catalog, initialStackId = stack)
        } } }
        compose.waitForIdle()
    }
}
