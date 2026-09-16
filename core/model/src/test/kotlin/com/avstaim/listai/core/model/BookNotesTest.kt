package com.avstaim.listai.core.model

import org.junit.Assert.*
import org.junit.Test

class BookNotesTest {
    private val id = NoteId("n")
    private fun call(marker: String = "[1]") = InlineNode.Link(LinkTarget.Note(id), listOf(InlineNode.Text(marker)))
    private fun paragraph(id: String, vararg content: InlineNode) = BookBlock.Paragraph(BlockId(id), content.toList())
    private fun document(blocks: List<BookBlock>, notes: List<BookNote>) = BookDocument(
        BookId("notes"), BookMetadata("Notes"), blocks, notes = notes)

    @Test fun referenceOffsetsIncludeSurrogatesNestedMarksPoemNewlinesAndImageAlternatives() {
        val first = paragraph("p", InlineNode.Text("😀"), InlineNode.Emphasis(listOf(call())),
            InlineNode.Image(ImageId("inline"), "alt"), call("†"))
        val stanza = BookPoemStanza(listOf(listOf(InlineNode.Text("verse")), listOf(call("*"))))
        val poem = BookBlock.Poem(BlockId("poem"), listOf(stanza),
            title = listOf(listOf(InlineNode.Text("Title"))))
        val doc = document(listOf(BookBlock.Quote(BlockId("q"), listOf(first)), poem),
            listOf(BookNote(id, listOf(paragraph("nbody", InlineNode.Text("Note"))))))
        val refs = doc.noteIndex.byId.getValue(id).references
        assertEquals(listOf(2, 8, 12), refs.map { it.source.charOffset })
        assertEquals(listOf(2L, 8L, 22L), refs.map { it.source.fallbackGlobalOffset })
        assertEquals(listOf("[1]", "†", "*"), refs.map { it.marker })
        assertEquals(listOf(5, 9, 13), refs.map { it.endCharOffsetExclusive })
        assertEquals(poem.plainText(), poem.inlineContent().plainText())
        assertSame(doc.noteIndex, doc.noteIndex)
        assertEquals(listOf(refs.first()), doc.noteIndex.referencesIn(first.id, 3, 5))
        assertEquals(listOf(refs[1]), doc.noteIndex.referencesIn(first.id, 5, 9))
        assertTrue(doc.noteIndex.referencesIn(first.id, 5, 8).isEmpty())
    }

    @Test fun emptyAndUnknownNotesHaveNoCallOrOrdinalButUnreferencedContentIsRetained() {
        val doc = document(listOf(paragraph("p", call(), InlineNode.Link(LinkTarget.Note(NoteId("missing")),
            listOf(InlineNode.Text("?"))))), listOf(
            BookNote(NoteId("empty"), listOf(BookBlock.Spacer(BlockId("space")))),
            BookNote(id, listOf(paragraph("body", InlineNode.Text("Valid")))),
            BookNote(NoteId("unlinked"), listOf(paragraph("u", InlineNode.Text("Unlinked")))),
            BookNote(NoteId("whitespace"), listOf(paragraph("ws", InlineNode.Text(" \n"))), title = "Title only"),
        ))
        assertEquals(listOf(id, NoteId("unlinked")), doc.noteIndex.entries.map { it.id })
        assertEquals(listOf(1, 2), doc.noteIndex.entries.map { it.ordinal })
        assertEquals(1, doc.noteIndex.referencesByBlock.getValue(BlockId("p")).size)
        assertTrue(doc.noteIndex.entries.last().references.isEmpty())
        assertEquals("[1]?", doc.blocks.single().plainText())
    }

    @Test fun imageOnlyNoteIsValidWithoutLoadingDeferredPayload() {
        var loads = 0
        val image = BookImage(ImageId("image"), "image/png", BookBinaryContent { loads++; null })
        val doc = document(listOf(paragraph("p", call())),
            listOf(BookNote(id, listOf(BookBlock.Image(BlockId("noteimage"), image.id)))))
            .copy(images = listOf(image))
        assertEquals(1, doc.noteIndex.entries.size)
        assertTrue(doc.noteIndex.entries.single().previewContent.isEmpty())
        assertEquals(0, loads)
    }

    @Test fun previewAndSlicePreserveRichMarksWithoutDuplicatingNoteIntoMainText() {
        val content = listOf(InlineNode.Strong(listOf(InlineNode.Text("bold"))),
            InlineNode.Emphasis(listOf(InlineNode.Strikethrough(listOf(InlineNode.Text(" tail"))))))
        val quote = BookBlock.Quote(BlockId("quote"), listOf(BookBlock.Paragraph(BlockId("note"), content)))
        val doc = document(listOf(paragraph("p", call())), listOf(BookNote(id, listOf(quote))))
        val preview = doc.noteIndex.entries.single().previewContent
        assertEquals(content, preview)
        val expected = listOf(InlineNode.Strong(listOf(InlineNode.Text("ld"))),
            InlineNode.Emphasis(listOf(InlineNode.Strikethrough(listOf(InlineNode.Text(" ta"))))))
        assertEquals(expected, preview.sliceInlineContent(2, 7))
        assertEquals("[1]", doc.plainText())
        assertEquals("bold tail", doc.notes.single().plainText())
    }

    @Test fun matchingLeadingTitleIsExcludedOnlyFromContentProjection() {
        val heading = BookBlock.Heading(BlockId("title"), 2,
            listOf(InlineNode.Strong(listOf(InlineNode.Text("Editorial\u00a0note")))))
        val body = paragraph("body", InlineNode.Emphasis(listOf(InlineNode.Text("Actual note body"))))
        val note = BookNote(id, listOf(heading, body), title = "  Editorial\n  note  ")
        val doc = document(listOf(paragraph("p", call())), listOf(note))

        assertEquals(listOf(body), note.contentBlocks())
        assertEquals(body.content, doc.noteIndex.entries.single().previewContent)
        assertEquals(listOf(heading, body), note.blocks)
        assertSame(heading, note.blocks.first())
        assertEquals("Editorial\u00a0note\nActual note body", note.plainText())
        assertSame(note.blocks, note.copy(title = "A different title").contentBlocks())
        assertSame(note.blocks, note.copy(title = null).contentBlocks())
        val nonLeading = note.copy(blocks = listOf(body, heading))
        assertEquals(listOf(body, heading), nonLeading.contentBlocks())
    }

    @Test fun numericTitleWithoutBodyDoesNotCreateAValidNoteOrSourceCall() {
        val heading = BookBlock.Heading(BlockId("number"), 2, listOf(InlineNode.Text(" 1\n")))
        val note = BookNote(id, listOf(heading, BookBlock.Spacer(BlockId("space"))), title = "1")
        val doc = document(listOf(paragraph("p", call())), listOf(note))

        assertTrue(doc.noteIndex.entries.isEmpty())
        assertTrue(doc.noteIndex.referencesByBlock.isEmpty())
        assertEquals(listOf(heading, BookBlock.Spacer(BlockId("space"))), note.blocks)
        assertEquals("[1]", doc.plainText())
    }
}
