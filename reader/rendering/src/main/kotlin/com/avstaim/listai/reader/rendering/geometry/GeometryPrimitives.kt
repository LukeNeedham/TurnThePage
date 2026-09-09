package com.avstaim.listai.reader.rendering.geometry

import kotlin.math.abs

/**
 * A framework-independent point in the BookView coordinate system.
 *
 * The coordinate system follows the Android convention: x grows to the right and y grows
 * downwards. Input points are deliberately allowed to contain non-finite values because raw
 * pointer data is an external input. Geometry returned by [PageCurlGeometryEngine] is always
 * finite.
 */
data class Point(
    val x: Double,
    val y: Double,
) {
    val isFinite: Boolean
        get() = x.isFinite() && y.isFinite()

    operator fun plus(other: Point): Point = Point(x + other.x, y + other.y)

    operator fun minus(other: Point): Point = Point(x - other.x, y - other.y)

    operator fun times(scale: Double): Point = Point(x * scale, y * scale)

    companion object {
        val ZERO: Point = Point(0.0, 0.0)
    }
}

/**
 * A finite, normalized rectangle. Zero width or height is accepted so transient zero-sized
 * layouts can be handled without a crash or invalid curl geometry.
 */
data class Rect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "Rectangle coordinates must be finite"
        }
        require(left <= right) { "left must not be greater than right" }
        require(top <= bottom) { "top must not be greater than bottom" }
    }

    val width: Double
        get() = finiteDifference(right, left)

    val height: Double
        get() = finiteDifference(bottom, top)

    val area: Double
        get() {
            val result = width * height
            return if (result.isFinite()) result else Double.MAX_VALUE
        }

    val topLeft: Point
        get() = Point(left, top)

    val topRight: Point
        get() = Point(right, top)

    val bottomRight: Point
        get() = Point(right, bottom)

    val bottomLeft: Point
        get() = Point(left, bottom)

    fun corners(): List<Point> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    private fun finiteDifference(high: Double, low: Double): Double {
        val difference = high - low
        return when {
            difference.isFinite() -> difference
            abs(high) >= abs(low) -> abs(high).coerceAtMost(Double.MAX_VALUE)
            else -> abs(low).coerceAtMost(Double.MAX_VALUE)
        }
    }
}

/** A polygon whose vertices are expressed in BookView coordinates. */
data class Polygon(
    val points: List<Point>,
) {
    val isFinite: Boolean
        get() = points.all(Point::isFinite)

    /** Absolute polygon area. A polygon with fewer than three vertices has zero area. */
    val area: Double
        get() {
            if (points.size < 3) return 0.0

            // Translate to the first vertex before multiplying. This avoids avoidable overflow
            // for a normal-sized page placed at a very large coordinate offset.
            val anchor = points.first()
            var twiceArea = 0.0
            for (index in points.indices) {
                val current = points[index] - anchor
                val next = points[(index + 1) % points.size] - anchor
                twiceArea += current.x * next.y - next.x * current.y
            }
            val result = abs(twiceArea) * 0.5
            return if (result.isFinite()) result else Double.MAX_VALUE
        }

    companion object {
        val EMPTY: Polygon = Polygon(emptyList())
    }
}
