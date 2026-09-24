package com.avstaim.listai.feature.library

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.avstaim.listai.core.model.BookId
import org.junit.Assert.*
import org.junit.Test

class LibraryReorderTest {
    private val ids = listOf("a", "b", "c", "d").map(::BookId)

    @Test fun movesBothDirectionsWithoutDroppingOrDuplicatingBooks() {
        assertEquals(listOf(ids[1], ids[2], ids[0], ids[3]), moveShelfBook(ids, ids[0], ids[2]))
        assertEquals(listOf(ids[3], ids[0], ids[1], ids[2]), moveShelfBook(ids, ids[3], ids[0]))
        assertEquals(ids, moveShelfBook(ids, ids[0], BookId("removed")))
    }

    @Test fun holdingWithoutMovingLeavesOrderForBookActions() {
        val state = state()
        state.start(Offset(30f, 40f))
        state.move(Offset(2f, 1f), slop = 8f)
        assertFalse(state.drag!!.moved)
        assertEquals(ids, state.ids)
    }

    @Test fun cancelledDragRestoresOriginalOrder() {
        val state = state()
        state.start(Offset(30f, 40f))
        state.move(Offset(220f, 0f), slop = 8f)
        assertEquals(listOf(ids[1], ids[2], ids[0], ids[3]), state.ids)
        assertEquals(ids[2], state.drag!!.originalOrder[state.ids.indexOf(ids[0])])
        state.cancel()
        assertEquals(ids, state.ids)
        assertNull(state.drag)
    }

    @Test fun repeatedPointerSamplesBeforeLayoutCannotUndoAMove() {
        val state = state()
        state.start(Offset(30f, 40f))
        state.move(Offset(110f, 0f), slop = 8f)
        state.move(Offset(1f, 0f), slop = 8f)
        state.updateDestination()
        assertEquals(listOf(ids[1], ids[0], ids[2], ids[3]), state.ids)
    }

    @Test fun scrolledBoundsCanBecomeTheDropTargetWithoutAnotherFingerMove() {
        val state = state()
        state.start(Offset(30f, 40f))
        state.move(Offset(0f, 100f), slop = 8f)
        state.bounds[ids[3]] = Rect(0f, 120f, 100f, 220f)
        state.updateDestination()
        assertEquals(listOf(ids[1], ids[2], ids[3], ids[0]), state.ids)
    }

    private fun state() = LibraryReorderState(ids).apply {
        ids.forEachIndexed { index, id -> bounds[id] = Rect(index * 110f, 0f, index * 110f + 100f, 100f) }
    }
}
