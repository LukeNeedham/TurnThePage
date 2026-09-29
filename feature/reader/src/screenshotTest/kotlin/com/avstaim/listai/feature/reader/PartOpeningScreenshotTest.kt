package com.avstaim.listai.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.bookview.PhysicalBookView

@PreviewTest
@Preview(name = "Part title — phone white", widthDp = 412, heightDp = 800, locale = "ru")
@Composable
fun PartOpeningPhoneScreenshot() { PartOpeningPreview() }

@PreviewTest
@Preview(name = "Part title — phone graphite", widthDp = 412, heightDp = 800, locale = "ru")
@Composable
fun PartOpeningDarkPhoneScreenshot() { PartOpeningPreview(dark = true) }

@PreviewTest
@Preview(name = "Part title — Fold recto", widthDp = 1000, heightDp = 760, locale = "ru")
@Composable
fun PartOpeningFoldScreenshot() { PartOpeningPreview() }

@PreviewTest
@Preview(name = "Part title — Fold graphite large text", widthDp = 1000, heightDp = 760, locale = "ru", fontScale = 1.3f)
@Composable
fun PartOpeningDarkFoldScreenshot() { PartOpeningPreview(dark = true) }

@PreviewTest
@Preview(name = "Part verso — blank and chapter recto", widthDp = 1000, heightDp = 760, locale = "ru")
@Composable
fun PartVersoFoldScreenshot() { PartOpeningPreview(chapter = true) }

@PreviewTest
@Preview(name = "Part title — long heading large text", widthDp = 360, heightDp = 760, locale = "ru", fontScale = 1.5f)
@Composable
fun PartLongTitleScreenshot() { PartOpeningPreview(longTitle = true) }

@Composable
private fun PartOpeningPreview(dark: Boolean = false, chapter: Boolean = false, longTitle: Boolean = false) {
    val document = remember(longTitle) {
        fun text(id: String, value: String, role: ParagraphRole = ParagraphRole.BODY) =
            BookBlock.Paragraph(BlockId(id), listOf(InlineNode.Text(value)), role)
        fun heading(id: String, value: String, level: Int = 1) = BookBlock.Heading(BlockId(id), level, listOf(InlineNode.Text(value)))
        BookDocument(BookId("part-preview"), BookMetadata("Седьмой огонь", listOf("Александр Ладожский"), "ru"), listOf(
            text("previous", "Вторая половина оставалась труднее: почему спасательный огонь оказался причиной аварии?"),
            heading("part", "Часть вторая"),
            heading("title", if (longTitle) "Карта прилива и обратная сторона далёкого берега" else "Карта прилива"),
            BookBlock.Quote(BlockId("quote"), listOf(
                text("epigraph", "На сухой карте глубины неподвижны. На живой они дышат."),
                text("author", "Семён Ракитин, полевой дневник", ParagraphRole.TEXT_AUTHOR),
            ), QuoteRole.EPIGRAPH),
            heading("chapter", "Глава четвёртая", 2), heading("chapter-title", "Сухой Камень", 2),
            text("body", "К Сухому Камню можно было подойти только в сизигийный отлив, когда луна и солнце складывали свои усилия и море отходило дальше обычного. ".repeat(8)),
        ), parts = listOf(BookPart(listOf(BlockId("part"), BlockId("title")), listOf(BlockId("epigraph"), BlockId("author")))),
            chapters = listOf(BookChapter(listOf(BlockId("chapter"), BlockId("chapter-title")), 4, "четвёртая", "Глава четвёртая".length)))
    }
    ListaiTheme(darkTheme = dark) {
        PhysicalBookView(emptyList(), document.metadata.title, document.metadata.authors.single(),
            document = document, darkTheme = dark,
            initialLocation = BookLocation(BlockId(if (chapter) "chapter" else "part"), 0, 0))
    }
}
