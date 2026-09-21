package com.avstaim.listai.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkColorsTest {
    @Test fun `durable bookmark id selects a stable palette slot`() {
        assertEquals(
            0,
            bookmarkColorIndex("64f49aa7-4ed6-42fd-a878-6c28d190027d", paletteSize = 8),
        )
        assertEquals(0, bookmarkColorIndex("bookmark-one", paletteSize = 8))
        assertEquals(6, bookmarkColorIndex("bookmark-two", paletteSize = 8))
    }

    @Test fun `durable ids remain deterministic and within the palette`() {
        val ids = listOf(
            "64f49aa7-4ed6-42fd-a878-6c28d190027d",
            "bookmark-one",
            "bookmark-two",
            "mark0",
            "mark1",
            "mark2",
            "",
            "a book with spaces",
        )
        ids.forEach { id ->
            val first = bookmarkColorIndex(id, paletteSize = 8)
            assertEquals(first, bookmarkColorIndex(id, paletteSize = 8))
            assertTrue("slot for $id", first in 0 until 8)
        }
    }
}
