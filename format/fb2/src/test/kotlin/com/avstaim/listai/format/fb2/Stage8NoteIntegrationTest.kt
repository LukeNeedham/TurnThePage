package com.avstaim.listai.format.fb2

import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.InlineNode
import com.avstaim.listai.core.model.LinkTarget
import com.avstaim.listai.core.model.NoteId
import com.avstaim.listai.core.model.contentBlocks
import com.avstaim.listai.core.model.plainText
import com.avstaim.listai.core.model.readingBlocks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage8NoteIntegrationTest {
    @Test
    fun stage8FixtureKeepsRichNotesSeparateAndIndexesEveryExactSourceCall() = runBlocking {
        val document = requireNotNull(javaClass.getResourceAsStream("/fixtures/stage8-notes.fb2")).use {
            Fb2BookParser().parse(it, "stage8-notes.fb2")
        }
        val entries = document.noteIndex.entries
        assertEquals(listOf("n1", "n2", "n3", "n4"), entries.map { it.id.value })
        assertEquals(4, document.notes.size)
        assertEquals(listOf(1, 2, 3, 4), entries.map { it.ordinal })
        assertTrue(entries.all { it.note.plainText().isNotBlank() })
        assertTrue(document.noteIndex.byId.getValue(NoteId("n4")).references.isEmpty())
        val expectedPreviewStarts = listOf("Обсерватория", "Карта", "Название", "Это примечание")
        entries.zip(expectedPreviewStarts).forEach { (entry, start) ->
            assertTrue(entry.previewContent.plainText().startsWith(start))
            assertFalse(entry.previewContent.plainText().startsWith(requireNotNull(entry.note.title)))
            assertEquals(entry.note.title, entry.note.blocks.first().plainText())
            assertTrue(entry.note.blocks.first() is BookBlock.Heading)
            assertEquals(entry.note.blocks.drop(1), entry.note.contentBlocks())
        }

        val calls = entries.flatMap { it.references }.sortedBy { it.source.fallbackGlobalOffset }
        assertEquals(listOf("n1", "n2", "n3", "n1"), calls.map { it.noteId.value })
        assertEquals(listOf("[1]", "[2]", "[3]", "[1]"), calls.map { it.marker })
        val repeated = document.noteIndex.byId.getValue(NoteId("n1")).references
        val expectedOrigins = listOf("paragraph-0" to "Обсерватория", "paragraph-6" to "Повторная ссылка")
            .map { (sourceId, prefix) ->
                val paragraph = document.locationsBySourceId.getValue(sourceId)
                paragraph.copy(charOffset = prefix.length, fallbackGlobalOffset = paragraph.fallbackGlobalOffset + prefix.length)
            }
        assertEquals(expectedOrigins, repeated.map { it.source })
        assertNotEquals(repeated.first().source, repeated.last().source)

        // The shared main reading flow excludes every note block, including nested quote children.
        val flow = document.readingBlocks()
        val noteBlockIds = buildSet<BlockId> {
            fun visit(block: BookBlock) {
                add(block.id)
                if (block is BookBlock.Quote) block.blocks.forEach(::visit)
            }
            document.notes.forEach { it.blocks.forEach(::visit) }
        }
        assertTrue(flow.none { it.id in noteBlockIds })
        assertFalse(document.plainText().contains("Это примечание без вызова"))
        val sourceBlocks = flow.associateBy { it.id }
        calls.forEach { call ->
            val block = sourceBlocks.getValue(call.source.blockId)
            assertEquals(call.marker, block.plainText().substring(call.source.charOffset, call.endCharOffsetExclusive))
            val globalStart = flow.takeWhile { it.id != block.id }.sumOf { it.plainText().length + 1L }
            assertEquals(globalStart + call.source.charOffset, call.source.fallbackGlobalOffset)
            assertTrue((block as BookBlock.Paragraph).content.filterIsInstance<InlineNode.Link>()
                .any { it.target == LinkTarget.Note(call.noteId) })
        }

        val firstPreview = document.noteIndex.byId.getValue(NoteId("n1")).previewContent
        assertTrue(firstPreview.any { it is InlineNode.Emphasis })
        val secondPreview = document.noteIndex.byId.getValue(NoteId("n2")).previewContent
        assertTrue(secondPreview.any { it is InlineNode.Strong })
        assertTrue(secondPreview.any { it is InlineNode.Strikethrough })
        val longNote = document.notesById.getValue(NoteId("n3"))
        assertTrue(longNote.blocks.filterIsInstance<BookBlock.Quote>().single().plainText()
            .contains("Путь измеряется не только расстоянием."))
        val poem = longNote.blocks.filterIsInstance<BookBlock.Poem>().single()
        assertEquals(listOf("Белый лист хранит дорогу,", "Тихий берег ждёт у ног."),
            poem.stanzas.single().lines.map { it.plainText() })
        assertTrue(longNote.plainText().length > 4000)
    }
}
