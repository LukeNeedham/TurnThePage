package com.avstaim.listai.feature.reader

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

internal const val PDF_VIEWER_PIXELS_PER_POINT = 4.0

internal data class PdfPageRenderSize(val width: Int, val height: Int)

/** Fixed source detail, independent of gesture frames, bounded like an original illustration. */
internal fun pdfPageRenderSize(widthPoints: Int, heightPoints: Int): PdfPageRenderSize {
    require(widthPoints > 0 && heightPoints > 0)
    val scale = min(PDF_VIEWER_PIXELS_PER_POINT, min(
        sqrt(BOOK_IMAGE_MAX_PIXELS.toDouble() / (widthPoints.toDouble() * heightPoints)),
        BOOK_IMAGE_MAX_AXIS.toDouble() / maxOf(widthPoints, heightPoints),
    ))
    return PdfPageRenderSize(
        floor(widthPoints * scale).toInt().coerceIn(1, BOOK_IMAGE_MAX_AXIS),
        floor(heightPoints * scale).toInt().coerceIn(1, BOOK_IMAGE_MAX_AXIS),
    )
}

/** Tall phones fill height from the source's left edge; panning reveals the remaining width. */
internal fun pdfPageInitialZoom(viewport: BookImageViewport): BookImageZoom =
    if (viewport.height > 0f && viewport.height.isFinite() &&
        viewport.fittedHeight > 0f && viewport.fittedHeight.isFinite()) {
        val scale = (viewport.height / viewport.fittedHeight).coerceIn(1f, BOOK_IMAGE_MAX_ZOOM)
        viewport.constrain(BookImageZoom(scale, maxOf(0f, (viewport.fittedWidth * scale - viewport.width) / 2f)))
    } else BookImageZoom()
