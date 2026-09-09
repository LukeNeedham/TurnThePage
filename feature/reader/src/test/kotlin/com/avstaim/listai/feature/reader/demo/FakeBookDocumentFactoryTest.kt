package com.avstaim.listai.feature.reader.demo

import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.plainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeBookDocumentFactoryTest {
    @Test
    fun `default demo document is deterministic and has 32 leaves`() {
        val first = FakeBookDocumentFactory.create()
        val second = FakeBookDocumentFactory.create()

        assertEquals(32, first.blocks.size)
        assertEquals(first, second)
        assertEquals(first.blocks.size, first.blocks.map { it.id }.distinct().size)
        assertTrue(first.blocks.first() is BookBlock.Paragraph)
        assertTrue(first.blocks.any { it is BookBlock.Image })
        assertTrue(first.blocks.last() is BookBlock.Heading)
    }

    @Test
    fun `first paper page starts content instead of repeating cover metadata`() {
        val document = FakeBookDocumentFactory.create()
        val firstPage = document.toDemoPages().first()

        assertEquals(DemoPageKind.TEXT, firstPage.kind)
        assertNull(firstPage.title)
        assertFalse(firstPage.body.contains(document.metadata.title))
        document.metadata.authors.forEach { author ->
            assertFalse(firstPage.body.contains(author))
        }
        assertEquals(document.blocks.first().plainText(), firstPage.body)
    }

    @Test
    fun `demo document accepts only the agreed range`() {
        assertEquals(20, FakeBookDocumentFactory.create(20).blocks.size)
        assertEquals(50, FakeBookDocumentFactory.create(50).blocks.size)

        assertThrows(IllegalArgumentException::class.java) {
            FakeBookDocumentFactory.create(19)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FakeBookDocumentFactory.create(51)
        }
    }

    @Test
    fun `navigator never leaves document bounds`() {
        assertEquals(0, DemoPageNavigator.previousIndex(0, 32, spread = false))
        assertEquals(1, DemoPageNavigator.nextIndex(0, 32, spread = true))
        assertEquals(3, DemoPageNavigator.nextIndex(1, 32, spread = true))
        assertEquals(31, DemoPageNavigator.nextIndex(29, 32, spread = true))
        assertEquals(31, DemoPageNavigator.nextIndex(31, 32, spread = true))
        assertEquals(29, DemoPageNavigator.nextIndex(29, 31, spread = true))
        assertEquals(31, DemoPageNavigator.nextIndex(31, 32, spread = false))
        assertEquals(0, DemoPageNavigator.previousIndex(1, 32, spread = true))
    }

    @Test
    fun `semantic page remains inside its canonical spread across repeated resize`() {
        (0 until 32).forEach { semanticIndex ->
            val preservedSemanticIndex = semanticIndex

            repeat(100) {
                val spreadAnchor = DemoPageNavigator.layoutAnchorIndex(
                    current = preservedSemanticIndex,
                    pageCount = 32,
                    spread = true,
                )
                val visibleIndices = if (spreadAnchor == 0) {
                    setOf(0)
                } else {
                    setOf(spreadAnchor, (spreadAnchor + 1).coerceAtMost(31))
                }
                assertTrue(preservedSemanticIndex in visibleIndices)

                // Returning to a one-page viewport uses the semantic page, not the spread anchor.
                assertEquals(
                    preservedSemanticIndex,
                    DemoPageNavigator.layoutAnchorIndex(
                        current = preservedSemanticIndex,
                        pageCount = 32,
                        spread = false,
                    ),
                )
            }

            assertEquals(semanticIndex, preservedSemanticIndex)
        }
    }

    @Test
    fun `navigation derives turns from spread containing semantic right page`() {
        // Semantic page index 2 is the right leaf of the spread anchored at index 1.
        assertEquals(1, DemoPageNavigator.layoutAnchorIndex(2, 32, spread = true))
        assertEquals(3, DemoPageNavigator.nextIndex(2, 32, spread = true))
        assertEquals(0, DemoPageNavigator.previousIndex(2, 32, spread = true))
    }
}
