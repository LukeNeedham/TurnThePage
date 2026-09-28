package com.avstaim.listai.feature.library

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*

@PreviewTest
@Preview(name = "Stacks — close English", widthDp = 412, heightDp = 800, locale = "en")
@Preview(name = "Stacks — close dark", widthDp = 412, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun V12LibraryLargeScreenshot() { V12LibraryPreview(ShelfZoom.LARGE) }

@PreviewTest
@Preview(name = "Стопки — средний русский", widthDp = 412, heightDp = 800, locale = "ru")
@Preview(name = "Стопки — средний тёмный", widthDp = 412, heightDp = 800, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun V12LibraryMediumScreenshot() { V12LibraryPreview(ShelfZoom.MEDIUM) }

@PreviewTest
@Preview(name = "Stacks — distant English", widthDp = 412, heightDp = 800, locale = "en")
@Preview(name = "Stacks — distant dark", widthDp = 412, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun V12LibrarySmallScreenshot() { V12LibraryPreview(ShelfZoom.SMALL) }

@PreviewTest
@Preview(name = "Stack open — Fold", widthDp = 840, heightDp = 700, locale = "en")
@Preview(name = "Stack open — large type", widthDp = 412, heightDp = 800, locale = "en", fontScale = 1.5f)
@Composable
fun V12LibraryOpenStackScreenshot() { V12LibraryPreview(stack = "sea") }

@PreviewTest
@Preview(name = "Search — books inside stacks", widthDp = 412, heightDp = 800, locale = "en")
@Preview(name = "Search — narrow dark", widthDp = 320, heightDp = 640, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun V12LibrarySearchScreenshot() { V12LibraryPreview(query = "North") }

@PreviewTest
@Preview(name = "Поиск — ничего не найдено", widthDp = 412, heightDp = 800, locale = "ru")
@Composable
fun V12LibraryEmptySearchScreenshot() { V12LibraryPreview(query = "Нет такой книги") }

@PreviewTest
@Preview(name = "Stack picker — actual membership", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun V12LibraryStackPickerScreenshot() {
    ListaiTheme { Surface { LibraryBookPicker(v12Catalog(), LibraryBookPickerRequest(listOf(BookId("sky"))), {}, {}) } }
}

@Composable
private fun V12LibraryPreview(zoom: ShelfZoom = ShelfZoom.MEDIUM, stack: String? = null, query: String? = null) {
    val catalog = v12Catalog()
    ListaiTheme(darkTheme = isSystemInDarkTheme()) { Surface {
        LibraryScreen(catalog.books, false, {}, {}, {}, {}, catalog = catalog, shelfZoom = zoom,
            initialStackId = stack, initialSearchQuery = query, versionLabel = "v1.2.0")
    } }
}

private fun v12Catalog(): LibraryCatalog {
    fun book(id: String, title: String, author: String, stack: String? = null, series: String? = null) = LibraryBook(
        BookId(id), title, author, "$id.fb2", null, 1, null, 1000, ReadingProgress(),
        authorNames = listOf(author), series = series, genres = listOf("Fiction"), stackId = stack,
    )
    val books = listOf(book("sky", "The Sky over the Sea", "Maria North", "sea", "Northern Seas"),
        book("island", "The Quiet Island", "Maria North", "sea", "Northern Seas"),
        book("tide", "A Book of Tides", "Maria North", "sea", "Northern Seas"),
        book("atlas", "An Atlas of Quiet Pages", "Alexander Volkov"),
        book("journey", "Journey to the Distant Observatory", "Alexander Volkov"),
        book("night", "The Night Library", "Julia Reed"))
    return LibraryCatalog(books, listOf(LibraryStack("sea", "Northern Seas", books.take(3).map { it.id })),
        listOf(LibraryShelfItem.Stack("sea")) + books.drop(3).map { LibraryShelfItem.Book(it.id) })
}
