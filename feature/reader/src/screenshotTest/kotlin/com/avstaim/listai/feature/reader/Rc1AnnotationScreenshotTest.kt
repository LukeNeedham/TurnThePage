package com.avstaim.listai.feature.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.bookview.*
import com.avstaim.listai.reader.rendering.turn.TurnDirection

@PreviewTest
@Preview(name = "RC1 annotation — empty inside light spread", widthDp = 1000, heightDp = 650, locale = "ru")
@Composable
fun Rc1EmptyInsideCoverLightSpreadScreenshot() { AnnotationPreview(kind = AnnotationKind.NONE) }

@PreviewTest
@Preview(name = "RC1 annotation — empty inside dark single opening", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun Rc1EmptyInsideCoverDarkSingleScreenshot() {
    AnnotationPreview(kind = AnnotationKind.NONE, dark = true,
        turn = DemoTurnPreview(TurnDirection.FORWARD, .75f))
}

@PreviewTest
@Preview(name = "RC1 annotation — short inside light single", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun Rc1ShortAnnotationLightSingleScreenshot() { AnnotationPreview() }

@PreviewTest
@Preview(name = "RC1 annotation — short inside dark spread", widthDp = 1000, heightDp = 650, locale = "ru")
@Composable
fun Rc1ShortAnnotationDarkSpreadScreenshot() { AnnotationPreview(dark = true) }

@PreviewTest
@Preview(name = "RC1 annotation — rich styles light single", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun Rc1RichAnnotationLightSingleScreenshot() { AnnotationPreview(kind = AnnotationKind.RICH) }

@PreviewTest
@Preview(name = "RC1 annotation — rich styles dark spread", widthDp = 1000, heightDp = 650, locale = "ru")
@Composable
fun Rc1RichAnnotationDarkSpreadScreenshot() { AnnotationPreview(kind = AnnotationKind.RICH, dark = true) }

@PreviewTest
@Preview(name = "RC1 annotation — long inside and continuation spread", widthDp = 1000, heightDp = 650, locale = "ru")
@Composable
fun Rc1LongAnnotationLightSpreadScreenshot() { AnnotationPreview(kind = AnnotationKind.LONG) }

@PreviewTest
@Preview(name = "RC1 annotation — overflow light single", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun Rc1AnnotationOverflowLightSingleScreenshot() { AnnotationPreview(kind = AnnotationKind.LONG, overflow = true) }

@PreviewTest
@Preview(name = "RC1 annotation — paper continuation single curl", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun Rc1AnnotationPaperContinuationSingleCurlScreenshot() {
    AnnotationPreview(kind = AnnotationKind.LONG,
        turn = DemoTurnPreview(TurnDirection.FORWARD, .38f))
}

@PreviewTest
@Preview(name = "RC1 annotation — overflow dark spread curl", widthDp = 1000, heightDp = 650, locale = "ru")
@Composable
fun Rc1AnnotationOverflowDarkSpreadCurlScreenshot() {
    AnnotationPreview(kind = AnnotationKind.LONG, dark = true, overflow = true,
        turn = DemoTurnPreview(TurnDirection.FORWARD, .3f))
}

@PreviewTest
@Preview(name = "RC1 annotation — rigid closing light single", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun Rc1AnnotationRigidClosingSingleScreenshot() {
    AnnotationPreview(kind = AnnotationKind.RICH, turn = DemoTurnPreview(TurnDirection.BACKWARD, .7f))
}

@PreviewTest
@Preview(name = "RC1 annotation — restore source keeps annotation away", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun Rc1RestoredSourceCoverOpeningScreenshot() { AnnotationPreview(kind = AnnotationKind.RICH, restoreSource = true) }

private enum class AnnotationKind { NONE, SHORT, RICH, LONG }

@Composable
private fun AnnotationPreview(kind: AnnotationKind = AnnotationKind.SHORT, dark: Boolean = false,
    overflow: Boolean = false, turn: DemoTurnPreview? = null, restoreSource: Boolean = false) {
    val document = remember(kind) { annotationPreviewDocument(kind) }
    val annotationLocation = when {
        kind == AnnotationKind.NONE || restoreSource -> null
        overflow -> BookLocation(BlockId("annotation-long"), 1900, 1900)
        else -> BookLocation(BlockId("annotation-lead"), 0, 0)
    }
    ListaiTheme(darkTheme = dark) {
        PhysicalBookView(emptyList(), document.metadata.title, document.metadata.authors.single(),
            document = document, includeContents = true, darkTheme = dark,
            initialTocEntryIndex = if (kind == AnnotationKind.NONE && turn == null) 0 else null,
            initialCover = if (kind == AnnotationKind.NONE && turn != null) CoverPosition.FRONT else CoverPosition.OPEN,
            initialAnnotationLocation = annotationLocation,
            initialLocation = if (restoreSource) BookLocation(BlockId("body-3"), 0, 0) else null,
            transitionCoverProgress = if (restoreSource) .75f else null,
            previewTurn = turn, outerPadding = 4.dp, coverInset = 3.dp,
            pageControls = { controls -> ReaderTopControls(controls, dark, {}, {}, {}, {}, Modifier.fillMaxSize()) })
    }
}

private fun annotationPreviewDocument(kind: AnnotationKind): BookDocument {
    val annotation = if (kind == AnnotationKind.NONE) emptyList() else buildList<BookBlock> {
        add(BookBlock.Paragraph(BlockId("annotation-lead"), listOf(InlineNode.Text(
            "За окнами старой библиотеки идёт дождь. Между страницами случайно найденной книги " +
                "сохранилось письмо, которое меняет привычный ход вещей."))))
        if (kind == AnnotationKind.RICH) {
            add(BookBlock.Spacer(BlockId("annotation-empty")))
            add(BookBlock.Paragraph(BlockId("annotation-emphasis"), listOf(
                InlineNode.Text("Это история о "), InlineNode.Emphasis(listOf(InlineNode.Text("памяти и доверии"))),
                InlineNode.Text(", о долгом путешествии домой и о людях, которые умеют ждать."))))
            add(BookBlock.Paragraph(BlockId("annotation-strong"), listOf(
                InlineNode.Strong(listOf(InlineNode.Text("У каждого письма есть адресат."))),
                InlineNode.Text(" Даже если адрес давно исчез с карты."))))
        }
        if (kind == AnnotationKind.LONG) add(BookBlock.Paragraph(BlockId("annotation-long"),
            listOf(InlineNode.Text(("Дорога ведёт через тихие улицы к реке. В каждом доме хранится своя история, " +
                "а на полях старых писем остаются следы времени. Читателю предстоит собрать их воедино. ").repeat(35)))))
    }
    val blocks = List(5) { index -> BookBlock.Paragraph(BlockId("body-$index"), listOf(InlineNode.Text(
        "Утром библиотека была пуста. Солнечный свет падал на деревянный стол у окна. ".repeat(18)))) }
    return BookDocument(BookId("rc1-annotation-$kind"),
        BookMetadata("Северная библиотека", authors = listOf("Елена Север"), language = "ru", annotationBlocks = annotation),
        blocks, tableOfContents = listOf(BookTocEntry("Глава I. Найденное письмо", BookLocation(blocks.first().id, 0, 0))))
}
