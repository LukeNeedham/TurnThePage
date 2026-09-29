package com.avstaim.listai.feature.library

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class LibraryShelfInsertionTest {
    private val original = ('a'..'f').map { "book:$it" }
    private fun shelf(source: String = "book:a") = LibraryShelfGestureState().apply {
        keys = original
        original.forEachIndexed { index, key ->
            val x = (index % 3) * 120f + 20f
            val y = (index / 3) * 210f + 20f
            bounds[key] = Rect(x, y, x + 100f, y + 150f)
        }
        viewportBounds = Rect(0f, 0f, 400f, 500f)
        start(source, bounds.getValue(source).center)
    }

    @Test fun rootGapRemainsAValidInsertionAfterLeavingAGroupableCover() {
        val state = shelf()
        state.move(Offset(190f, 95f), 8f, 100, { _, _ -> true })
        state.move(Offset(250f, 95f), 8f, 200, { _, _ -> true })
        assertNull(state.drag!!.hoverKey)
        assertEquals("book:b", state.drag!!.dropKey)
        assertEquals(Rect(250f, 20f, 250f, 170f), state.drag!!.insertionMarker)
        assertEquals("Targets stay still until release", original, state.keys)
        state.release()
        assertEquals(listOf("book:b", "book:a", "book:c", "book:d", "book:e", "book:f"), state.keys)
    }

    @Test fun repeatedStationarySamplesAndFinalUpNeverUndoAnInternalStackMove() {
        val state = shelf()
        repeat(20) { state.move(Offset(190f, 95f), 8f, 100L + it * 32, { _, _ -> false }) }
        assertFalse(state.armHover(10_000))
        assertEquals("book:b", state.drag!!.dropKey)
        assertEquals(original, state.keys)
        state.release()
        assertEquals(listOf("book:b", "book:a", "book:c", "book:d", "book:e", "book:f"), state.keys)
    }

    @Test fun rowEndAndVerticalGapInsertIntoTheNearestRow() {
        val end = shelf()
        end.move(Offset(380f, 95f), 8f, 100, { _, _ -> true })
        assertEquals("book:c", end.release()!!.dropKey)
        assertEquals(listOf("book:b", "book:c", "book:a", "book:d", "book:e", "book:f"), end.keys)

        val nextRow = shelf()
        nextRow.move(Offset(130f, 215f), 8f, 100, { _, _ -> true })
        assertEquals("book:d", nextRow.release()!!.dropKey)
        assertEquals(listOf("book:b", "book:c", "book:d", "book:a", "book:e", "book:f"), nextRow.keys)
    }

    @Test fun partialFinalRowAcceptsTheBlankSpaceToItsRight() {
        val state = shelf().apply { keys = original.take(4); bounds.keys.retainAll(keys); start("book:a", bounds.getValue("book:a").center) }
        state.move(Offset(300f, 300f), 8f, 100, { _, _ -> true })
        assertEquals("book:d", state.release()!!.dropKey)
        assertEquals(listOf("book:b", "book:c", "book:d", "book:a"), state.keys)
    }

    @Test fun backwardsGapInsertionAccountsForTheRemovedSource() {
        val state = shelf("book:f")
        state.move(Offset(130f, 95f), 8f, 100, { _, _ -> true })
        assertEquals("book:b", state.release()!!.dropKey)
        assertEquals(listOf("book:a", "book:f", "book:b", "book:c", "book:d", "book:e"), state.keys)
    }

    @Test fun oneColumnShelfUsesTheVerticalGapWithoutLeftRightJitter() {
        val state = shelf("book:f").apply {
            original.forEachIndexed { index, key -> bounds[key] = Rect(20f, index * 210f, 120f, index * 210f + 150f) }
            viewportBounds = Rect(0f, 0f, 140f, 1400f)
            start("book:f", bounds.getValue("book:f").center)
        }
        listOf(69f, 70f, 71f).forEach { x ->
            state.move(Offset(x, 205f), 8f, 100, { _, _ -> false })
            assertEquals("book:b", state.drag!!.dropKey)
        }
        state.release()
        assertEquals(listOf("book:a", "book:f", "book:b", "book:c", "book:d", "book:e"), state.keys)
    }

    @Test fun scrollingUsesAbsoluteOrderInsteadOfVisibleRowIndices() {
        val state = shelf().apply { bounds.keys.retainAll(original.drop(3)) }
        state.move(Offset(380f, 300f), 8f, 100, { _, _ -> false })
        assertEquals("book:f", state.release()!!.dropKey)
        assertEquals(original.drop(1) + original.first(), state.keys)
    }

    @Test fun returningToTheSourceOrItsAdjacentGapLeavesOrderUnchanged() {
        val state = shelf()
        state.move(Offset(380f, 95f), 8f, 100, { _, _ -> true })
        state.move(Offset(130f, 95f), 8f, 200, { _, _ -> true })
        assertNull(state.drag!!.dropKey)
        assertNull(state.drag!!.insertionMarker)
        state.release()
        assertEquals(original, state.keys)
    }

    @Test fun releaseOutsideTheShelfAndCancellationCannotCommitAPriorInsertion() {
        val state = shelf()
        state.move(Offset(250f, 95f), 8f, 100, { _, _ -> true })
        state.move(Offset(250f, -10f), 8f, 200, { _, _ -> true })
        assertNull(state.release()!!.dropKey)
        assertEquals(original, state.keys)
        state.start("book:a", state.bounds.getValue("book:a").center)
        state.move(Offset(250f, 95f), 8f, 300, { _, _ -> true })
        state.cancel()
        assertEquals(original, state.keys)
        assertNull(state.release())
    }
}
