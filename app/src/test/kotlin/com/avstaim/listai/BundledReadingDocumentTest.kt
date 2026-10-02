package com.avstaim.listai

import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookChapterKind
import com.avstaim.listai.core.model.ImageId
import com.avstaim.listai.core.model.InlineNode
import com.avstaim.listai.core.model.LinkTarget
import com.avstaim.listai.core.model.plainText
import com.avstaim.listai.format.fb2.Fb2BookParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BundledReadingDocumentTest {
    @Test fun productionReaderSampleIsACompleteIllustratedRussianNovella() = runBlocking {
        val input = requireNotNull(javaClass.classLoader?.getResourceAsStream("stage4_reading_sample.fb2"))
        val document = input.use { Fb2BookParser().parse(it, "stage4_reading_sample.fb2") }

        assertEquals("Седьмой огонь", document.metadata.title)
        assertEquals(listOf("Нина Ладожина"), document.metadata.authors)
        assertEquals("ru", document.metadata.language)
        assertEquals(ImageId("cover.jpg"), document.metadata.coverImageId)
        assertTrue(requireNotNull(document.metadata.annotation).contains("беломорский маяк"))
        assertTrue(document.metadata.annotationBlocks.size >= 3)

        assertEquals(4, document.tableOfContents.size)
        assertEquals(
            listOf(
                "Часть первая Кромка воды",
                "Часть вторая Карта прилива",
                "Часть третья Седьмой огонь",
                "Эпилог Дом для света",
            ),
            document.tableOfContents.map { it.title },
        )
        assertEquals(listOf(3, 3, 3, 0), document.tableOfContents.map { it.children.size })
        assertEquals("Глава первая Письмо с координатами", document.tableOfContents[0].children[0].title)
        assertEquals("Глава девятая Берег, нанесённый заново", document.tableOfContents[2].children[2].title)

        val numberedChapters = document.chapters.filter { it.kind == BookChapterKind.CHAPTER }
        assertEquals("The epilogue must not change the nine source chapters", (1..9).toList(), numberedChapters.map { it.ordinal })
        val epilogue = document.chapters.single { it.kind == BookChapterKind.EPILOGUE }
        assertNull(epilogue.ordinal)
        assertNull(epilogue.number)
        assertEquals(document.tableOfContents.last().location.blockId, epilogue.startBlockId)
        assertEquals(2, epilogue.headingBlockIds.size)

        val plainText = document.plainText()
        assertTrue("Demo novella must be long enough for meaningful reading", plainText.length > 30_000)
        assertTrue(plainText.endsWith("Лида всё равно проверила направление."))
        assertTrue(document.blocks.filterIsInstance<BookBlock.Paragraph>().size >= 125)
        assertTrue(document.blocks.any { it is BookBlock.Poem })
        assertTrue(document.blocks.any { it is BookBlock.Quote })
        assertNotEquals(document.metadata.title, document.blocks.firstOrNull()?.plainText())

        assertEquals(2, document.notes.size)
        assertEquals(listOf("Примечание 1", "Примечание 2"), document.notes.map { it.title })
        assertEquals(2, document.noteLinks().size)

        assertEquals(
            setOf(
                ImageId("cover.jpg"),
                ImageId("illustration-lighthouse.jpg"),
                ImageId("illustration-map.jpg"),
                ImageId("illustration-night.jpg"),
            ),
            document.images.mapTo(mutableSetOf()) { it.id },
        )
        assertEquals(3, document.blocks.filterIsInstance<BookBlock.Image>().size)
        document.images.forEach { image ->
            assertEquals("image/jpeg", image.mediaType)
            val bytes = requireNotNull(image.content.load())
            assertTrue("Bundled JPEG must not be a placeholder", bytes.size > 100_000)
            assertEquals(0xff, bytes[0].toInt() and 0xff)
            assertEquals(0xd8, bytes[1].toInt() and 0xff)
        }
    }

    private fun com.avstaim.listai.core.model.BookDocument.noteLinks(): List<InlineNode.Link> =
        blocks.flatMap { block ->
            when (block) {
                is BookBlock.Paragraph -> block.content.filterIsInstance<InlineNode.Link>()
                is BookBlock.Heading -> block.content.filterIsInstance<InlineNode.Link>()
                else -> emptyList()
            }
        }.filter { it.target is LinkTarget.Note }
}
