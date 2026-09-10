package com.avstaim.listai.feature.reader.bookview

import com.avstaim.listai.reader.rendering.turn.TurnDirection
import org.junit.Assert.*
import org.junit.Test

class BookSurfaceWindowTest {
    @Test fun singlePageWindowPrioritizesCurrentThenDirectionAndKeepsBothNeighbors() {
        val state = PhysicalBookState.Open(8)
        assertEquals(BookSurfacePlan(listOf(8, 9, 7), listOf(10, 11)),
            bookSurfacePlan(state, BookPresentation.SINGLE, 30))
        assertEquals(BookSurfacePlan(listOf(8, 7, 9), listOf(6, 5)),
            bookSurfacePlan(state, BookPresentation.SINGLE, 30, TurnDirection.BACKWARD))
    }

    @Test fun spreadUsesSixHotPagesAndTwoOptionalDirectionalPages() {
        val plan = bookSurfacePlan(PhysicalBookState.Open(8), BookPresentation.SPREAD, 30)
        assertEquals(listOf(7, 8, 9, 10, 6, 5), plan.required)
        assertEquals(listOf(11, 12), plan.prefetch)
    }

    @Test fun allPhysicalBoundariesAndBothTurnDestinationsAreAlreadyInHotWindow() {
        for (count in 1..40) for (mode in BookPresentation.entries) {
            val navigator = PhysicalBookNavigator(count)
            val states = listOf(PhysicalBookState.ClosedFront, PhysicalBookState.ClosedBack, PhysicalBookState.BackBoundary) +
                (0 until count).map { PhysicalBookState.Open(it) }
            for (state in states) for (direction in TurnDirection.entries) {
                val plan = bookSurfacePlan(state, mode, count, direction)
                assertTrue(plan.required.size <= if (mode == BookPresentation.SPREAD) 6 else 3)
                assertTrue(plan.prefetch.size <= 2)
                assertEquals(plan.required.size, plan.required.distinct().size)
                assertTrue((plan.required + plan.prefetch).all { it in 0 until count })
                assertTrue(plan.required.intersect(plan.prefetch.toSet()).isEmpty())
                assertTrue(plan.required.containsAll(visiblePageIndices(state, mode, count)))
                for (turn in TurnDirection.entries) {
                    navigator.transition(state, turn, mode)?.let {
                        assertTrue("$count $mode $state -> ${it.to}",
                            plan.required.containsAll(visiblePageIndices(it.to, mode, count)))
                    }
                }
            }
        }
    }

    @Test fun sparseWindowDoesNotAllocateAnEntryForEveryPage() {
        val window = BookSurfaceWindow(1_000_000, emptyMap())
        assertEquals(1_000_000, window.size)
        assertNull(window[999_999])
        assertThrows(IndexOutOfBoundsException::class.java) { window[-1] }
        assertThrows(IndexOutOfBoundsException::class.java) { window[1_000_000] }
    }
}
