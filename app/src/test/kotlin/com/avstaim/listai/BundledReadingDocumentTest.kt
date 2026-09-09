package com.avstaim.listai

import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.plainText
import com.avstaim.listai.format.fb2.Fb2BookParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BundledReadingDocumentTest {
    @Test fun productionReaderSampleParsesAsMultiChapterRussianBook() = runBlocking {
        val input = requireNotNull(javaClass.classLoader?.getResourceAsStream("stage4_reading_sample.fb2"))
        val document = input.use { Fb2BookParser().parse(it, "stage4_reading_sample.fb2") }
        assertEquals("Атлас тихих страниц", document.metadata.title)
        assertEquals("ru", document.metadata.language)
        assertEquals(3, document.tableOfContents.size)
        assertTrue("Sample must be long enough to exercise paragraph splitting", document.plainText().length > 5_000)
        assertTrue(document.plainText().endsWith("На сегодня работа была закончена."))
        assertTrue(document.blocks.filterIsInstance<BookBlock.Paragraph>().size >= 15)
        assertTrue(document.blocks.any { it is BookBlock.Poem })
        assertFalse(document.blocks.any { it is BookBlock.Heading && it.plainText() == document.metadata.title })
    }
}
