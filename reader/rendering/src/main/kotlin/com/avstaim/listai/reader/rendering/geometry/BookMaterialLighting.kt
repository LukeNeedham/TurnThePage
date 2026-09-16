package com.avstaim.listai.reader.rendering.geometry

import kotlin.math.sqrt

/** Lighting of paper only. Source and destination ink remain fully opaque through the turn. */
data class PaperCurlLighting(
    val castWidth: Double,
    val castOpacity: Double,
    val contactWidth: Double,
    val contactOpacity: Double,
    val creaseOpacity: Double,
    val ridgeOpacity: Double,
) {
    companion object {
        fun calculate(pageWidth: Double, density: Double, lift: Double): PaperCurlLighting {
            val width = pageWidth.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
            val pixelsPerDp = density.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
            val elevation = lift.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
            // Low paper gives a sharp contact; elevated paper casts a broader, softer shadow.
            // All lighting reaches zero with the geometric lift, removing a last-frame seam.
            val presence = sqrt(elevation)
            return PaperCurlLighting(
                castWidth = (width * (.018 + .070 * elevation)).coerceAtLeast(3.0 * pixelsPerDp),
                castOpacity = presence * (.15 - .035 * elevation),
                contactWidth = pixelsPerDp * (1.0 + 1.3 * elevation),
                contactOpacity = presence * (.17 - .025 * elevation),
                creaseOpacity = .095 * presence,
                ridgeOpacity = .13 * elevation,
            )
        }
    }
}

data class PageStackThickness(val read: Float, val remaining: Float)

/** The two paper blocks exchange thickness; the book never gains leaves while turning. */
fun pageStackThickness(progress: Float, unit: Float = 1f): PageStackThickness {
    val p = progress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val scale = unit.takeIf { it.isFinite() && it > 0f } ?: 1f
    return PageStackThickness((1f + 7f * p) * scale, (1f + 7f * (1f - p)) * scale)
}
