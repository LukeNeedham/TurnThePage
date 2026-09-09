package com.avstaim.listai.reader.rendering.geometry

import com.avstaim.listai.reader.rendering.turn.CurlOrigin
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PageCurlBandGeometryEngineTest {
    private val curlEngine = PageCurlGeometryEngine()
    private val bandEngine = PageCurlBandGeometryEngine()
    private val page = Rect(left = 0.0, top = 0.0, right = 100.0, bottom = 160.0)

    @Test
    fun `mid turn creates ten finite strips in a narrow band`() {
        val geometry = middleTurn(TurnDirection.FORWARD)
        val band = bandEngine.calculate(geometry)

        assertTrue(band.isActive)
        assertTrue(band.isFinite)
        assertEquals(10, band.strips.size)
        assertEquals(10.0, band.width, TOLERANCE)
        assertEquals(1.0, band.lift, TOLERANCE)
        val rigidRemainder = requireNotNull(band.rigidRemainder)
        assertTrue(rigidRemainder.isFinite)
        assertEquals(40.0 * page.height, rigidRemainder.source.area, AREA_TOLERANCE)
        assertEquals(rigidRemainder.source.area, rigidRemainder.deformed.area, AREA_TOLERANCE)
        band.strips.forEachIndexed { index, strip ->
            assertEquals(index, strip.index)
            assertEquals(index.toDouble(), strip.normalStart, TOLERANCE)
            assertEquals(index + 1.0, strip.normalEnd, TOLERANCE)
            assertTrue(strip.isFinite)
            assertTrue(strip.source.points.size >= 3)
            assertTrue(strip.deformed.points.size >= 3)
        }
    }

    @Test
    fun `band is absent on both flat endpoints`() {
        TurnDirection.entries.forEach { direction ->
            val startPointer = when (direction) {
                TurnDirection.FORWARD -> Point(page.right, 80.0)
                TurnDirection.BACKWARD -> Point(page.left, 80.0)
            }
            val start = calculateEdgeTurn(direction, startPointer)
            val complete = calculateEdgeTurn(direction, start.completionPointer)

            assertEquals(0.0, start.progress, TOLERANCE)
            assertEquals(1.0, complete.progress, TOLERANCE)
            assertEquals(PageCurlBandGeometry.EMPTY, bandEngine.calculate(start))
            assertEquals(PageCurlBandGeometry.EMPTY, bandEngine.calculate(complete))
        }
    }

    @Test
    fun `piecewise transforms meet at every boundary and join ordinary reflection`() {
        val geometry = middleTurn(TurnDirection.FORWARD)
        val band = bandEngine.calculate(geometry)
        val linePoint = Point(50.0, 73.0)

        // The fold itself stays fixed.
        assertPointEquals(
            linePoint,
            band.strips.first().sourceToDeformed.map(linePoint),
        )

        band.strips.zipWithNext().forEach { (before, after) ->
            val boundary = Point(50.0 + before.normalEnd, linePoint.y)
            assertPointEquals(
                before.sourceToDeformed.map(boundary),
                after.sourceToDeformed.map(boundary),
            )
        }

        // The outer band edge is exactly the hard-fold reflection, so the remaining sheet can
        // continue using PageCurlGeometry.currentReflection without a positional seam.
        val outerBoundary = Point(50.0 + band.width, linePoint.y)
        val rigidRemainder = requireNotNull(band.rigidRemainder)
        assertEquals(geometry.currentReflection, rigidRemainder.sourceToDeformed)
        assertEquals(
            geometry.destinationToReflectedBack,
            rigidRemainder.destinationToDeformed,
        )
        assertPointEquals(
            rigidRemainder.sourceToDeformed.map(outerBoundary),
            band.strips.last().sourceToDeformed.map(outerBoundary),
        )
        listOf(0.0, 160.0).forEach { y ->
            val reflectedSeamPoint = geometry.currentReflection.map(
                Point(50.0 + band.width, y),
            )
            assertContainsPoint(band.strips.last().deformed, reflectedSeamPoint)
            assertContainsPoint(rigidRemainder.deformed, reflectedSeamPoint)
        }
    }

    @Test
    fun `curl strips and rigid remainder partition lifted source without area overlap`() {
        val forwardDiagonal = curlEngine.calculate(
            pageBounds = page,
            pointer = Point(24.0, 99.0),
            direction = TurnDirection.FORWARD,
            origin = CurlOrigin.OUTER_EDGE,
            edgeAnchorY = 61.0,
        )
        val backwardDiagonal = curlEngine.calculate(
            pageBounds = page,
            pointer = mirror(Point(24.0, 99.0)),
            direction = TurnDirection.BACKWARD,
            origin = CurlOrigin.OUTER_EDGE,
            edgeAnchorY = 61.0,
        )
        val freePoint = curlEngine.calculate(
            pageBounds = page,
            pointer = Point(26.0, 103.0),
            direction = TurnDirection.FORWARD,
            origin = CurlOrigin.FREE_POINT,
            customAnchor = Point(72.0, 44.0),
        )

        listOf(
            middleTurn(TurnDirection.FORWARD),
            middleTurn(TurnDirection.BACKWARD),
            forwardDiagonal,
            backwardDiagonal,
            freePoint,
        ).forEach { geometry ->
            val band = bandEngine.calculate(geometry, stripCount = 12, minimumWidth = 13.0)
            val remainder = requireNotNull(band.rigidRemainder)
            val partitionArea = band.strips.sumOf { it.source.area } + remainder.source.area

            // Every piece is constrained to a disjoint normal-distance interval. Together with
            // equal summed area this proves coverage without positive-area overlap; only their
            // shared boundary lines can coincide.
            val linePoint = Point(
                x = geometry.grabPoint.x * 0.5 + geometry.constrainedPointer.x * 0.5,
                y = geometry.grabPoint.y * 0.5 + geometry.constrainedPointer.y * 0.5,
            )
            val normalLength = kotlin.math.hypot(
                geometry.fold.normal.x,
                geometry.fold.normal.y,
            )
            val normal = Point(
                geometry.fold.normal.x / normalLength,
                geometry.fold.normal.y / normalLength,
            )
            band.strips.forEach { strip ->
                strip.source.points.forEach { point ->
                    val distance = signedDistance(point, linePoint, normal)
                    assertTrue(distance >= strip.normalStart - DISTANCE_TOLERANCE)
                    assertTrue(distance <= strip.normalEnd + DISTANCE_TOLERANCE)
                }
            }
            remainder.source.points.forEach { point ->
                assertTrue(
                    signedDistance(point, linePoint, normal) >=
                        band.width - DISTANCE_TOLERANCE,
                )
            }
            assertEquals(geometry.liftedFront.area, partitionArea, AREA_TOLERANCE)
        }
    }

    @Test
    fun `band uses infinite fold midpoint when clipped fold segment misses page`() {
        val anchor = Point(50.0, 80.0)
        val geometry = curlEngine.calculate(
            pageBounds = page,
            pointer = Point(0.0, 320.0),
            direction = TurnDirection.FORWARD,
            origin = CurlOrigin.FREE_POINT,
            customAnchor = anchor,
        )
        // PageCurlGeometry represents a non-intersecting clipped segment by its fallback point.
        assertEquals(anchor, geometry.fold.start)
        assertEquals(anchor, geometry.fold.end)

        val band = bandEngine.calculate(geometry)
        assertTrue(band.isActive)
        val linePoint = Point(
            x = geometry.grabPoint.x * 0.5 + geometry.constrainedPointer.x * 0.5,
            y = geometry.grabPoint.y * 0.5 + geometry.constrainedPointer.y * 0.5,
        )
        val normalLength = kotlin.math.hypot(geometry.fold.normal.x, geometry.fold.normal.y)
        val normal = Point(
            geometry.fold.normal.x / normalLength,
            geometry.fold.normal.y / normalLength,
        )
        val outerBoundary = Point(
            x = linePoint.x + normal.x * band.width,
            y = linePoint.y + normal.y * band.width,
        )

        assertPointEquals(linePoint, band.strips.first().sourceToDeformed.map(linePoint))
        assertPointEquals(
            geometry.currentReflection.map(outerBoundary),
            band.strips.last().sourceToDeformed.map(outerBoundary),
        )
    }

    @Test
    fun `mid turn band bows away from hard reflection using documented curve`() {
        val geometry = middleTurn(TurnDirection.FORWARD)
        val band = bandEngine.calculate(geometry)
        val u = 0.2
        val source = Point(50.0 + band.width * u, 80.0)
        val ordinary = geometry.currentReflection.map(source)
        val actual = band.strips[1].sourceToDeformed.map(source)
        val expectedNormal = band.width * (-u + 2.0 * u * (1.0 - u) * (1.0 - u))

        assertPointEquals(Point(50.0 + expectedNormal, 80.0), actual)
        assertTrue(abs(actual.x - ordinary.x) > 2.0)
    }

    @Test
    fun `destination transform carries final page content onto each curved strip`() {
        val geometry = middleTurn(TurnDirection.FORWARD)
        val band = bandEngine.calculate(geometry)

        band.strips.forEach { strip ->
            val distance = (strip.normalStart + strip.normalEnd) * 0.5
            val source = Point(50.0 + distance, 80.0)
            val destination = geometry.finalReflection.map(source)

            assertPointEquals(
                strip.sourceToDeformed.map(source),
                strip.destinationToDeformed.map(destination),
            )
        }
    }

    @Test
    fun `forward and backward bands are horizontal mirrors`() {
        val forward = bandEngine.calculate(middleTurn(TurnDirection.FORWARD))
        val backward = bandEngine.calculate(middleTurn(TurnDirection.BACKWARD))

        assertEquals(forward.width, backward.width, TOLERANCE)
        assertEquals(forward.lift, backward.lift, TOLERANCE)
        assertEquals(forward.strips.size, backward.strips.size)
        forward.strips.zip(backward.strips).forEach { (forwardStrip, backwardStrip) ->
            assertEquals(forwardStrip.normalStart, backwardStrip.normalStart, TOLERANCE)
            assertEquals(forwardStrip.normalEnd, backwardStrip.normalEnd, TOLERANCE)
            assertPointSetsEqual(forwardStrip.source.points.map(::mirror), backwardStrip.source.points)
            assertPointSetsEqual(
                forwardStrip.ordinaryReflection.points.map(::mirror),
                backwardStrip.ordinaryReflection.points,
            )
            assertPointSetsEqual(
                forwardStrip.deformed.points.map(::mirror),
                backwardStrip.deformed.points,
            )
        }
        val forwardRemainder = requireNotNull(forward.rigidRemainder)
        val backwardRemainder = requireNotNull(backward.rigidRemainder)
        assertPointSetsEqual(
            forwardRemainder.source.points.map(::mirror),
            backwardRemainder.source.points,
        )
        assertPointSetsEqual(
            forwardRemainder.deformed.points.map(::mirror),
            backwardRemainder.deformed.points,
        )
    }

    @Test
    fun `strip count and minimum width inputs are safely bounded`() {
        val geometry = middleTurn(TurnDirection.FORWARD)
        val tooFew = bandEngine.calculate(
            geometry = geometry,
            stripCount = Int.MIN_VALUE,
            minimumWidth = Double.NaN,
        )
        val tooMany = bandEngine.calculate(
            geometry = geometry,
            stripCount = Int.MAX_VALUE,
            minimumWidth = 25.0,
        )
        val infiniteWidth = bandEngine.calculate(
            geometry = geometry,
            minimumWidth = Double.POSITIVE_INFINITY,
        )

        assertEquals(PageCurlBandGeometryEngine.MIN_STRIP_COUNT, tooFew.strips.size)
        assertEquals(10.0, tooFew.width, TOLERANCE)
        assertEquals(PageCurlBandGeometryEngine.MAX_STRIP_COUNT, tooMany.strips.size)
        assertEquals(25.0, tooMany.width, TOLERANCE)
        assertEquals(50.0, infiniteWidth.width, TOLERANCE)
        assertTrue(tooFew.isFinite)
        assertTrue(tooMany.isFinite)
        assertTrue(infiniteWidth.isFinite)
    }

    @Test
    fun `hostile pointer and malformed geometry never produce non finite band output`() {
        val values = listOf(
            Double.NaN,
            Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY,
            -Double.MAX_VALUE,
            Double.MAX_VALUE,
            -1e200,
            0.0,
            1e200,
        )

        TurnDirection.entries.forEach { direction ->
            values.forEach { x ->
                values.forEach { y ->
                    val geometry = curlEngine.calculate(
                        pageBounds = page,
                        pointer = Point(x, y),
                        direction = direction,
                        origin = CurlOrigin.FREE_POINT,
                        customAnchor = Point(Double.NaN, Double.POSITIVE_INFINITY),
                    )
                    val band = bandEngine.calculate(
                        geometry = geometry,
                        stripCount = if (x.hashCode() % 2 == 0) Int.MIN_VALUE else Int.MAX_VALUE,
                        minimumWidth = y,
                    )

                    assertTrue("Non-finite band for $direction at ($x, $y)", band.isFinite)
                    assertTrue(
                        band.strips.isEmpty() ||
                            band.strips.size in PageCurlBandGeometryEngine.MIN_STRIP_COUNT..
                            PageCurlBandGeometryEngine.MAX_STRIP_COUNT,
                    )
                }
            }
        }

        val invalid = middleTurn(TurnDirection.FORWARD).copy(
            fold = FoldLine(
                start = Point(Double.NaN, 0.0),
                end = Point(50.0, 160.0),
                normal = Point(Double.NaN, Double.POSITIVE_INFINITY),
            ),
        )
        assertFalse(invalid.isFinite)
        assertEquals(PageCurlBandGeometry.EMPTY, bandEngine.calculate(invalid))
    }

    @Test
    fun `same page curl always produces identical strips`() {
        val geometry = curlEngine.calculate(
            pageBounds = page,
            pointer = Point(23.0, 104.0),
            direction = TurnDirection.FORWARD,
            origin = CurlOrigin.OUTER_EDGE,
            edgeAnchorY = 61.0,
        )

        assertEquals(
            bandEngine.calculate(geometry, stripCount = 11, minimumWidth = 14.0),
            bandEngine.calculate(geometry, stripCount = 11, minimumWidth = 14.0),
        )
    }

    private fun middleTurn(direction: TurnDirection): PageCurlGeometry {
        val pointer = when (direction) {
            TurnDirection.FORWARD -> Point(0.0, 80.0)
            TurnDirection.BACKWARD -> Point(100.0, 80.0)
        }
        return calculateEdgeTurn(direction, pointer)
    }

    private fun calculateEdgeTurn(
        direction: TurnDirection,
        pointer: Point,
    ): PageCurlGeometry = curlEngine.calculate(
        pageBounds = page,
        pointer = pointer,
        direction = direction,
        origin = CurlOrigin.OUTER_EDGE,
        edgeAnchorY = 80.0,
    )

    private fun mirror(point: Point): Point = Point(page.left + page.right - point.x, point.y)

    private fun signedDistance(point: Point, linePoint: Point, normal: Point): Double =
        (point.x - linePoint.x) * normal.x + (point.y - linePoint.y) * normal.y

    private fun assertContainsPoint(polygon: Polygon, expected: Point) {
        assertTrue(
            "No matching point for $expected in ${polygon.points}",
            polygon.points.any { actual ->
                abs(expected.x - actual.x) <= TOLERANCE &&
                    abs(expected.y - actual.y) <= TOLERANCE
            },
        )
    }

    private fun assertPointEquals(expected: Point, actual: Point) {
        assertEquals(expected.x, actual.x, TOLERANCE)
        assertEquals(expected.y, actual.y, TOLERANCE)
    }

    private fun assertPointSetsEqual(expected: List<Point>, actual: List<Point>) {
        assertEquals("Polygon vertex counts differ", expected.size, actual.size)
        val unmatched = actual.toMutableList()
        expected.forEach { expectedPoint ->
            val matchIndex = unmatched.indexOfFirst { actualPoint ->
                abs(expectedPoint.x - actualPoint.x) <= TOLERANCE &&
                    abs(expectedPoint.y - actualPoint.y) <= TOLERANCE
            }
            assertTrue("No matching point for $expectedPoint in $actual", matchIndex >= 0)
            unmatched.removeAt(matchIndex)
        }
    }

    companion object {
        private const val TOLERANCE = 1e-6
        private const val DISTANCE_TOLERANCE = 1e-5
        private const val AREA_TOLERANCE = 1e-3
    }
}
