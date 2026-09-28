package com.avstaim.listai.feature.library

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class LibraryViewportStateTest {
    @Test fun returningAfterSearchResizeUsesRootItemNotItsOldRowNumber() {
        val state = LibraryViewportState()
        val keys = (0..99).map { "book:$it" }
        state.record("root", "book:20", 12)
        state.record("search", "book:4", 0)
        assertEquals(10, libraryAnchorRow(keys, 2, state.anchor("root")!!))
        assertEquals(4, libraryAnchorRow(keys, 5, state.anchor("root")!!))
        assertEquals("book:4", state.anchor("search")!!.key)
    }
    @Test fun independentStackAndSearchAnchorsSurviveSavedStateRoundTrip() {
        val state = LibraryViewportState().apply {
            record("root", "stack:sea", 8); record("sea", "book:waves", 22); record("search", "book:stars", 0)
        }
        val saver = LibraryViewportState.Saver
        val saved = with(saver) { (object : SaverScope { override fun canBeSaved(value: Any): Boolean = true }).save(state) }!!
        val restored = saver.restore(saved)!!
        assertEquals(LibraryViewportAnchor("stack:sea", 8), restored.anchor("root"))
        assertEquals(LibraryViewportAnchor("book:waves", 22), restored.anchor("sea"))
        assertEquals(LibraryViewportAnchor("book:stars", 0), restored.anchor("search"))
        assertNull(libraryAnchorRow(listOf("book:other"), 2, restored.anchor("root")!!))
    }
}
