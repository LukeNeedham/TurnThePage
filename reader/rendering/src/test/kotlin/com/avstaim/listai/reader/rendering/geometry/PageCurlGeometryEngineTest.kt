package com.avstaim.listai.reader.rendering.geometry

import com.avstaim.listai.reader.rendering.turn.CurlOrigin
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PageCurlGeometryEngineTest {
    private val engine = PageCurlGeometryEngine()
    private val page = Rect(left = 0.0, top = 0.0, right = 100.0, bottom = 160.0)

    @Test
    fun `pointer at outer corner produces a flat page`() {
        val geometry = engine.calculate(
            pageBounds = page,
            pointer = Point(100.0, 160.0),
            direction = TurnDirection.FORWARD,
        )

        assertTrue(geometry.isFinite)
        assertEquals(Point(100.0, 160.0), geometry.grabPoint)
        assertEquals(geometry.grabPoint, geometry.constrainedPointer)
        assertEquals(0.0, geometry.progress, TOLERANCE)
        assertEquals(0.0, geometry.lift, TOLERANCE)
        assertEquals(page.area, geometry.flatFront.area, TOLERANCE)
        assertTrue(geometry.liftedFront.points.isEmpty())
        assertTrue(geometry.reflectedBack.points.isEmpty())
    }

    @Test
    fun `extreme pointer is constrained and complete geometry stays finite`() {
        val geometry = engine.calculate(
            pageBounds = page,
            pointer = Point(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY),
            direction = TurnDirection.FORWARD,
        )

        assertTrue(geometry.isFinite)
        assertEquals(Point(-100.0, 160.0), geometry.constrainedPointer)
        assertEquals(1.0, geometry.progress, TOLERANCE)
        assertEquals(0.0, geometry.lift, TOLERANCE)
        assertTrue(geometry.flatFront.area <= TOLERANCE)
        assertEquals(page.area, geometry.liftedFront.area, TOLERANCE)
        assertEquals(page.area, geometry.reflectedBack.area, TOLERANCE)
    }

    @Test
    fun `forward and backward curls are horizontal mirrors`() {
        val forwardPointer = Point(20.0, 90.0)
        val backwardPointer = mirror(forwardPointer)

        val forward = engine.calculate(page, forwardPointer, TurnDirection.FORWARD)
        val backward = engine.calculate(page, backwardPointer, TurnDirection.BACKWARD)

        assertEquals(forward.progress, backward.progress, TOLERANCE)
        assertEquals(forward.lift, backward.lift, TOLERANCE)
        assertPointSetsEqual(forward.flatFront.points.map(::mirror), backward.flatFront.points)
        assertPointSetsEqual(forward.liftedFront.points.map(::mirror), backward.liftedFront.points)
        assertPointSetsEqual(forward.reflectedBack.points.map(::mirror), backward.reflectedBack.points)
        assertPointSetsEqual(
            listOf(mirror(forward.fold.start), mirror(forward.fold.end)),
            listOf(backward.fold.start, backward.fold.end),
        )
        assertEquals(-forward.fold.normal.x, backward.fold.normal.x, TOLERANCE)
        assertEquals(forward.fold.normal.y, backward.fold.normal.y, TOLERANCE)
    }

    @Test
    fun `all outputs stay finite for hostile pointer values`() {
        val coordinates = listOf(
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
            coordinates.forEach { x ->
                coordinates.forEach { y ->
                    val geometry = engine.calculate(page, Point(x, y), direction)
                    assertTrue("Non-finite geometry for $direction at ($x, $y)", geometry.isFinite)
                    assertTrue(geometry.progress in 0.0..1.0)
                    assertTrue(geometry.lift in 0.0..1.0)
                }
            }
        }
    }

    @Test
    fun `zero sized pages return finite flat geometry`() {
        val points = listOf(
            Rect(12.0, 20.0, 12.0, 80.0),
            Rect(12.0, 20.0, 80.0, 20.0),
            Rect(12.0, 20.0, 12.0, 20.0),
        )

        points.forEach { bounds ->
            TurnDirection.entries.forEach { direction ->
                val geometry = engine.calculate(
                    bounds,
                    Point(Double.NaN, Double.POSITIVE_INFINITY),
                    direction,
                )
                assertTrue(geometry.isFinite)
                assertEquals(0.0, geometry.progress, TOLERANCE)
                assertEquals(0.0, geometry.lift, TOLERANCE)
                assertTrue(geometry.liftedFront.points.isEmpty())
                assertTrue(geometry.reflectedBack.points.isEmpty())
            }
        }
    }

    @Test
    fun `fold normal is unit length and points toward grab corner`() {
        TurnDirection.entries.forEach { direction ->
            val pointer = when (direction) {
                TurnDirection.FORWARD -> Point(25.0, 120.0)
                TurnDirection.BACKWARD -> Point(75.0, 120.0)
            }
            val geometry = engine.calculate(page, pointer, direction)
            val normal = geometry.fold.normal
            val length = kotlin.math.hypot(normal.x, normal.y)
            val midpoint = Point(
                (geometry.fold.start.x + geometry.fold.end.x) * 0.5,
                (geometry.fold.start.y + geometry.fold.end.y) * 0.5,
            )
            val towardGrab = geometry.grabPoint - midpoint
            val dot = towardGrab.x * normal.x + towardGrab.y * normal.y

            assertEquals(1.0, length, TOLERANCE)
            assertTrue("Normal must point to the original outer corner", dot >= -TOLERANCE)
        }
    }

    @Test
    fun `half plane clipping conserves source area and reflection conserves lifted area`() {
        val pointerPositions = listOf(
            Point(99.0, 159.0),
            Point(75.0, 145.0),
            Point(25.0, 80.0),
            Point(-25.0, 15.0),
            Point(-100.0, 160.0),
        )

        TurnDirection.entries.forEach { direction ->
            pointerPositions.forEach { forwardPointer ->
                val pointer = if (direction == TurnDirection.FORWARD) {
                    forwardPointer
                } else {
                    mirror(forwardPointer)
                }
                val geometry = engine.calculate(page, pointer, direction)

                assertEquals(
                    page.area,
                    geometry.flatFront.area + geometry.liftedFront.area,
                    AREA_TOLERANCE,
                )
                assertEquals(
                    geometry.liftedFront.area,
                    geometry.reflectedBack.area,
                    AREA_TOLERANCE,
                )
            }
        }
    }

    @Test
    fun `outer edge origin uses clamped touch down y as grab point`() {
        val middle = engine.calculate(
            pageBounds = page,
            pointer = Point(70.0, 64.0),
            direction = TurnDirection.FORWARD,
            origin = CurlOrigin.OUTER_EDGE,
            edgeAnchorY = 64.0,
        )
        val above = engine.calculate(
            pageBounds = page,
            pointer = Point(70.0, 0.0),
            direction = TurnDirection.FORWARD,
            origin = CurlOrigin.OUTER_EDGE,
            edgeAnchorY = -50.0,
        )
        val below = engine.calculate(
            pageBounds = page,
            pointer = Point(70.0, 160.0),
            direction = TurnDirection.FORWARD,
            origin = CurlOrigin.OUTER_EDGE,
            edgeAnchorY = 500.0,
        )

        assertEquals(Point(page.right, 64.0), middle.grabPoint)
        assertEquals(Point(page.right, page.top), above.grabPoint)
        assertEquals(Point(page.right, page.bottom), below.grabPoint)
    }

    @Test
    fun `outer edge curl mirrors pointer fold and polygons backward`() {
        val anchorY = 61.0
        val forwardPointer = Point(17.0, 88.0)
        val backwardPointer = mirror(forwardPointer)

        val forward = engine.calculate(
            page,
            forwardPointer,
            TurnDirection.FORWARD,
            CurlOrigin.OUTER_EDGE,
            anchorY,
        )
        val backward = engine.calculate(
            page,
            backwardPointer,
            TurnDirection.BACKWARD,
            CurlOrigin.OUTER_EDGE,
            anchorY,
        )

        assertEquals(mirror(forward.grabPoint), backward.grabPoint)
        assertEquals(mirror(forward.constrainedPointer), backward.constrainedPointer)
        assertEquals(forward.progress, backward.progress, TOLERANCE)
        assertEquals(forward.lift, backward.lift, TOLERANCE)
        assertPointSetsEqual(forward.flatFront.points.map(::mirror), backward.flatFront.points)
        assertPointSetsEqual(forward.liftedFront.points.map(::mirror), backward.liftedFront.points)
        assertPointSetsEqual(forward.reflectedBack.points.map(::mirror), backward.reflectedBack.points)
        assertPointSetsEqual(
            listOf(mirror(forward.fold.start), mirror(forward.fold.end)),
            listOf(backward.fold.start, backward.fold.end),
        )
        assertEquals(-forward.fold.normal.x, backward.fold.normal.x, TOLERANCE)
        assertEquals(forward.fold.normal.y, backward.fold.normal.y, TOLERANCE)
    }

    @Test
    fun `hostile outer edge anchors are clamped to finite page bounds`() {
        val anchors = listOf(
            null,
            Double.NaN,
            Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY,
            -Double.MAX_VALUE,
            Double.MAX_VALUE,
        )

        TurnDirection.entries.forEach { direction ->
            anchors.forEach { anchor ->
                val geometry = engine.calculate(
                    pageBounds = page,
                    pointer = Point(Double.NaN, Double.POSITIVE_INFINITY),
                    direction = direction,
                    origin = CurlOrigin.OUTER_EDGE,
                    edgeAnchorY = anchor,
                )

                assertTrue(geometry.isFinite)
                assertTrue(geometry.grabPoint.y in page.top..page.bottom)
                assertTrue(geometry.constrainedPointer.y in page.top..page.bottom)
                assertEquals(
                    if (direction == TurnDirection.FORWARD) page.right else page.left,
                    geometry.grabPoint.x,
                    TOLERANCE,
                )
            }
        }
    }

    @Test
    fun `fixed edge anchor stays deterministic and continuous while dragging both ways`() {
        val anchorY = 73.0
        val inwardPointers = (0..200).map { step -> Point(100.0 - step, anchorY) }
        val inward = inwardPointers.map { pointer ->
            engine.calculate(
                page,
                pointer,
                TurnDirection.FORWARD,
                CurlOrigin.OUTER_EDGE,
                anchorY,
            )
        }
        val outward = inwardPointers.asReversed().map { pointer ->
            engine.calculate(
                page,
                pointer,
                TurnDirection.FORWARD,
                CurlOrigin.OUTER_EDGE,
                anchorY,
            )
        }

        inward.zipWithNext().forEach { (before, after) ->
            assertTrue(after.progress + TOLERANCE >= before.progress)
            assertTrue(abs(after.progress - before.progress) <= 0.006)
            assertTrue(abs(after.lift - before.lift) <= 0.017)
            assertTrue(abs(after.fold.start.x - before.fold.start.x) <= 0.51)
            assertTrue(abs(after.fold.end.x - before.fold.end.x) <= 0.51)
        }
        assertEquals(inward.asReversed(), outward)
    }

    @Test
    fun `vertical pointer freedom grows from anchor and stays bounded`() {
        val anchorY = 80.0
        val stationary = engine.calculate(
            page,
            Point(page.right, page.top),
            TurnDirection.FORWARD,
            CurlOrigin.OUTER_EDGE,
            anchorY,
        )
        val diagonal = engine.calculate(
            page,
            Point(page.left, page.top),
            TurnDirection.FORWARD,
            CurlOrigin.OUTER_EDGE,
            anchorY,
        )

        assertEquals(anchorY, stationary.constrainedPointer.y, TOLERANCE)
        assertTrue(diagonal.constrainedPointer.y < anchorY)
        assertTrue(
            abs(diagonal.constrainedPointer.y - anchorY) <= page.height * 0.22 + TOLERANCE,
        )
    }

    @Test
    fun `reflected sheet remains in a bounded projection envelope`() {
        val anchorPositions = listOf(page.top, 40.0, 80.0, 120.0, page.bottom)
        val pointerPositions = listOf(
            Point(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY),
            Point(-100.0, 0.0),
            Point(0.0, 80.0),
            Point(50.0, 160.0),
            Point(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
        )
        val horizontalMargin = page.width * 1.5
        val verticalMargin = page.height * 0.25

        TurnDirection.entries.forEach { direction ->
            anchorPositions.forEach { anchorY ->
                pointerPositions.forEach { forwardPointer ->
                    val pointer = if (direction == TurnDirection.FORWARD) {
                        forwardPointer
                    } else {
                        mirror(forwardPointer)
                    }
                    val geometry = engine.calculate(
                        page,
                        pointer,
                        direction,
                        CurlOrigin.OUTER_EDGE,
                        anchorY,
                    )

                    geometry.reflectedBack.points.forEach { point ->
                        assertTrue(point.x in page.left - horizontalMargin..page.right + horizontalMargin)
                        assertTrue(point.y in page.top - verticalMargin..page.bottom + verticalMargin)
                    }
                }
            }
        }
    }

    @Test
    fun `free point is flat when pointer equals arbitrary touch down anchor`() {
        val anchor = Point(63.0, 47.0)
        val geometry = engine.calculate(
            pageBounds = page,
            pointer = anchor,
            direction = TurnDirection.FORWARD,
            origin = CurlOrigin.FREE_POINT,
            customAnchor = anchor,
        )

        assertEquals(anchor, geometry.grabPoint)
        assertEquals(anchor, geometry.constrainedPointer)
        assertEquals(0.0, geometry.progress, TOLERANCE)
        assertEquals(0.0, geometry.lift, TOLERANCE)
        assertEquals(page.area, geometry.flatFront.area, AREA_TOLERANCE)
        assertTrue(geometry.liftedFront.points.isEmpty())
        assertTrue(geometry.reflectedBack.points.isEmpty())
        assertEquals(AffineTransform2D.IDENTITY, geometry.currentReflection)
    }

    @Test
    fun `free point reflection follows physically reachable pointer`() {
        val anchor = Point(72.0, 44.0)
        val pointer = Point(31.0, 50.0)
        val geometry = engine.calculate(
            pageBounds = page,
            pointer = pointer,
            direction = TurnDirection.FORWARD,
            origin = CurlOrigin.FREE_POINT,
            customAnchor = anchor,
        )

        assertEquals(pointer, geometry.constrainedPointer)
        assertPointEquals(pointer, geometry.currentReflection.map(anchor))

        val sourceSamples = page.corners() + anchor + Point(40.0, 80.0)
        sourceSamples.forEach { sourcePoint ->
            val pointInFinalDestination = geometry.finalReflection.map(sourcePoint)
            val expectedCurrentPoint = geometry.currentReflection.map(sourcePoint)
            val mappedFromFinal = geometry.destinationToReflectedBack.map(
                pointInFinalDestination,
            )
            assertPointEquals(expectedCurrentPoint, mappedFromFinal)
        }
    }

    @Test
    fun `free point completion maps complete source page exactly into neighbour`() {
        val anchor = Point(72.0, 44.0)
        val start = engine.calculate(
            pageBounds = page,
            pointer = anchor,
            direction = TurnDirection.FORWARD,
            origin = CurlOrigin.FREE_POINT,
            customAnchor = anchor,
        )
        val completed = engine.calculate(
            pageBounds = page,
            pointer = start.completionPointer,
            direction = TurnDirection.FORWARD,
            origin = CurlOrigin.FREE_POINT,
            customAnchor = anchor,
        )
        val expectedDestination = Rect(-100.0, 0.0, 0.0, 160.0)

        assertEquals(Point(-72.0, 44.0), completed.completionPointer)
        assertEquals(completed.completionPointer, completed.constrainedPointer)
        assertEquals(expectedDestination, completed.finalDestinationBounds)
        assertEquals(1.0, completed.progress, TOLERANCE)
        assertEquals(page.area, completed.liftedFront.area, AREA_TOLERANCE)
        assertTrue(completed.flatFront.area <= AREA_TOLERANCE)
        assertPointSetsEqual(
            expectedDestination.corners(),
            completed.reflectedBack.points,
        )
        expectedDestination.corners().forEach { destinationPoint ->
            assertPointEquals(
                destinationPoint,
                completed.destinationToReflectedBack.map(destinationPoint),
            )
        }
    }

    @Test
    fun `free point geometry mirrors forward and backward`() {
        val forwardAnchor = Point(72.0, 44.0)
        val forwardPointer = Point(26.0, 103.0)
        val backwardAnchor = mirror(forwardAnchor)
        val backwardPointer = mirror(forwardPointer)
        val forward = engine.calculate(
            page,
            forwardPointer,
            TurnDirection.FORWARD,
            CurlOrigin.FREE_POINT,
            customAnchor = forwardAnchor,
        )
        val backward = engine.calculate(
            page,
            backwardPointer,
            TurnDirection.BACKWARD,
            CurlOrigin.FREE_POINT,
            customAnchor = backwardAnchor,
        )

        assertEquals(mirror(forward.grabPoint), backward.grabPoint)
        assertEquals(mirror(forward.constrainedPointer), backward.constrainedPointer)
        assertEquals(mirror(forward.completionPointer), backward.completionPointer)
        assertEquals(mirror(forward.finalDestinationBounds), backward.finalDestinationBounds)
        assertEquals(forward.progress, backward.progress, TOLERANCE)
        assertEquals(forward.lift, backward.lift, TOLERANCE)
        assertPointSetsEqual(forward.flatFront.points.map(::mirror), backward.flatFront.points)
        assertPointSetsEqual(forward.liftedFront.points.map(::mirror), backward.liftedFront.points)
        assertPointSetsEqual(forward.reflectedBack.points.map(::mirror), backward.reflectedBack.points)
    }

    @Test
    fun `free point drag is deterministic in both travel directions`() {
        val anchor = Point(80.0, 57.0)
        val initial = engine.calculate(
            page,
            anchor,
            TurnDirection.FORWARD,
            CurlOrigin.FREE_POINT,
            customAnchor = anchor,
        )
        val pointers = (0..40).map { step ->
            lerp(anchor, initial.completionPointer, step / 40.0)
        }
        val inward = pointers.map { pointer -> freePointGeometry(anchor, pointer) }
        val outward = pointers.asReversed().map { pointer -> freePointGeometry(anchor, pointer) }

        inward.zipWithNext().forEach { (before, after) ->
            assertTrue(after.progress + TOLERANCE >= before.progress)
            assertTrue(abs(after.progress - before.progress) <= 0.026)
            assertPointEquals(after.constrainedPointer, after.currentReflection.map(anchor))
        }
        assertEquals(inward.asReversed(), outward)
    }

    @Test
    fun `free point hostile anchor and pointer remain finite`() {
        val hostileAnchors = listOf(
            null,
            Point(Double.NaN, Double.NaN),
            Point(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY),
            Point(-Double.MAX_VALUE, Double.MAX_VALUE),
        )

        TurnDirection.entries.forEach { direction ->
            hostileAnchors.forEach { anchor ->
                val geometry = engine.calculate(
                    pageBounds = page,
                    pointer = Point(Double.NaN, Double.POSITIVE_INFINITY),
                    direction = direction,
                    origin = CurlOrigin.FREE_POINT,
                    customAnchor = anchor,
                )

                assertTrue(geometry.isFinite)
                assertTrue(geometry.grabPoint.x in page.left..page.right)
                assertTrue(geometry.grabPoint.y in page.top..page.bottom)
                assertTrue(geometry.completionPointer.isFinite)
                assertTrue(geometry.finalDestinationBounds.corners().all(Point::isFinite))
            }
        }
    }

    @Test
    fun `forward curl crosses a physical hinge and completes exactly in destination`() {
        val destination = Rect(left = -112.0, top = 0.0, right = -12.0, bottom = 160.0)
        val initial = engine.calculate(
            pageBounds = page,
            pointer = page.bottomRight,
            direction = TurnDirection.FORWARD,
            destinationBounds = destination,
        )
        val completed = engine.calculate(
            pageBounds = page,
            pointer = initial.completionPointer,
            direction = TurnDirection.FORWARD,
            destinationBounds = destination,
        )

        assertTrue(completed.isFinite)
        assertEquals(destination, completed.finalDestinationBounds)
        assertPointEquals(Point(-112.0, 160.0), completed.completionPointer)
        assertEquals(1.0, completed.progress, TOLERANCE)
        assertTrue(completed.flatFront.area <= AREA_TOLERANCE)
        assertEquals(page.area, completed.liftedFront.area, AREA_TOLERANCE)
        assertPointSetsEqual(destination.corners(), completed.reflectedBack.points)
        page.corners().forEach { sourcePoint ->
            assertPointEquals(
                completed.finalReflection.map(sourcePoint),
                completed.currentReflection.map(sourcePoint),
            )
        }
        destination.corners().forEach { destinationPoint ->
            assertPointEquals(
                destinationPoint,
                completed.destinationToReflectedBack.map(destinationPoint),
            )
        }
    }

    @Test
    fun `backward curl crosses a physical hinge and mirrors forward geometry`() {
        val forwardDestination = Rect(-112.0, 0.0, -12.0, 160.0)
        val backwardDestination = mirror(forwardDestination)
        val forwardInitial = engine.calculate(
            pageBounds = page,
            pointer = page.bottomRight,
            direction = TurnDirection.FORWARD,
            destinationBounds = forwardDestination,
        )
        val backwardInitial = engine.calculate(
            pageBounds = page,
            pointer = page.bottomLeft,
            direction = TurnDirection.BACKWARD,
            destinationBounds = backwardDestination,
        )
        val forwardPointer = lerp(page.bottomRight, forwardInitial.completionPointer, 0.37)
        val backwardPointer = mirror(forwardPointer)
        val forward = engine.calculate(
            pageBounds = page,
            pointer = forwardPointer,
            direction = TurnDirection.FORWARD,
            destinationBounds = forwardDestination,
        )
        val backward = engine.calculate(
            pageBounds = page,
            pointer = backwardPointer,
            direction = TurnDirection.BACKWARD,
            destinationBounds = backwardDestination,
        )

        assertEquals(Rect(112.0, 0.0, 212.0, 160.0), backwardDestination)
        assertEquals(mirror(forward.completionPointer), backward.completionPointer)
        assertEquals(mirror(forward.finalDestinationBounds), backward.finalDestinationBounds)
        assertEquals(forward.progress, backward.progress, TOLERANCE)
        assertEquals(forward.lift, backward.lift, TOLERANCE)
        assertPointSetsEqual(forward.flatFront.points.map(::mirror), backward.flatFront.points)
        assertPointSetsEqual(forward.liftedFront.points.map(::mirror), backward.liftedFront.points)
        assertPointSetsEqual(forward.reflectedBack.points.map(::mirror), backward.reflectedBack.points)

        val completed = engine.calculate(
            pageBounds = page,
            pointer = backwardInitial.completionPointer,
            direction = TurnDirection.BACKWARD,
            destinationBounds = backwardDestination,
        )
        assertEquals(1.0, completed.progress, TOLERANCE)
        assertPointSetsEqual(backwardDestination.corners(), completed.reflectedBack.points)
    }

    @Test
    fun `gap aware progress is normalized across the full hinge travel`() {
        val forwardDestination = Rect(-130.0, 0.0, -30.0, 160.0)
        val backwardDestination = mirror(forwardDestination)

        listOf(
            TurnDirection.FORWARD to forwardDestination,
            TurnDirection.BACKWARD to backwardDestination,
        ).forEach { (direction, destination) ->
            val grabPoint = if (direction == TurnDirection.FORWARD) {
                page.bottomRight
            } else {
                page.bottomLeft
            }
            val initial = engine.calculate(
                pageBounds = page,
                pointer = grabPoint,
                direction = direction,
                destinationBounds = destination,
            )
            val midpoint = lerp(initial.grabPoint, initial.completionPointer, 0.5)
            val geometry = engine.calculate(
                pageBounds = page,
                pointer = midpoint,
                direction = direction,
                destinationBounds = destination,
            )

            assertTrue(geometry.isFinite)
            assertEquals(midpoint, geometry.constrainedPointer)
            assertEquals(0.5, geometry.progress, TOLERANCE)
            assertEquals(1.0, geometry.lift, TOLERANCE)
        }
    }

    @Test
    fun `float rounded equal leaves retain their physical hinge destination`() {
        val pageWidth = 453.6f
        val sourceLeft = 512f
        val sourceRight = sourceLeft + pageWidth
        val destinationRight = 488f
        val destinationLeft = destinationRight - pageWidth
        val roundedSource = Rect(
            left = sourceLeft.toDouble(),
            top = 20.2f.toDouble(),
            right = sourceRight.toDouble(),
            bottom = 692.2f.toDouble(),
        )
        val roundedDestination = Rect(
            left = destinationLeft.toDouble(),
            top = 20.2f.toDouble(),
            right = destinationRight.toDouble(),
            bottom = 692.2f.toDouble(),
        )

        assertTrue(abs(roundedSource.width - roundedDestination.width) > TOLERANCE)
        val initial = engine.calculate(
            pageBounds = roundedSource,
            pointer = roundedSource.bottomRight,
            direction = TurnDirection.FORWARD,
            destinationBounds = roundedDestination,
        )
        val completed = engine.calculate(
            pageBounds = roundedSource,
            pointer = initial.completionPointer,
            direction = TurnDirection.FORWARD,
            destinationBounds = roundedDestination,
        )

        assertEquals(roundedDestination, completed.finalDestinationBounds)
        assertEquals(1.0, completed.progress, TOLERANCE)
        assertPointSetsEqual(
            roundedDestination.corners(),
            completed.reflectedBack.points,
            FLOAT_ROUNDING_TOLERANCE,
        )
    }

    @Test
    fun `invalid destinations fall back exactly to legacy adjacent geometry`() {
        val invalidForwardDestinations = listOf(
            Rect(-90.0, 0.0, 0.0, 160.0), // Wrong width.
            Rect(-100.0, 1.0, 0.0, 161.0), // Wrong vertical bounds.
            Rect(100.0, 0.0, 200.0, 160.0), // Wrong side.
            page, // Overlaps the source.
        )
        val invalidBackwardDestinations = invalidForwardDestinations.map(::mirror)

        listOf(
            Triple(TurnDirection.FORWARD, Point(23.0, 117.0), invalidForwardDestinations),
            Triple(TurnDirection.BACKWARD, Point(77.0, 117.0), invalidBackwardDestinations),
        ).forEach { (direction, pointer, invalidDestinations) ->
            val legacy = engine.calculate(page, pointer, direction)
            invalidDestinations.forEach { invalidDestination ->
                val geometry = engine.calculate(
                    pageBounds = page,
                    pointer = pointer,
                    direction = direction,
                    destinationBounds = invalidDestination,
                )

                assertEquals(legacy, geometry)
                assertTrue(geometry.isFinite)
            }
        }
    }

    @Test
    fun `arc drags keep both binding endpoints on the flat side in either direction`() {
        for (direction in TurnDirection.entries) {
            for (origin in CurlOrigin.entries) {
                for (anchorY in listOf(0.0, 8.0, 80.0, 152.0, 160.0)) {
                    for (gap in listOf(0.0, 20.0)) {
                        val forwardDestination = Rect(-100.0 - gap, 0.0, -gap, 160.0)
                        val destination = if (direction == TurnDirection.FORWARD) forwardDestination else mirror(forwardDestination)
                        val forwardAnchor = Point(if (origin == CurlOrigin.FREE_POINT) 72.0 else 100.0, anchorY)
                        val anchor = if (direction == TurnDirection.FORWARD) forwardAnchor else mirror(forwardAnchor)
                        fun at(pointer: Point) = engine.calculate(page, pointer, direction, origin,
                            edgeAnchorY = anchorY, customAnchor = anchor, destinationBounds = destination)
                        val initial = at(anchor)
                        val spineX = (initial.grabPoint.x + initial.completionPointer.x) / 2
                        for (step in 0..100) {
                            val fraction = step / 100.0
                            val baseline = lerp(initial.grabPoint, initial.completionPointer, fraction)
                            for (arc in listOf(-160.0, -14.0, 14.0, 160.0)) {
                                val pointer = baseline.copy(y = baseline.y + arc * kotlin.math.sin(Math.PI * fraction))
                                val geometry = at(pointer)
                                assertTrue(geometry.isFinite)
                                assertEquals(baseline.x, geometry.constrainedPointer.x, TOLERANCE)
                                if (step == 0) continue
                                val midpoint = lerp(geometry.grabPoint, geometry.constrainedPointer, .5)
                                for (endpoint in listOf(Point(spineX, page.top), Point(spineX, page.bottom))) {
                                    val relative = endpoint - midpoint
                                    val signedDistance = relative.x * geometry.fold.normal.x + relative.y * geometry.fold.normal.y
                                    assertTrue("Binding lifted: $direction $origin y=$anchorY gap=$gap step=$step arc=$arc",
                                        signedDistance <= TOLERANCE)
                                }
                                assertEquals(page.area, geometry.flatFront.area + geometry.liftedFront.area, AREA_TOLERANCE)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `late upper edge drag cannot lift the spine or leave a tilted completed sheet`() {
        for (direction in TurnDirection.entries) {
            fun at(pointer: Point) = engine.calculate(page,
                if (direction == TurnDirection.FORWARD) pointer else mirror(pointer), direction,
                CurlOrigin.OUTER_EDGE, edgeAnchorY = 8.0)
            val late = at(Point(-95.0, 22.0))
            val spineX = if (direction == TurnDirection.FORWARD) page.left else page.right
            val spine = listOf(Point(spineX, page.top), Point(spineX, page.bottom))
            assertTrue(spine.all { point -> late.flatFront.points.any {
                abs(it.x - point.x) <= TOLERANCE && abs(it.y - point.y) <= TOLERANCE
            } })
            val completed = at(Point(-100.0, 22.0))
            assertPointEquals(completed.completionPointer, completed.constrainedPointer)
            assertPointSetsEqual(completed.finalDestinationBounds.corners(), completed.reflectedBack.points)
        }
    }

    private fun freePointGeometry(anchor: Point, pointer: Point): PageCurlGeometry =
        engine.calculate(
            page,
            pointer,
            TurnDirection.FORWARD,
            CurlOrigin.FREE_POINT,
            customAnchor = anchor,
        )

    private fun lerp(start: Point, end: Point, fraction: Double): Point = Point(
        x = start.x + (end.x - start.x) * fraction,
        y = start.y + (end.y - start.y) * fraction,
    )

    private fun mirror(point: Point): Point = Point(page.left + page.right - point.x, point.y)

    private fun mirror(rect: Rect): Rect = Rect(
        left = page.left + page.right - rect.right,
        top = rect.top,
        right = page.left + page.right - rect.left,
        bottom = rect.bottom,
    )

    private fun assertPointEquals(expected: Point, actual: Point) {
        assertEquals(expected.x, actual.x, TOLERANCE)
        assertEquals(expected.y, actual.y, TOLERANCE)
    }

    private fun assertPointSetsEqual(
        expected: List<Point>,
        actual: List<Point>,
        tolerance: Double = TOLERANCE,
    ) {
        assertEquals("Polygon vertex counts differ", expected.size, actual.size)
        val unmatched = actual.toMutableList()
        expected.forEach { expectedPoint ->
            val matchIndex = unmatched.indexOfFirst { actualPoint ->
                abs(expectedPoint.x - actualPoint.x) <= tolerance &&
                    abs(expectedPoint.y - actualPoint.y) <= tolerance
            }
            assertTrue("No matching point for $expectedPoint in $actual", matchIndex >= 0)
            unmatched.removeAt(matchIndex)
        }
    }

    companion object {
        private const val TOLERANCE = 1e-6
        private const val AREA_TOLERANCE = 1e-4
        private const val FLOAT_ROUNDING_TOLERANCE = 1e-4
    }
}
