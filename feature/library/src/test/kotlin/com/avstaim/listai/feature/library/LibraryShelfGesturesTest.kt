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
    @Test fun movingOutsideTheCenterClearsGroupingButKeepsTargetInPlaceUntilDrop() {
        val state = shelf(); state.move(Offset(170f, 75f), 8f, 100, { _, _ -> true }); state.armHover(650)
        state.move(Offset(126f, 75f), 8f, 700, { _, _ -> true })
        assertFalse(state.drag!!.armed); assertNull(state.drag!!.hoverKey)
        assertEquals("book:b", state.drag!!.dropKey)
        assertEquals(listOf("book:a", "book:b", "stack:c"), state.keys)
    }
    @Test fun secondFingerCancellationRestoresOriginalOrderingAndCannotCommitGroup() {
        val state = shelf(); state.move(Offset(126f, 75f), 8f, 100, { _, _ -> false })
        assertEquals(listOf("book:b", "book:a", "stack:c"), state.keys)
        state.cancel()
        assertEquals(listOf("book:a", "book:b", "stack:c"), state.keys); assertNull(state.release())
    }
    @Test fun disallowedNestedStacksCanOnlyReorder() {
        val state = shelf(); state.move(Offset(290f, 75f), 8f, 100, { _, _ -> false })
        assertFalse(state.armHover(1000)); assertNull(state.drag!!.hoverKey)
        assertEquals(listOf("book:b", "stack:c", "book:a"), state.keys)
    }
    @Test fun gradualTrajectoryThroughTargetEdgeLeavesItsSlotStableAndArmsTheIntendedBook() {
        val state = shelf()
        listOf(64f, 90f, 108f, 121f, 132f, 146f, 158f, 170f).forEachIndexed { index, x ->
            state.move(Offset(x, 75f), 8f, index * 32L, { _, _ -> true })
            assertEquals("No edge may move the target out from under the finger",
                listOf("book:a", "book:b", "stack:c"), state.keys)
        }
        assertEquals("book:b", state.drag!!.hoverKey)
        assertEquals("book:b", state.drag!!.dropKey)
        assertFalse(state.armHover(700))
        assertTrue(state.armHover(710))
        assertEquals("book:b", state.release()!!.hoverKey)
    }

    @Test fun crossingANeighbourAndDwellingOnAStackNeverReordersEitherTarget() {
        val state = shelf()
        listOf(121f, 150f, 175f, 219f, 241f, 265f, 290f).forEachIndexed { index, x ->
            state.move(Offset(x, 75f), 8f, index * 50L, { _, _ -> true })
        }
        assertEquals(listOf("book:a", "book:b", "stack:c"), state.keys)
        assertEquals("stack:c", state.drag!!.hoverKey)
        assertTrue(state.armHover(300))
    }

    @Test fun leavingAnArmedTargetOrCancellingCannotCreateAGroup() {
        val state = shelf()
        state.move(Offset(170f, 75f), 8f, 100, { _, _ -> true }); state.armHover(650)
        state.move(Offset(230f, 75f), 8f, 700, { _, _ -> true })
        assertFalse(state.drag!!.armed); assertNull(state.drag!!.hoverKey); assertNull(state.drag!!.dropKey)
        state.cancel()
        assertNull(state.release())
        assertEquals(listOf("book:a", "book:b", "stack:c"), state.keys)
    }

    @Test fun quickEdgeDropRetainsTheIntendedOrdinaryMoveWithoutStartingADwell() {
        val state = shelf()
        state.move(Offset(126f, 75f), 8f, 100, { _, _ -> true })
        assertFalse(state.armHover(2000))
        val drop = state.release()!!
        assertEquals("book:b", drop.dropKey); assertNull(drop.hoverKey); assertFalse(drop.armed)
        assertEquals(drop.originalKeys, state.keys)
    }

    @Test fun eventTimeoutAndScrollClockNeverRewindsAfterARealStationaryPause() {
        var elapsed = 0L
        val clock = LibraryGestureClock { elapsed }
        assertEquals(1_000L, clock.start(1_000))
        elapsed = 650L
        assertEquals(1_650L, clock.now())
        assertEquals(1_650L, clock.advanceTo(1_016)) // a queued event cannot undo the hold
        assertEquals(2_200L, clock.advanceTo(2_200)) // completed pointer timeout
        elapsed += 32L
        assertEquals(2_232L, clock.now()) // same clock used by auto-scroll
        assertEquals(2_232L, clock.advanceTo(1_032))
    }

    @Test fun existingStackAcceptsItsVisibleBottomEdgeWithoutAStationaryDwell() {
        val state = shelf()
        state.move(Offset(290f, 148f), 8f, 100, { _, _ -> true })
        assertEquals("stack:c", state.drag!!.hoverKey)
        assertTrue(state.armHover(100))
        assertTrue(state.release()!!.armed)
        assertEquals(listOf("book:a", "book:b", "stack:c"), state.keys)
    }

    @Test fun crossingStackThenReleasingOutsideClearsTheImmediateDrop() {
        val state = shelf()
        state.move(Offset(290f, 145f), 8f, 100, { _, _ -> true })
        assertTrue(state.armHover(100))
        state.move(Offset(290f, 160f), 8f, 120, { _, _ -> true })
        val released = state.release()!!
        assertFalse(released.armed)
        assertNull(released.hoverKey)
        assertNull(released.dropKey)
    }

    @Test fun existingStackStillRequiresAGroupableSourceAndCannotNestAnotherStack() {
        val state = shelf()
        state.move(Offset(290f, 148f), 8f, 100, { _, _ -> false })
        assertFalse(state.armHover(1000))
        assertNull(state.drag!!.hoverKey)
    }

    @Test fun explicitExtractionTargetWinsOverAnUnderlyingBookAndRestoresOnExit() {
        val state = shelf()
        state.extractionBounds = Rect(120f, 0f, 220f, 60f)
        state.move(Offset(170f, 40f), 8f, 100, { _, _ -> true })
        assertTrue(state.drag!!.extractReady)
        assertNull(state.drag!!.hoverKey)
        assertNull(state.drag!!.dropKey)
        assertFalse(state.armHover(1000))
        assertEquals(listOf("book:a", "book:b", "stack:c"), state.keys)
        state.move(Offset(170f, 75f), 8f, 1100, { _, _ -> true })
        assertFalse(state.drag!!.extractReady)
        assertEquals("book:b", state.drag!!.hoverKey)
        assertFalse(state.drag!!.armed)
    }

    @Test fun cancellingExtractionOrReleasingOutsideTheTargetCannotExtract() {
        val state = shelf()
        state.extractionBounds = Rect(0f, -80f, 200f, -20f)
        state.move(Offset(100f, -40f), 8f, 100, { _, _ -> false })
        assertTrue(state.drag!!.extractReady)
        state.move(Offset(100f, -10f), 8f, 200, { _, _ -> false })
        assertFalse(state.release()!!.extractReady)
        state.start("book:a", Offset(50f, 75f))
        state.move(Offset(100f, -40f), 8f, 300, { _, _ -> false })
        state.cancel()
        assertNull(state.release())
    }

}
