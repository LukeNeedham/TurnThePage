package com.avstaim.listai.feature.reader.bookview

import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.ReaderNavigationState
import com.avstaim.listai.reader.pagination.*
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EpilogueReadingPositionTest {
    private val key = LayoutKey(900, 700, PageMode.SPREAD, leadingPageCount = 3)
    private val engine = IncrementalPaginationEngine { block, layout ->
        MeasurementMetrics(listOf(MeasuredLine(0, block.source.plainText().length, 0f, 40f)), layout.contentWidth.toFloat())
    }

    @Test fun contentsAndReflowResolveTheOriginalEpilogueSourceRatherThanTheInsertedRectoBlank() = runBlocking {
        fun heading(id: String, label: String) = BookBlock.Heading(BlockId(id), 1, listOf(InlineNode.Text(label)))
        val part = heading("part", "Часть вторая")
        val epilogue = heading("epilogue", "Эпилог")
        val body = BookBlock.Paragraph(BlockId("body"), listOf(InlineNode.Text("Весной Лида вернулась.")))
        val original = BookDocument(BookId("epilogue-position"), BookMetadata("Книга"), listOf(part, epilogue, body),
            parts = listOf(BookPart(listOf(part.id))),
            chapters = listOf(BookChapter(listOf(epilogue.id), null, kind = BookChapterKind.EPILOGUE)))
        val source = original.readingFlow().first { it.source.id == epilogue.id }
        val target = BookLocation(epilogue.id, 0, source.globalOffset)
        val document = original.copy(tableOfContents = listOf(BookTocEntry("Эпилог", target)))
        val spread = engine.paginate(document, target, key).last()
        val page = spread.pages[spread.anchorPageIndex]
        assertEquals(PageKind.TEXT, page.kind)
        assertEquals(target, page.start)
        assertEquals(1, page.displayNumber % 2)
        assertEquals(page.displayNumber, ReaderNavigationState(document, spread, target).current.pageNumber)
        assertEquals(page.displayNumber, PhysicalContents(document, "Оглавление", "Текст").sourcePageNumbers(spread).values.single())
        val position = ReaderPosition(target)
        val phone = engine.paginate(document, position.location, key.copy(pageMode = PageMode.SINGLE, leadingPageCount = 4)).last()
        val state = position.initialState(phone) as PhysicalBookState.Open
        assertEquals(target, phone.pages[state.semanticPageIndex].start)
        assertEquals(PageKind.TEXT, phone.pages[state.semanticPageIndex].kind)
        assertEquals(target, position.location)
    }
}
