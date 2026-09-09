package com.avstaim.listai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun `plain text keeps nested inline content`() {
        val nodes = listOf(
            InlineNode.Text("Turn "),
            InlineNode.Emphasis(
                listOf(
                    InlineNode.Text("the "),
                    InlineNode.Strong(listOf(InlineNode.Text("Page"))),
                ),
            ),
        )

        assertEquals("Turn the Page", nodes.plainText())
    }

    @Test
    fun `document rejects duplicate block ids`() {
        val id = BlockId("duplicate")

        assertThrows(IllegalArgumentException::class.java) {
            BookDocument(
                id = BookId("book"),
                metadata = BookMetadata(title = "Book"),
                blocks = listOf(
                    BookBlock.Spacer(id),
                    BookBlock.Spacer(id),
                ),
            )
        }
    }
}

