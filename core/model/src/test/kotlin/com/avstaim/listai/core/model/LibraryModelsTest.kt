package com.avstaim.listai.core.model

import org.junit.Assert.*
import org.junit.Test

class LibraryModelsTest {
    @Test fun readingLengthAndSnippetUseUtf16AndFlattenQuotesOnce() {
        val first = BookBlock.Paragraph(BlockId("first"), listOf(InlineNode.Text("Hello 😀")))
        val second = BookBlock.Paragraph(BlockId("second"), listOf(InlineNode.Text("Another   line\ncontinues")))
        val doc = BookDocument(BookId("book"), BookMetadata("Book"), listOf(first,
            BookBlock.Quote(BlockId("quote"), listOf(second))))
        assertEquals(listOf(first, second), doc.readingBlocks())
        assertEquals(first.plainText().length + second.plainText().length + 2L, doc.readingLength())
        assertEquals("line continues", doc.bookmarkSnippet(BookLocation(second.id, 10, 20)))
        assertEquals("", doc.bookmarkSnippet(BookLocation(BlockId("missing"), 0, 0)))
    }

    @Test fun progressRejectsInvalidFractions() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.1f).forEach {
            assertThrows(IllegalArgumentException::class.java) { ReadingProgress(fraction = it) }
        }
    }
}
