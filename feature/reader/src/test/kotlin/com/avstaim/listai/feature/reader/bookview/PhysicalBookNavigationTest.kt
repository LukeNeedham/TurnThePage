package com.avstaim.listai.feature.reader.bookview

import com.avstaim.listai.reader.rendering.turn.TurnDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalBookNavigationTest {
    @Test
    fun `front cover opens forward and cannot move farther backward`() {
        val navigator = PhysicalBookNavigator(pageCount = 5)

        assertNull(
            navigator.transition(
                state = PhysicalBookState.ClosedFront,
                direction = TurnDirection.BACKWARD,
                presentation = BookPresentation.SINGLE,
            ),
        )
        assertEquals(
            PhysicalBookTransition(
                from = PhysicalBookState.ClosedFront,
                to = PhysicalBookState.Open(0),
                direction = TurnDirection.FORWARD,
                subject = PhysicalTurnSubject.FRONT_COVER,
            ),
            navigator.transition(
                state = PhysicalBookState.ClosedFront,
                direction = TurnDirection.FORWARD,
                presentation = BookPresentation.SINGLE,
            ),
        )
    }

    @Test
    fun `first page closes front cover backward`() {
        val navigator = PhysicalBookNavigator(pageCount = 5)

        assertEquals(
            PhysicalBookTransition(
                from = PhysicalBookState.Open(0),
                to = PhysicalBookState.ClosedFront,
                direction = TurnDirection.BACKWARD,
                subject = PhysicalTurnSubject.FRONT_COVER,
            ),
            navigator.transition(
                state = PhysicalBookState.Open(0),
                direction = TurnDirection.BACKWARD,
                presentation = BookPresentation.SPREAD,
            ),
        )
    }

    @Test
    fun `single presentation visits every page before closing back cover`() {
        val navigator = PhysicalBookNavigator(pageCount = 4)

        val states = collectStates(
            navigator = navigator,
            initial = PhysicalBookState.ClosedFront,
            direction = TurnDirection.FORWARD,
            presentation = BookPresentation.SINGLE,
        )

        assertEquals(
            listOf(
                PhysicalBookState.ClosedFront,
                PhysicalBookState.Open(0),
                PhysicalBookState.Open(1),
                PhysicalBookState.Open(2),
                PhysicalBookState.Open(3),
                PhysicalBookState.ClosedBack,
            ),
            states,
        )
    }

    @Test
    fun `even page count uses canonical odd spread anchors`() {
        val navigator = PhysicalBookNavigator(pageCount = 6)

        val states = collectStates(
            navigator = navigator,
            initial = PhysicalBookState.ClosedFront,
            direction = TurnDirection.FORWARD,
            presentation = BookPresentation.SPREAD,
        )

        assertEquals(
            listOf(
                PhysicalBookState.ClosedFront,
                PhysicalBookState.Open(0),
                PhysicalBookState.Open(1),
                PhysicalBookState.Open(3),
                PhysicalBookState.Open(5),
                PhysicalBookState.ClosedBack,
            ),
            states,
        )
    }

    @Test
    fun `odd page count closes after final complete spread`() {
        val navigator = PhysicalBookNavigator(pageCount = 7)

        val states = collectStates(
            navigator = navigator,
            initial = PhysicalBookState.ClosedFront,
            direction = TurnDirection.FORWARD,
            presentation = BookPresentation.SPREAD,
        )

        assertEquals(
            listOf(
                PhysicalBookState.ClosedFront,
                PhysicalBookState.Open(0),
                PhysicalBookState.Open(1),
                PhysicalBookState.Open(3),
                PhysicalBookState.Open(5),
                PhysicalBookState.BackBoundary,
                PhysicalBookState.ClosedBack,
            ),
            states,
        )
    }

    @Test
    fun `odd final printed sheet and back cover are separate physical turns`() {
        val navigator = PhysicalBookNavigator(pageCount = 7)

        val finalSheet = requireNotNull(
            navigator.transition(
                state = PhysicalBookState.Open(5),
                direction = TurnDirection.FORWARD,
                presentation = BookPresentation.SPREAD,
            ),
        )
        val backCover = requireNotNull(
            navigator.transition(
                state = finalSheet.to,
                direction = TurnDirection.FORWARD,
                presentation = BookPresentation.SPREAD,
            ),
        )

        assertEquals(PhysicalTurnSubject.PAGE, finalSheet.subject)
        assertEquals(PhysicalBookState.BackBoundary, finalSheet.to)
        assertEquals(PhysicalTurnSubject.BACK_COVER, backCover.subject)
        assertEquals(PhysicalBookState.ClosedBack, backCover.to)
    }

    @Test
    fun `back cover opens onto final semantic page`() {
        assertEquals(
            PhysicalBookState.BackBoundary,
            PhysicalBookNavigator(pageCount = 7).stateAfter(
                state = PhysicalBookState.ClosedBack,
                direction = TurnDirection.BACKWARD,
                presentation = BookPresentation.SPREAD,
            ),
        )
        assertEquals(
            PhysicalBookState.Open(5),
            PhysicalBookNavigator(pageCount = 6).stateAfter(
                state = PhysicalBookState.ClosedBack,
                direction = TurnDirection.BACKWARD,
                presentation = BookPresentation.SPREAD,
            ),
        )
        assertEquals(
            PhysicalBookState.Open(5),
            PhysicalBookNavigator(pageCount = 6).stateAfter(
                state = PhysicalBookState.ClosedBack,
                direction = TurnDirection.BACKWARD,
                presentation = BookPresentation.SINGLE,
            ),
        )
    }

    @Test
    fun `opening odd back cover preserves final semantic page across presentation change`() {
        val navigator = PhysicalBookNavigator(pageCount = 7)
        val boundary = navigator.stateAfter(
            state = PhysicalBookState.ClosedBack,
            direction = TurnDirection.BACKWARD,
            presentation = BookPresentation.SPREAD,
        )
        val opened = navigator.stateAfter(
            state = boundary,
            direction = TurnDirection.BACKWARD,
            presentation = BookPresentation.SPREAD,
        )

        assertEquals(PhysicalBookState.BackBoundary, boundary)
        assertEquals(5, navigator.layoutAnchor(opened, BookPresentation.SPREAD))
        assertEquals(6, navigator.layoutAnchor(opened, BookPresentation.SINGLE))
    }

    @Test
    fun `back cover cannot move farther forward`() {
        val navigator = PhysicalBookNavigator(pageCount = 5)

        assertNull(
            navigator.transition(
                state = PhysicalBookState.ClosedBack,
                direction = TurnDirection.FORWARD,
                presentation = BookPresentation.SINGLE,
            ),
        )
        assertFalse(
            navigator.canTurn(
                state = PhysicalBookState.ClosedBack,
                direction = TurnDirection.FORWARD,
                presentation = BookPresentation.SPREAD,
            ),
        )
        assertEquals(
            PhysicalBookState.ClosedBack,
            navigator.stateAfter(
                state = PhysicalBookState.ClosedBack,
                direction = TurnDirection.FORWARD,
                presentation = BookPresentation.SPREAD,
            ),
        )
    }

    @Test
    fun `semantic right page maps to its canonical spread before turning`() {
        val navigator = PhysicalBookNavigator(pageCount = 8)
        val semanticRightPage = PhysicalBookState.Open(2)

        assertEquals(1, navigator.layoutAnchor(semanticRightPage, BookPresentation.SPREAD))
        assertEquals(
            PhysicalBookState.Open(3),
            navigator.stateAfter(
                state = semanticRightPage,
                direction = TurnDirection.FORWARD,
                presentation = BookPresentation.SPREAD,
            ),
        )
        assertEquals(
            PhysicalBookState.Open(0),
            navigator.stateAfter(
                state = semanticRightPage,
                direction = TurnDirection.BACKWARD,
                presentation = BookPresentation.SPREAD,
            ),
        )
    }

    @Test
    fun `semantic position is preserved only for layout queries`() {
        val navigator = PhysicalBookNavigator(pageCount = 8)
        val semanticPage = PhysicalBookState.Open(4)

        assertEquals(3, navigator.layoutAnchor(semanticPage, BookPresentation.SPREAD))
        assertEquals(4, navigator.layoutAnchor(semanticPage, BookPresentation.SINGLE))
    }

    @Test
    fun `one page book opens and closes both covers`() {
        val navigator = PhysicalBookNavigator(pageCount = 1)

        assertEquals(
            PhysicalBookState.Open(0),
            navigator.stateAfter(
                PhysicalBookState.ClosedFront,
                TurnDirection.FORWARD,
                BookPresentation.SPREAD,
            ),
        )
        assertEquals(
            PhysicalBookState.BackBoundary,
            navigator.stateAfter(
                PhysicalBookState.Open(0),
                TurnDirection.FORWARD,
                BookPresentation.SPREAD,
            ),
        )
        assertEquals(
            PhysicalBookState.ClosedBack,
            navigator.stateAfter(
                PhysicalBookState.BackBoundary,
                TurnDirection.FORWARD,
                BookPresentation.SPREAD,
            ),
        )
        assertEquals(
            PhysicalBookState.BackBoundary,
            navigator.stateAfter(
                PhysicalBookState.ClosedBack,
                TurnDirection.BACKWARD,
                BookPresentation.SPREAD,
            ),
        )
        assertEquals(
            PhysicalBookState.Open(0),
            navigator.stateAfter(
                PhysicalBookState.BackBoundary,
                TurnDirection.BACKWARD,
                BookPresentation.SPREAD,
            ),
        )
        assertEquals(
            PhysicalBookState.ClosedFront,
            navigator.stateAfter(
                PhysicalBookState.Open(0),
                TurnDirection.BACKWARD,
                BookPresentation.SPREAD,
            ),
        )
    }

    @Test
    fun `successful last page transition identifies back cover`() {
        val navigator = PhysicalBookNavigator(pageCount = 3)

        val transition = navigator.transition(
            state = PhysicalBookState.Open(2),
            direction = TurnDirection.FORWARD,
            presentation = BookPresentation.SINGLE,
        )

        assertEquals(PhysicalTurnSubject.BACK_COVER, transition?.subject)
        assertEquals(PhysicalBookState.ClosedBack, transition?.to)
        assertTrue(
            navigator.canTurn(
                state = PhysicalBookState.ClosedBack,
                direction = TurnDirection.BACKWARD,
                presentation = BookPresentation.SINGLE,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty book is rejected`() {
        PhysicalBookNavigator(pageCount = 0)
    }

    private fun collectStates(
        navigator: PhysicalBookNavigator,
        initial: PhysicalBookState,
        direction: TurnDirection,
        presentation: BookPresentation,
    ): List<PhysicalBookState> {
        val states = mutableListOf(initial)
        var current = initial
        repeat(32) {
            val transition = navigator.transition(current, direction, presentation) ?: return states
            current = transition.to
            states += current
        }
        error("Navigation did not reach a physical boundary")
    }
}
