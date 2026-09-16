package com.avstaim.listai.reader.rendering.geometry

import com.avstaim.listai.reader.rendering.turn.CurlOrigin
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookMaterialLightingTest {
    @Test fun `settled paper in either direction has no residual curl shadow`() {
        val engine = PageCurlGeometryEngine()
        val bounds = Rect(0.0, 0.0, 400.0, 600.0)
        TurnDirection.entries.forEach { direction ->
            val grab = Point(if (direction == TurnDirection.FORWARD) bounds.right else bounds.left, 300.0)
            val start = engine.calculate(bounds, grab, direction, CurlOrigin.OUTER_EDGE, 300.0)
            val settled = engine.calculate(bounds, start.completionPointer, direction, CurlOrigin.OUTER_EDGE, 300.0)
            listOf(start, settled).forEach { geometry ->
                val light = PaperCurlLighting.calculate(bounds.width, 1.0, geometry.lift)
                assertEquals(0.0, light.castOpacity, .000001)
                assertEquals(0.0, light.contactOpacity, .000001)
                assertEquals(0.0, light.creaseOpacity, .000001)
            }
        }
    }

    @Test fun `elevation broadens cast shadow while contact remains narrow`() {
        val low = PaperCurlLighting.calculate(412.0, 1.0, .1)
        val high = PaperCurlLighting.calculate(412.0, 1.0, 1.0)
        assertTrue(high.castWidth > low.castWidth * 2)
        assertTrue(high.contactWidth < high.castWidth * .1)
        assertTrue(high.castOpacity < .2 && high.contactOpacity < .2)
        assertTrue(high.ridgeOpacity <= .15)
    }

    @Test fun `material math stays finite for invalid geometry and conserves stack depth`() {
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, -1.0, 0.0, .5, 1.0, 2.0).forEach { lift ->
            val light = PaperCurlLighting.calculate(Double.NaN, Double.POSITIVE_INFINITY, lift)
            assertTrue(light.castWidth.isFinite() && light.castWidth > 0)
            assertTrue(light.contactWidth.isFinite() && light.contactWidth > 0)
            assertTrue(light.castOpacity in 0.0..1.0 && light.contactOpacity in 0.0..1.0)
        }
        (0..100).forEach { step ->
            val depth = pageStackThickness(step / 100f, 2f)
            val mirrored = pageStackThickness(1f - step / 100f, 2f)
            assertEquals(18f, depth.read + depth.remaining, .00001f)
            assertEquals(depth.read, mirrored.remaining, .00001f)
        }
        assertEquals(pageStackThickness(0f), pageStackThickness(Float.NaN))
    }
}
