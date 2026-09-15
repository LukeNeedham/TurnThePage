package com.avstaim.listai.feature.reader.bookview

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Totals refer only to source pages; synthetic contents leaves have their own numbering. */
internal data class PageReadingFooter(
    val pageNumber: Int,
    val totalPages: Int?,
    val sourceFraction: Float?,
    val contentsLabel: String? = null,
) {
    fun text(locale: Locale): String {
        val number = NumberFormat.getIntegerInstance(locale)
        val page = number.format(pageNumber)
        val numbered = totalPages?.let { "$page / ${number.format(it)}" } ?: page
        if (contentsLabel != null) return "$contentsLabel · $numbered"
        return numbered
    }
}

/** A width-based ruler, not one dot per paginated page. Coordinates are local to its own row. */
internal data class PageFooterProgressDots(
    val count: Int,
    val filledCount: Int,
    val radius: Float,
    val spacing: Float,
) {
    val width: Float get() = radius * 2 + (count - 1) * spacing
    fun centerX(index: Int): Float = radius + index * spacing
}

internal fun PageReadingFooter.progressDots(availableWidthPx: Float, density: Float): PageFooterProgressDots? {
    if (contentsLabel != null) return null
    val fraction = sourceFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: return null
    if (!availableWidthPx.isFinite() || availableWidthPx <= 0f || !density.isFinite() || density <= 0f) return null
    val width = min(availableWidthPx, 320f * density)
    val radius = min(.9f * density, width / 2)
    val spacing = 6f * density
    val count = (floor((width - radius * 2) / spacing).toInt() + 1).coerceAtLeast(1)
    return PageFooterProgressDots(count, (fraction * count).roundToInt(), radius, spacing)
}

internal fun sourcePageFooter(pageNumber: Int, completedPageCount: Int?, sourceOffset: Long,
    sourceLength: Long): PageReadingFooter = PageReadingFooter(
    pageNumber, completedPageCount,
    (sourceOffset.toDouble() / sourceLength.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f),
)
