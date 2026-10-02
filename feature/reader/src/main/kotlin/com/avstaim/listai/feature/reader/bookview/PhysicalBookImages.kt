package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import com.avstaim.listai.core.model.ImageId
import com.avstaim.listai.feature.reader.BookImagePreview
import kotlin.math.ceil
import kotlin.math.floor

/** Prepared with the page raster, in native page coordinates, without retaining decoded pixels. */
internal data class PreparedBookImage(
    val bounds: Rect,
    val imageId: ImageId,
    val alternativeText: String?,
)

/** Retains a published page raster only while its illustration is being inspected. */
internal data class BookImageHit(
    val bounds: Rect,
    val image: PreparedBookImage,
    val preview: BookImagePreview,
)

internal fun imagePreviewCrop(bounds: Rect, pageWidth: Int, pageHeight: Int,
    bitmapWidth: Int, bitmapHeight: Int): IntRect {
    val left = floor(bounds.left * bitmapWidth / pageWidth).toInt().coerceIn(0, bitmapWidth - 1)
    val top = floor(bounds.top * bitmapHeight / pageHeight).toInt().coerceIn(0, bitmapHeight - 1)
    return IntRect(left, top,
        ceil(bounds.right * bitmapWidth / pageWidth).toInt().coerceIn(left + 1, bitmapWidth),
        ceil(bounds.bottom * bitmapHeight / pageHeight).toInt().coerceIn(top + 1, bitmapHeight))
}

internal fun imageTapBounds(bounds: Rect, leaf: Rect, pageWidth: Int, pageHeight: Int): Rect {
    val scaleX = leaf.width / pageWidth
    val scaleY = leaf.height / pageHeight
    return Rect(
        leaf.left + bounds.left * scaleX,
        leaf.top + bounds.top * scaleY,
        leaf.left + bounds.right * scaleX,
        leaf.top + bounds.bottom * scaleY,
    ).intersect(leaf)
}
