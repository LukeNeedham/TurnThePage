package com.avstaim.listai.feature.library

import com.avstaim.listai.core.model.ShelfZoom

internal data class LibraryShelfGeometry(val columns: Int, val bookWidthDp: Float) {
    val bookHeightDp: Float get() = bookWidthDp * 1.48f
}

/** MEDIUM deliberately uses the exact 1.1 geometry, including gaps and width cap. */
internal fun libraryShelfGeometry(widthDp: Float, zoom: ShelfZoom): LibraryShelfGeometry {
    val target = when (zoom) { ShelfZoom.LARGE -> 248f; ShelfZoom.MEDIUM -> 164f; ShelfZoom.SMALL -> 112f }
    val maximum = when (zoom) { ShelfZoom.LARGE -> 228f; ShelfZoom.MEDIUM -> 154f; ShelfZoom.SMALL -> 102f }
    val columnLimit = when (zoom) { ShelfZoom.LARGE -> 4; ShelfZoom.MEDIUM -> 6; ShelfZoom.SMALL -> 9 }
    val columns = (widthDp.coerceAtLeast(40f) / target).toInt().coerceIn(1, columnLimit)
    val width = ((widthDp - 40f - 24f * (columns - 1)) / columns).coerceIn(1f, maximum)
    return LibraryShelfGeometry(columns, width)
}

internal fun ShelfZoom.closer(): ShelfZoom = when (this) {
    ShelfZoom.SMALL -> ShelfZoom.MEDIUM
    ShelfZoom.MEDIUM, ShelfZoom.LARGE -> ShelfZoom.LARGE
}
internal fun ShelfZoom.farther(): ShelfZoom = when (this) {
    ShelfZoom.LARGE -> ShelfZoom.MEDIUM
    ShelfZoom.MEDIUM, ShelfZoom.SMALL -> ShelfZoom.SMALL
}

/** One step per physical gesture: no jitter or bounce after a threshold crossing. */
internal class LibraryPinchGesture(private val startingZoom: ShelfZoom) {
    private var initialSpan: Float? = null
    private var switched = false
    fun update(span: Float): ShelfZoom? {
        if (!span.isFinite() || span <= 0f || switched) return null
        val initial = initialSpan ?: run { initialSpan = span; return null }
        val ratio = span / initial
        val next = when { ratio >= 1.22f -> startingZoom.closer(); ratio <= .82f -> startingZoom.farther(); else -> return null }
        switched = true
        return next.takeUnless { it == startingZoom }
    }
}

/** A gesture that began below the top is never promoted to pull-search while scrolling. */
internal fun shouldOpenPullSearch(startedAtTop: Boolean, multiTouch: Boolean, dx: Float, dy: Float, threshold: Float): Boolean =
    startedAtTop && !multiTouch && dy >= threshold && kotlin.math.abs(dx) < dy * .55f
