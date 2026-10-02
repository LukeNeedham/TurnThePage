package com.avstaim.listai.feature.reader

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntRect

/** A crop of the already prepared paper texture. No bitmap is allocated for the transition. */
internal data class BookImagePreview(val bitmap: ImageBitmap, val source: IntRect) {
    init {
        require(source.width > 0 && source.height > 0)
        require(source.left >= 0 && source.top >= 0 && source.right <= bitmap.width && source.bottom <= bitmap.height)
    }
}

internal fun bookImageZoomBounds(fitted: Rect, zoom: BookImageZoom, pivot: Offset = fitted.center): Rect {
    val halfWidth = fitted.width * zoom.scale / 2f
    val halfHeight = fitted.height * zoom.scale / 2f
    val centerX = pivot.x + (fitted.center.x - pivot.x) * zoom.scale + zoom.x
    val centerY = pivot.y + (fitted.center.y - pivot.y) * zoom.scale + zoom.y
    return Rect(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
}

internal fun bookImageTransitionBounds(source: Rect?, target: Rect, progress: Float): Rect {
    val amount = progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val start = source?.takeIf {
        it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() &&
            it.width > 0f && it.height > 0f
    } ?: target
    if (amount == 0f) return start
    if (amount == 1f) return target
    fun mix(a: Float, b: Float) = a + (b - a) * amount
    return Rect(mix(start.left, target.left), mix(start.top, target.top),
        mix(start.right, target.right), mix(start.bottom, target.bottom))
}

internal fun interpolateBookImageZoom(start: BookImageZoom, end: BookImageZoom, progress: Float): BookImageZoom {
    val amount = progress.coerceIn(0f, 1f)
    if (amount == 0f) return start
    if (amount == 1f) return end
    fun mix(a: Float, b: Float) = a + (b - a) * amount
    return BookImageZoom(mix(start.scale, end.scale), mix(start.x, end.x), mix(start.y, end.y))
}
