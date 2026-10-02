package com.avstaim.listai.reader.pagination

import com.avstaim.listai.core.model.*
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PartPaginationTest {
    private val key = LayoutKey(380, 700, PageMode.SPREAD)
    private val engine = IncrementalPaginationEngine { block, layout ->
        val text = block.source.plainText()
        MeasurementMetrics((if (text.isEmpty()) listOf(0) else text.indices.step(24).toList()).mapIndexed { index, start ->
            MeasuredLine(start, minOf(start + 24, text.length), index * 24f, (index + 1) * 24f)
        }, layout.contentWidth.toFloat())
    }

    @Test fun `a native title carrying part metadata is not duplicated into the source flow`() = runBlocking {
        val heading = BookBlock.Heading(BlockId("native"), 1, listOf(InlineNode.Text("Book One")))
        val doc = BookDocument(BookId("native-part"), BookMetadata("Book One"), listOf(heading),
            titlePageBlockIds = listOf(heading.id), parts = listOf(BookPart(listOf(heading.id))))
        assertTrue(engine.paginate(doc, null, key).last().pages.isEmpty())
    }

    @Test fun `part and first chapter occupy rectos after any frontmatter count in both engines`() = runBlocking {
        for (notes in listOf(false, true)) for (prefix in 0..5) {
            val doc = document(notes)
            val pages = engine.paginate(doc, null, key.copy(leadingPageCount = prefix)).last().pages
            val divider = pages.indexOfFirst { it.kind == PageKind.PART_TITLE }
            val chapter = pages.indexOfFirst { it.blocks.firstOrNull()?.blockId == BlockId("chapter") }
            assertEquals(0, (divider + prefix) % 2)
            assertEquals(0, (chapter + prefix) % 2)
            assertEquals(PageKind.BLANK, pages[divider + 1].kind)
            assertEquals(listOf("part", "title", "epigraph", "author"), pages[divider].blocks.map { it.blockId.value })
            assertTrue(pages[divider].blocks.first().y > key.contentTop)
            assertTrue(pages[divider].blocks.last().y + pages[divider].blocks.last().height < key.contentTop + key.contentHeight)
            assertEquals(((prefix + 1)..(prefix + pages.size)).toList(), pages.map { it.displayNumber })
            assertEquals(1, pages[divider].displayNumber % 2)
            assertEquals(1, pages[chapter].displayNumber % 2)
            assertEquals(pages.indices.map { it.toLong() }, pages.map { it.id.value })
            assertSourceCoverage(doc, pages)
        }
    }

    @Test fun `single pages omit part alignment and blank reverse leaves in both engines`() = runBlocking {
        for (notes in listOf(false, true)) for (prefix in 0..5) {
            val doc = document(notes)
            val single = engine.paginate(doc, null, key.copy(pageMode = PageMode.SINGLE, leadingPageCount = prefix)).last().pages
            val spread = engine.paginate(doc, null, key.copy(leadingPageCount = prefix)).last().pages
            assertFalse("A phone must never stop on a synthetic blank leaf", single.any { it.kind == PageKind.BLANK })
            assertTrue("The same source still has physical blank leaves in spread", spread.any { it.kind == PageKind.BLANK })
            val divider = single.indexOfFirst { it.kind == PageKind.PART_TITLE }
            val chapter = single.indexOfFirst { it.blocks.firstOrNull()?.blockId == BlockId("chapter") }
            assertEquals("One forward turn opens the chapter", divider + 1, chapter)
            assertEquals(spread.filter { it.kind != PageKind.BLANK }.map { it.blocks }, single.map { it.blocks })
            assertEquals(((prefix + 1)..(prefix + single.size)).toList(), single.map { it.displayNumber })
            assertEquals(single.indices.map { it.toLong() }, single.map { it.id.value })
            assertSourceCoverage(doc, single)
        }
    }

    @Test fun `a long epigraph keeps every source character and its anchor through reflow`() = runBlocking {
        val doc = document(notes = true, longEpigraph = true)
        val target = doc.readingFlow().first { it.source.id.value == "epigraph" }.let {
            BookLocation(it.source.id, 720, it.globalOffset + 720)
        }
        for (height in listOf(240, 700, 1300)) for (mode in PageMode.entries) {
            val pages = engine.paginate(doc, target, key.copy(pageHeightPx = height, pageMode = mode, leadingPageCount = 3)).last().pages
            assertTrue(pages.any { it.kind == PageKind.PART_CONTINUATION })
            val restored = pages[pages.pageIndexContaining(target)]
            assertTrue(restored.blocks.any { it.blockId == target.blockId && target.charOffset in it.startCharOffset until it.endCharOffsetExclusive })
            assertNotEquals(PageKind.BLANK, restored.kind)
            assertSourceCoverage(doc, pages)
            if (mode == PageMode.SPREAD)
                assertEquals(0, (pages.indexOfFirst { it.blocks.firstOrNull()?.blockId == BlockId("chapter") } + 3) % 2)
            else assertTrue(pages.none { it.kind == PageKind.BLANK })
        }
    }

    @Test fun `blank leaves never steal source links and an introduction stays after the divider`() = runBlocking {
        val doc = document(notes = false, introduction = true)
        val pages = engine.paginate(doc, null, key.copy(leadingPageCount = 1)).last().pages
        for (block in doc.readingFlow()) for (offset in listOf(0, block.source.plainText().length)) {
            val target = BookLocation(block.source.id, offset, block.globalOffset + offset)
            assertNotEquals(PageKind.BLANK, pages[pages.pageIndexContaining(target)].kind)
        }
        val divider = pages.indexOfFirst { it.kind == PageKind.PART_TITLE }
        val introduction = pages.indexOfFirst { it.blocks.any { fragment -> fragment.blockId.value == "introduction" } }
        val chapter = pages.indexOfFirst { it.blocks.firstOrNull()?.blockId?.value == "chapter" }
        assertTrue(introduction > divider + 1)
        assertTrue(chapter > introduction)
        assertEquals(0, (chapter + 1) % 2)
        assertSourceCoverage(doc, pages)
    }

    @Test fun `part composition reserves the actual footnote area beneath an epigraph`() = runBlocking {
        val original = document(notes = true)
        val doc = original.copy(blocks = original.blocks.map { block ->
            if (block is BookBlock.Quote) block.copy(blocks = block.blocks.map { leaf ->
                if (leaf is BookBlock.Paragraph && leaf.id.value == "epigraph") leaf.copy(content = leaf.content +
                    InlineNode.Link(LinkTarget.Note(NoteId("n")), listOf(InlineNode.Text("1")))) else leaf
            }) else block
        })
        val pages = engine.paginate(doc, null, key).last().pages
        val divider = pages.first { it.kind == PageKind.PART_TITLE }
        assertFalse(divider.footnotes.isEmpty())
        assertTrue(divider.blocks.maxOf { it.y + it.height } < divider.footnotes.minOf { it.y })
        assertSourceCoverage(doc, pages)
    }

    @Test fun `incremental publications preserve the entire already published physical prefix`() = runBlocking {
        val doc = document(notes = true, longEpigraph = true)
        for (mode in PageMode.entries) {
            val snapshots = engine.paginate(doc, null, key.copy(pageMode = mode, pageHeightPx = 200, leadingPageCount = 3)).toList()
            assertTrue(snapshots.size > 1)
            snapshots.zipWithNext().forEach { (before, after) ->
                assertEquals(before.pages, after.pages.take(before.pages.size))
            }
            if (mode == PageMode.SINGLE) assertTrue(snapshots.all { snapshot -> snapshot.pages.none { it.kind == PageKind.BLANK } })
        }
    }

    private fun assertSourceCoverage(doc: BookDocument, pages: List<PageLayout>) {
        for (source in doc.readingFlow()) {
            val fragments = pages.flatMap { it.blocks }.filter { it.blockId == source.source.id }
            assertEquals(source.source.plainText(), fragments.joinToString("") {
                source.source.plainText().substring(it.startCharOffset, it.endCharOffsetExclusive)
            })
        }
        assertTrue(pages.filter { it.kind == PageKind.BLANK }.all { it.blocks.isEmpty() && it.start == it.endExclusive })
    }

    private fun document(notes: Boolean, longEpigraph: Boolean = false, introduction: Boolean = false): BookDocument {
        fun paragraph(id: String, text: String, role: ParagraphRole = ParagraphRole.BODY) =
            BookBlock.Paragraph(BlockId(id), listOf(InlineNode.Text(text)), role)
        fun heading(id: String, text: String) = BookBlock.Heading(BlockId(id), 1, listOf(InlineNode.Text(text)))
        val chapter = heading("chapter", "Глава четвёртая")
        val body = paragraph("body", "Текст продолжается. ".repeat(12)).let { block ->
            if (notes) block.copy(content = block.content + InlineNode.Link(LinkTarget.Note(NoteId("n")), listOf(InlineNode.Text("1")))) else block
        }
        return BookDocument(BookId("parts"), BookMetadata("Книга", language = "ru"), listOf(
            paragraph("previous", "Конец предыдущей части."),
            heading("part", "Часть вторая"), heading("title", "Карта прилива"),
            BookBlock.Quote(BlockId("quote"), listOf(
                paragraph("epigraph", "На сухой карте глубины неподвижны. На живой они дышат. ".repeat(if (longEpigraph) 160 else 1)),
                paragraph("author", "Семён Ракитин, полевой дневник", ParagraphRole.TEXT_AUTHOR),
            ), QuoteRole.EPIGRAPH),
        ) + (if (introduction) listOf(paragraph("introduction", "Введение в часть. ".repeat(20))) else emptyList()) + listOf(chapter, body),
            notes = if (notes) listOf(BookNote(NoteId("n"), listOf(paragraph("note", "Сноска.")))) else emptyList(),
            chapters = listOf(BookChapter(listOf(chapter.id), 4, "четвёртая", chapter.plainText().length)),
            parts = listOf(BookPart(listOf(BlockId("part"), BlockId("title")), listOf(BlockId("epigraph"), BlockId("author")))))
    }
}
