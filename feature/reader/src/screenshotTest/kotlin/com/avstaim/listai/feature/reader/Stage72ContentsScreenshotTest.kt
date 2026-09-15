package com.avstaim.listai.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.bookview.*
import com.avstaim.listai.reader.pagination.readingFlow
import com.avstaim.listai.reader.rendering.turn.TurnDirection

@PreviewTest
@Preview(name = "Contents 7.2 — Russian printed chapters", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun Stage72RussianContentsScreenshot() { PrintedContentsPreview() }

@PreviewTest
@Preview(name = "Contents 7.2 — English large type and long titles", widthDp = 412, heightDp = 850, locale = "en")
@Composable
fun Stage72EnglishContentsLargeScreenshot() { PrintedContentsPreview(english = true, large = true) }

@PreviewTest
@Preview(name = "Contents 7.2 — bluegray hierarchy spread", widthDp = 1000, heightDp = 650, locale = "ru")
@Composable
fun Stage72ContentsDarkSpreadScreenshot() { PrintedContentsPreview(dark = true) }

@PreviewTest
@Preview(name = "Contents 7.2 — continuation leaf paper curl", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun Stage72ContentsContinuationCurlScreenshot() { PrintedContentsPreview(curl = true) }

@Composable
private fun PrintedContentsPreview(english: Boolean = false, large: Boolean = false, dark: Boolean = false,
    curl: Boolean = false) {
    val document = remember(english) { printedContentsDocument(english) }
    ListaiTheme(darkTheme = dark) {
        PhysicalBookView(emptyList(), document.metadata.title, "E. North", document = document,
            includeContents = true, initialTocEntryIndex = if (curl) 10 else 0, darkTheme = dark,
            fontSizeSp = if (large) 26f else 18f, outerPadding = 4.dp, coverInset = 3.dp,
            previewTurn = if (curl) DemoTurnPreview(TurnDirection.FORWARD, .36f) else null)
    }
}

private fun printedContentsDocument(english: Boolean): BookDocument {
    val blocks = List(28) { index -> BookBlock.Paragraph(BlockId("printed-$index"), listOf(InlineNode.Text(
        if (english) "The old library opened its doors to the quiet morning. ".repeat(28)
        else "Старая библиотека открыла двери навстречу тихому утру. ".repeat(28)))) }
    val source = BookDocument(BookId("printed-contents-$english"), BookMetadata(if (english) "The Quiet City" else "Тихий город"), blocks)
    val flow = source.readingFlow()
    fun target(index: Int) = BookLocation(blocks[index].id, 0, flow[index].globalOffset)
    val romans = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")
    val entries = listOf(BookTocEntry(if (english) "A word to the reader" else "Слово к читателю", target(0)),
        BookTocEntry(if (english) "Preface" else "Предисловие", target(1))) + romans.mapIndexed { index, roman ->
        BookTocEntry(if (english) "Chapter $roman\n" + listOf("The morning library",
            "A remarkably long journey along the river to a distant observatory", "The city beyond the forest")[index % 3]
        else "Глава $roman\n" + listOf("Что хранит старая библиотека?",
            "Путешествие вдоль реки к далёкой обсерватории на вершине горы", "Безмолвные города на рассвете")[index % 3],
            target(index * 2 + 2), if (index % 3 == 0) listOf(BookTocEntry(
                if (english) "Notes from the road" else "Заметки с дороги", target(index * 2 + 3))) else emptyList())
    }
    return source.copy(tableOfContents = entries)
}
