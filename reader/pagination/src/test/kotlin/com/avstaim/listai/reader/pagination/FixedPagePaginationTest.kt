package com.avstaim.listai.reader.pagination

import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.BookMetadata
import com.avstaim.listai.core.model.InlineNode
import com.avstaim.listai.core.model.bookmarkSnippet
import com.avstaim.listai.core.model.readingLength
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedPagePaginationTest {
    private fun page(index: Int) = BookBlock.FixedPage(BlockId("pdf-page-${index + 1}"),
        "/not-opened-during-pagination.pdf", "application/pdf", index, 800, 200)
    private val document = BookDocument(BookId("pdf"), BookMetadata("PDF"), List(40, ::page))
    private val key = LayoutKey(600, 900, PageMode.SINGLE)
    // Short pages would all fit on one sheet without fixed-layout boundaries.
    private val engine = IncrementalPaginationEngine { block, layout ->
        val length = if (block.source is BookBlock.FixedPage) 0 else 4
        MeasurementMetrics(listOf(MeasuredLine(0, length, 0f, 30f)), layout.contentWidth.toFloat())
    }

    @Test fun shortSourcePagesNeverMergeAndKeepBlankPageAnchors() = runBlocking {
        val snapshot = engine.paginate(document, null, key).last()
        assertEquals(40, snapshot.pages.size)
        snapshot.pages.forEachIndexed { index, page ->
            assertEquals(1, page.blocks.size)
            assertEquals(BookLocation(BlockId("pdf-page-${index + 1}"), 0, index.toLong()), page.start)
            assertEquals(page.start, page.endExclusive)
            assertEquals(key.contentTop, page.blocks.single().y, .001f)
            assertTrue(page.blocks.single().measurement is MeasurementMetrics)
        }
        assertEquals(40L, document.readingLength())
        assertEquals("PDF · 19", document.bookmarkSnippet(snapshot.pages[18].start))
    }

    @Test fun exactFixedPageSurvivesSpreadTypographyAndViewportChanges() = runBlocking {
        val anchor = BookLocation(BlockId("pdf-page-19"), 0, 0)
        for (layout in listOf(key, key.copy(pageMode = PageMode.SPREAD, fontSizeSp = 32f,
            pageWidthPx = 350, pageHeightPx = 420, darkTheme = true))) {
            val snapshot = engine.paginate(document, anchor, layout).last()
            assertEquals(18, snapshot.anchorPageIndex)
            assertEquals(40, snapshot.pages.size)
            assertEquals(anchor.blockId, snapshot.pages[snapshot.anchorPageIndex].start.blockId)
            assertEquals(18L, snapshot.pages[snapshot.anchorPageIndex].start.fallbackGlobalOffset)
        }
    }

    @Test fun fixedPageCannotSharePaperWithAdjacentReflowText() = runBlocking {
        val text = BookBlock.Paragraph(BlockId("text"), listOf(InlineNode.Text("text")))
        val mixed = document.copy(blocks = listOf(text, page(0), text.copy(id = BlockId("after"))))
        val pages = engine.paginate(mixed, null, key).last().pages
        assertEquals(3, pages.size)
        assertEquals(listOf("text", "pdf-page-1", "after"), pages.map { it.blocks.single().blockId.value })
    }
}
