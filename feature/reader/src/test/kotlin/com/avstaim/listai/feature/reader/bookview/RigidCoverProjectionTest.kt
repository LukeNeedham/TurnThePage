package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Rect
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RigidCoverProjectionTest {
    private val right = Rect(left = 500f, top = 20f, right = 900f, bottom = 620f)
    private val left = Rect(left = 80f, top = 20f, right = 480f, bottom = 620f)

    @Test
    fun `forward cover is full source edge-on then full destination`() {
        val early = projection(TurnDirection.FORWARD, 0f)
        val middle = projection(TurnDirection.FORWARD, 0.5f)
        val late = projection(TurnDirection.FORWARD, 1f)

        assertRectEquals(right, early.projectedBounds)
        assertTrue(early.showsSourceFace)
        assertEquals(0f, middle.projectedBounds.width, TOLERANCE)
        assertEquals(490f, middle.projectedHingeX, TOLERANCE)
        assertLateDestination(late)
    }

    @Test
    fun `backward cover mirrors the same rigid hinge motion`() {
        val early = calculateRigidCoverProjection(
            sourceBounds = left,
            destinationBounds = right,
            direction = TurnDirection.BACKWARD,
            progress = 0f,
        )
        val middle = calculateRigidCoverProjection(
            sourceBounds = left,
            destinationBounds = right,
            direction = TurnDirection.BACKWARD,
            progress = 0.5f,
        )
        val late = calculateRigidCoverProjection(
            sourceBounds = left,
            destinationBounds = right,
            direction = TurnDirection.BACKWARD,
            progress = 1f,
        )

        assertRectEquals(left, early.projectedBounds)
        assertEquals(0f, middle.projectedBounds.width, TOLERANCE)
        assertEquals(490f, middle.projectedHingeX, TOLERANCE)
        assertRectEquals(right, late.projectedBounds)
        assertFalse(late.showsSourceFace)
    }

    @Test
    fun `all intermediate cover projections remain rectangular and finite`() {
        for (step in 0..100) {
            val projection = projection(TurnDirection.FORWARD, step / 100f)

            assertTrue("projection at $step must be finite", projection.isFinite)
            assertEquals(right.top, projection.projectedBounds.top, TOLERANCE)
            assertEquals(right.bottom, projection.projectedBounds.bottom, TOLERANCE)
            assertTrue(projection.projectedBounds.width in 0f..right.width)
            assertTrue(projection.horizontalScale in 0f..1f)
        }
    }

    @Test
    fun `invalid progress falls back to intact source cover`() {
        val projection = projection(TurnDirection.FORWARD, Float.NaN)

        assertRectEquals(right, projection.projectedBounds)
        assertTrue(projection.showsSourceFace)
    }

    private fun projection(direction: TurnDirection, progress: Float): RigidCoverProjection =
        calculateRigidCoverProjection(
            sourceBounds = right,
            destinationBounds = left,
            direction = direction,
            progress = progress,
        )

    private fun assertLateDestination(projection: RigidCoverProjection) {
        assertRectEquals(left, projection.projectedBounds)
        assertFalse(projection.showsSourceFace)
    }

    private fun assertRectEquals(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, TOLERANCE)
        assertEquals(expected.top, actual.top, TOLERANCE)
        assertEquals(expected.right, actual.right, TOLERANCE)
        assertEquals(expected.bottom, actual.bottom, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
