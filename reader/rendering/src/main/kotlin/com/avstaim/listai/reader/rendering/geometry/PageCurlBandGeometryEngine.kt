package com.avstaim.listai.reader.rendering.geometry

import kotlin.math.hypot
import kotlin.math.max

/**
 * One affine approximation of the curved paper immediately behind the fold.
 *
 * [source] is expressed in the turning page's original coordinates. [ordinaryReflection] is
 * where that source slice would land with the hard-fold reflection from [PageCurlGeometry].
 * [deformed] is the softened curl position. A renderer can either draw source-page content with
 * [sourceToDeformed], or draw content prepared in the final destination page with
 * [destinationToDeformed]. Adjacent strips share exactly the same boundary positions.
 */
data class PageCurlBandStrip(
    val index: Int,
    val normalStart: Double,
    val normalEnd: Double,
    val source: Polygon,
    val ordinaryReflection: Polygon,
    val deformed: Polygon,
    val sourceToDeformed: AffineTransform2D,
    val destinationToDeformed: AffineTransform2D,
) {
    val isFinite: Boolean
        get() =
            normalStart.isFinite() &&
                normalEnd.isFinite() &&
                source.isFinite &&
                ordinaryReflection.isFinite &&
                deformed.isFinite &&
                sourceToDeformed.isFinite &&
                destinationToDeformed.isFinite
}

/**
 * The lifted source-page region beyond the curl band, kept on the ordinary rigid reflection.
 *
 * Together, this piece and [PageCurlBandGeometry.strips] partition the lifted source polygon.
 * Rendering those pieces instead of the complete [PageCurlGeometry.reflectedBack] prevents the
 * hard-reflected band from remaining visible underneath the softened curl.
 */
data class PageCurlRigidRemainder(
    val source: Polygon,
    val deformed: Polygon,
    /** Always [PageCurlGeometry.currentReflection] for the geometry used to build this piece. */
    val sourceToDeformed: AffineTransform2D,
    /** Always [PageCurlGeometry.destinationToReflectedBack] for the source geometry. */
    val destinationToDeformed: AffineTransform2D,
) {
    val isFinite: Boolean
        get() =
            source.isFinite &&
                deformed.isFinite &&
                sourceToDeformed.isFinite &&
                destinationToDeformed.isFinite
}

/**
 * A narrow, piecewise-affine curl plus the rigid remainder of the lifted sheet.
 *
 * When active, render [rigidRemainder] and [strips] as the complete back face; do not additionally
 * render [PageCurlGeometry.reflectedBack], because that would duplicate the band underneath.
 */
data class PageCurlBandGeometry(
    /** Width in source-page coordinates, measured along the fold normal. */
    val width: Double,
    /** Normalized bend strength. Zero at the beginning/end and one near half turn. */
    val lift: Double,
    val strips: List<PageCurlBandStrip>,
    val rigidRemainder: PageCurlRigidRemainder?,
) {
    val isActive: Boolean
        get() = strips.isNotEmpty()

    val isFinite: Boolean
        get() =
            width.isFinite() &&
                lift.isFinite() &&
                strips.all(PageCurlBandStrip::isFinite) &&
                (rigidRemainder?.isFinite != false)

    companion object {
        val EMPTY: PageCurlBandGeometry = PageCurlBandGeometry(
            width = 0.0,
            lift = 0.0,
            strips = emptyList(),
            rigidRemainder = null,
        )
    }
}

/**
 * Builds a small physical bend around the otherwise sharp reflection in [PageCurlGeometry].
 *
 * Coordinates relative to the fold are `q = m + t*v + n*d`, where `n` points into the lifted
 * source half and `t` is the fold tangent. Ordinary reflection maps its normal coordinate to
 * `-d`. Inside a band of width `C`, this engine instead uses
 *
 * ```text
 * u = d / C
 * g(d) = d * (-1 + 2 * lift * (1 - u)^2)
 * D(q) = m + t*v + n*g(d)
 * ```
 *
 * The bump is zero at `d = 0` and `d = C`; at the outer edge its derivative also matches the
 * ordinary reflection. [g] is approximated by 8..12 affine strips. The approximation is C0
 * continuous because neighbouring transforms interpolate the same boundary samples.
 */
class PageCurlBandGeometryEngine(
    epsilon: Double = DEFAULT_EPSILON,
) {
    private val epsilon: Double = epsilon
        .takeIf { it.isFinite() && it > 0.0 }
        ?: DEFAULT_EPSILON

    /**
     * Creates the curl band for [geometry].
     *
     * [stripCount] is clamped to 8..12. [minimumWidth] is expressed in the same coordinate units
     * as the page (a Compose renderer may pass `10.dp.toPx()`). The actual width is
     * `min(maxLiftedDistance, max(minimumWidth, pageWidth * 0.10))`. Non-finite configuration or
     * invalid geometry safely falls back to an empty or ordinary-reflection result.
     */
    fun calculate(
        geometry: PageCurlGeometry,
        stripCount: Int = DEFAULT_STRIP_COUNT,
        minimumWidth: Double = 0.0,
    ): PageCurlBandGeometry {
        if (!geometry.isFinite) return PageCurlBandGeometry.EMPTY

        val progress = geometry.progress.coerceIn(0.0, 1.0)
        val lift = geometry.lift.coerceIn(0.0, 1.0)
        if (
            progress <= epsilon ||
            progress >= 1.0 - epsilon ||
            lift <= epsilon ||
            geometry.liftedFront.points.size < 3
        ) {
            return PageCurlBandGeometry.EMPTY
        }

        val normalLength = hypot(geometry.fold.normal.x, geometry.fold.normal.y)
        if (!normalLength.isFinite() || normalLength <= epsilon) {
            return PageCurlBandGeometry.EMPTY
        }
        val normal = Point(
            x = geometry.fold.normal.x / normalLength,
            y = geometry.fold.normal.y / normalLength,
        )
        val tangent = Point(-normal.y, normal.x)
        // FoldLine.start/end are the line clipped to the page and may collapse to the grab point
        // when the infinite fold misses the rectangle. The midpoint of grab and pointer is always
        // on the actual reflection line used by PageCurlGeometryEngine.
        val linePoint = Point(
            x = geometry.grabPoint.x * 0.5 + geometry.constrainedPointer.x * 0.5,
            y = geometry.grabPoint.y * 0.5 + geometry.constrainedPointer.y * 0.5,
        )
        if (!linePoint.isFinite) return PageCurlBandGeometry.EMPTY

        val sourceDistances = geometry.liftedFront.points.map { point ->
            signedDistanceOrNull(point, linePoint, normal)
                ?: return PageCurlBandGeometry.EMPTY
        }
        val maxLiftedDistance = sourceDistances.maxOrNull()
            ?.coerceAtLeast(0.0)
            ?: return PageCurlBandGeometry.EMPTY
        if (maxLiftedDistance <= epsilon) return PageCurlBandGeometry.EMPTY

        val ratioWidth = (geometry.pageBounds.width * WIDTH_RATIO)
            .takeIf(Double::isFinite)
            ?.coerceAtLeast(0.0)
            ?: maxLiftedDistance
        val safeMinimumWidth = when {
            minimumWidth.isNaN() || minimumWidth <= 0.0 -> 0.0
            minimumWidth == Double.POSITIVE_INFINITY -> maxLiftedDistance
            !minimumWidth.isFinite() -> 0.0
            else -> minimumWidth
        }
        val width = max(ratioWidth, safeMinimumWidth).coerceAtMost(maxLiftedDistance)
        if (!width.isFinite() || width <= epsilon) return PageCurlBandGeometry.EMPTY

        val resolvedStripCount = stripCount.coerceIn(MIN_STRIP_COUNT, MAX_STRIP_COUNT)
        val clipEpsilon = max(epsilon, width * RELATIVE_EPSILON)
            .takeIf(Double::isFinite)
            ?: epsilon
        val strips = ArrayList<PageCurlBandStrip>(resolvedStripCount)

        repeat(resolvedStripCount) { index ->
            // Computing from normalized boundaries prevents accumulated strip-width drift and
            // ensures the final sample is exactly C.
            val u0 = index.toDouble() / resolvedStripCount
            val u1 = (index + 1).toDouble() / resolvedStripCount
            val d0 = width * u0
            val d1 = if (index == resolvedStripCount - 1) width else width * u1
            val h0 = curledNormalUnit(u0, lift)
            val h1 = curledNormalUnit(u1, lift)
            val slope = ((h1 - h0) / (u1 - u0))
                .takeIf(Double::isFinite)
                ?: -1.0
            val intercept = (width * (h0 - slope * u0))
                .takeIf(Double::isFinite)
                ?: 0.0

            val sourcePoints = clipDistanceRange(
                polygon = geometry.liftedFront.points,
                linePoint = linePoint,
                normal = normal,
                minimum = d0,
                maximum = d1,
                clipEpsilon = clipEpsilon,
            )
            val sourceToDeformed = deformationTransform(
                linePoint = linePoint,
                tangent = tangent,
                normal = normal,
                slope = slope,
                intercept = intercept,
            ) ?: geometry.currentReflection
            val destinationToDeformed = composeOrNull(
                outer = sourceToDeformed,
                inner = geometry.finalReflection,
            ) ?: geometry.destinationToReflectedBack

            val ordinaryPoints = mapFiniteOrEmpty(sourcePoints, geometry.currentReflection)
            val deformedPoints = mapFiniteOrEmpty(sourcePoints, sourceToDeformed)
            strips += PageCurlBandStrip(
                index = index,
                normalStart = d0,
                normalEnd = d1,
                source = Polygon(sourcePoints),
                ordinaryReflection = Polygon(ordinaryPoints),
                deformed = Polygon(deformedPoints),
                sourceToDeformed = sourceToDeformed,
                destinationToDeformed = destinationToDeformed,
            )
        }

        val remainderSourcePoints = clipHalfPlane(
            polygon = geometry.liftedFront.points,
            linePoint = linePoint,
            normal = normal,
            threshold = width,
            keepGreater = true,
            clipEpsilon = clipEpsilon,
        )
        val rigidRemainder = PageCurlRigidRemainder(
            source = Polygon(remainderSourcePoints),
            deformed = Polygon(
                mapFiniteOrEmpty(remainderSourcePoints, geometry.currentReflection),
            ),
            sourceToDeformed = geometry.currentReflection,
            destinationToDeformed = geometry.destinationToReflectedBack,
        )

        return PageCurlBandGeometry(
            width = width,
            lift = lift,
            strips = strips,
            rigidRemainder = rigidRemainder,
        ).takeIf(PageCurlBandGeometry::isFinite) ?: PageCurlBandGeometry.EMPTY
    }

    private fun curledNormalUnit(u: Double, lift: Double): Double {
        val remaining = 1.0 - u
        return -u + 2.0 * lift * u * remaining * remaining
    }

    private fun clipDistanceRange(
        polygon: List<Point>,
        linePoint: Point,
        normal: Point,
        minimum: Double,
        maximum: Double,
        clipEpsilon: Double,
    ): List<Point> {
        val afterMinimum = clipHalfPlane(
            polygon = polygon,
            linePoint = linePoint,
            normal = normal,
            threshold = minimum,
            keepGreater = true,
            clipEpsilon = clipEpsilon,
        )
        return clipHalfPlane(
            polygon = afterMinimum,
            linePoint = linePoint,
            normal = normal,
            threshold = maximum,
            keepGreater = false,
            clipEpsilon = clipEpsilon,
        )
    }

    private fun clipHalfPlane(
        polygon: List<Point>,
        linePoint: Point,
        normal: Point,
        threshold: Double,
        keepGreater: Boolean,
        clipEpsilon: Double,
    ): List<Point> {
        if (polygon.isEmpty()) return emptyList()

        val result = ArrayList<Point>(polygon.size + 2)
        var previous = polygon.last()
        var previousOffset = signedDistanceOrNull(previous, linePoint, normal)
            ?.minus(threshold)
            ?: return emptyList()
        var previousInside = isInside(previousOffset, keepGreater)

        polygon.forEach { current ->
            val currentOffset = signedDistanceOrNull(current, linePoint, normal)
                ?.minus(threshold)
                ?: return emptyList()
            val currentInside = isInside(currentOffset, keepGreater)
            if (currentInside != previousInside) {
                intersection(previous, current, previousOffset, currentOffset)?.let(result::add)
            }
            if (currentInside) result += current

            previous = current
            previousOffset = currentOffset
            previousInside = currentInside
        }
        return deduplicate(result, clipEpsilon)
    }

    private fun isInside(offset: Double, keepGreater: Boolean): Boolean =
        if (keepGreater) offset >= 0.0 else offset <= 0.0

    private fun intersection(
        start: Point,
        end: Point,
        startOffset: Double,
        endOffset: Double,
    ): Point? {
        val denominator = startOffset - endOffset
        if (!denominator.isFinite() || kotlin.math.abs(denominator) <= epsilon) return null
        val fraction = (startOffset / denominator).takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)
            ?: return null
        val x = start.x + (end.x - start.x) * fraction
        val y = start.y + (end.y - start.y) * fraction
        return Point(x, y).takeIf(Point::isFinite)
    }

    private fun deduplicate(points: List<Point>, tolerance: Double): List<Point> {
        if (points.isEmpty()) return points
        val result = ArrayList<Point>(points.size)
        points.forEach { point ->
            val previous = result.lastOrNull()
            if (
                previous == null ||
                kotlin.math.abs(previous.x - point.x) > tolerance ||
                kotlin.math.abs(previous.y - point.y) > tolerance
            ) {
                result += point
            }
        }
        if (result.size > 1) {
            val first = result.first()
            val last = result.last()
            if (
                kotlin.math.abs(first.x - last.x) <= tolerance &&
                kotlin.math.abs(first.y - last.y) <= tolerance
            ) {
                result.removeAt(result.lastIndex)
            }
        }
        return result
    }

    private fun signedDistanceOrNull(point: Point, linePoint: Point, normal: Point): Double? {
        val dx = point.x - linePoint.x
        val dy = point.y - linePoint.y
        if (!dx.isFinite() || !dy.isFinite()) return null
        return (dx * normal.x + dy * normal.y).takeIf(Double::isFinite)
    }

    private fun deformationTransform(
        linePoint: Point,
        tangent: Point,
        normal: Point,
        slope: Double,
        intercept: Double,
    ): AffineTransform2D? {
        // A = t*t^T + slope*n*n^T
        val m00 = tangent.x * tangent.x + slope * normal.x * normal.x
        val m01 = tangent.x * tangent.y + slope * normal.x * normal.y
        val m10 = tangent.y * tangent.x + slope * normal.y * normal.x
        val m11 = tangent.y * tangent.y + slope * normal.y * normal.y
        // b = m - A*m + n*intercept
        val m02 = linePoint.x - m00 * linePoint.x - m01 * linePoint.y + normal.x * intercept
        val m12 = linePoint.y - m10 * linePoint.x - m11 * linePoint.y + normal.y * intercept
        val coefficients = doubleArrayOf(m00, m01, m02, m10, m11, m12)
        if (coefficients.any { !it.isFinite() }) return null
        return AffineTransform2D(
            m00 = m00,
            m01 = m01,
            m02 = m02,
            m10 = m10,
            m11 = m11,
            m12 = m12,
        )
    }

    private fun composeOrNull(
        outer: AffineTransform2D,
        inner: AffineTransform2D,
    ): AffineTransform2D? {
        val m00 = outer.m00 * inner.m00 + outer.m01 * inner.m10
        val m01 = outer.m00 * inner.m01 + outer.m01 * inner.m11
        val m02 = outer.m00 * inner.m02 + outer.m01 * inner.m12 + outer.m02
        val m10 = outer.m10 * inner.m00 + outer.m11 * inner.m10
        val m11 = outer.m10 * inner.m01 + outer.m11 * inner.m11
        val m12 = outer.m10 * inner.m02 + outer.m11 * inner.m12 + outer.m12
        val coefficients = doubleArrayOf(m00, m01, m02, m10, m11, m12)
        if (coefficients.any { !it.isFinite() }) return null
        return AffineTransform2D(
            m00 = m00,
            m01 = m01,
            m02 = m02,
            m10 = m10,
            m11 = m11,
            m12 = m12,
        )
    }

    private fun mapFiniteOrEmpty(
        points: List<Point>,
        transform: AffineTransform2D,
    ): List<Point> {
        val mapped = ArrayList<Point>(points.size)
        points.forEach { point ->
            val result = transform.map(point)
            if (!result.isFinite) return emptyList()
            mapped += result
        }
        return mapped
    }

    companion object {
        const val MIN_STRIP_COUNT: Int = 8
        const val MAX_STRIP_COUNT: Int = 12
        const val DEFAULT_STRIP_COUNT: Int = 10

        private const val WIDTH_RATIO: Double = 0.10
        private const val DEFAULT_EPSILON: Double = 1e-7
        private const val RELATIVE_EPSILON: Double = 1e-12
    }
}
