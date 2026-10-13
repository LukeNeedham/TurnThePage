package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import com.avstaim.listai.reader.pagination.LayoutKey

/** A generous hit region around the printed number, contained in this leaf's lower margin.
 * The geometry follows the prepared footer, not an arbitrary fraction of the screen. */
internal fun pageNumberTapBounds(paper: Rect, key: LayoutKey): Rect {
    val scaleX = paper.width / key.pageWidthPx
    val scaleY = paper.height / key.pageHeightPx
    val bottom = paper.top + (key.pageHeightPx - key.marginY) * scaleY
    val footerHeight = key.footerHeight * scaleY
    val touchHeight = maxOf(footerHeight, 40f * key.density * scaleY)
    val halfWidth = minOf(56f * key.density * scaleX, paper.width / 2f)
    // Expand into the free bottom margin, not over the last line of the book's text.
    val top = maxOf(paper.top, bottom - footerHeight)
    return Rect(paper.center.x - halfWidth, top,
        paper.center.x + halfWidth, minOf(paper.bottom, top + touchHeight))
}

/** One centre belongs to the viewport, including the physical spine and hinge gap. */
internal fun readerMenuTapBounds(viewport: Size): Rect = Rect(
    viewport.width * .36f, viewport.height * .23f,
    viewport.width * .64f, viewport.height * .77f,
)

internal fun BookCanvasLayout.closedBookBounds(state: PhysicalBookState): Rect? = when (state) {
    PhysicalBookState.ClosedFront -> rightPage
    PhysicalBookState.ClosedBack -> leftPage ?: rightPage
    else -> null
}

internal fun BookCanvasLayout.isClosedBookBackground(state: PhysicalBookState, position: Offset): Boolean =
    closedBookBounds(state)?.let { !it.contains(position) } == true

internal fun BookCanvasLayout.containsPage(position: Offset): Boolean =
    rightPage.contains(position) || leftPage?.contains(position) == true

/** A drag that starts at the global centre still owns an ordinary curl, on either leaf. */
internal fun BookCanvasLayout.dragDirection(
    down: Offset,
    horizontalDisplacement: Float,
    canForward: Boolean,
    canBackward: Boolean,
    viewport: Size,
): TurnDirection? {
    val centre = readerMenuTapBounds(viewport).contains(down)
    if (presentation == BookPresentation.SPREAD && !centre) return when {
        rightPage.contains(down) && horizontalDisplacement < 0f && canForward -> TurnDirection.FORWARD
        leftPage?.contains(down) == true && horizontalDisplacement > 0f && canBackward -> TurnDirection.BACKWARD
        else -> null
    }
    return when {
        horizontalDisplacement < 0f && canForward -> TurnDirection.FORWARD
        horizontalDisplacement > 0f && canBackward -> TurnDirection.BACKWARD
        else -> null
    }
}

internal fun BookCanvasLayout.tapDirection(
    position: Offset,
    canForward: Boolean,
    canBackward: Boolean,
): TurnDirection? = when {
    position.x >= (if (presentation == BookPresentation.SPREAD) spineX else rightPage.center.x) && canForward -> TurnDirection.FORWARD
    position.x < (if (presentation == BookPresentation.SPREAD) spineX else rightPage.center.x) && canBackward -> TurnDirection.BACKWARD
    else -> null
}
