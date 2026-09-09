package com.avstaim.listai.core.adaptive

import kotlin.math.abs
import kotlin.math.max

internal enum class FoldFeatureState {
    FLAT,
    HALF_OPENED,
}

/** Android-free representation used at the WindowManager boundary and in JVM tests. */
internal data class FoldFeatureSnapshot(
    val bounds: FoldBounds,
    val orientation: FoldOrientation,
    val state: FoldFeatureState,
    val separating: Boolean,
    val occluding: Boolean,
)

internal fun mapFoldingFeatures(
    features: List<FoldFeatureSnapshot>,
    windowBounds: FoldBounds? = null,
): DevicePosture {
    val feature = selectRelevantFeature(features, windowBounds) ?: return DevicePosture.Unknown

    return when (feature.state) {
        FoldFeatureState.FLAT -> DevicePosture.Flat(
            foldBounds = feature.bounds,
            orientation = feature.orientation,
            separating = feature.separating,
            occluding = feature.occluding,
        )

        FoldFeatureState.HALF_OPENED -> DevicePosture.HalfOpened(
            foldBounds = feature.bounds,
            orientation = feature.orientation,
            separating = true,
            occluding = feature.occluding,
        )
    }
}

private fun selectRelevantFeature(
    features: List<FoldFeatureSnapshot>,
    windowBounds: FoldBounds?,
): FoldFeatureSnapshot? = features.minWithOrNull(
    compareBy<FoldFeatureSnapshot>(
        { !it.separating },
        { !it.occluding },
        { it.state != FoldFeatureState.HALF_OPENED },
        { it.orientation != FoldOrientation.VERTICAL },
        { crossAxisDistanceFromCenter(it, windowBounds) },
        { it.bounds.left },
        { it.bounds.top },
        { it.bounds.right },
        { it.bounds.bottom },
    ),
)

private fun crossAxisDistanceFromCenter(
    feature: FoldFeatureSnapshot,
    windowBounds: FoldBounds?,
): Double {
    windowBounds ?: return 0.0

    return when (feature.orientation) {
        FoldOrientation.VERTICAL -> {
            abs(feature.bounds.center.x - windowBounds.center.x) / max(1, windowBounds.width)
        }

        FoldOrientation.HORIZONTAL -> {
            abs(feature.bounds.center.y - windowBounds.center.y) / max(1, windowBounds.height)
        }
    }
}
