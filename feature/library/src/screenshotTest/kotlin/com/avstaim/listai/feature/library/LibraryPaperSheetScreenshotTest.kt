package com.avstaim.listai.feature.library

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*

@PreviewTest
@Preview(name = "Book menu — paper English", widthDp = 412, heightDp = 800, locale = "en")
@Preview(name = "Меню книги — крупный русский", widthDp = 320, heightDp = 680, locale = "ru", fontScale = 1.5f)
@Composable
fun LibraryBookMenuPaperScreenshot() {
    ListaiTheme(darkTheme = isSystemInDarkTheme()) {
        LibraryItemMenu(book = paperSheetCatalog().books.first(), actions = listOf(
            LibraryMenuAction(R.string.library_share, "share", LibraryMenuIcon.SHARE, {}),
            LibraryMenuAction(R.string.library_create_stack, "create", LibraryMenuIcon.STACK, {}),
            LibraryMenuAction(R.string.library_add_to_stack, "add", LibraryMenuIcon.ADD, {}),
            LibraryMenuAction(R.string.library_move_earlier, "earlier", LibraryMenuIcon.EARLIER, {}),
            LibraryMenuAction(R.string.library_delete, "delete", LibraryMenuIcon.DELETE, {}, destructive = true),
        ), onDismiss = {})
    }
}

@PreviewTest
@Preview(name = "Меню стопки — графит", widthDp = 412, heightDp = 800, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Stack menu — wide paper", widthDp = 840, heightDp = 680, locale = "en")
@Composable
fun LibraryStackMenuPaperScreenshot() {
    val catalog = paperSheetCatalog()
    ListaiTheme(darkTheme = isSystemInDarkTheme()) {
        LibraryItemMenu(stack = catalog.stacks.first(), books = catalog.books, actions = listOf(
            LibraryMenuAction(R.string.library_add_books, "add", LibraryMenuIcon.ADD, {}),
            LibraryMenuAction(R.string.library_rename_stack, "rename", LibraryMenuIcon.RENAME, {}),
            LibraryMenuAction(R.string.library_dissolve_stack, "dissolve", LibraryMenuIcon.DISSOLVE, {}, destructive = true),
        ), onDismiss = {})
    }
}

@PreviewTest
@Preview(name = "Создание стопки — один лист", widthDp = 412, heightDp = 860, locale = "ru")
@Preview(name = "Stack creation — narrow dark large type", widthDp = 320, heightDp = 680, locale = "en",
    fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Stack creation — Fold", widthDp = 840, heightDp = 760, locale = "en")
@Composable
fun LibraryStackCreationPaperScreenshot() {
    val catalog = paperSheetCatalog()
    ListaiTheme(darkTheme = isSystemInDarkTheme()) {
        LibraryBookPicker(catalog, LibraryBookPickerRequest(catalog.books.take(2).map { it.id }), {}, { _, _ -> })
    }
}

@PreviewTest
@Preview(name = "Add books — one sheet", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun LibraryStackAddPaperScreenshot() {
    val catalog = paperSheetCatalog()
    ListaiTheme {
        LibraryBookPicker(catalog, LibraryBookPickerRequest(listOf(catalog.books.last().id), "journeys"), {}, { _, _ -> })
    }
}

private fun paperSheetCatalog(): LibraryCatalog {
    val titles = listOf("The Wind in the Willows", "The Golden Age", "Dream Days", "The Reluctant Dragon", "A Journey to the North", "A Quiet Library")
    val books = titles.mapIndexed { index, title -> LibraryBook(BookId("paper-$index"), title,
        if (index < 4) "Kenneth Grahame" else "Maria North", "$index.fb2", null, 1, null, 1000,
        ReadingProgress(), authorNames = listOf(if (index < 4) "Kenneth Grahame" else "Maria North"),
        series = if (index < 2) "Favourite stories" else null,
        genres = listOf("Fiction"), stackId = if (index == 4) "journeys" else null) }
    return LibraryCatalog(books, listOf(LibraryStack("journeys", "Journeys and discoveries", listOf(books[4].id))),
        books.filter { it.stackId == null }.map { LibraryShelfItem.Book(it.id) } + LibraryShelfItem.Stack("journeys"))
}
