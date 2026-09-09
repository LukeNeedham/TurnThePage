package com.avstaim.listai.reader.rendering.turn

import com.avstaim.listai.reader.rendering.geometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PageTurnPhysicsTest {
    private val config = PageTurnPhysicsConfig(
        completionDistanceRatio = 0.5,
        completionVelocityPxPerSec = 1_000.0,
    )

    @Test
    fun `forward drag completes at distance threshold`() {
        assertEquals(
            SettleTarget.CANCEL,
            decide(TurnDirection.FORWARD, distanceX = -49.9),
        )
        assertEquals(
            SettleTarget.COMPLETE,
            decide(TurnDirection.FORWARD, distanceX = -50.0),
        )
    }

    @Test
    fun `backward drag mirrors forward distance decision`() {
        assertEquals(
            SettleTarget.CANCEL,
            decide(TurnDirection.BACKWARD, distanceX = 49.9),
        )
        assertEquals(
            SettleTarget.COMPLETE,
            decide(TurnDirection.BACKWARD, distanceX = 50.0),
        )
    }

    @Test
    fun `short inward flick completes in both directions`() {
        assertEquals(
            SettleTarget.COMPLETE,
            decide(TurnDirection.FORWARD, distanceX = -5.0, velocityX = -1_000.0),
        )
        assertEquals(
            SettleTarget.COMPLETE,
            decide(TurnDirection.BACKWARD, distanceX = 5.0, velocityX = 1_000.0),
        )
    }

    @Test
    fun `fast outward movement cancels even after distance threshold`() {
        assertEquals(
            SettleTarget.CANCEL,
            decide(TurnDirection.FORWARD, distanceX = -80.0, velocityX = 1_001.0),
        )
        assertEquals(
            SettleTarget.CANCEL,
            decide(TurnDirection.BACKWARD, distanceX = 80.0, velocityX = -1_001.0),
        )
    }

    @Test
    fun `non-finite motion and degenerate page cancel safely`() {
        assertEquals(
            SettleTarget.CANCEL,
            decideSettleTarget(
                direction = TurnDirection.FORWARD,
                pointerDisplacement = Point(Double.NaN, Double.POSITIVE_INFINITY),
                velocityPxPerSecond = Point(Double.NEGATIVE_INFINITY, Double.NaN),
                pageWidthPx = 0.0,
                config = config,
            ),
        )
    }

    @Test
    fun `invalid physics configuration is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PageTurnPhysicsConfig(completionDistanceRatio = 1.1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageTurnPhysicsConfig(completionVelocityPxPerSec = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageTurnPhysicsConfig(completionVelocityPxPerSec = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageTurnPhysicsConfig(minSettleDurationMs = 400, maxSettleDurationMs = 200)
        }
    }

    @Test
    fun `dragging exposes pointer displacement and settling validates fraction`() {
        val dragging = TurnState.Dragging(
            direction = TurnDirection.FORWARD,
            startPointer = Point(100.0, 160.0),
            currentPointer = Point(65.0, 120.0),
        )
        assertEquals(Point(-35.0, -40.0), dragging.pointerDisplacement)

        assertThrows(IllegalArgumentException::class.java) {
            TurnState.Settling(
                direction = TurnDirection.FORWARD,
                target = SettleTarget.COMPLETE,
                startPointer = Point(65.0, 120.0),
                currentPointer = Point(65.0, 120.0),
                animationFraction = Double.NaN,
            )
        }
    }

    private fun decide(
        direction: TurnDirection,
        distanceX: Double,
        velocityX: Double = 0.0,
    ): SettleTarget = decideSettleTarget(
        direction = direction,
        pointerDisplacement = Point(distanceX, 0.0),
        velocityPxPerSecond = Point(velocityX, 0.0),
        pageWidthPx = 100.0,
        config = config,
    )
}
