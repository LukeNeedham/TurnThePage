package com.avstaim.listai.reader.pagination

import com.avstaim.listai.core.model.*
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChapterPaginationTest {
    @Test fun `title-only source has no duplicate main leaf in either paginator branch`() = runBlocking {
        for (withNotes in listOf(false, true)) {
            val id = NoteId("title-note")
            val title = BookBlock.Heading(BlockId("title"), 1, listOf(InlineNode.Text("Title 😀")) +
                if (withNotes) listOf(InlineNode.Link(LinkTarget.Note(id), listOf(InlineNode.Text("1")))) else emptyList())
            val source = BookDocument(BookId("title-only"), BookMetadata("Title"), listOf(title),
                notes = if (withNotes) listOf(BookNote(id, listOf(BookBlock.Paragraph(BlockId("note"), listOf(InlineNode.Text("Note")))))) else emptyList(),
                titlePageBlockIds = listOf(title.id))
            assertTrue(source.readingFlow().isEmpty())
            assertSame(title, source.readingFlow(includeNativeTitle = true).single().source)
            val snapshot = engine.paginate(source, BookLocation(title.id, 5, 5), key).last()
            assertTrue(snapshot.complete)
            assertTrue(snapshot.pages.isEmpty())
            assertEquals(0, snapshot.anchorPageIndex)
            val projected = engine.paginate(source.copy(isFrontmatter = true), null, key).last()
            assertTrue(projected.pages.isNotEmpty())
            assertEquals(title.id, projected.pages.first().start.blockId)
        }
    }

    @Test fun `native title projection skips duplicates without shifting original global coordinates`() {
        val title = BookBlock.Heading(BlockId("native-title"), 1, listOf(InlineNode.Text("Book 😀")))
        val body = BookBlock.Paragraph(BlockId("body"), listOf(InlineNode.Text("Actual source")))
        val source = BookDocument(BookId("native"), BookMetadata("Book"), listOf(title, body), titlePageBlockIds = listOf(title.id))
        val main = source.readingFlow().single()
        assertEquals(body.id, main.source.id)
        assertEquals(title.plainText().length + 1L, main.globalOffset)
        assertEquals(listOf(title.id, body.id), source.copy(isFrontmatter = true).readingFlow().map { it.source.id })
        val legacyTitleAnchor = BookLocation(title.id, 6, 6)
        assertEquals(legacyTitleAnchor, source.readingFlow(includeNativeTitle = true).resolveLocation(legacyTitleAnchor))
        assertEquals("Book 😀", source.blocks.first().plainText())
    }
    private val key = LayoutKey(300, 500, PageMode.SINGLE)
    private val engine = IncrementalPaginationEngine { block, layout ->
        val text = block.source.plainText()
        MeasurementMetrics((if (text.isEmpty()) listOf(0) else text.indices.step(30).toList()).mapIndexed { index, start ->
            MeasuredLine(start, minOf(start + 30, text.length), index * 20f, (index + 1) * 20f)
        }, layout.contentWidth.toFloat())
    }

    @Test fun `chapter starts new leaf in both pagination branches and nested heading stays inline`() = runBlocking {
        for (withNotes in listOf(false, true)) {
            val before = BookBlock.Paragraph(BlockId("before"), listOf(InlineNode.Text("Earlier text")))
            val number = BookBlock.Heading(BlockId("number"), 1, listOf(InlineNode.Text("Chapter XI.")))
            val title = BookBlock.Heading(BlockId("title"), 1, listOf(InlineNode.Text("The Pacific")))
            val text = BookBlock.Paragraph(BlockId("text"), listOf(InlineNode.Text("Body")) + if (withNotes)
                listOf(InlineNode.Link(LinkTarget.Note(NoteId("n")), listOf(InlineNode.Text("*")))) else emptyList())
            val sub = BookBlock.Heading(BlockId("sub"), 2, listOf(InlineNode.Text("A subtitle")))
            val doc = BookDocument(BookId("book"), BookMetadata("Book"), listOf(before, number, title, text, sub),
                notes = if (withNotes) listOf(BookNote(NoteId("n"), listOf(BookBlock.Paragraph(BlockId("note"), listOf(InlineNode.Text("Note")))))) else emptyList(),
                chapters = listOf(BookChapter(listOf(number.id, title.id), 1, "XI", 11)))
            val pages = engine.paginate(doc, null, key).last().pages
            assertEquals(2, pages.size)
            assertEquals(number.id, pages[1].start.blockId)
            assertTrue(pages[1].blocks.any { it.blockId == text.id })
            assertTrue(pages[1].blocks.any { it.blockId == sub.id })
            assertEquals(number.id, pages[1].chapterId)
            assertEquals(doc.readingFlow().first { it.source.id == number.id }.globalOffset, pages[1].start.fallbackGlobalOffset)
        }
    }

    @Test fun `oversized heading advances with complete source ranges`() = runBlocking {
        val heading = BookBlock.Heading(BlockId("h"), 1, listOf(InlineNode.Text("Long ".repeat(600))))
        val body = BookBlock.Paragraph(BlockId("b"), listOf(InlineNode.Text("Body text")))
        val doc = BookDocument(BookId("book"), BookMetadata("Book"), listOf(heading, body),
            chapters = listOf(BookChapter(listOf(heading.id), 1)))
        val pages = engine.paginate(doc, null, key.copy(pageHeightPx = 100)).last().pages
        assertTrue(pages.size > 2)
        assertTrue(pages.all { it.blocks.isNotEmpty() })
        val fragments = pages.flatMap { it.blocks }.filter { it.blockId == heading.id }
        assertEquals(0, fragments.first().startCharOffset)
        assertEquals(heading.plainText().length, fragments.last().endCharOffsetExclusive)
        fragments.zipWithNext().forEach { (a, b) -> assertEquals(a.endCharOffsetExclusive, b.startCharOffset) }
        assertEquals(body.id, pages.last().blocks.last().blockId)
    }
}
