package com.avstaim.listai.reader.rendering.geometry

import com.avstaim.listai.reader.rendering.turn.CurlOrigin
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class FoldLine(
    val start: Point,
    val end: Point,
    /** Unit normal pointing from the fold towards the original outer corner. */
    val normal: Point,
)

/**
 * Geometry of a single turning page.
 *
 * [flatFront] is the portion of the source page that has not crossed the fold. [liftedFront] is
 * the source-space portion on the outer-corner side of the fold. [reflectedBack] is that lifted
 * polygon reflected through the fold and is suitable for drawing the back side of the sheet.
 */
data class PageCurlGeometry(
    val direction: TurnDirection,
    val origin: CurlOrigin,
    val pageBounds: Rect,
    val grabPoint: Point,
    val constrainedPointer: Point,
    /** Pointer position which makes the current reflection equal the final spine reflection. */
    val completionPointer: Point,
    /** Bounds occupied by this sheet after a complete turn. */
    val finalDestinationBounds: Rect,
    val flatFront: Polygon,
    val liftedFront: Polygon,
    val reflectedBack: Polygon,
    val fold: FoldLine,
    /** Reflection carrying source-page coordinates to the current back-face position. */
    val currentReflection: AffineTransform2D,
    /** Final reflection carrying source-page coordinates into [finalDestinationBounds]. */
    val finalReflection: AffineTransform2D,
    /**
     * Maps content already expressed in [finalDestinationBounds] to [reflectedBack]. It is
     * `currentReflection ∘ finalReflection`; final reflection is its own inverse.
     */
    val destinationToReflectedBack: AffineTransform2D,
    /** Travel from [grabPoint] towards [completionPointer], normalized to 0..1. */
    val progress: Double,
    /** Normalized paper lift. It is zero when flat and peaks near half turn. */
    val lift: Double,
) {
    val isFinite: Boolean
        get() =
            grabPoint.isFinite &&
                constrainedPointer.isFinite &&
                completionPointer.isFinite &&
                flatFront.isFinite &&
                liftedFront.isFinite &&
                reflectedBack.isFinite &&
                fold.start.isFinite &&
                fold.end.isFinite &&
                fold.normal.isFinite &&
                currentReflection.isFinite &&
                finalReflection.isFinite &&
                destinationToReflectedBack.isFinite &&
                progress.isFinite() &&
                lift.isFinite()
}

/**
 * A deterministic 2D/2.5D page-curl foundation with no Android or Compose dependency.
 *
 * The fold is the perpendicular bisector between the resolved grab anchor and the constrained
 * pointer. The page rectangle is clipped against both half-planes of that line, and the lifted
 * half is reflected to obtain the back-face polygon.
 */
class PageCurlGeometryEngine(
    private val epsilon: Double = DEFAULT_EPSILON,
) {
    init {
        require(epsilon.isFinite() && epsilon > 0.0) { "epsilon must be finite and positive" }
    }

    /**
     * Binary-compatible entry point retained for clients compiled before hinge-aware destinations
     * were introduced.
     */
    fun calculate(
        pageBounds: Rect,
        pointer: Point,
        direction: TurnDirection,
        origin: CurlOrigin = CurlOrigin.LOWER_OUTER,
        edgeAnchorY: Double? = null,
        customAnchor: Point? = null,
    ): PageCurlGeometry = calculate(
        pageBounds = pageBounds,
        pointer = pointer,
        direction = direction,
        origin = origin,
        edgeAnchorY = edgeAnchorY,
        customAnchor = customAnchor,
        destinationBounds = null,
    )

    fun calculate(
        pageBounds: Rect,
        pointer: Point,
        direction: TurnDirection,
        origin: CurlOrigin = CurlOrigin.LOWER_OUTER,
        /**
         * Touch-down y for [CurlOrigin.OUTER_EDGE]. It is clamped to [pageBounds]. Passing null or
         * NaN falls back to the lower edge; infinities clamp to the corresponding vertical edge.
         * The value is ignored for [CurlOrigin.LOWER_OUTER].
         */
        edgeAnchorY: Double? = null,
        /**
         * Touch-down point for [CurlOrigin.FREE_POINT]. Both coordinates are clamped to
         * [pageBounds]. A missing or NaN coordinate falls back to the page centre. The caller
         * should retain this value unchanged for the complete drag/settle gesture.
         */
        customAnchor: Point? = null,
        /**
         * Optional bounds of the page surface on the other side of a physical hinge or gap.
         * A valid destination has the same size and vertical bounds as [pageBounds], and lies
         * completely to the left for [TurnDirection.FORWARD] or to the right for
         * [TurnDirection.BACKWARD]. Invalid bounds safely fall back to an edge-adjacent page.
         */
        destinationBounds: Rect? = null,
    ): PageCurlGeometry {
        val grabPoint = resolveGrabPoint(
            bounds = pageBounds,
            direction = direction,
            origin = origin,
            edgeAnchorY = edgeAnchorY,
            customAnchor = customAnchor,
        )
        val finalDestinationBounds = resolveFinalDestinationBounds(
            bounds = pageBounds,
            direction = direction,
            destinationBounds = destinationBounds,
        )
        val finalReflection = finalReflection(
            bounds = pageBounds,
            destinationBounds = finalDestinationBounds,
            direction = direction,
        )
        val completionPointer = finalReflection.map(grabPoint).finiteOr(grabPoint)
        if (pageBounds.width <= epsilon || pageBounds.height <= epsilon) {
            return flatGeometry(
                bounds = pageBounds,
                direction = direction,
                origin = origin,
                grabPoint = grabPoint,
                completionPointer = completionPointer,
                finalDestinationBounds = finalDestinationBounds,
                finalReflection = finalReflection,
            )
        }

        val boundedPointer = constrainPointer(
            bounds = pageBounds,
            pointer = pointer,
            direction = direction,
            origin = origin,
            grabPoint = grabPoint,
            completionPointer = completionPointer,
        )
        val constrainedPointer = constrainToBinding(pageBounds, boundedPointer, grabPoint, completionPointer)
        val delta = grabPoint - constrainedPointer
        val distance = hypot(delta.x, delta.y)
        if (!distance.isFinite() || distance <= scaledEpsilon(pageBounds)) {
            return flatGeometry(
                bounds = pageBounds,
                direction = direction,
                origin = origin,
                grabPoint = grabPoint,
                completionPointer = completionPointer,
                finalDestinationBounds = finalDestinationBounds,
                finalReflection = finalReflection,
            )
        }

        val normal = Point(delta.x / distance, delta.y / distance).finiteOr(
            fallbackNormal(direction),
        )
        // a/2 + b/2 cannot overflow for finite a and b, unlike (a + b) / 2.
        val foldMidpoint = Point(
            x = grabPoint.x * 0.5 + constrainedPointer.x * 0.5,
            y = grabPoint.y * 0.5 + constrainedPointer.y * 0.5,
        ).finiteOr(grabPoint)

        val source = pageBounds.corners()
        val liftedPoints = clipHalfPlane(
            polygon = source,
            linePoint = foldMidpoint,
            normal = normal,
            keepPositive = true,
        )
        val flatPoints = clipHalfPlane(
            polygon = source,
            linePoint = foldMidpoint,
            normal = normal,
            keepPositive = false,
        )
        val currentReflection = AffineTransform2D.reflection(foldMidpoint, normal)
        val reflectedPoints = liftedPoints.map { point ->
            currentReflection.map(point).finiteOr(foldMidpoint)
        }
        val lifted = Polygon(liftedPoints)
        val progress = normalizedProgress(grabPoint, constrainedPointer, completionPointer)
        val lift = sin(PI * progress).finiteOrZero().coerceIn(0.0, 1.0)
        val fold = foldSegment(pageBounds, foldMidpoint, normal, grabPoint)
        val destinationToReflectedBack = currentReflection.compose(finalReflection)

        return PageCurlGeometry(
            direction = direction,
            origin = origin,
            pageBounds = pageBounds,
            grabPoint = grabPoint,
            constrainedPointer = constrainedPointer,
            completionPointer = completionPointer,
            finalDestinationBounds = finalDestinationBounds,
            flatFront = Polygon(flatPoints),
            liftedFront = lifted,
            reflectedBack = Polygon(reflectedPoints),
            fold = fold,
            currentReflection = currentReflection,
            finalReflection = finalReflection,
            destinationToReflectedBack = destinationToReflectedBack,
            progress = progress,
            lift = lift,
        )
    }

    private fun constrainPointer(
        bounds: Rect,
        pointer: Point,
        direction: TurnDirection,
        origin: CurlOrigin,
        grabPoint: Point,
        completionPointer: Point,
    ): Point {
        if (origin == CurlOrigin.FREE_POINT) {
            // Bound corrupt coordinates before applying the physical binding constraint below.
            val horizontalLow = minOf(bounds.left, completionPointer.x)
            val horizontalHigh = maxOf(bounds.right, completionPointer.x)
            val minX = saturatingSubtract(horizontalLow, bounds.width)
            val maxX = saturatingAdd(horizontalHigh, bounds.width)
            val minY = saturatingSubtract(bounds.top, bounds.height)
            val maxY = saturatingAdd(bounds.bottom, bounds.height)
            return Point(
                x = pointer.x.clampFinite(minX, maxX, grabPoint.x),
                y = pointer.y.clampFinite(minY, maxY, grabPoint.y),
            )
        }

        val minX = when (direction) {
            TurnDirection.FORWARD -> completionPointer.x
            TurnDirection.BACKWARD -> bounds.left
        }
        val maxX = when (direction) {
            TurnDirection.FORWARD -> bounds.right
            TurnDirection.BACKWARD -> completionPointer.x
        }

        val constrainedX = pointer.x.clampFinite(minX, maxX, grabPoint.x)
        val rawInwardTravel = grabPoint.x - constrainedX
        val inwardTravel = if (rawInwardTravel.isFinite()) {
            abs(rawInwardTravel)
        } else {
            Double.MAX_VALUE
        }
        // Vertical freedom grows with inward travel but remains bounded. This prevents a nearly
        // stationary finger from twisting the entire sheet and keeps diagonal flicks inside a
        // predictable projection envelope around the book.
        val heightLimit = (bounds.height * MAX_VERTICAL_TRAVEL_RATIO).finiteOr(bounds.height)
        val travelLimit = (inwardTravel * VERTICAL_TO_HORIZONTAL_RATIO).finiteOr(heightLimit)
        val verticalTravel = minOf(heightLimit, travelLimit).coerceAtLeast(0.0)
        val minY = maxOf(bounds.top, saturatingSubtract(grabPoint.y, verticalTravel))
        val maxY = minOf(bounds.bottom, saturatingAdd(grabPoint.y, verticalTravel))

        return Point(
            x = constrainedX,
            y = pointer.y.clampFinite(minY, maxY, grabPoint.y),
        )
    }

    private fun constrainToBinding(bounds: Rect, pointer: Point, grab: Point, completion: Point): Point {
        val x = pointer.x.coerceIn(minOf(grab.x, completion.x), maxOf(grab.x, completion.x))
        val spineX = midpoint(grab.x, completion.x)
        val reach = abs(grab.x - spineX)
        val horizontal = abs(x - spineX).coerceAtMost(reach)
        // The pointer must remain inside both circles centred at the binding's endpoints,
        // with radii equal to their distances from the grab point. Otherwise the perpendicular
        // bisector cuts through the binding and reflects part of the spine away from the book.
        // Keep horizontal travel direct; restrict only the vertical excursion. The permitted
        // tilt goes continuously to zero at rest and at completion, including across a hinge.
        val slack = sqrt(((reach - horizontal) * (reach + horizontal)).coerceAtLeast(0.0))
        val minY = bounds.bottom - hypot(grab.y - bounds.bottom, slack)
        val maxY = bounds.top + hypot(grab.y - bounds.top, slack)
        return Point(x, pointer.y.coerceIn(minOf(minY, grab.y), maxOf(maxY, grab.y)))
    }

    private fun clipHalfPlane(
        polygon: List<Point>,
        linePoint: Point,
        normal: Point,
        keepPositive: Boolean,
    ): List<Point> {
        if (polygon.isEmpty()) return emptyList()

        val result = ArrayList<Point>(polygon.size + 2)
        var previous = polygon.last()
        var previousDistance = signedDistance(previous, linePoint, normal)
        var previousInside = isInside(previousDistance, keepPositive)

        polygon.forEach { current ->
            val currentDistance = signedDistance(current, linePoint, normal)
            val currentInside = isInside(currentDistance, keepPositive)

            if (currentInside != previousInside) {
                lineIntersection(previous, current, previousDistance, currentDistance)?.let {
                    result += it
                }
            }
            if (currentInside) result += current

            previous = current
            previousDistance = currentDistance
            previousInside = currentInside
        }
        return deduplicate(result)
    }

    private fun isInside(distance: Double, keepPositive: Boolean): Boolean =
        if (keepPositive) distance >= -epsilon else distance <= epsilon

    private fun lineIntersection(
        start: Point,
        end: Point,
        startDistance: Double,
        endDistance: Double,
    ): Point? {
        val denominator = startDistance - endDistance
        if (!denominator.isFinite() || abs(denominator) <= epsilon) return null
        val t = (startDistance / denominator).finiteOrZero().coerceIn(0.0, 1.0)
        return Point(
            x = start.x + (end.x - start.x) * t,
            y = start.y + (end.y - start.y) * t,
        ).takeIf(Point::isFinite)
    }

    private fun foldSegment(
        bounds: Rect,
        midpoint: Point,
        normal: Point,
        fallback: Point,
    ): FoldLine {
        val tangent = Point(-normal.y, normal.x)
        var tMin = Double.NEGATIVE_INFINITY
        var tMax = Double.POSITIVE_INFINITY

        fun constrainAxis(position: Double, direction: Double, low: Double, high: Double): Boolean {
            if (abs(direction) <= epsilon) return position in (low - epsilon)..(high + epsilon)
            var first = (low - position) / direction
            var second = (high - position) / direction
            if (!first.isFinite() || !second.isFinite()) return false
            if (first > second) {
                val swap = first
                first = second
                second = swap
            }
            tMin = maxOf(tMin, first)
            tMax = minOf(tMax, second)
            return tMin <= tMax
        }

        val intersects =
            constrainAxis(midpoint.x, tangent.x, bounds.left, bounds.right) &&
                constrainAxis(midpoint.y, tangent.y, bounds.top, bounds.bottom)
        if (!intersects || !tMin.isFinite() || !tMax.isFinite()) {
            return FoldLine(fallback, fallback, normal)
        }

        val start = (midpoint + tangent * tMin).finiteOr(fallback)
        val end = (midpoint + tangent * tMax).finiteOr(fallback)
        return FoldLine(start, end, normal)
    }

    private fun signedDistance(point: Point, linePoint: Point, unitNormal: Point): Double {
        val relative = point - linePoint
        val result = relative.x * unitNormal.x + relative.y * unitNormal.y
        return result.finiteOrZero()
    }

    private fun resolveGrabPoint(
        bounds: Rect,
        direction: TurnDirection,
        origin: CurlOrigin,
        edgeAnchorY: Double?,
        customAnchor: Point?,
    ): Point {
        val anchorY = when (origin) {
            CurlOrigin.LOWER_OUTER -> bounds.bottom
            CurlOrigin.OUTER_EDGE -> edgeAnchorY
                ?.clampFinite(bounds.top, bounds.bottom, bounds.bottom)
                ?: bounds.bottom
            CurlOrigin.FREE_POINT -> customAnchor
                ?.y
                ?.clampFinite(bounds.top, bounds.bottom, midpoint(bounds.top, bounds.bottom))
                ?: midpoint(bounds.top, bounds.bottom)
        }
        val anchorX = when (origin) {
            CurlOrigin.LOWER_OUTER,
            CurlOrigin.OUTER_EDGE,
            -> outerX(bounds, direction)
            CurlOrigin.FREE_POINT -> customAnchor
                ?.x
                ?.clampFinite(bounds.left, bounds.right, midpoint(bounds.left, bounds.right))
                ?: midpoint(bounds.left, bounds.right)
        }
        return Point(anchorX, anchorY)
    }

    private fun resolveFinalDestinationBounds(
        bounds: Rect,
        direction: TurnDirection,
        destinationBounds: Rect?,
    ): Rect {
        val tolerance = destinationBounds?.let { destination ->
            maxOf(
                epsilon,
                maxOf(
                    bounds.width,
                    bounds.height,
                    destination.width,
                    destination.height,
                ) * DESTINATION_RELATIVE_TOLERANCE,
            )
        } ?: epsilon
        if (destinationBounds != null &&
            abs(destinationBounds.width - bounds.width) <= tolerance &&
            abs(destinationBounds.top - bounds.top) <= tolerance &&
            abs(destinationBounds.bottom - bounds.bottom) <= tolerance &&
            when (direction) {
                TurnDirection.FORWARD -> destinationBounds.right <= bounds.left + tolerance
                TurnDirection.BACKWARD -> destinationBounds.left >= bounds.right - tolerance
            }
        ) {
            return destinationBounds
        }
        return adjacentDestinationBounds(bounds, direction)
    }

    private fun adjacentDestinationBounds(bounds: Rect, direction: TurnDirection): Rect =
        when (direction) {
            TurnDirection.FORWARD -> Rect(
                left = saturatingSubtract(bounds.left, bounds.width),
                top = bounds.top,
                right = bounds.left,
                bottom = bounds.bottom,
            )
            TurnDirection.BACKWARD -> Rect(
                left = bounds.right,
                top = bounds.top,
                right = saturatingAdd(bounds.right, bounds.width),
                bottom = bounds.bottom,
            )
        }

    private fun finalReflection(
        bounds: Rect,
        destinationBounds: Rect,
        direction: TurnDirection,
    ): AffineTransform2D {
        val reflectionAxisX = when (direction) {
            TurnDirection.FORWARD -> midpoint(bounds.left, destinationBounds.right)
            TurnDirection.BACKWARD -> midpoint(bounds.right, destinationBounds.left)
        }
        return AffineTransform2D.reflection(
            linePoint = Point(reflectionAxisX, midpoint(bounds.top, bounds.bottom)),
            normal = fallbackNormal(direction),
        )
    }

    private fun normalizedProgress(
        grabPoint: Point,
        pointer: Point,
        completionPointer: Point,
    ): Double {
        val completion = completionPointer - grabPoint
        val completionLengthSquared =
            completion.x * completion.x + completion.y * completion.y
        if (!completionLengthSquared.isFinite() || completionLengthSquared <= epsilon * epsilon) {
            return 0.0
        }
        val current = pointer - grabPoint
        val projection =
            (current.x * completion.x + current.y * completion.y) / completionLengthSquared
        return projection.finiteOrZero().coerceIn(0.0, 1.0)
    }

    private fun outerX(bounds: Rect, direction: TurnDirection): Double = when (direction) {
        TurnDirection.FORWARD -> bounds.right
        TurnDirection.BACKWARD -> bounds.left
    }

    private fun fallbackNormal(direction: TurnDirection): Point = when (direction) {
        TurnDirection.FORWARD -> Point(1.0, 0.0)
        TurnDirection.BACKWARD -> Point(-1.0, 0.0)
    }

    private fun flatGeometry(
        bounds: Rect,
        direction: TurnDirection,
        origin: CurlOrigin,
        grabPoint: Point,
        completionPointer: Point,
        finalDestinationBounds: Rect,
        finalReflection: AffineTransform2D,
    ): PageCurlGeometry = PageCurlGeometry(
        direction = direction,
        origin = origin,
        pageBounds = bounds,
        grabPoint = grabPoint,
        constrainedPointer = grabPoint,
        completionPointer = completionPointer,
        finalDestinationBounds = finalDestinationBounds,
        flatFront = Polygon(deduplicate(bounds.corners())),
        liftedFront = Polygon.EMPTY,
        reflectedBack = Polygon.EMPTY,
        fold = FoldLine(
            start = Point(grabPoint.x, bounds.top),
            end = Point(grabPoint.x, bounds.bottom),
            normal = fallbackNormal(direction),
        ),
        currentReflection = AffineTransform2D.IDENTITY,
        finalReflection = finalReflection,
        destinationToReflectedBack = finalReflection,
        progress = 0.0,
        lift = 0.0,
    )

    private fun deduplicate(points: List<Point>): List<Point> {
        if (points.isEmpty()) return points
        val result = ArrayList<Point>(points.size)
        points.forEach { point ->
            if (result.lastOrNull()?.isNear(point) != true) result += point
        }
        if (result.size > 1 && result.first().isNear(result.last())) result.removeAt(result.lastIndex)
        return result
    }

    private fun Point.isNear(other: Point): Boolean =
        abs(x - other.x) <= epsilon && abs(y - other.y) <= epsilon

    private fun Point.finiteOr(fallback: Point): Point = if (isFinite) this else fallback

    private fun Double.finiteOr(fallback: Double): Double = if (isFinite()) this else fallback

    private fun Double.finiteOrZero(): Double = if (isFinite()) this else 0.0

    private fun midpoint(first: Double, second: Double): Double =
        (first * 0.5 + second * 0.5).finiteOrZero()

    private fun Double.clampFinite(minimum: Double, maximum: Double, fallback: Double): Double =
        when {
            isNaN() -> fallback
            this == Double.NEGATIVE_INFINITY -> minimum
            this == Double.POSITIVE_INFINITY -> maximum
            else -> coerceIn(minimum, maximum)
        }

    private fun saturatingAdd(first: Double, second: Double): Double {
        val result = first + second
        return when {
            result.isFinite() -> result
            first >= 0.0 && second >= 0.0 -> Double.MAX_VALUE
            else -> -Double.MAX_VALUE
        }
    }

    private fun saturatingSubtract(first: Double, second: Double): Double {
        val result = first - second
        return when {
            result.isFinite() -> result
            first >= 0.0 && second < 0.0 -> Double.MAX_VALUE
            else -> -Double.MAX_VALUE
        }
    }

    private fun scaledEpsilon(bounds: Rect): Double =
        maxOf(epsilon, maxOf(bounds.width, bounds.height) * 1e-12).finiteOrZero()

    companion object {
        private const val DEFAULT_EPSILON = 1e-7
        private const val DESTINATION_RELATIVE_TOLERANCE = 1e-6
        private const val MAX_VERTICAL_TRAVEL_RATIO = 0.22
        private const val VERTICAL_TO_HORIZONTAL_RATIO = 0.72
    }
}
