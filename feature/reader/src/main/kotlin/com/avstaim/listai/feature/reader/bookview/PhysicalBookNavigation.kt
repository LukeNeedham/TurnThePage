package com.avstaim.listai.feature.reader.bookview

import com.avstaim.listai.feature.reader.demo.DemoPageNavigator
import com.avstaim.listai.reader.rendering.turn.TurnDirection

/**
 * The stable physical position of a book between turns.
 *
 * [Open.semanticPageIndex] is deliberately semantic rather than a spread index. This lets a
 * resize preserve the page the reader was looking at. Use [PhysicalBookNavigator.layoutAnchor]
 * when the renderer needs the canonical first page of a spread.
 */
internal sealed interface PhysicalBookState {
    data object ClosedFront : PhysicalBookState

    data class Open(
        val semanticPageIndex: Int,
    ) : PhysicalBookState {
        init {
            require(semanticPageIndex >= 0) { "semanticPageIndex must not be negative" }
        }
    }

    /**
     * The printed leaves are exhausted and the book is open against its inner back cover.
     *
     * This state is needed when an odd page count leaves the final printed page on the right: the
     * reader first turns that real sheet, then closes the hard cover with a separate gesture.
     */
    data object BackBoundary : PhysicalBookState

    data object ClosedBack : PhysicalBookState
}

/** The physical leaf moved by a successful transition. */
internal enum class PhysicalTurnSubject {
    FRONT_COVER,
    PAGE,
    BACK_COVER,
}

internal data class PhysicalBookTransition(
    val from: PhysicalBookState,
    val to: PhysicalBookState,
    val direction: TurnDirection,
    val subject: PhysicalTurnSubject,
)

/**
 * Pure boundary-aware navigation for a physical book.
 *
 * A turn past the front or back cover is unavailable and returns `null`. Successful page turns
 * always land on canonical spread anchors (page 0, then odd page indices), while an externally
 * supplied semantic page remains intact until the reader actually turns a leaf.
 */
internal class PhysicalBookNavigator(
    private val pageCount: Int,
) {
    init {
        require(pageCount > 0) { "pageCount must be positive" }
    }

    fun layoutAnchor(
        state: PhysicalBookState,
        presentation: BookPresentation,
    ): Int? = when (state) {
        PhysicalBookState.ClosedFront,
        PhysicalBookState.BackBoundary,
        PhysicalBookState.ClosedBack,
        -> null

        is PhysicalBookState.Open -> DemoPageNavigator.layoutAnchorIndex(
            current = state.semanticPageIndex,
            pageCount = pageCount,
            spread = presentation == BookPresentation.SPREAD,
        )
    }

    fun canTurn(
        state: PhysicalBookState,
        direction: TurnDirection,
        presentation: BookPresentation,
    ): Boolean = transition(state, direction, presentation) != null

    fun stateAfter(
        state: PhysicalBookState,
        direction: TurnDirection,
        presentation: BookPresentation,
    ): PhysicalBookState = transition(state, direction, presentation)?.to ?: state

    fun transition(
        state: PhysicalBookState,
        direction: TurnDirection,
        presentation: BookPresentation,
    ): PhysicalBookTransition? = when (state) {
        PhysicalBookState.ClosedFront -> when (direction) {
            TurnDirection.FORWARD -> PhysicalBookTransition(
                from = state,
                to = PhysicalBookState.Open(semanticPageIndex = 0),
                direction = direction,
                subject = PhysicalTurnSubject.FRONT_COVER,
            )

            TurnDirection.BACKWARD -> null
        }

        PhysicalBookState.BackBoundary -> if (presentation == BookPresentation.SINGLE) {
            transitionFromOpen(
                state = PhysicalBookState.Open(pageCount - 1),
                direction = direction,
                presentation = presentation,
            ).copy(from = state)
        } else {
            when (direction) {
                TurnDirection.FORWARD -> PhysicalBookTransition(
                    from = state,
                    to = PhysicalBookState.ClosedBack,
                    direction = direction,
                    subject = PhysicalTurnSubject.BACK_COVER,
                )

                TurnDirection.BACKWARD -> PhysicalBookTransition(
                    from = state,
                    to = PhysicalBookState.Open(pageCount - 1),
                    direction = direction,
                    subject = PhysicalTurnSubject.PAGE,
                )
            }
        }

        PhysicalBookState.ClosedBack -> when (direction) {
            TurnDirection.FORWARD -> null
            TurnDirection.BACKWARD -> PhysicalBookTransition(
                from = state,
                // Preserve the actual final semantic page. The renderer derives a canonical
                // spread anchor only for layout, so a later SPREAD -> SINGLE resize still opens
                // on the last page rather than its left-hand neighbour.
                to = if (
                    presentation == BookPresentation.SPREAD && pageCount % 2 == 1
                ) {
                    PhysicalBookState.BackBoundary
                } else {
                    PhysicalBookState.Open(semanticPageIndex = pageCount - 1)
                },
                direction = direction,
                subject = PhysicalTurnSubject.BACK_COVER,
            )
        }

        is PhysicalBookState.Open -> transitionFromOpen(state, direction, presentation)
    }

    private fun transitionFromOpen(
        state: PhysicalBookState.Open,
        direction: TurnDirection,
        presentation: BookPresentation,
    ): PhysicalBookTransition {
        val spread = presentation == BookPresentation.SPREAD
        val anchor = DemoPageNavigator.layoutAnchorIndex(
            current = state.semanticPageIndex,
            pageCount = pageCount,
            spread = spread,
        )

        val destination = when (direction) {
            TurnDirection.FORWARD -> {
                val next = DemoPageNavigator.nextIndex(
                    current = anchor,
                    pageCount = pageCount,
                    spread = spread,
                )
                if (next != anchor) {
                    PhysicalBookState.Open(next)
                } else if (spread && pageCount % 2 == 1) {
                    PhysicalBookState.BackBoundary
                } else {
                    PhysicalBookState.ClosedBack
                }
            }

            TurnDirection.BACKWARD -> {
                val previous = DemoPageNavigator.previousIndex(
                    current = anchor,
                    pageCount = pageCount,
                    spread = spread,
                )
                if (previous == anchor) PhysicalBookState.ClosedFront else PhysicalBookState.Open(previous)
            }
        }

        val subject = when (destination) {
            PhysicalBookState.ClosedFront -> PhysicalTurnSubject.FRONT_COVER
            PhysicalBookState.ClosedBack -> PhysicalTurnSubject.BACK_COVER
            PhysicalBookState.BackBoundary,
            is PhysicalBookState.Open -> PhysicalTurnSubject.PAGE
        }

        return PhysicalBookTransition(
            from = state,
            to = destination,
            direction = direction,
            subject = subject,
        )
    }
}
