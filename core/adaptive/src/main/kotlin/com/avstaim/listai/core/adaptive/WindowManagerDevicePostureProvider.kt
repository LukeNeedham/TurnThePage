package com.avstaim.listai.core.adaptive

import android.app.Activity
import android.graphics.Rect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class WindowManagerDevicePostureProvider(
    private val activity: Activity,
    lifecycle: Lifecycle,
    scope: CoroutineScope,
    private val tracker: WindowInfoTracker = WindowInfoTracker.getOrCreate(activity),
) : DevicePostureProvider {
    private val mutablePosture = MutableStateFlow<DevicePosture>(DevicePosture.Unknown)

    override val posture: StateFlow<DevicePosture> = mutablePosture.asStateFlow()

    init {
        scope.launch {
            try {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    collectWindowLayoutInfo()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutablePosture.value = DevicePosture.Unknown
            }
        }
    }

    private suspend fun collectWindowLayoutInfo() {
        try {
            tracker.windowLayoutInfo(activity).collect { layoutInfo ->
                val currentWindowBounds = WindowMetricsCalculator
                    .getOrCreate()
                    .computeCurrentWindowMetrics(activity)
                    .bounds
                    .toLocalWindowBounds()

                mutablePosture.value = layoutInfo.toDevicePosture(currentWindowBounds)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            mutablePosture.value = DevicePosture.Unknown
        }
    }
}

internal fun WindowLayoutInfo.toDevicePosture(windowBounds: FoldBounds): DevicePosture =
    mapFoldingFeatures(
        features = displayFeatures
            .filterIsInstance<FoldingFeature>()
            .map(FoldingFeature::toSnapshot),
        windowBounds = windowBounds,
    )

private fun FoldingFeature.toSnapshot(): FoldFeatureSnapshot = FoldFeatureSnapshot(
    bounds = bounds.toFoldBounds(),
    orientation = when (orientation) {
        FoldingFeature.Orientation.VERTICAL -> FoldOrientation.VERTICAL
        else -> FoldOrientation.HORIZONTAL
    },
    state = when (state) {
        FoldingFeature.State.HALF_OPENED -> FoldFeatureState.HALF_OPENED
        else -> FoldFeatureState.FLAT
    },
    separating = isSeparating,
    occluding = occlusionType == FoldingFeature.OcclusionType.FULL,
)

private fun Rect.toFoldBounds(): FoldBounds = FoldBounds(
    left = left,
    top = top,
    right = right,
    bottom = bottom,
)

private fun Rect.toLocalWindowBounds(): FoldBounds = FoldBounds(
    left = 0,
    top = 0,
    right = width(),
    bottom = height(),
)
