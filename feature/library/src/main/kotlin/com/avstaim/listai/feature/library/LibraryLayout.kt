package com.avstaim.listai.feature.library

/**
 * The shelf uses a non-scrollable column whenever all real rows fit. Keeping this
 * calculation outside composition makes the scrolling boundary explicit and testable.
 */
internal data class LibraryLayout(
    val rowCount: Int,
    val contentHeightDp: Float,
    val scrollable: Boolean,
    /** Empty cabinet rows are visual fill only and never belong to scroll content. */
    val decorativeRowCount: Int,
)

internal fun calculateLibraryLayout(
    viewportHeightDp: Float,
    rowCount: Int,
    bookHeightDp: Float,
    hasEmptyMessage: Boolean = false,
    topPaddingDp: Float = 32f,
    bottomPaddingDp: Float = 88f,
    bookBaselineGapDp: Float = 22f,
    shelfHeightDp: Float = 8f,
    rowSpacingDp: Float = 20f,
    emptyMessageHeightDp: Float = 68f,
    topInsetDp: Float = 0f,
    bottomInsetDp: Float = 0f,
    /** Measured paper captions below each occupied shelf; includes the user's font scale. */
    rowCaptionHeightsDp: List<Float> = emptyList(),
): LibraryLayout {
    val actualRowCount = rowCount.coerceAtLeast(if (hasEmptyMessage) 1 else 0)
    val rowHeight = bookHeightDp + bookBaselineGapDp + shelfHeightDp + rowSpacingDp
    val contentHeight = topPaddingDp + actualRowCount * rowHeight + rowCaptionHeightsDp.take(actualRowCount).sum() +
        (if (hasEmptyMessage) emptyMessageHeightDp else 0f) + bottomPaddingDp
    val availableHeight = (viewportHeightDp - topInsetDp.coerceAtLeast(0f) - bottomInsetDp.coerceAtLeast(0f)).coerceAtLeast(0f)
    val scrollable = contentHeight > availableHeight
    val decorativeRows = if (scrollable || rowHeight <= 0f) 0 else
        ((availableHeight - contentHeight) / rowHeight).toInt().coerceAtLeast(0)
    return LibraryLayout(actualRowCount, contentHeight, scrollable, decorativeRows)
}
