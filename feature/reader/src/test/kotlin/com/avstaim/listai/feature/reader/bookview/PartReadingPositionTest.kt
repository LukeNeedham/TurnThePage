package com.avstaim.listai.feature.reader.bookview

import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.ReaderNavigationState
import com.avstaim.listai.reader.pagination.*
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PartReadingPositionTest {
    private val key = LayoutKey(800, 700, PageMode.SPREAD)
    private val engine = IncrementalPaginationEngine { block, layout ->
        MeasurementMetrics(listOf(MeasuredLine(0, block.source.plainText().length, 0f, 40f)), layout.contentWidth.toFloat())
    }
    private val part = BookBlock.Heading(BlockId("part"), 1, listOf(InlineNode.Text("Часть вторая")))
    private val chapter = BookBlock.Heading(BlockId("chapter"), 2, listOf(InlineNode.Text("Глава четвёртая")))
    private val body = BookBlock.Paragraph(BlockId("body"), listOf(InlineNode.Text("Исходный текст.")))
    private val document = BookDocument(BookId("part-navigation"), BookMetadata("Книга"), listOf(part, chapter, body),
        parts = listOf(BookPart(listOf(part.id))), chapters = listOf(BookChapter(listOf(chapter.id), 4)))

    @Test fun spreadCommitOnBlankVersoKeepsTheRealChapterThroughSingleReflow() = runBlocking {
        val snapshot = engine.paginate(document, null, key).last()
        assertEquals(listOf(PageKind.PART_TITLE, PageKind.BLANK, PageKind.TEXT), snapshot.pages.map { it.kind })
        val position = ReaderPosition(snapshot.pages.first().start)
        position.commit(PhysicalBookState.Open(1), snapshot)
        assertEquals(chapter.id, position.location?.blockId)
        val phone = engine.paginate(document, position.location, key.copy(pageMode = PageMode.SINGLE)).last()
        assertEquals(listOf(PageKind.PART_TITLE, PageKind.TEXT), phone.pages.map { it.kind })
        val restored = position.initialState(phone) as PhysicalBookState.Open
        assertEquals(chapter.id, phone.pages[restored.semanticPageIndex].start.blockId)
        assertEquals(PageKind.TEXT, phone.pages[restored.semanticPageIndex].kind)
        val chapterLocation = position.location
        position.commit(restored, phone)
        val unfolded = engine.paginate(document, position.location, key).last()
        assertEquals(chapterLocation, position.location)
        assertEquals(listOf(PageKind.PART_TITLE, PageKind.BLANK, PageKind.TEXT), unfolded.pages.map { it.kind })
        assertEquals(chapter.id, unfolded.pages[(position.initialState(unfolded) as PhysicalBookState.Open).semanticPageIndex].start.blockId)
    }

    @Test fun navigationAndContentsUsePrintedPhysicalFoliosAfterFrontmatter() = runBlocking {
        val flow = document.readingFlow()
        val target = flow.first { it.source.id == chapter.id }.let { BookLocation(it.source.id, 0, it.globalOffset) }
        val source = document.copy(tableOfContents = listOf(BookTocEntry("Глава четвёртая", target)))
        val snapshot = engine.paginate(source, target, key.copy(leadingPageCount = 3)).last()
        val page = snapshot.pages[snapshot.pages.pageIndexContaining(target)]
        val navigation = ReaderNavigationState(source, snapshot, target)
        assertEquals(page.displayNumber, navigation.current.pageNumber)
        assertEquals(snapshot.pages.last().displayNumber, navigation.current.totalPages)
        assertEquals(target, navigation.previewPage(page.displayNumber)?.location)
        assertEquals(4, navigation.firstAvailablePage)
        assertNull(navigation.previewPage(1))
        val contents = PhysicalContents(source, "Оглавление", "Текст")
        assertEquals(page.displayNumber, contents.sourcePageNumbers(snapshot).values.single())
        assertEquals(1, page.displayNumber % 2)
    }
}
