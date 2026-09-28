package com.avstaim.listai.core.model

import org.junit.Assert.*
import org.junit.Test

class BookChapterTest {
    @Test fun `numeric and Roman-looking narrative titles remain intact`() {
        listOf("1984: A journey", "I remember", "V значит победа").forEach { text ->
            val heading = BookBlock.Heading(BlockId("h"), 1, listOf(InlineNode.Text(text)))
            val chapter = chapterOpenings(listOf(heading), listOf(BookHeadingGroup(listOf(heading.id), 1))).single()
            assertNull(chapter.number)
            assertEquals(0, chapter.numberPrefixLength)
        }
    }
    @Test fun `parts and subheadings do not consume chapter numbers`() {
        val headings = listOf("Part One", "Arrival", "A nested heading", "Chapter XI. The Pacific", "Epilogue")
            .mapIndexed { index, text -> BookBlock.Heading(BlockId("h$index"), if (index == 2) 2 else 1, listOf(InlineNode.Text(text))) }
        val chapters = chapterOpenings(headings, headings.map { BookHeadingGroup(listOf(it.id), it.level) })
        assertEquals(listOf("h1", "h3"), chapters.map { it.startBlockId.value })
        assertEquals(listOf(1, 2), chapters.map { it.ordinal })
        assertNull(chapters.first().number)
        assertEquals("XI", chapters.last().number)
        assertEquals("The Pacific", headings[3].plainText().drop(chapters.last().numberPrefixLength))
    }
    @Test fun `word numbers and grouped titles preserve the original source text`() {
        val heading = BookBlock.Heading(BlockId("h"), 1, listOf(InlineNode.Text("Глава первая. Начало")))
        val chapter = chapterOpenings(listOf(heading), listOf(BookHeadingGroup(listOf(heading.id), 1))).single()
        assertEquals("первая", chapter.number)
        assertEquals("Начало", heading.plainText().drop(chapter.numberPrefixLength))
        assertEquals("Глава первая. Начало", heading.plainText())
    }
}
