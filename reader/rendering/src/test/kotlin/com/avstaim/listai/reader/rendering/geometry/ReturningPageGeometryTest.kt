package com.avstaim.listai.reader.rendering.geometry

import com.avstaim.listai.reader.rendering.turn.CurlOrigin
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import org.junit.Assert.*
import org.junit.Test

class ReturningPageGeometryTest {
    private val engine = PageCurlGeometryEngine()
    private val page = Rect(23.0, 41.0, 423.0, 841.0)

    @Test fun previousLeafStartsBeyondLeftBindingAndFinishesFlat() {
        val start = engine.calculateReturningPage(page, page.bottomLeft)
        assertEquals(1.0, start.progress, 1e-9)
        assertEquals(0.0, start.flatFront.area, 1e-6)
        assertTrue(start.reflectedBack.points.all { it.x <= page.left + 1e-6 })
        assertEquals(page.left, start.finalDestinationBounds.right, 1e-9)
        val end = engine.calculateReturningPage(page, Point(page.left + page.width * 2, page.bottom))
        assertEquals(0.0, end.progress, 1e-9)
        assertEquals(page.area, end.flatFront.area, 1e-6)
        assertTrue(end.reflectedBack.points.isEmpty())
    }

    @Test fun rightwardDragRetracesForwardLeafRatherThanMirroringItsBinding() {
        for (origin in listOf(CurlOrigin.LOWER_OUTER, CurlOrigin.OUTER_EDGE)) {
            val y = if (origin == CurlOrigin.LOWER_OUTER) page.bottom else 317.0
            for (step in 0..100) {
                val returningProgress = step / 100.0
                val forwardPointer = Point(page.left - page.width + page.width * 2 * returningProgress,
                    y - 16 * kotlin.math.sin(Math.PI * returningProgress))
                val expected = engine.calculate(page, forwardPointer, TurnDirection.FORWARD, origin, y)
                val actual = engine.calculateReturningPage(page,
                    forwardPointer.copy(x = forwardPointer.x + page.width), origin, y)
                assertEquals(expected, actual)
                assertTrue(actual.isFinite)
                assertEquals(TurnDirection.FORWARD, actual.direction)
            }
        }
    }

    @Test fun arbitraryAnchorsPreserveReversedEndpointsAndTilt() {
        val backwardAnchor = Point(171.0, 392.0)
        val forwardAnchor = Point(page.left + page.right - backwardAnchor.x, backwardAnchor.y)
        val backwardCompletion = Point(2 * page.right - backwardAnchor.x, backwardAnchor.y)
        for (step in 0..20) {
            val t = step / 20.0
            val pointer = Point(backwardAnchor.x + (backwardCompletion.x - backwardAnchor.x) * t,
                backwardAnchor.y + 13 * kotlin.math.sin(Math.PI * t))
            val returning = engine.calculateReturningPage(page, pointer, CurlOrigin.FREE_POINT,
                customAnchor = backwardAnchor)
            val forward = engine.calculate(page, pointer.copy(x = pointer.x - page.width),
                TurnDirection.FORWARD, CurlOrigin.FREE_POINT, customAnchor = forwardAnchor)
            assertEquals(forward, returning)
        }
        assertEquals(1.0, engine.calculateReturningPage(page, backwardAnchor, CurlOrigin.FREE_POINT,
            customAnchor = backwardAnchor).progress, 1e-9)
        assertEquals(0.0, engine.calculateReturningPage(page, backwardCompletion, CurlOrigin.FREE_POINT,
            customAnchor = backwardAnchor).progress, 1e-9)
    }

    @Test fun cancellationRetracesIdenticalGeometryAndInvalidInputRemainsFinite() {
        val pointers = (0..20).map { Point(page.left + it * 12.0, 392.0 - it) }
        val outward = pointers.map { engine.calculateReturningPage(page, it, CurlOrigin.OUTER_EDGE, 392.0) }
        val cancelled = pointers.reversed().map { engine.calculateReturningPage(page, it, CurlOrigin.OUTER_EDGE, 392.0) }
        assertEquals(outward.reversed(), cancelled)
        for (x in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertTrue(engine.calculateReturningPage(page, Point(x, Double.NaN)).isFinite)
        }
    }
}
