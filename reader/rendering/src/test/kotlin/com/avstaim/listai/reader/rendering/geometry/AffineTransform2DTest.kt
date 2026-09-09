package com.avstaim.listai.reader.rendering.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AffineTransform2DTest {
    @Test
    fun `reflection maps a point across its line and is its own inverse`() {
        val reflection = AffineTransform2D.reflection(
            linePoint = Point(10.0, 0.0),
            normal = Point(1.0, 0.0),
        )
        val source = Point(16.0, 7.0)
        val reflected = reflection.map(source)

        assertPointEquals(Point(4.0, 7.0), reflected)
        assertPointEquals(source, reflection.map(reflected))
    }

    @Test
    fun `compose applies inner transform before outer transform`() {
        val finalReflection = AffineTransform2D.reflection(
            linePoint = Point(0.0, 0.0),
            normal = Point(1.0, 0.0),
        )
        val currentReflection = AffineTransform2D.reflection(
            linePoint = Point(25.0, 30.0),
            normal = Point(2.0, 1.0),
        )
        val composed = currentReflection.compose(finalReflection)
        val point = Point(-35.0, 42.0)

        assertPointEquals(
            currentReflection.map(finalReflection.map(point)),
            composed.map(point),
        )
    }

    @Test
    fun `hostile reflection normal safely becomes finite identity`() {
        val transforms = listOf(
            AffineTransform2D.reflection(Point.ZERO, Point.ZERO),
            AffineTransform2D.reflection(
                Point(Double.NaN, Double.POSITIVE_INFINITY),
                Point(Double.NaN, 1.0),
            ),
        )

        transforms.forEach { transform ->
            assertTrue(transform.isFinite)
            assertEquals(AffineTransform2D.IDENTITY, transform)
        }
    }

    private fun assertPointEquals(expected: Point, actual: Point) {
        assertEquals(expected.x, actual.x, TOLERANCE)
        assertEquals(expected.y, actual.y, TOLERANCE)
    }

    companion object {
        private const val TOLERANCE = 1e-8
    }
}
