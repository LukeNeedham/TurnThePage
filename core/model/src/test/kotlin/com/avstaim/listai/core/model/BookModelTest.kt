package com.avstaim.listai.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BookModelTest {
    @Test
    fun `book location rejects negative offsets`() {
        assertThrows(IllegalArgumentException::class.java) {
            BookLocation(BlockId("chapter-1"), charOffset = -1, fallbackGlobalOffset = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BookLocation(BlockId("chapter-1"), charOffset = 0, fallbackGlobalOffset = -1)
        }
    }

    @Test
    fun `legacy constructors keep neutral defaults`() {
        val paragraph = BookBlock.Paragraph(
            id = BlockId("paragraph"),
            content = listOf(InlineNode.Text("Text")),
        )
        val document = BookDocument(
            id = BookId("book"),
            metadata = BookMetadata(title = "Book"),
            blocks = listOf(paragraph),
        )

        assertEquals(ParagraphRole.BODY, paragraph.role)
        assertTrue(document.tableOfContents.isEmpty())
        assertTrue(document.notes.isEmpty())
        assertTrue(document.images.isEmpty())
        assertTrue(document.locationsBySourceId.isEmpty())
    }

    @Test
    fun `plain text keeps every inline style and typed link label`() {
        val nodes = listOf(
            InlineNode.Text("Turn "),
            InlineNode.Emphasis(
                listOf(
                    InlineNode.Text("the "),
                    InlineNode.Strong(listOf(InlineNode.Text("Page"))),
                ),
            ),
            InlineNode.Strikethrough(listOf(InlineNode.Text(" draft"))),
            InlineNode.Link(
                target = LinkTarget.External("https://example.com"),
                children = listOf(InlineNode.Text(" link")),
            ),
            InlineNode.Image(ImageId("inline-image"), alternativeText = " illustration"),
        )

        assertEquals("Turn the Page draft link illustration", nodes.plainText())
        assertEquals(" link", nodes[3].plainText())
    }

    @Test
    fun `quote poem note toc and document expose format-neutral plain text`() {
        val quote = BookBlock.Quote(
            id = BlockId("quote"),
            role = QuoteRole.EPIGRAPH,
            blocks = listOf(
                paragraph("quote-line", "A quotation"),
                paragraph("quote-author", "The Author", ParagraphRole.TEXT_AUTHOR),
            ),
        )
        val stanza = BookPoemStanza(
            lines = listOf(
                listOf(InlineNode.Text("First line")),
                listOf(InlineNode.Emphasis(listOf(InlineNode.Text("Second line")))),
            ),
        )
        val poem = BookBlock.Poem(
            id = BlockId("poem"),
            stanzas = listOf(stanza),
            title = listOf(listOf(InlineNode.Text("A Poem"))),
            textAuthors = listOf(listOf(InlineNode.Text("The Poet"))),
        )
        val note = BookNote(NoteId("note-1"), listOf(paragraph("note-body", "A note")))
        val toc = BookTocEntry(
            title = "Part One",
            location = location("quote"),
            children = listOf(BookTocEntry("Poem", location("poem"))),
        )
        val document = BookDocument(
            id = BookId("book"),
            metadata = BookMetadata(title = "Book"),
            blocks = listOf(quote, poem),
            tableOfContents = listOf(toc),
            notes = listOf(note),
        )

        assertEquals("A quotation\nThe Author", quote.plainText())
        assertEquals("First line\nSecond line", stanza.plainText())
        assertEquals("A Poem\nFirst line\nSecond line\nThe Poet", poem.plainText())
        assertEquals("A note", note.plainText())
        assertEquals("Part One\nPoem", toc.plainText())
        assertEquals(
            "A quotation\nThe Author\nA Poem\nFirst line\nSecond line\nThe Poet",
            document.plainText(),
        )
    }

    @Test
    fun `document carries hierarchy notes source anchors and lazy cached image`() {
        var decodeCount = 0
        val decoded by lazy {
            decodeCount += 1
            byteArrayOf(1, 2, 3)
        }
        val main = paragraph("main", "Main")
        val subchapter = paragraph("subchapter", "Subchapter")
        val noteBlock = paragraph("note-body", "Note")
        val document = BookDocument(
            id = BookId("book"),
            metadata = BookMetadata(
                title = "Title",
                sequence = BookSequence("Series", number = 2),
            ),
            blocks = listOf(main, subchapter),
            tableOfContents = listOf(
                BookTocEntry(
                    title = "Chapter",
                    location = location("main"),
                    children = listOf(BookTocEntry("Subchapter", location("subchapter"))),
                ),
            ),
            notes = listOf(BookNote(NoteId("note"), listOf(noteBlock), title = "Note title")),
            images = listOf(
                BookImage(
                    id = ImageId("image"),
                    mediaType = "image/png",
                    content = BookBinaryContent { decoded },
                ),
            ),
            locationsBySourceId = mapOf(
                "chapter" to location("main"),
                "note" to location("note-body"),
            ),
        )

        assertEquals(0, decodeCount)
        assertArrayEquals(byteArrayOf(1, 2, 3), document.images.single().content.load())
        assertArrayEquals(byteArrayOf(1, 2, 3), document.images.single().content.load())
        assertEquals(1, decodeCount)
        assertEquals("Series", document.metadata.sequence?.name)
        assertEquals(2, document.metadata.sequence?.number)
        assertEquals("Subchapter", document.tableOfContents.single().children.single().title)
        assertEquals("Note title", document.notesById.getValue(NoteId("note")).title)
        assertEquals("image/png", document.imagesById.getValue(ImageId("image")).mediaType)
    }

    @Test
    fun `corrupt binary and unresolved references are representable without document failure`() {
        val missingImage = ImageId("missing-image")
        val block = BookBlock.Paragraph(
            id = BlockId("body"),
            content = listOf(
                InlineNode.Link(
                    LinkTarget.Note(NoteId("missing-note")),
                    listOf(InlineNode.Text("note")),
                ),
                InlineNode.Link(
                    LinkTarget.UnresolvedInternal("missing-anchor"),
                    listOf(InlineNode.Text("chapter")),
                ),
                InlineNode.Image(missingImage, "missing image"),
            ),
        )
        val document = BookDocument(
            id = BookId("damaged-book"),
            metadata = BookMetadata(title = "Damaged book", coverImageId = missingImage),
            blocks = listOf(block, BookBlock.Image(BlockId("image-block"), missingImage)),
            images = listOf(
                BookImage(
                    id = ImageId("corrupt-image"),
                    mediaType = "image/jpeg",
                    content = BookBinaryContent { null },
                ),
            ),
        )

        assertNull(document.images.single().content.load())
        assertEquals("notechaptermissing image", block.plainText())
        assertTrue(document.notes.isEmpty())
    }

    @Test
    fun `document rejects duplicate block ids across main quote and notes`() {
        val id = BlockId("duplicate")

        assertThrows(IllegalArgumentException::class.java) {
            BookDocument(
                id = BookId("book"),
                metadata = BookMetadata(title = "Book"),
                blocks = listOf(
                    BookBlock.Quote(
                        id = BlockId("quote"),
                        blocks = listOf(BookBlock.Spacer(id)),
                    ),
                ),
                notes = listOf(BookNote(NoteId("note"), listOf(BookBlock.Spacer(id)))),
            )
        }
    }

    @Test
    fun `document rejects duplicate note and image ids`() {
        val block = BookBlock.Spacer(BlockId("block"))
        val metadata = BookMetadata(title = "Book")

        assertThrows(IllegalArgumentException::class.java) {
            BookDocument(
                id = BookId("book"),
                metadata = metadata,
                blocks = listOf(block),
                notes = listOf(
                    BookNote(NoteId("same-note"), emptyList()),
                    BookNote(NoteId("same-note"), emptyList()),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BookDocument(
                id = BookId("book"),
                metadata = metadata,
                blocks = listOf(block),
                images = listOf(
                    image("same-image"),
                    image("same-image"),
                ),
            )
        }
    }

    @Test
    fun `toc resolves only against main flow while source anchors may resolve notes`() {
        val known = BookBlock.Spacer(BlockId("known"))
        val noteBlock = BookBlock.Spacer(BlockId("note-body"))

        BookDocument(
            id = BookId("book"),
            metadata = BookMetadata(title = "Book"),
            blocks = listOf(known),
            notes = listOf(BookNote(NoteId("note"), listOf(noteBlock))),
            locationsBySourceId = mapOf("note" to location("note-body")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BookDocument(
                id = BookId("book"),
                metadata = BookMetadata(title = "Book"),
                blocks = listOf(known),
                notes = listOf(BookNote(NoteId("note"), listOf(noteBlock))),
                tableOfContents = listOf(BookTocEntry("Note", location("note-body"))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BookDocument(
                id = BookId("book"),
                metadata = BookMetadata(title = "Book"),
                blocks = listOf(known),
                locationsBySourceId = mapOf("missing" to location("missing")),
            )
        }
    }

    @Test
    fun `identifiers and source anchor keys enforce normalized values`() {
        assertThrows(IllegalArgumentException::class.java) { NoteId(" note") }
        assertThrows(IllegalArgumentException::class.java) { NoteId("#note") }
        assertThrows(IllegalArgumentException::class.java) {
            BookDocument(
                id = BookId("book"),
                metadata = BookMetadata(title = "Book"),
                blocks = listOf(BookBlock.Spacer(BlockId("known"))),
                locationsBySourceId = mapOf("#known" to location("known")),
            )
        }
    }

    @Test
    fun `rich model rejects malformed structural values`() {
        assertThrows(IllegalArgumentException::class.java) { BookSequence(" ") }
        assertThrows(IllegalArgumentException::class.java) { BookSequence("Series", -1) }
        assertThrows(IllegalArgumentException::class.java) {
            BookTocEntry(" ", location("block"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BookNote(NoteId("note"), emptyList(), title = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BookImage(ImageId("image"), mediaType = " ", content = BookBinaryContent { null })
        }
        assertThrows(IllegalArgumentException::class.java) { LinkTarget.External(" ") }
        assertThrows(IllegalArgumentException::class.java) {
            LinkTarget.UnresolvedInternal("#anchor")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BookBlock.Poem(BlockId("poem"), stanzas = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            BookPoemStanza(lines = emptyList())
        }
    }

    @Test
    fun `metadata validates required and optional structured values`() {
        assertThrows(IllegalArgumentException::class.java) { BookMetadata(title = " ") }
        assertThrows(IllegalArgumentException::class.java) {
            BookMetadata(title = "Book", authors = listOf("Author", " "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BookMetadata(title = "Book", language = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BookMetadata(title = "Book", genres = listOf(" "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BookMetadata(title = "Book", annotation = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BookMetadata(title = "Book", documentId = " ")
        }
    }

    private fun paragraph(
        id: String,
        text: String,
        role: ParagraphRole = ParagraphRole.BODY,
    ): BookBlock.Paragraph = BookBlock.Paragraph(
        id = BlockId(id),
        content = listOf(InlineNode.Text(text)),
        role = role,
    )

    private fun location(blockId: String): BookLocation = BookLocation(
        blockId = BlockId(blockId),
        charOffset = 0,
        fallbackGlobalOffset = 0,
    )

    private fun image(id: String): BookImage = BookImage(
        id = ImageId(id),
        mediaType = "image/png",
        content = BookBinaryContent { byteArrayOf() },
    )
}
