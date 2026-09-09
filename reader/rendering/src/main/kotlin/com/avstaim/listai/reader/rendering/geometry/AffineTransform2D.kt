package com.avstaim.listai.reader.rendering.geometry

import kotlin.math.hypot

/**
 * Framework-independent 2D affine transform.
 *
 * The matrix maps a point as follows:
 *
 * ```text
 * x' = m00 * x + m01 * y + m02
 * y' = m10 * x + m11 * y + m12
 * ```
 */
data class AffineTransform2D(
    val m00: Double,
    val m01: Double,
    val m02: Double,
    val m10: Double,
    val m11: Double,
    val m12: Double,
) {
    val isFinite: Boolean
        get() =
            m00.isFinite() &&
                m01.isFinite() &&
                m02.isFinite() &&
                m10.isFinite() &&
                m11.isFinite() &&
                m12.isFinite()

    init {
        require(isFinite) { "Affine transform coefficients must be finite" }
    }

    fun map(point: Point): Point = Point(
        x = m00 * point.x + m01 * point.y + m02,
        y = m10 * point.x + m11 * point.y + m12,
    )

    /**
     * Returns `this ∘ inner`: [inner] is applied first, then this transform is applied.
     */
    fun compose(inner: AffineTransform2D): AffineTransform2D = AffineTransform2D(
        m00 = m00 * inner.m00 + m01 * inner.m10,
        m01 = m00 * inner.m01 + m01 * inner.m11,
        m02 = m00 * inner.m02 + m01 * inner.m12 + m02,
        m10 = m10 * inner.m00 + m11 * inner.m10,
        m11 = m10 * inner.m01 + m11 * inner.m11,
        m12 = m10 * inner.m02 + m11 * inner.m12 + m12,
    )

    companion object {
        val IDENTITY: AffineTransform2D = AffineTransform2D(
            m00 = 1.0,
            m01 = 0.0,
            m02 = 0.0,
            m10 = 0.0,
            m11 = 1.0,
            m12 = 0.0,
        )

        /**
         * Reflection in the infinite line through [linePoint] with the supplied line [normal].
         * A zero or non-finite normal safely produces [IDENTITY].
         */
        fun reflection(
            linePoint: Point,
            normal: Point,
        ): AffineTransform2D {
            val safeNormal = if (normal.isFinite) normal else return IDENTITY
            val length = hypot(safeNormal.x, safeNormal.y)
            if (!length.isFinite() || length <= NORMAL_EPSILON) return IDENTITY

            val nx = safeNormal.x / length
            val ny = safeNormal.y / length
            val px = linePoint.x.finiteOrZero()
            val py = linePoint.y.finiteOrZero()
            val projection = nx * px + ny * py
            val transform = doubleArrayOf(
                1.0 - 2.0 * nx * nx,
                -2.0 * nx * ny,
                2.0 * nx * projection,
                -2.0 * nx * ny,
                1.0 - 2.0 * ny * ny,
                2.0 * ny * projection,
            )
            if (transform.any { !it.isFinite() }) return IDENTITY
            return AffineTransform2D(
                m00 = transform[0],
                m01 = transform[1],
                m02 = transform[2],
                m10 = transform[3],
                m11 = transform[4],
                m12 = transform[5],
            )
        }

        private const val NORMAL_EPSILON = 1e-12

        private fun Double.finiteOrZero(): Double = if (isFinite()) this else 0.0
    }
}
