package com.avstaim.listai.reader.pagination

import com.avstaim.listai.core.model.*
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EpiloguePaginationTest {
    private val engine = IncrementalPaginationEngine { block, key ->
        val text = block.source.plainText()
        MeasurementMetrics((text.indices.step(30).toList().ifEmpty { listOf(0) }).mapIndexed { index, start ->
            MeasuredLine(start, minOf(start + 30, text.length), index * 20f, (index + 1) * 20f)
        }, key.contentWidth.toFloat())
    }
    private val key = LayoutKey(600, 600, PageMode.SPREAD)

    @Test fun `epilogue begins a text leaf without a synthetic number or blank reverse in both engines`() = runBlocking {
        for (notes in listOf(false, true)) {
            val doc = document(withNotes = notes)
            val pages = engine.paginate(doc, null, key).last().pages
            assertEquals(2, pages.size)
            val page = pages[1]
            assertEquals(BlockId("epilogue"), page.start.blockId)
            assertEquals(BlockId("epilogue"), page.chapterId)
            assertEquals(listOf("epilogue", "title", "body"), page.blocks.map { it.blockId.value })
            assertTrue(pages.all { it.kind == PageKind.TEXT })
            assertEquals(2, page.displayNumber)
            if (notes) assertEquals(NoteId("n"), page.footnotes.single().noteId)
            assertSource(doc, pages)
        }
    }

    @Test fun `epilogue of a book with parts starts recto after every frontmatter parity`() = runBlocking {
        for (notes in listOf(false, true)) for (prefix in 0..4) {
            val doc = document(withNotes = notes, withPart = true)
            val snapshot = engine.paginate(doc, null, key.copy(leadingPageCount = prefix)).last()
            val index = snapshot.pages.indexOfFirst { it.start.blockId == BlockId("epilogue") && it.kind != PageKind.BLANK }
            assertEquals(0, (index + prefix) % 2)
            assertEquals(PageKind.TEXT, snapshot.pages[index].kind)
            assertTrue(snapshot.pages[index].blocks.any { it.blockId == BlockId("body") })
            assertEquals("No unused verso is added at the end of an epilogue", index, snapshot.pages.lastIndex)
            assertEquals(1, snapshot.pages[index].displayNumber % 2)
            assertSource(doc, snapshot.pages)
        }
    }

    @Test fun `long epilogue keeps immutable prefixes and every source offset through reflow`() = runBlocking {
        val doc = document(withPart = true, longBody = true)
        val source = doc.readingFlow().first { it.source.id == BlockId("body") }
        val anchor = BookLocation(source.source.id, 120, source.globalOffset + 120)
        val snapshots = engine.paginate(doc, anchor, key).toList()
        assertTrue(snapshots.size > 1)
        snapshots.zipWithNext().forEach { (a, b) -> assertEquals(a.pages, b.pages.take(a.pages.size)) }
        for (layout in listOf(key, key.copy(pageMode = PageMode.SINGLE, pageHeightPx = 430))) {
            val result = engine.paginate(doc, anchor, layout).last()
            assertTrue(result.pages[result.anchorPageIndex].blocks.any {
                it.blockId == anchor.blockId && it.startCharOffset <= anchor.charOffset && it.endCharOffsetExclusive > anchor.charOffset
            })
            val firstEpilogue = result.pages.indexOfFirst { it.kind != PageKind.BLANK && it.start.blockId == BlockId("epilogue") }
            assertEquals(PageKind.TEXT, result.pages[firstEpilogue + 1].kind)
            assertSource(doc, result.pages)
        }
    }

    @Test fun `single mode omits recto padding before the epilogue of a book with parts`() = runBlocking {
        for (notes in listOf(false, true)) for (prefix in 0..4) {
            val doc = document(withNotes = notes, withPart = true)
            val pages = engine.paginate(doc, null, key.copy(pageMode = PageMode.SINGLE, leadingPageCount = prefix)).last().pages
            assertTrue(pages.none { it.kind == PageKind.BLANK })
            assertEquals(listOf("part", "chapter", "epilogue"), pages.map { it.start.blockId.value })
            assertEquals(((prefix + 1)..(prefix + pages.size)).toList(), pages.map { it.displayNumber })
            assertSource(doc, pages)
        }
    }

    private fun assertSource(doc: BookDocument, pages: List<PageLayout>) {
        for (block in doc.readingFlow()) {
            val fragments = pages.flatMap { it.blocks }.filter { it.blockId == block.source.id }
            assertEquals(0, fragments.first().startCharOffset)
            assertEquals(block.source.plainText().length, fragments.last().endCharOffsetExclusive)
            fragments.zipWithNext().forEach { (a, b) -> assertEquals(a.endCharOffsetExclusive, b.startCharOffset) }
        }
    }

    private fun document(withNotes: Boolean = false, withPart: Boolean = false, longBody: Boolean = false): BookDocument {
        fun heading(id: String, text: String) = BookBlock.Heading(BlockId(id), 1, listOf(InlineNode.Text(text)))
        fun text(id: String, value: String) = BookBlock.Paragraph(BlockId(id), listOf(InlineNode.Text(value)))
        val part = heading("part", "Часть вторая")
        val chapter = heading("chapter", "Глава шестая")
        val epilogue = heading("epilogue", "Эпилог")
        val title = heading("title", "Дом для света 😀")
        val body = BookBlock.Paragraph(BlockId("body"), listOf(InlineNode.Text(
            "Весной Лида вернулась на Тонкий. ".repeat(if (longBody) 800 else 2))) +
            if (withNotes) listOf(InlineNode.Link(LinkTarget.Note(NoteId("n")), listOf(InlineNode.Text("1")))) else emptyList())
        return BookDocument(BookId("epilogue-test"), BookMetadata("Книга"),
            (if (withPart) listOf(part) else emptyList()) + listOf(chapter, text("previous", "Конец предыдущей главы."), epilogue, title, body),
            notes = if (withNotes) listOf(BookNote(NoteId("n"), listOf(text("note", "Примечание.")))) else emptyList(),
            parts = if (withPart) listOf(BookPart(listOf(part.id))) else emptyList(),
            chapters = listOf(BookChapter(listOf(chapter.id), 6, "шестая", chapter.plainText().length),
                BookChapter(listOf(epilogue.id, title.id), null, kind = BookChapterKind.EPILOGUE)))
    }
}
