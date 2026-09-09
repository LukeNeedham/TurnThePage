package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Rect
import com.avstaim.listai.core.adaptive.DevicePosture
import com.avstaim.listai.core.adaptive.FoldBounds
import com.avstaim.listai.core.adaptive.FoldOrientation

/** Converts the platform-neutral window posture into the reader's window-space seam model. */
internal fun DevicePosture.toBookDisplayFeatureInWindow(): BookDisplayFeature? = when (this) {
    DevicePosture.Unknown -> null

    is DevicePosture.Flat -> {
        val bounds = foldBounds
        val foldOrientation = orientation
        if (bounds == null || foldOrientation == null) {
            null
        } else {
            BookDisplayFeature(
                bounds = bounds.toComposeRect(),
                orientation = foldOrientation.toBookOrientation(),
                separating = separating,
                occluding = occluding,
                halfOpened = false,
            )
        }
    }

    is DevicePosture.HalfOpened -> BookDisplayFeature(
        bounds = foldBounds.toComposeRect(),
        orientation = orientation.toBookOrientation(),
        separating = separating,
        occluding = occluding,
        halfOpened = true,
    )
}

private fun FoldBounds.toComposeRect(): Rect = Rect(
    left = left.toFloat(),
    top = top.toFloat(),
    right = right.toFloat(),
    bottom = bottom.toFloat(),
)

private fun FoldOrientation.toBookOrientation(): BookFoldOrientation = when (this) {
    FoldOrientation.VERTICAL -> BookFoldOrientation.VERTICAL
    FoldOrientation.HORIZONTAL -> BookFoldOrientation.HORIZONTAL
}
