package com.avstaim.listai.feature.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.bookview.*
import com.avstaim.listai.reader.rendering.turn.TurnDirection

@PreviewTest
@Preview(name = "1.2 — титул в тёмной теме", widthDp = 412, heightDp = 800, locale = "ru")
@Composable
fun V12TitleDarkScreenshot() { V12BookPreview(dark = true, title = true) }

@PreviewTest
@Preview(name = "1.2 — title on graphite Fold", widthDp = 840, heightDp = 700, locale = "en")
@Composable
fun V12TitleDarkFoldScreenshot() { V12BookPreview(russian = false, dark = true, title = true) }

@PreviewTest
@Preview(name = "1.2 — title on Fold", widthDp = 840, heightDp = 700, locale = "en")
@Composable
fun V12TitleFoldScreenshot() { V12BookPreview(russian = false, title = true) }

@PreviewTest
@Preview(name = "1.2 — описание на Fold и меню форзаца", widthDp = 840, heightDp = 700, locale = "ru")
@Composable
fun V12DescriptionFoldScreenshot() { V12BookPreview(description = true) }

@PreviewTest
@Preview(name = "1.2 — description on graphite paper in dark Fold", widthDp = 840, heightDp = 700, locale = "en")
@Composable
fun V12DescriptionDarkFoldScreenshot() { V12BookPreview(russian = false, dark = true, description = true) }

@PreviewTest
@Preview(name = "1.2 — описание в тёмной теме телефона", widthDp = 412, heightDp = 800, locale = "ru")
@Composable
fun V12DescriptionDarkPhoneScreenshot() { V12BookPreview(dark = true, description = true) }

@PreviewTest
@Preview(name = "1.2 — начало главы", widthDp = 412, heightDp = 800, locale = "ru")
@Composable
fun V12ChapterPhoneScreenshot() { V12BookPreview() }

@PreviewTest
@Preview(name = "1.2 — chapter on graphite Fold", widthDp = 840, heightDp = 700, locale = "en")
@Composable
fun V12ChapterDarkFoldScreenshot() { V12BookPreview(russian = false, dark = true) }

@PreviewTest
@Preview(name = "1.2 — глава и часы крупный шрифт", widthDp = 360, heightDp = 640, fontScale = 1.5f, locale = "ru")
@Composable
fun V12ChapterLargeFontScreenshot() { V12BookPreview(largeFont = true) }

@PreviewTest
@Preview(name = "1.2 — title and vector ornament during curl", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun V12TitleCurlScreenshot() { V12BookPreview(russian = false, title = true, curl = true) }

@PreviewTest
@Preview(name = "1.2 — long title narrow window", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "en")
@Composable
fun V12LongTitleScreenshot() { V12BookPreview(russian = false, title = true, longTitle = true) }

@Composable
private fun V12BookPreview(russian: Boolean = true, dark: Boolean = false, title: Boolean = false,
    description: Boolean = false, curl: Boolean = false, largeFont: Boolean = false, longTitle: Boolean = false) {
    val source = remember(russian, longTitle) {
        val heading = BookBlock.Heading(BlockId("chapter-number"), 1,
            listOf(InlineNode.Text(if (russian) "Глава XI." else "Chapter XI.")))
        val chapterTitle = BookBlock.Heading(BlockId("chapter-title"), 1,
            listOf(InlineNode.Text(if (russian) "Тихий океан" else "The Pacific")))
        BookDocument(BookId("v12-screenshot"), BookMetadata(
            if (longTitle) "A journey through the distant islands and quiet libraries of the northern seas" else
                if (russian) "Атлас тихих страниц" else "An Atlas of Quiet Pages",
            listOf(if (russian) "Мария Север" else "Maria North"), if (russian) "ru" else "en",
            annotation = if (russian) "Книга о дальних берегах, тихих библиотеках и людях, которые находят дорогу домой. История начинается с белой страницы и продолжается там, куда ведёт воображение." else
                "A book about distant shores, quiet libraries, and the people who find their way home. The journey begins with a blank page and continues wherever imagination leads."),
            listOf(heading, chapterTitle) + List(10) { index -> BookBlock.Paragraph(BlockId("p$index"), listOf(InlineNode.Text(
                if (russian) "Море ещё хранило ночную тишину. За окном раскрывалась длинная линия берега, и никто не торопился нарушить утренний покой. ".repeat(3)
                else "The sea still held the quiet of the night. Beyond the window lay the long line of the shore, and nobody wished to disturb the calm of the morning. ".repeat(3)))) },
            chapters = listOf(BookChapter(listOf(heading.id, chapterTitle.id), 11, "XI", heading.plainText().length)))
    }
    ListaiTheme(darkTheme = dark) {
        PhysicalBookView(emptyList(), source.metadata.title, source.metadata.authors.first(), document = source,
            darkTheme = dark, fontSizeSp = if (largeFont) 26f else 18f,
            initialTitleLocation = if (title) BookLocation(BlockId("listai:title:name"), 0, 0) else null,
            initialAnnotationLocation = if (description) BookLocation(BlockId("listai:annotation:heading"), 0, 0) else null,
            previewTurn = if (curl) DemoTurnPreview(TurnDirection.FORWARD, fraction = .35f) else null,
            pageControls = { layout -> ReaderTopControls(layout, dark, {}, {}, {}, {},
                modifier = Modifier.fillMaxSize(), showClock = true) },
            insideCoverControls = { bookBounds, viewportBounds, onDescription ->
                ClosedBookControls({}, onDescription, {}, {}, {}, {}, bookBounds, viewportBounds,
                    overlayOnCover = true, modifier = Modifier.fillMaxSize())
            })
    }
}
