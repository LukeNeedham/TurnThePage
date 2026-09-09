package com.avstaim.listai.reader.rendering.turn

import com.avstaim.listai.reader.rendering.geometry.Point
import kotlin.math.abs

/**
 * Chooses the settle target at pointer release.
 *
 * Velocity has precedence over distance when it reaches the flick threshold. This lets a fast
 * inward flick complete a short drag and, symmetrically, lets a fast outward movement cancel a
 * drag that had previously crossed the distance threshold.
 */
fun decideSettleTarget(
    direction: TurnDirection,
    pointerDisplacement: Point,
    velocityPxPerSecond: Point,
    pageWidthPx: Double,
    config: PageTurnPhysicsConfig = PageTurnPhysicsConfig(),
): SettleTarget {
    val directionSign = when (direction) {
        TurnDirection.FORWARD -> -1.0
        TurnDirection.BACKWARD -> 1.0
    }
    val inwardVelocity = velocityPxPerSecond.x.finiteOrZero() * directionSign
    val velocityThreshold = config.completionVelocityPxPerSec

    if (abs(inwardVelocity) >= velocityThreshold) {
        return if (inwardVelocity > 0.0) {
            SettleTarget.COMPLETE
        } else {
            SettleTarget.CANCEL
        }
    }

    val safePageWidth = if (pageWidthPx.isFinite()) abs(pageWidthPx) else 0.0
    if (safePageWidth <= GEOMETRY_EPSILON) return SettleTarget.CANCEL

    val inwardDistance = pointerDisplacement.x.finiteOrZero() * directionSign
    val completionDistance = safePageWidth * config.completionDistanceRatio
    return if (inwardDistance >= completionDistance) {
        SettleTarget.COMPLETE
    } else {
        SettleTarget.CANCEL
    }
}

private const val GEOMETRY_EPSILON = 1e-9

private fun Double.finiteOrZero(): Double = if (isFinite()) this else 0.0
