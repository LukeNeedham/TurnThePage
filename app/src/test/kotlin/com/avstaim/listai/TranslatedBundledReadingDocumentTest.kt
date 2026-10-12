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
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class TranslatedBundledReadingDocumentTest(private val language: String, private val title: String) {
    @Test fun translationPreservesTheWholeIllustratedNovellaAndNavigableSourceStructure() = runBlocking {
        val name = "stage4_reading_sample_${language.replace('-', '_').lowercase()}.fb2"
        val input = requireNotNull(javaClass.classLoader?.getResourceAsStream(name))
        val document = input.use { Fb2BookParser().parse(it, name) }
        assertEquals(title, document.metadata.title)
        assertEquals(language, document.metadata.language)
        assertEquals(1, document.metadata.authors.size)
        assertTrue(document.metadata.annotationBlocks.size >= 3)
        assertTrue("The complete story must retain meaningful reading length", document.plainText().length > 25_000)
        assertTrue(document.blocks.filterIsInstance<BookBlock.Paragraph>().size >= 125)
        assertTrue(document.blocks.any { it is BookBlock.Poem })
        assertTrue(document.blocks.any { it is BookBlock.Quote })

        assertEquals(4, document.tableOfContents.size)
        assertEquals(listOf(3, 3, 3, 0), document.tableOfContents.map { it.children.size })
        assertEquals(3, document.parts.size)
        assertTrue(document.parts.first().epigraphBlockIds.isNotEmpty())
        val chapters = document.chapters.filter { it.kind == BookChapterKind.CHAPTER }
        assertEquals((1..9).toList(), chapters.map { it.ordinal })
        assertEquals(listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"), chapters.map { it.number })
        val epilogue = document.chapters.single { it.kind == BookChapterKind.EPILOGUE }
        assertNull(epilogue.ordinal)
        assertEquals(document.tableOfContents.last().location.blockId, epilogue.startBlockId)
        assertEquals(2, epilogue.headingBlockIds.size)

        assertEquals(2, document.notes.size)
        val links = document.blocks.filterIsInstance<BookBlock.Paragraph>()
            .flatMap { it.content.filterIsInstance<InlineNode.Link>() }
            .mapNotNull { it.target as? LinkTarget.Note }
        assertEquals(2, links.size)
        assertEquals(2, links.toSet().size)
        assertEquals(ImageId("cover.jpg"), document.metadata.coverImageId)
        assertEquals(4, document.images.size)
        assertEquals(3, document.blocks.filterIsInstance<BookBlock.Image>().size)
        document.images.forEach { image ->
            assertEquals("image/jpeg", image.mediaType)
            val bytes = requireNotNull(image.content.load())
            assertTrue(bytes.size > 100_000)
            assertEquals(0xff, bytes[0].toInt() and 0xff)
            assertEquals(0xd8, bytes[1].toInt() and 0xff)
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun editions() = listOf(
            arrayOf("en", "The Seventh Light"), arrayOf("de", "Das siebte Licht"),
            arrayOf("es", "La séptima luz"), arrayOf("fr", "La Septième Lumière"),
            arrayOf("it", "La settima luce"), arrayOf("pt-BR", "A sétima luz"),
        )
    }
}
