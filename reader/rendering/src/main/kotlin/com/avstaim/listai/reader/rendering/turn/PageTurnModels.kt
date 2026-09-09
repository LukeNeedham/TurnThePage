package com.avstaim.listai.reader.rendering.turn

import com.avstaim.listai.reader.rendering.geometry.Point

enum class TurnDirection {
    FORWARD,
    BACKWARD,
}

enum class CurlOrigin {
    LOWER_OUTER,
    /** A point on the outer page edge whose y coordinate is captured when dragging starts. */
    OUTER_EDGE,
    /** A touch-down point anywhere inside the page bounds. */
    FREE_POINT,
}

enum class SettleTarget {
    COMPLETE,
    CANCEL,
}

/** Framework-independent state machine values consumed by the future Compose adapter. */
sealed interface TurnState {
    data object Idle : TurnState

    data class Dragging(
        val direction: TurnDirection,
        val origin: CurlOrigin = CurlOrigin.LOWER_OUTER,
        val startPointer: Point,
        val currentPointer: Point,
        val velocityPxPerSecond: Point = Point.ZERO,
    ) : TurnState {
        val pointerDisplacement: Point
            get() = currentPointer - startPointer
    }

    data class Settling(
        val direction: TurnDirection,
        val target: SettleTarget,
        val startPointer: Point,
        val currentPointer: Point,
        val animationFraction: Double = 0.0,
        val origin: CurlOrigin = CurlOrigin.LOWER_OUTER,
    ) : TurnState {
        init {
            require(animationFraction.isFinite() && animationFraction in 0.0..1.0) {
                "animationFraction must be finite and in 0..1"
            }
        }
    }
}

/**
 * All interaction/animation coefficients live in one immutable value instead of being spread
 * through gesture and rendering code. Dp values are converted by the UI boundary when used.
 */
data class PageTurnPhysicsConfig(
    val edgeActivationWidthDp: Double = 48.0,
    val cornerActivationSizeDp: Double = 72.0,
    val completionDistanceRatio: Double = 0.45,
    val completionVelocityPxPerSec: Double = 1_200.0,
    val minSettleDurationMs: Int = 140,
    val maxSettleDurationMs: Int = 380,
    val pointerSmoothing: Double = 0.15,
    val curlTension: Double = 0.65,
) {
    init {
        require(edgeActivationWidthDp.isFinite() && edgeActivationWidthDp >= 0.0) {
            "edgeActivationWidthDp must be finite and non-negative"
        }
        require(cornerActivationSizeDp.isFinite() && cornerActivationSizeDp >= 0.0) {
            "cornerActivationSizeDp must be finite and non-negative"
        }
        require(completionDistanceRatio.isFinite() && completionDistanceRatio in 0.0..1.0) {
            "completionDistanceRatio must be finite and in 0..1"
        }
        require(
            completionVelocityPxPerSec.isFinite() && completionVelocityPxPerSec > 0.0,
        ) {
            "completionVelocityPxPerSec must be finite and positive"
        }
        require(minSettleDurationMs >= 0) { "minSettleDurationMs must be non-negative" }
        require(maxSettleDurationMs >= minSettleDurationMs) {
            "maxSettleDurationMs must be at least minSettleDurationMs"
        }
        require(pointerSmoothing.isFinite() && pointerSmoothing in 0.0..1.0) {
            "pointerSmoothing must be finite and in 0..1"
        }
        require(curlTension.isFinite() && curlTension in 0.0..1.0) {
            "curlTension must be finite and in 0..1"
        }
    }
}
