package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Rect
import com.avstaim.listai.reader.pagination.LayoutKey

/** Native page coordinates become physical hit regions once, before a pointer owns the scene. */
internal fun noteTapBounds(bounds: Rect, leaf: Rect, key: LayoutKey, density: Float,
    expand: Boolean = true): Rect {
    val scaleX = leaf.width / key.pageWidthPx
    val scaleY = leaf.height / key.pageHeightPx
    val left = leaf.left + bounds.left * scaleX
    val top = leaf.top + bounds.top * scaleY
    val right = leaf.left + bounds.right * scaleX
    val bottom = leaf.top + bounds.bottom * scaleY
    val growX = if (expand) maxOf(0f, (38f * density - (right - left)) / 2) else 0f
    val growY = if (expand) maxOf(0f, (38f * density - (bottom - top)) / 2) else 0f
    // Never cross the gutter, another leaf, or the physical edge of this sheet.
    return Rect(maxOf(leaf.left, left - growX), maxOf(leaf.top, top - growY),
        minOf(leaf.right, right + growX), minOf(leaf.bottom, bottom + growY))
}
