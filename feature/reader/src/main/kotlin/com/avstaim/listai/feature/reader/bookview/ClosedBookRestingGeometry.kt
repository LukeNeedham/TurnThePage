package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import kotlin.math.roundToInt

/** A closed hardback keeps book proportions even when reflowable paper fills a wide viewport. */
internal const val RESTING_CLOSED_BOOK_ASPECT_RATIO = 0.6756757f // width / height == 1 / 1.48

internal data class RestingClosedBookScale(
    val horizontal: Float,
    val vertical: Float,
)

/**
 * Fits only the resting closed book. Open paper intentionally retains the full adaptive layout.
 * Keeping this outside pagination means closing a book never changes its semantic page layout.
 */
internal fun restingClosedBookScale(
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    density: Float,
    bookBounds: Rect?,
    maximumAspectRatio: Float = RESTING_CLOSED_BOOK_ASPECT_RATIO,
): RestingClosedBookScale {
    val height = canvasHeightPx.takeIf { it.isFinite() && it > 0f } ?: 1f
    val width = canvasWidthPx.takeIf { it.isFinite() && it > 0f } ?: height
    val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    val vertical = (1f - 92f * safeDensity / (height * .65f)).coerceIn(.60f, .88f)
    val aspect = bookBounds?.takeIf {
        it.width.isFinite() && it.height.isFinite() && it.width > 0f && it.height > 0f
    }?.let { it.width / it.height } ?: width / height
    val correction = min(1f, maximumAspectRatio / aspect)
    return RestingClosedBookScale(horizontal = vertical * correction, vertical = vertical)
}

/**
 * Prepare cover artwork in its final closed-board aspect. The outer resting transform then
 * cancels the page viewport's temporary stretch, so lettering and imported art are not squashed.
 */
internal fun closedCoverPreparedSize(
    pageSize: IntSize,
    maximumAspectRatio: Float = RESTING_CLOSED_BOOK_ASPECT_RATIO,
): IntSize {
    val height = pageSize.height.coerceAtLeast(1)
    val fittedWidth = (height * maximumAspectRatio).roundToInt().coerceAtLeast(1)
    return IntSize(min(pageSize.width.coerceAtLeast(1), fittedWidth), height)
}
