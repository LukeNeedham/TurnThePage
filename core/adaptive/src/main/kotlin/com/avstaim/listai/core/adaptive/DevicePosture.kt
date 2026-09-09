package com.avstaim.listai.core.adaptive

import kotlinx.coroutines.flow.StateFlow

/** Integer fold bounds expressed in the current window's pixel coordinate space. */
data class FoldBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left <= right) { "left must not be greater than right" }
        require(top <= bottom) { "top must not be greater than bottom" }
        require(right.toLong() - left <= Int.MAX_VALUE) { "width must fit in Int" }
        require(bottom.toLong() - top <= Int.MAX_VALUE) { "height must fit in Int" }
    }

    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top

    val center: FoldCenter
        get() = FoldCenter(
            x = (left.toLong() + right.toLong()) / 2.0,
            y = (top.toLong() + bottom.toLong()) / 2.0,
        )
}

data class FoldCenter(
    val x: Double,
    val y: Double,
)

enum class FoldOrientation {
    VERTICAL,
    HORIZONTAL,
}

sealed interface DevicePosture {
    data object Unknown : DevicePosture

    data class Flat(
        val foldBounds: FoldBounds? = null,
        val orientation: FoldOrientation? = null,
        val separating: Boolean = false,
        val occluding: Boolean = false,
    ) : DevicePosture

    data class HalfOpened(
        val foldBounds: FoldBounds,
        val orientation: FoldOrientation,
        val separating: Boolean = true,
        val occluding: Boolean,
    ) : DevicePosture
}

interface DevicePostureProvider {
    val posture: StateFlow<DevicePosture>
}
