package com.avstaim.listai.core.model

import org.junit.Assert.*
import org.junit.Test

class BookPartTest {
    @Test fun `numbered part labels keep original words and exclude narrative headings`() {
        val labels = listOf("Часть вторая", "Part IV. The sea", "Книга третья", "Том первый", "Part Twenty One")
        labels.forEach { label ->
            val heading = heading("part", label)
            val part = partOpenings(listOf(heading), listOf(group(heading))).single()
            assertEquals(listOf(heading.id), part.headingBlockIds)
            assertEquals(label, heading.plainText())
            assertTrue(chapterOpenings(listOf(heading), listOf(group(heading))).isEmpty())
        }
        listOf("Часть речи", "Part of the journey", "Book review", "A partial result", "Глава первая").forEach { label ->
            val heading = heading("ordinary", label)
            assertTrue(label, partOpenings(listOf(heading), listOf(group(heading))).isEmpty())
        }
    }

    @Test fun `explicit named part and grouped source title do not become chapters`() {
        val label = heading("label", "Лето")
        val name = heading("name", "Карта прилива", 2)
        val chapter = heading("chapter", "Глава четвёртая", 2)
        val groups = listOf(BookHeadingGroup(listOf(label.id, name.id), 1, BookHeadingRole.PART), group(chapter))
        val blocks = listOf(label, name, chapter)
        assertEquals(listOf(label.id, name.id), partOpenings(blocks, groups).single().headingBlockIds)
        assertEquals(chapter.id, chapterOpenings(blocks, groups).single().startBlockId)
    }

    @Test fun `only a directly following explicit epigraph belongs to the part`() {
        val part = heading("part", "Часть вторая")
        val verse = paragraph("verse", "Вода 😀")
        val author = BookBlock.Paragraph(BlockId("author"), listOf(InlineNode.Text("Дневник")), ParagraphRole.TEXT_AUTHOR)
        val epigraph = BookBlock.Quote(BlockId("epigraph"), listOf(verse, author), QuoteRole.EPIGRAPH)
        val text = paragraph("text", "Начало текста")
        assertEquals(listOf(verse.id, author.id), partOpenings(listOf(part, epigraph, text), listOf(group(part))).single().epigraphBlockIds)
        assertTrue(partOpenings(listOf(part, text, epigraph), listOf(group(part))).single().epigraphBlockIds.isEmpty())
        assertTrue(partOpenings(listOf(part, epigraph.copy(role = QuoteRole.CITE)), listOf(group(part))).single().epigraphBlockIds.isEmpty())
        assertEquals(listOf(verse.id), partOpenings(listOf(part, verse, text), listOf(group(part)), setOf(verse.id)).single().epigraphBlockIds)
        val space = BookBlock.Spacer(BlockId("space"))
        assertEquals(listOf(space.id, verse.id, author.id), partOpenings(listOf(part, space, epigraph, text), listOf(group(part))).single().epigraphBlockIds)
        assertTrue(partOpenings(listOf(part, space, text, epigraph), listOf(group(part))).single().epigraphBlockIds.isEmpty())
    }

    @Test fun `part metadata cannot duplicate or invent source IDs`() {
        val heading = heading("part", "Часть первая")
        assertThrows(IllegalArgumentException::class.java) { BookPart(listOf(heading.id), listOf(heading.id)) }
        assertThrows(IllegalArgumentException::class.java) {
            BookDocument(BookId("book"), BookMetadata("Book"), listOf(heading), parts = listOf(BookPart(listOf(BlockId("missing")))))
        }
    }

    private fun heading(id: String, text: String, level: Int = 1) = BookBlock.Heading(BlockId(id), level, listOf(InlineNode.Text(text)))
    private fun paragraph(id: String, text: String) = BookBlock.Paragraph(BlockId(id), listOf(InlineNode.Text(text)))
    private fun group(heading: BookBlock.Heading) = BookHeadingGroup(listOf(heading.id), heading.level)
}
