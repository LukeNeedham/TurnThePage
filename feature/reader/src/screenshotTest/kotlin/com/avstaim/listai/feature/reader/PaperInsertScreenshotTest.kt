package com.avstaim.listai.feature.reader

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*

@PreviewTest
@Preview(name = "Insert menu — light en", widthDp = 412, heightDp = 800, locale = "en")
@Preview(name = "Вкладка — светлая ru", widthDp = 412, heightDp = 800, locale = "ru")
@Preview(name = "Insert menu — dark en", widthDp = 412, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Вкладка — тёмная ru", widthDp = 412, heightDp = 800, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InsertMenuScreenshots() { InsertPreview(PaperInsertSection.MENU) }

@PreviewTest
@Preview(name = "Settings — light en", widthDp = 412, heightDp = 800, locale = "en")
@Preview(name = "Настройки — светлые ru", widthDp = 412, heightDp = 800, locale = "ru")
@Preview(name = "Settings — dark en", widthDp = 412, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Настройки — тёмные ru", widthDp = 412, heightDp = 800, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InsertSettingsScreenshots() { InsertPreview(PaperInsertSection.SETTINGS) }

@PreviewTest
@Preview(name = "Bookmarks — light en", widthDp = 412, heightDp = 800, locale = "en")
@Preview(name = "Закладки — светлые ru", widthDp = 412, heightDp = 800, locale = "ru")
@Preview(name = "Bookmarks — dark en", widthDp = 412, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Закладки — тёмные ru", widthDp = 412, heightDp = 800, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InsertBookmarksScreenshots() { InsertPreview(PaperInsertSection.BOOKMARKS) }

@PreviewTest
@Preview(name = "Ruler — light en", widthDp = 412, heightDp = 800, locale = "en")
@Preview(name = "Линейка — светлая ru", widthDp = 412, heightDp = 800, locale = "ru")
@Preview(name = "Ruler — dark en", widthDp = 412, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Линейка — тёмная ru", widthDp = 412, heightDp = 800, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InsertRulerScreenshots() { InsertPreview(PaperInsertSection.PROGRESS) }

@PreviewTest
@Preview(name = "Settings — system en", widthDp = 412, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Настройки — системные ru", widthDp = 412, heightDp = 800, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InsertSystemScreenshots() { InsertPreview(PaperInsertSection.SETTINGS, system = true) }

@PreviewTest
@Preview(name = "Reading card — small phone en", widthDp = 320, heightDp = 568, locale = "en")
@Preview(name = "Вкладка — узкий экран ru", widthDp = 320, heightDp = 568, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Reading card — landscape en", widthDp = 740, heightDp = 360, locale = "en")
@Preview(name = "Вкладка — альбомная ru", widthDp = 740, heightDp = 360, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Reading card — Fold en", widthDp = 673, heightDp = 841, locale = "en")
@Preview(name = "Вкладка — Fold ru", widthDp = 673, heightDp = 841, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InsertAdaptiveScreenshots() { InsertPreview(PaperInsertSection.MENU) }

@PreviewTest
@Preview(name = "Type — small phone en", widthDp = 320, heightDp = 568, locale = "en")
@Preview(name = "Шрифт — узкий экран ru", widthDp = 320, heightDp = 568, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Type — large labels en", widthDp = 360, heightDp = 740, fontScale = 1.3f, locale = "en")
@Composable
fun InsertSmallSettingsScreenshots() { InsertPreview(PaperInsertSection.SETTINGS) }

@PreviewTest
@Preview(name = "Ruler — small phone en", widthDp = 320, heightDp = 568, locale = "en")
@Preview(name = "Линейка — узкий экран ru", widthDp = 320, heightDp = 568, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InsertSmallRulerScreenshots() { InsertPreview(PaperInsertSection.PROGRESS) }

@PreviewTest
@Preview(name = "Saved ribbons — small phone en", widthDp = 320, heightDp = 568, locale = "en")
@Preview(name = "Закладки — узкий экран ru", widthDp = 320, heightDp = 568, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InsertSmallBookmarksScreenshots() { InsertPreview(PaperInsertSection.BOOKMARKS) }

@Composable
private fun InsertPreview(section: PaperInsertSection, system: Boolean = false) {
    val russian = LocalConfiguration.current.locales[0].language == "ru"
    val dark = isSystemInDarkTheme()
    val document = remember(russian) {
        val blocks = List(12) { i -> BookBlock.Paragraph(BlockId("p$i"), listOf(InlineNode.Text(
            (if (russian) "Белые страницы хранили историю дороги к обсерватории. "
            else "White pages kept the story of a journey to the observatory. ").repeat(15)))) }
        BookDocument(BookId("insert-preview"), BookMetadata("Atlas"), blocks,
            tableOfContents = listOf(BookTocEntry(if (russian) "Дорога к обсерватории" else "The road to the observatory", BookLocation(blocks.first().id, 0, 0))))
    }
    val navigation = remember(document) {
        val blocks = document.readingFlow()
        val pages = blocks.mapIndexed { i, block -> PageLayout(PageId(i.toLong()),
            BookLocation(block.source.id, 0, block.globalOffset),
            BookLocation(block.source.id, block.source.plainText().length, block.globalOffset + block.source.plainText().length), emptyList(), i + 1) }
        ReaderNavigationState(document, PaginationSnapshot(LayoutKey(360, 680, PageMode.SINGLE), pages, 4, true), pages[4].start)
    }
    val snippets = if (russian) listOf("На мостовой блестели следы ночного дождя.", "Сохранить место — значит сохранить возможность продолжить.", "Город ещё не проснулся, и небо было ясным.")
        else listOf("The pavement still glistened after the rain.", "Keeping a place means keeping the chance to continue.", "The city had not yet woken, and the sky was clear.")
    ListaiTheme(darkTheme = dark) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            PaperReaderInsert(ReaderPreferences(theme = if (system) ReaderTheme.SYSTEM else if (dark) ReaderTheme.DARK else ReaderTheme.LIGHT),
                {}, snippets.mapIndexed { i, text -> Bookmark("mark$i", document.id, BookLocation(BlockId("p$i"), 0, i * 1000L), 0, text) },
                {}, {}, {}, {}, {}, {}, null, navigation, {}, Modifier.padding(18.dp), initialSection = section)
        }
    }
}

/** Shelf settings deliberately have no Surface ancestor providing LocalContentColor. */
@PreviewTest
@Preview(name = "Shelf settings — dark en", widthDp = 412, heightDp = 800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Настройки полки — тёмные ru", widthDp = 412, heightDp = 800, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Shelf settings — light en", widthDp = 412, heightDp = 800, locale = "en")
@Preview(name = "Настройки полки — крупный шрифт ru", widthDp = 320, heightDp = 700, fontScale = 1.3f, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ShelfSettingsScreenshots() {
    val dark = isSystemInDarkTheme()
    ListaiTheme(darkTheme = dark) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(18.dp),
            contentAlignment = Alignment.Center) {
            PaperSettingsSheet(ReaderPreferences(theme = if (dark) ReaderTheme.DARK else ReaderTheme.LIGHT), {}, {}, onShowHints = {})
        }
    }
}
