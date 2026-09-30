package com.avstaim.listai.reader.pagination

import com.avstaim.listai.core.model.*
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TitlePaginationTest {
    private val key = LayoutKey(600, 600, PageMode.SPREAD, pageWidthPx = 300)
    private val engine = IncrementalPaginationEngine { block, layout ->
        val text = block.source.plainText()
        MeasurementMetrics((if (text.isEmpty()) listOf(0) else text.indices.step(24).toList()).mapIndexed { index, start ->
            MeasuredLine(start, minOf(start + 24, text.length), index * 24f, (index + 1) * 24f)
        }, layout.contentWidth.toFloat())
    }

    @Test fun `title starts recto with exactly the necessary padding and single never gets padding`() = runBlocking {
        for (notes in listOf(false, true)) for (descriptionPages in 0..5) for (prefix in 0..3) {
            val document = document(descriptionPages, notes)
            val single = engine.paginate(document, null, key.copy(pageMode = PageMode.SINGLE, leadingPageCount = prefix)).last()
            val spread = engine.paginate(document, null, key.copy(leadingPageCount = prefix)).last()
            val needsBlank = (descriptionPages + prefix) % 2
            assertEquals(descriptionPages + 1, single.pages.size)
            assertTrue(single.pages.none { it.kind == PageKind.BLANK })
            assertEquals(needsBlank, spread.pages.count { it.kind == PageKind.BLANK })
            val title = spread.pages.indexOfFirst { it.blocks.any { block -> block.blockId == BlockId("title") } }
            assertEquals(descriptionPages + needsBlank, title)
            assertEquals(0, (prefix + title) % 2)
            assertEquals(single.pages.map { it.blocks }, spread.pages.filter { it.kind != PageKind.BLANK }.map { it.blocks })
            assertEquals(spread.pages.indices.map { it.toLong() }, spread.pages.map { it.id.value })
            assertEquals((1..spread.pages.size).toList(), spread.pages.map { it.displayNumber })
            if (needsBlank == 0) assertEquals(single.pages, spread.pages)
            else {
                val blank = spread.pages[title - 1]
                assertTrue(blank.blocks.isEmpty())
                assertEquals(blank.start, blank.endExclusive)
                assertEquals(spread.pages[title].start, blank.start)
                assertEquals(title, spread.pages.pageIndexContaining(blank.start))
            }
        }
    }

    @Test fun `long title keeps every character stable prefixes and exact anchors in both engines`() = runBlocking {
        for (notes in listOf(false, true)) for (mode in PageMode.entries) {
            val document = document(13, notes, longTitle = true)
            val snapshots = engine.paginate(document, null, key.copy(pageMode = mode)).toList()
            assertTrue(snapshots.size > 2)
            snapshots.zipWithNext().forEach { (before, after) ->
                assertEquals(before.pages, after.pages.take(before.pages.size))
            }
            val pages = snapshots.last().pages
            assertEquals(if (mode == PageMode.SPREAD) 1 else 0, pages.count { it.kind == PageKind.BLANK })
            for (block in document.readingFlow()) {
                val fragments = pages.flatMap { it.blocks }.filter { it.blockId == block.source.id }
                assertEquals(0, fragments.first().startCharOffset)
                assertEquals(block.source.plainText().length, fragments.last().endCharOffsetExclusive)
                fragments.zipWithNext().forEach { (a, b) -> assertEquals(a.endCharOffsetExclusive, b.startCharOffset) }
            }
            val title = document.readingFlow().first { it.source.id == BlockId("title") }
            for (offset in listOf(0, 4321, title.source.plainText().length)) {
                val location = BookLocation(title.source.id, offset, title.globalOffset + offset)
                val anchored = engine.paginate(document, location, key.copy(pageMode = mode)).last()
                assertEquals(pages.pageIndexContaining(location), anchored.anchorPageIndex)
                assertNotEquals(PageKind.BLANK, anchored.pages[anchored.anchorPageIndex].kind)
            }
        }
    }

    private fun document(descriptionPages: Int, notes: Boolean, longTitle: Boolean = false): BookDocument {
        val paragraphs = (0 until descriptionPages).map { BookBlock.Paragraph(BlockId("description:$it"), listOf(InlineNode.Text("Description $it"))) }
        val noteId = NoteId("n")
        val title = BookBlock.Heading(BlockId("title"), 1, listOf(InlineNode.Text(if (longTitle) "Book 😀 ".repeat(1000) else "Book 😀")) +
            if (notes) listOf(InlineNode.Link(LinkTarget.Note(noteId), listOf(InlineNode.Text("1")))) else emptyList())
        val author = BookBlock.Paragraph(BlockId("author"), listOf(InlineNode.Text("Author")))
        return BookDocument(BookId("frontmatter"), BookMetadata("Book"), paragraphs + title + author,
            titlePageBlockIds = listOf(title.id, author.id), isFrontmatter = true,
            pageBreakBefore = (paragraphs.map { it.id } + title.id).toSet(),
            notes = if (notes) listOf(BookNote(noteId, listOf(BookBlock.Paragraph(BlockId("note"), listOf(InlineNode.Text("Note")))))) else emptyList())
    }
}
