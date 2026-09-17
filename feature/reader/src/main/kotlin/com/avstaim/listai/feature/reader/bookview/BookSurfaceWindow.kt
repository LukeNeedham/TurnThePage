package com.avstaim.listai.feature.reader.bookview

import com.avstaim.listai.reader.rendering.turn.TurnDirection

internal data class BookSurfacePlan(val required: List<Int>, val prefetch: List<Int>)

internal fun visiblePageIndices(state: PhysicalBookState, presentation: BookPresentation, pageCount: Int): List<Int> {
    if (state == PhysicalBookState.BackBoundary && presentation == BookPresentation.SINGLE) return listOf(pageCount - 1)
    if (state !is PhysicalBookState.Open) return emptyList()
    val anchor = requireNotNull(PhysicalBookNavigator(pageCount).layoutAnchor(state, presentation))
    return if (presentation == BookPresentation.SINGLE || anchor == 0) listOf(anchor)
    else listOf(anchor, anchor + 1).filter { it < pageCount }
}

/** Current + previous + next spread are pinned; two farther pages are optional prefetch. */
internal fun bookSurfacePlan(
    state: PhysicalBookState,
    presentation: BookPresentation,
    pageCount: Int,
    direction: TurnDirection = TurnDirection.FORWARD,
): BookSurfacePlan {
    require(pageCount > 0)
    val center = when (state) {
        is PhysicalBookState.Open -> state.semanticPageIndex.coerceAtMost(pageCount - 1)
        PhysicalBookState.ClosedFront, PhysicalBookState.FrontBoundary -> 0
        else -> pageCount - 1
    }
    val navigator = PhysicalBookNavigator(pageCount)
    val anchor = requireNotNull(navigator.layoutAnchor(PhysicalBookState.Open(center), presentation))
    val current = visiblePageIndices(PhysicalBookState.Open(center), presentation, pageCount)
    val spread = presentation == BookPresentation.SPREAD
    val start = (anchor - if (spread) 2 else 1).coerceAtLeast(0)
    val end = (anchor + if (!spread) 1 else if (anchor == 0) 2 else 3).coerceAtMost(pageCount - 1)
    val neighbors = (start..end).filterNot { it in current }
    val ordered = if (direction == TurnDirection.FORWARD) neighbors.sortedBy { if (it > anchor) it - anchor else pageCount + anchor - it }
        else neighbors.sortedBy { if (it < anchor) anchor - it else pageCount + it - anchor }
    val extra = if (direction == TurnDirection.FORWARD) listOf(end + 1, end + 2) else listOf(start - 1, start - 2)
    return BookSurfacePlan(current + ordered, extra.filter { it in 0 until pageCount })
}

/** Sparse surface view: navigation uses index size, while only cached pages own bitmaps. */
internal class BookSurfaceWindow(override val size: Int, private val cached: Map<Int, DemoPageSurface>) : AbstractList<DemoPageSurface?>() {
    override fun get(index: Int): DemoPageSurface? {
        if (index !in 0 until size) throw IndexOutOfBoundsException(index.toString())
        return cached[index]
    }
}
