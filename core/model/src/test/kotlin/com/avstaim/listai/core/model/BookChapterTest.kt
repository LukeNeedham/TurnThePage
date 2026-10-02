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
        assertEquals(listOf("h1", "h3", "h4"), chapters.map { it.startBlockId.value })
        assertEquals(listOf(1, 2, null), chapters.map { it.ordinal })
        assertNull(chapters.first().number)
        assertEquals("XI", chapters[1].number)
        assertEquals("The Pacific", headings[3].plainText().drop(chapters[1].numberPrefixLength))
        assertEquals(BookChapterKind.EPILOGUE, chapters.last().kind)
    }
    @Test fun `word numbers and grouped titles preserve the original source text`() {
        val heading = BookBlock.Heading(BlockId("h"), 1, listOf(InlineNode.Text("Глава первая. Начало")))
        val chapter = chapterOpenings(listOf(heading), listOf(BookHeadingGroup(listOf(heading.id), 1))).single()
        assertEquals("первая", chapter.number)
        assertEquals("Начало", heading.plainText().drop(chapter.numberPrefixLength))
        assertEquals("Глава первая. Начало", heading.plainText())
    }

    @Test fun `top level epilogue preserves chapter level inside parts and consumes no ordinal`() {
        val headings = listOf(
            heading("part", "Часть первая", 1), heading("one", "Прибытие", 2),
            heading("epilogue", "Эпилог", 1), heading("title", "Дом для света", 1),
            heading("two", "Отъезд", 2),
        )
        val groups = listOf(group(headings[0]), group(headings[1]),
            BookHeadingGroup(listOf(headings[2].id, headings[3].id), 1), group(headings[4]))
        val openings = chapterOpenings(headings, groups)
        assertEquals(listOf("one", "epilogue", "two"), openings.map { it.startBlockId.value })
        assertEquals(listOf(1, null, 2), openings.map { it.ordinal })
        assertEquals(listOf(headings[2].id, headings[3].id), openings[1].headingBlockIds)
        assertNull(openings[1].number)
        assertEquals(0, openings[1].numberPrefixLength)
    }

    @Test fun `only exact principal epilogue label or explicit semantics creates an opening`() {
        val headings = listOf(
            heading("chapter", "Arrival", 1), heading("nested", "Epilogue", 2),
            heading("phrase", "Эпилог путешествия", 1), heading("explicit", "Ten years later", 2),
            heading("prologue", "Пролог", 1), heading("other", "Эпилог", 1),
        )
        val groups = headings.map(::group).mapIndexed { index, group -> group.copy(role = when (index) {
            3 -> BookHeadingRole.EPILOGUE
            5 -> BookHeadingRole.OTHER
            else -> group.role
        }) }
        val openings = chapterOpenings(headings, groups)
        assertEquals(listOf("chapter", "explicit"), openings.map { it.startBlockId.value })
        assertEquals(BookChapterKind.EPILOGUE, openings.last().kind)
        listOf("ЭПИЛОГ", " Epilogue. ", "Эпилог:").forEach { assertTrue(isStandaloneEpilogueLabel(it)) }
        listOf("Epilogue of a journey", "An epilogue", "Эпилог 1").forEach { assertFalse(isStandaloneEpilogueLabel(it)) }
    }

    @Test fun `a standalone unnumbered epilogue needs no ordinary chapter`() {
        val heading = heading("epilogue", "Эпилог", 1)
        val epilogue = chapterOpenings(listOf(heading), listOf(group(heading))).single()
        assertEquals(BookChapterKind.EPILOGUE, epilogue.kind)
        assertNull(epilogue.ordinal)
    }

    private fun heading(id: String, text: String, level: Int) =
        BookBlock.Heading(BlockId(id), level, listOf(InlineNode.Text(text)))
    private fun group(heading: BookBlock.Heading) = BookHeadingGroup(listOf(heading.id), heading.level)
}
