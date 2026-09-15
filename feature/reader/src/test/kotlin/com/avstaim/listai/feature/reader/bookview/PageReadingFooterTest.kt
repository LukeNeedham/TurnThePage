package com.avstaim.listai.feature.reader.bookview

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class PageReadingFooterTest {
    @Test fun `pending pagination keeps its page number and source progress without a fabricated total`() {
        val footer = sourcePageFooter(3, null, 2500, 10000)
        assertEquals("3", footer.text(Locale.ENGLISH))
        assertEquals(.25f, footer.sourceFraction!!, 0f)
        assertNull(footer.totalPages)
    }

    @Test fun `complete source index adds true total without changing source progress`() {
        val pending = sourcePageFooter(3, null, 2500, 10000)
        val complete = sourcePageFooter(3, 12, 2500, 10000)
        assertEquals(pending.sourceFraction, complete.sourceFraction)
        assertEquals("3 / 12", complete.text(Locale.ENGLISH))
        assertEquals(pending.progressDots(320f, 1f), complete.progressDots(320f, 1f))
    }

    @Test fun `contents numbering never claims to be source reading progress`() {
        assertEquals("Contents · 2 / 3", PageReadingFooter(2, 3, null, "Contents").text(Locale.ENGLISH))
        assertEquals("Оглавление · 2 / 3", PageReadingFooter(2, 3, null, "Оглавление").text(Locale.forLanguageTag("ru")))
    }

    @Test fun `source offset fraction is bounded and handles an empty document`() {
        assertEquals(0f, sourcePageFooter(1, 1, 0, 0).sourceFraction!!, 0f)
        assertEquals(1f, sourcePageFooter(1, 1, 900, 100).sourceFraction!!, 0f)
        assertEquals(0f, sourcePageFooter(1, 1, -5, 100).sourceFraction!!, 0f)
    }

    @Test fun `dots show a contiguous source segment including exact start and end`() {
        val start = sourcePageFooter(1, null, 0, 100).progressDots(320f, 1f)!!
        val middle = sourcePageFooter(17, 50, 50, 100).progressDots(320f, 1f)!!
        val end = sourcePageFooter(500, 500, 100, 100).progressDots(320f, 1f)!!
        assertEquals(start.count, end.count)
        assertEquals(0, start.filledCount)
        assertEquals(middle.count / 2, middle.filledCount)
        assertEquals(end.count, end.filledCount)
    }

    @Test fun `dot count follows physical width and density rather than page count`() {
        val shortBook = sourcePageFooter(2, 4, 25, 100)
        val longBook = sourcePageFooter(500, 2000, 25, 100)
        val row = shortBook.progressDots(260f, 1f)!!
        assertEquals(row, longBook.progressDots(260f, 1f))
        val dense = shortBook.progressDots(780f, 3f)!!
        assertEquals(row.count, dense.count)
        assertEquals(row.filledCount, dense.filledCount)
        assertEquals(row.width * 3, dense.width, .001f)
        assertTrue(row.count < shortBook.progressDots(320f, 1f)!!.count)
        assertEquals(shortBook.progressDots(320f, 1f), shortBook.progressDots(1600f, 1f))
    }

    @Test fun `dots fit a narrow page and never overlap`() {
        val footer = sourcePageFooter(1, 1, 50, 100)
        for (width in listOf(.5f, 1f, 2f, 24f, 150f, 320f)) {
            val row = footer.progressDots(width, 1f)!!
            assertTrue(row.count >= 1)
            assertTrue(row.width <= width)
            assertEquals(row.radius, row.centerX(0), 0f)
            assertEquals(row.width - row.radius, row.centerX(row.count - 1), .001f)
            if (row.count > 1) assertTrue(row.spacing > row.radius * 2)
        }
    }

    @Test fun `synthetic contents do not create a source ruler`() {
        assertNull(PageReadingFooter(2, 3, null, "Contents").progressDots(320f, 1f))
        assertNull(PageReadingFooter(2, 3, .5f, "Contents").progressDots(320f, 1f))
        assertNull(PageReadingFooter(2, 3, null).progressDots(320f, 1f))
    }

    @Test fun `invalid geometry or source progress does not publish a dot row`() {
        val footer = sourcePageFooter(1, 1, 50, 100)
        assertNull(footer.progressDots(0f, 1f))
        assertNull(footer.progressDots(Float.NaN, 1f))
        assertNull(footer.progressDots(320f, 0f))
        assertNull(footer.copy(sourceFraction = Float.NaN).progressDots(320f, 1f))
    }
}
