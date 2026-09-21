package com.avstaim.listai.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.designsystem.rememberPlyosTypefaceFamily
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.bookview.*
import com.avstaim.listai.reader.pagination.*
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt

@PreviewTest
@Preview(name = "Notes 8 — Russian white paper and rich footers", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun Stage8RussianFootnotesScreenshot() { FootnoteBookPreview() }

@PreviewTest
@Preview(name = "Notes 8 — English white paper and rich footers", widthDp = 412, heightDp = 850, locale = "en")
@Composable
fun Stage8EnglishFootnotesScreenshot() { FootnoteBookPreview(english = true) }

@PreviewTest
@Preview(name = "Notes 8 — Russian graphite footers", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun Stage8RussianDarkFootnotesScreenshot() { FootnoteBookPreview(dark = true) }

@PreviewTest
@Preview(name = "Notes 8 — English graphite footers", widthDp = 412, heightDp = 850, locale = "en")
@Composable
fun Stage8EnglishDarkFootnotesScreenshot() { FootnoteBookPreview(english = true, dark = true) }

@PreviewTest
@Preview(name = "Notes 8 — independent spread footers", widthDp = 1000, heightDp = 650, locale = "ru")
@Composable
fun Stage8SpreadFootnotesScreenshot() { FootnoteBookPreview(spread = true) }

@PreviewTest
@Preview(name = "Notes 8 — English mid curl printed footnote", widthDp = 412, heightDp = 850, locale = "en")
@Composable
fun Stage8FootnotesCurlScreenshot() { FootnoteBookPreview(english = true, curl = true) }

@PreviewTest
@Preview(name = "Notes 8 — Russian dark spread curl", widthDp = 1000, heightDp = 650, locale = "ru")
@Composable
fun Stage8DarkSpreadFootnotesCurlScreenshot() { FootnoteBookPreview(dark = true, spread = true, curl = true) }

@PreviewTest
@Preview(name = "Notes 8 — Russian full notes and margin return", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun Stage8RussianNotesSectionScreenshot() { FootnoteBookPreview(notesSection = true) }

@PreviewTest
@Preview(name = "Notes 8 — English graphite full notes and return", widthDp = 412, heightDp = 850, locale = "en")
@Composable
fun Stage8EnglishDarkNotesSectionScreenshot() { FootnoteBookPreview(english = true, dark = true, notesSection = true) }

@PreviewTest
@Preview(name = "Notes 8 — Russian dark continuation spread and repeated returns", widthDp = 1000, heightDp = 650, locale = "ru")
@Composable
fun Stage8RussianDarkNotesContinuationScreenshot() {
    val notes = remember {
        val source = footnoteScreenshotDocument(english = false)
        val extended = source.copy(notes = source.notes.map { note ->
            if (note.id == NoteId("long")) note.copy(blocks = listOf(BookBlock.Paragraph(BlockId("long-body"),
                listOf(InlineNode.Text(note.blocks.single().plainText().repeat(3)))))) else note
        })
        requireNotNull(PhysicalNotes.create(extended, "Примечания", { "Примечание $it" }, "к тексту"))
    }
    ListaiTheme(darkTheme = true) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val layout = with(density) {
                calculateBookCanvasLayout(Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()),
                    BookPresentation.SPREAD, 6.dp.toPx(), 4.dp.toPx())
            }
            val key = LayoutKey(constraints.maxWidth, constraints.maxHeight, PageMode.SPREAD,
                density = density.density, densityKey = density.density.toString(), textScale = density.fontScale,
                darkTheme = true, localeTag = "ru", pageWidthPx = layout.pageWidth.roundToInt(),
                pageHeightPx = layout.pageHeight.roundToInt()).forNotes()
            val typefaces = rememberPlyosTypefaceFamily()
            // Select real consecutive continuation leaves without adding a production navigation override.
            val surfaces = remember(notes, key, typefaces) { runBlocking {
                val pages = IncrementalPaginationEngine(AndroidBookMeasurer(notes.document,
                    "Нет изображения", typefaces = typefaces))
                    .paginate(notes.document, null, key).last().pages
                fun PageLayout.isContinuation() = blocks.firstOrNull()?.let {
                    it.blockId == BlockId("long-body") && it.startCharOffset > 0
                } == true
                val leftIndex = pages.indices.first { index ->
                    pages[index].isContinuation() && pages.getOrNull(index + 1)?.isContinuation() == true
                }
                val rasterizer = AndroidPageRasterizer(notes.document, key, "Нет изображения",
                    typefaces = typefaces)
                suspend fun surface(index: Int): BookVisualSurface.Page {
                    val page = pages[index]
                    val returns = notes.returns(page, key, visit = null)
                    check(returns.single().noteId == NoteId("long"))
                    return BookVisualSurface.Page(rasterizer.render(page).withNoteReturns(returns)
                        .withFooter(PageReadingFooter(page.displayNumber, pages.size, null, notes.title))
                        .asBookSurface(page.displayNumber))
                }
                VisibleBookSurfaces(surface(leftIndex), surface(leftIndex + 1))
            } }
            Canvas(Modifier.fillMaxSize()) {
                drawReaderBackdrop(darkTheme = true)
                drawPhysicalBook(layout, surfaces, .85f, PhysicalBookPose.OPEN)
            }
        }
    }
}

@Composable
private fun FootnoteBookPreview(english: Boolean = false, dark: Boolean = false, spread: Boolean = false,
    curl: Boolean = false, notesSection: Boolean = false) {
    val document = remember(english) { footnoteScreenshotDocument(english) }
    ListaiTheme(darkTheme = dark) {
        PhysicalBookView(emptyList(), document.metadata.title, null, document = document, darkTheme = dark,
            initialLocation = if (spread) BookLocation(BlockId("body-2"), 0, 0) else null,
            initialNoteId = if (notesSection) NoteId("short") else null,
            outerPadding = 6.dp, coverInset = 4.dp,
            previewTurn = if (curl) DemoTurnPreview(TurnDirection.FORWARD, .40f) else null)
    }
}

private fun footnoteScreenshotDocument(english: Boolean): BookDocument {
    val shortId = NoteId("short")
    val longId = NoteId("long")
    fun call(id: NoteId, marker: String) = InlineNode.Link(LinkTarget.Note(id), listOf(InlineNode.Text(marker)))
    val morning = if (english) "Morning light fell across the desk. The atlas lay open beside a cup of tea. "
        else "Утренний свет падал на письменный стол. Атлас лежал раскрытым рядом с чашкой чая. "
    val blocks = listOf<BookBlock>(BookBlock.Heading(BlockId("chapter"), 1,
        listOf(InlineNode.Text(if (english) "The atlas of quiet places" else "Атлас тихих мест")))) +
        List(12) { index -> BookBlock.Paragraph(BlockId("body-$index"), listOf(
            InlineNode.Text(if (english) "The old observatory" else "Старая обсерватория"), call(shortId, "[1]"),
            InlineNode.Text(if (english) " stood above the river. A pencilled name" else " стояла над рекой. Имя, написанное карандашом"),
            call(longId, "[2]"), InlineNode.Text(if (english) " led us back to the map. " else ", возвращало нас к карте. "),
            InlineNode.Text(morning.repeat(3)),
        )) }
    val short = listOf(InlineNode.Text(if (english) "The " else "Название "),
        InlineNode.Emphasis(listOf(InlineNode.Text(if (english) "observatory" else "обсерватории"))),
        InlineNode.Text(if (english) " appears in the " else " встречается в "),
        InlineNode.Strong(listOf(InlineNode.Text(if (english) "1894" else "1894 году"))),
        InlineNode.Text(if (english) " register; the " else "; слово "),
        InlineNode.Strikethrough(listOf(InlineNode.Text(if (english) "abandoned" else "заброшенная"))),
        InlineNode.Text(if (english) " annotation was added later." else " добавили позднее."))
    val long = listOf(InlineNode.Text((if (english)
        "The pencilled name belongs to the cartographer who surveyed both banks. Her notebooks preserve distances, weather, and the changing names of small villages. "
        else "Имя принадлежит картографу, исследовавшей оба берега. В её записных книжках сохранились расстояния, погода и прежние названия маленьких деревень. ").repeat(18)))
    return BookDocument(BookId("stage8-$english"), BookMetadata(if (english) "The quiet atlas" else "Тихий атлас",
        language = if (english) "en" else "ru"), blocks, notes = listOf(
        BookNote(shortId, listOf(BookBlock.Paragraph(BlockId("short-body"), short))),
        BookNote(longId, listOf(BookBlock.Paragraph(BlockId("long-body"), long)),
            title = if (english) "The cartographer's notebooks" else "Записки картографа"),
    ))
}
