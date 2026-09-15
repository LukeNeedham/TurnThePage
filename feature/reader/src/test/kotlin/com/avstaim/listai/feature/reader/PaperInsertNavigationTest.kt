package com.avstaim.listai.feature.reader

import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*
import org.junit.Assert.*
import org.junit.Test

class PaperInsertNavigationTest {
    private val document = BookDocument(BookId("pending-page"), BookMetadata("A long book"),
        List(220) { index -> BookBlock.Paragraph(BlockId("p$index"), listOf(InlineNode.Text("A page of text. ".repeat(20)))) })
    private val pages = document.readingFlow().mapIndexed { index, block ->
        PageLayout(PageId(index.toLong()), BookLocation(block.source.id, 0, block.globalOffset),
            BookLocation(block.source.id, block.source.plainText().length, block.globalOffset + block.source.plainText().length),
            emptyList(), index + 1)
    }

    @Test fun pendingExactPageOpensThatPageWhenTheIndexReachesIt() {
        val partial = navigation(100)
        val draft = PaperNavigationDraft.at(partial.current).editPage("200", partial)
        assertNull(draft.confirmationLocation(partial))
        assertEquals(partial.current.location, draft.preview.location)
        val expanded = navigation(210)
        // The button can be recomposed before the refresh effect: that must still resolve page 200.
        assertEquals(pages[199].start, draft.confirmationLocation(expanded))
        val refreshed = draft.refreshed(expanded)
        assertEquals("200", refreshed.pageText)
        assertEquals(200, refreshed.preview.pageNumber)
        assertEquals(pages[199].start, refreshed.preview.location)
        assertEquals(pages[199].start, refreshed.confirmationLocation(expanded))
        assertEquals(pages.first().start, expanded.current.location)
    }

    @Test fun invalidPercentEditSurvivesPaginationGrowthWithoutConfirming() {
        val partial = navigation(100)
        val selected = partial.previewFraction(.735f)
        val draft = PaperNavigationDraft.at(selected).editPercent("101", partial)
        val refreshed = draft.refreshed(navigation(220))
        assertEquals("101", refreshed.percentText)
        assertEquals(selected.location, refreshed.preview.location)
        assertNull(refreshed.confirmationLocation(navigation(220)))
    }

    @Test fun percentageAndRulerKeepTheirPreviewAsMorePagesArrive() {
        val partial = navigation(100)
        val percentage = PaperNavigationDraft.at(partial.current).editPercent("73,50", partial)
        assertNull(percentage.preview.pageNumber)
        val expanded = navigation(220)
        val refreshed = percentage.refreshed(expanded)
        assertEquals("73,50", refreshed.percentText)
        assertEquals(percentage.preview.location, refreshed.confirmationLocation(expanded))
        assertNotNull(refreshed.preview.pageNumber)
        val ruler = PaperNavigationDraft.at(partial.previewFraction(.91f))
        assertEquals(ruler.preview.location, ruler.refreshed(expanded).confirmationLocation(expanded))
    }

    @Test fun invalidPageCannotFallBackToThePreviousValidPreview() {
        val complete = navigation(220)
        val initial = PaperNavigationDraft.at(complete.current)
        listOf("", "0", "-1", "221", "two hundred").forEach { text ->
            val draft = initial.editPage(text, complete).refreshed(complete)
            assertNull("Invalid page $text must not confirm the old place", draft.confirmationLocation(complete))
        }
    }

    private fun navigation(count: Int) = ReaderNavigationState(document,
        PaginationSnapshot(LayoutKey(320, 600, PageMode.SINGLE), pages.take(count), 0, count == pages.size), pages.first().start)
}
