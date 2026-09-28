package com.avstaim.listai.feature.library

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class LibraryShelfGesturesTest {
    private fun shelf() = LibraryShelfGestureState().apply {
        keys = listOf("book:a", "book:b", "stack:c")
        keys.forEachIndexed { i, key -> bounds[key] = Rect(i * 120f, 0f, i * 120f + 100f, 150f) }
        start("book:a", Offset(50f, 75f))
    }
    @Test fun centerHoverGroupsOnlyAfterDwellAndDoesNotReorder() {
        val state = shelf(); state.move(Offset(170f, 75f), 8f, 100, { _, _ -> true })
        assertEquals(listOf("book:a", "book:b", "stack:c"), state.keys)
        assertFalse(state.armHover(649)); assertTrue(state.armHover(650)); assertFalse(state.armHover(900))
        assertTrue(state.release()!!.armed)
    }
    @Test fun movingOutsideTheCenterClearsGroupingAndPreviewsReordering() {
        val state = shelf(); state.move(Offset(170f, 75f), 8f, 100, { _, _ -> true }); state.armHover(650)
        state.move(Offset(126f, 75f), 8f, 700, { _, _ -> true })
        assertFalse(state.drag!!.armed); assertNull(state.drag!!.hoverKey)
        assertEquals(listOf("book:b", "book:a", "stack:c"), state.keys)
    }
    @Test fun secondFingerCancellationRestoresOriginalOrderingAndCannotCommitGroup() {
        val state = shelf(); state.move(Offset(126f, 75f), 8f, 100, { _, _ -> true })
        state.cancel()
        assertEquals(listOf("book:a", "book:b", "stack:c"), state.keys); assertNull(state.release())
    }
    @Test fun disallowedNestedStacksCanOnlyReorder() {
        val state = shelf(); state.move(Offset(290f, 75f), 8f, 100, { _, _ -> false })
        assertFalse(state.armHover(1000)); assertNull(state.drag!!.hoverKey)
        assertEquals(listOf("book:b", "stack:c", "book:a"), state.keys)
    }
}
