package com.avstaim.listai.core.designsystem

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
/**
 * Print the complete jacket over the complete native board. Both axes scale independently:
 * there are no letterbox mats, cropped titles, or a second fitted image inside the cover.
 * Rigid turns project this native rectangle afterward, keeping the same UVs at every angle.
 */
fun bookCoverArtworkRect(image: IntSize, board: Rect): Rect {
    if (image.width <= 0 || image.height <= 0 || !board.width.isFinite() ||
        !board.height.isFinite() || board.width <= 0f || board.height <= 0f) return Rect.Zero
    return board
}
