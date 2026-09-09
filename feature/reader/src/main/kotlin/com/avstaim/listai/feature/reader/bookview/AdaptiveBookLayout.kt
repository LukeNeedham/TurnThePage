package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min

internal enum class BookFoldOrientation {
    VERTICAL,
    HORIZONTAL,
}

/**
 * Platform-neutral description of a fold or hinge in the reader's local coordinates.
 *
 * [bounds] may be a zero-width vertical line or a zero-height horizontal line.
 */
internal data class BookDisplayFeature(
    val bounds: Rect,
    val orientation: BookFoldOrientation,
    val separating: Boolean,
    val occluding: Boolean,
    val halfOpened: Boolean,
) {
    /** Content must not be placed underneath a separating fold or an occluding hinge. */
    val avoidsContent: Boolean
        get() = separating || occluding
}

internal data class AdaptiveBookLayoutConfig(
    val minimumUsableLeafWidthDp: Float = 320f,
    val minimumUsableLeafHeightDp: Float = 240f,
    val minimumGenericSpreadWidthDp: Float = 720f,
) {
    init {
        require(minimumUsableLeafWidthDp.isFinite() && minimumUsableLeafWidthDp > 0f)
        require(minimumUsableLeafHeightDp.isFinite() && minimumUsableLeafHeightDp > 0f)
        require(minimumGenericSpreadWidthDp.isFinite() && minimumGenericSpreadWidthDp > 0f)
    }
}

/**
 * Resolves the reader presentation without consulting Android window APIs.
 *
 * A vertical fold is treated as the prospective spine and each side is measured on its own.
 * Without a vertical fold, a spread additionally needs the generic width breakpoint.
 */
internal fun resolveAdaptiveBookPresentation(
    canvasSize: Size,
    density: Float,
    outerPaddingPx: Float,
    displayFeature: BookDisplayFeature? = null,
    forcedPresentation: BookPresentation? = null,
    config: AdaptiveBookLayoutConfig = AdaptiveBookLayoutConfig(),
): BookPresentation {
    forcedPresentation?.let { return it }

    require(density.isFinite() && density > 0f) { "density must be finite and positive" }
    require(outerPaddingPx.isFinite() && outerPaddingPx >= 0f) {
        "outerPaddingPx must be finite and non-negative"
    }

    val canvasWidth = canvasSize.width.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val canvasHeight = canvasSize.height.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val availableHeight = (canvasHeight - outerPaddingPx * 2f).coerceAtLeast(0f)
    val minimumLeafWidthPx = config.minimumUsableLeafWidthDp * density
    val minimumLeafHeightPx = config.minimumUsableLeafHeightDp * density

    // Width alone is not enough: an extremely short multi-window would technically fit two
    // leaves but provide no useful reading surface.
    if (availableHeight < minimumLeafHeightPx) return BookPresentation.SINGLE

    if (displayFeature?.orientation == BookFoldOrientation.HORIZONTAL) {
        if (displayFeature.avoidsContent || displayFeature.halfOpened) {
            return BookPresentation.SINGLE
        }
    }

    if (displayFeature?.orientation == BookFoldOrientation.VERTICAL) {
        val fold = displayFeature.bounds
        val leftCandidateWidth = (fold.left.coerceIn(0f, canvasWidth) - outerPaddingPx)
            .coerceAtLeast(0f)
        val rightCandidateWidth = (
            canvasWidth - outerPaddingPx - fold.right.coerceIn(0f, canvasWidth)
            ).coerceAtLeast(0f)

        return if (
            leftCandidateWidth >= minimumLeafWidthPx &&
            rightCandidateWidth >= minimumLeafWidthPx
        ) {
            BookPresentation.SPREAD
        } else {
            BookPresentation.SINGLE
        }
    }

    val availableWidth = (canvasWidth - outerPaddingPx * 2f).coerceAtLeast(0f)
    val genericLeafWidth = availableWidth * 0.5f
    val clearsGenericBreakpoint =
        availableWidth >= config.minimumGenericSpreadWidthDp * density

    return if (clearsGenericBreakpoint && genericLeafWidth >= minimumLeafWidthPx) {
        BookPresentation.SPREAD
    } else {
        BookPresentation.SINGLE
    }
}

/**
 * Clips a window-space feature to [viewportBoundsInWindow] and translates it to viewport-local
 * coordinates. Inclusive intersection intentionally preserves zero-thickness fold lines.
 */
internal fun BookDisplayFeature.toLocalViewport(
    viewportBoundsInWindow: Rect,
): BookDisplayFeature? {
    val clippedLeft = max(bounds.left, viewportBoundsInWindow.left)
    val clippedTop = max(bounds.top, viewportBoundsInWindow.top)
    val clippedRight = min(bounds.right, viewportBoundsInWindow.right)
    val clippedBottom = min(bounds.bottom, viewportBoundsInWindow.bottom)

    if (clippedLeft > clippedRight || clippedTop > clippedBottom) return null

    return copy(
        bounds = Rect(
            left = clippedLeft - viewportBoundsInWindow.left,
            top = clippedTop - viewportBoundsInWindow.top,
            right = clippedRight - viewportBoundsInWindow.left,
            bottom = clippedBottom - viewportBoundsInWindow.top,
        ),
    )
}
