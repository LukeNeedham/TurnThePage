package com.avstaim.listai.feature.reader

import kotlin.math.max
import kotlin.math.min

internal const val BOOK_IMAGE_MAX_ZOOM = 5f
internal const val BOOK_IMAGE_MAX_PIXELS = 8_388_608L
internal const val BOOK_IMAGE_MAX_AXIS = 8192

/** Translation is relative to the centre of the fitted image, in viewport pixels. */
internal data class BookImageZoom(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f)

internal data class BookImageViewport(
    val width: Float,
    val height: Float,
    val fittedWidth: Float,
    val fittedHeight: Float,
) {
    fun constrain(zoom: BookImageZoom): BookImageZoom {
        val scale = zoom.scale.takeIf { it.isFinite() }?.coerceIn(1f, BOOK_IMAGE_MAX_ZOOM) ?: 1f
        val maxX = max(0f, (fittedWidth * scale - width) / 2f)
        val maxY = max(0f, (fittedHeight * scale - height) / 2f)
        return BookImageZoom(scale,
            if (maxX == 0f) 0f else zoom.x.takeIf { it.isFinite() }?.coerceIn(-maxX, maxX) ?: 0f,
            if (maxY == 0f) 0f else zoom.y.takeIf { it.isFinite() }?.coerceIn(-maxY, maxY) ?: 0f)
    }

    /** Keeps the image point beneath the gesture centroid stationary while scaling. */
    fun transform(
        current: BookImageZoom,
        centroidX: Float,
        centroidY: Float,
        panX: Float = 0f,
        panY: Float = 0f,
        zoomFactor: Float = 1f,
    ): BookImageZoom {
        val previous = constrain(current)
        val factor = zoomFactor.takeIf { it.isFinite() && it > 0f } ?: 1f
        val scale = (previous.scale * factor).coerceIn(1f, BOOK_IMAGE_MAX_ZOOM)
        val ratio = scale / previous.scale
        val focalX = (centroidX - width / 2f).takeIf { it.isFinite() } ?: 0f
        val focalY = (centroidY - height / 2f).takeIf { it.isFinite() } ?: 0f
        return constrain(BookImageZoom(scale,
            previous.x * ratio + focalX * (1f - ratio) + panX,
            previous.y * ratio + focalY * (1f - ratio) + panY))
    }

    fun doubleTap(current: BookImageZoom, x: Float, y: Float): BookImageZoom =
        if (current.scale > 1.01f) BookImageZoom()
        else transform(current, x, y, zoomFactor = 2.5f)
}

internal fun bookImageViewport(
    width: Float,
    height: Float,
    imageWidth: Int,
    imageHeight: Int,
): BookImageViewport {
    val viewportWidth = width.takeIf { it.isFinite() && it > 0f } ?: 0f
    val viewportHeight = height.takeIf { it.isFinite() && it > 0f } ?: 0f
    if (imageWidth <= 0 || imageHeight <= 0) return BookImageViewport(viewportWidth, viewportHeight, 0f, 0f)
    val fit = min(viewportWidth / imageWidth, viewportHeight / imageHeight)
    return BookImageViewport(viewportWidth, viewportHeight, imageWidth * fit, imageHeight * fit)
}

/** Power-of-two subsampling leaves original detail available without allocating an unbounded bitmap. */
internal fun bookImageDecodeSample(width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    fun sampled(axis: Int) = (axis.toLong() + sample - 1L) / sample
    while (sampled(width) * sampled(height) > BOOK_IMAGE_MAX_PIXELS ||
        sampled(width) > BOOK_IMAGE_MAX_AXIS || sampled(height) > BOOK_IMAGE_MAX_AXIS) {
        sample *= 2
    }
    return sample
}
