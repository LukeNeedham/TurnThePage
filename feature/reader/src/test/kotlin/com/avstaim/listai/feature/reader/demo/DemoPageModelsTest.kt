package com.avstaim.listai.feature.reader.demo

import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.BookMetadata
import com.avstaim.listai.core.model.InlineNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoPageModelsTest {
    @Test
    fun `normalized metadata title heading is omitted from paper pages`() {
        val document = BookDocument(
            id = BookId("imported-book"),
            metadata = BookMetadata(
                title = "  THE   Atlas\nOF Quiet Pages  ",
                authors = listOf("Imported Author"),
            ),
            blocks = listOf(
                heading(id = "title", text = "The Atlas of Quiet Pages"),
                paragraph(id = "opening", text = "This is the actual opening paragraph."),
            ),
        )

        val pages = document.toDemoPages()

        assertEquals(1, pages.size)
        assertEquals("opening", pages.single().stableId)
        assertNull(pages.single().title)
        assertEquals("This is the actual opening paragraph.", pages.single().body)
        assertEquals(DemoPageKind.TEXT, pages.single().kind)
    }

    @Test
    fun `metadata-only title document produces safe blank inner leaf`() {
        val document = BookDocument(
            id = BookId("metadata-only-book"),
            metadata = BookMetadata(
                title = "Only a Title",
                authors = listOf("Cover Author"),
            ),
            blocks = listOf(heading(id = "title", text = "only A title")),
        )

        val pages = document.toDemoPages()

        assertEquals(1, pages.size)
        assertEquals(DemoPageKind.TEXT, pages.single().kind)
        assertNull(pages.single().title)
        assertTrue(pages.single().body.isEmpty())
    }

    private fun heading(id: String, text: String): BookBlock.Heading = BookBlock.Heading(
        id = BlockId(id),
        level = 1,
        content = listOf(InlineNode.Text(text)),
    )

    private fun paragraph(id: String, text: String): BookBlock.Paragraph = BookBlock.Paragraph(
        id = BlockId(id),
        content = listOf(InlineNode.Text(text)),
    )
}
