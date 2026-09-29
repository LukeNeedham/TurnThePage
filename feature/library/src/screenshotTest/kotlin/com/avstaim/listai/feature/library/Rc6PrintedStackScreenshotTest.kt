package com.avstaim.listai.feature.library

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*

@PreviewTest
@Preview(name = "Printed stacks — phone near light", widthDp = 412, heightDp = 800, locale = "en")
@Preview(name = "Printed stacks — phone near dark", widthDp = 412, heightDp = 800, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Printed stacks — Fold near light", widthDp = 840, heightDp = 700, locale = "ru")
@Preview(name = "Printed stacks — Fold near dark", widthDp = 840, heightDp = 700, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Rc6PrintedStacksLargeScreenshot() { PrintedStacks(ShelfZoom.LARGE) }

@PreviewTest
@Preview(name = "Printed stacks — phone medium light", widthDp = 412, heightDp = 800, locale = "ru")
@Preview(name = "Printed stacks — phone medium dark", widthDp = 412, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Printed stacks — Fold medium light", widthDp = 840, heightDp = 700, locale = "en")
@Preview(name = "Printed stacks — Fold medium dark", widthDp = 840, heightDp = 700, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Rc6PrintedStacksMediumScreenshot() { PrintedStacks(ShelfZoom.MEDIUM) }

@PreviewTest
@Preview(name = "Printed stacks — phone far light", widthDp = 320, heightDp = 640, locale = "ru", fontScale = 1.5f)
@Preview(name = "Printed stacks — phone far dark", widthDp = 320, heightDp = 640, locale = "en", fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Printed stacks — Fold far light", widthDp = 840, heightDp = 700, locale = "ru")
@Preview(name = "Printed stacks — Fold far dark", widthDp = 840, heightDp = 700, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Rc6PrintedStacksSmallScreenshot() { PrintedStacks(ShelfZoom.SMALL) }

@Composable
private fun PrintedStacks(zoom: ShelfZoom) {
    // Original, busy printed artwork catches detached-paper regressions hidden by blue fallbacks.
    // The illustration is a debug-only copy from docs/images/demo-book. The deliberately
    // bright printed cover exposes any gap between the artwork and the upper board's edge.
    val illustrated = ImageBitmap.imageResource(R.drawable.rc6_stack_cover)
    val graphic = remember { brightPrintedStackFixture() }
    val catalog = printedStackCatalog()
    ListaiTheme(darkTheme = isSystemInDarkTheme()) {
        LibraryScreen(catalog.books, false, {}, {}, {}, {}, catalog = catalog, shelfZoom = zoom,
            coverContent = { book -> LibraryCover(book, when (book.stackId) {
                "illustrated" -> illustrated
                "graphic" -> graphic
                else -> null
            }) })
    }
}


private fun printedStackCatalog(): LibraryCatalog {
    val stacks = listOf("illustrated" to "Седьмой свет", "graphic" to "Collected stories", "cloth" to "Путешествия")
    val books = stacks.flatMapIndexed { stackIndex, (id, title) ->
        List(if (stackIndex == 1) 2 else 6) { index ->
            LibraryBook(BookId("$id-$index"), title, "Александр Северный", "$id-$index.fb2", null,
                1, null, 1000, ReadingProgress(), stackId = id)
        }
    }
    return LibraryCatalog(books, stacks.map { (id, title) ->
        LibraryStack(id, title, books.filter { it.stackId == id }.map { it.id })
    }, stacks.map { LibraryShelfItem.Stack(it.first) })
}
