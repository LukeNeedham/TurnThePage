package com.avstaim.listai.feature.library

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*

/** The approved prototype's three scenes, using the real shelf and cover renderer. */
@PreviewTest
@Preview(name = "Прототип — на полке", widthDp = 412, heightDp = 700, locale = "ru")
@Preview(name = "Прототип — тёмная полка", widthDp = 412, heightDp = 700, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Rc5PrototypeShelfScreenshot() { PrototypeScene() }

@PreviewTest
@Preview(name = "Прототип — внутри стопки", widthDp = 412, heightDp = 700, locale = "ru")
@Preview(name = "Прототип — стопка Fold", widthDp = 840, heightDp = 700, locale = "ru")
@Composable
fun Rc5PrototypeOpenStackScreenshot() { PrototypeScene(stackId = "verne") }

@PreviewTest
@Preview(name = "Прототип — создать стопку", widthDp = 412, heightDp = 700, locale = "ru")
@Preview(name = "Прототип — диалог узкий крупный", widthDp = 320, heightDp = 640, locale = "ru", fontScale = 1.5f,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Rc5PrototypeCreateScreenshot() { PrototypeScene(creating = true) }

@PreviewTest
@Preview(name = "Стопка — длинное имя вдали", widthDp = 320, heightDp = 640, locale = "ru", fontScale = 1.5f)
@Composable
fun Rc5LongStackSmallScreenshot() { PrototypeScene(zoom = ShelfZoom.SMALL, longName = true) }

@PreviewTest
@Preview(name = "Стопка — длинное имя средняя", widthDp = 412, heightDp = 800, locale = "ru")
@Composable
fun Rc5LongStackMediumScreenshot() { PrototypeScene(longName = true) }

@PreviewTest
@Preview(name = "Стопка — длинное имя вблизи", widthDp = 412, heightDp = 800, locale = "ru")
@Composable
fun Rc5LongStackLargeScreenshot() { PrototypeScene(zoom = ShelfZoom.LARGE, longName = true) }

@Composable
private fun PrototypeScene(stackId: String? = null, creating: Boolean = false,
    zoom: ShelfZoom = ShelfZoom.MEDIUM, longName: Boolean = false) {
    val catalog = prototypeCatalog().let { source -> if (longName) source.copy(stacks = source.stacks.map {
        it.copy(name = "Жюль Верн: невероятные путешествия и открытия")
    }) else source }
    ListaiTheme(darkTheme = isSystemInDarkTheme()) {
        Box {
            LibraryScreen(catalog.books, false, {}, {}, {}, {}, catalog = catalog, initialStackId = stackId, shelfZoom = zoom)
            if (creating) LibraryStackNameDialog("Джек Лондон", false, {}, {}, catalog.books.takeLast(2))
        }
    }
}

private fun prototypeCatalog(): LibraryCatalog {
    val titles = listOf("Таинственный остров", "Дети капитана Гранта", "Вокруг света за 80 дней",
        "Двадцать тысяч лье", "Пятнадцатилетний капитан", "Путешествие к центру Земли",
        "Человек-амфибия", "Морской волк", "Белый Клык")
    val books = titles.mapIndexed { index, title -> LibraryBook(BookId("prototype-$index"), title,
        when { index < 6 -> "Жюль Верн"; index == 6 -> "Александр Беляев"; else -> "Джек Лондон" },
        "$index.fb2", null, 1, null, 1000, ReadingProgress(), stackId = if (index < 6) "verne" else null) }
    return LibraryCatalog(books, listOf(LibraryStack("verne", "Жюль Верн", books.take(6).map { it.id })),
        listOf(LibraryShelfItem.Stack("verne")) + books.drop(6).map { LibraryShelfItem.Book(it.id) })
}
