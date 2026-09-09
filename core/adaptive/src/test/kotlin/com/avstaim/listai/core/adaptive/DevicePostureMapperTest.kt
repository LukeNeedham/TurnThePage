package com.avstaim.listai.core.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test

class DevicePostureMapperTest {
    private val windowBounds = FoldBounds(left = 0, top = 0, right = 2_000, bottom = 1_800)

    @Test
    fun `no folding feature maps to unknown`() {
        assertEquals(
            DevicePosture.Unknown,
            mapFoldingFeatures(features = emptyList(), windowBounds = windowBounds),
        )
    }

    @Test
    fun `flat folding feature preserves its physical properties`() {
        val foldBounds = FoldBounds(left = 996, top = 0, right = 1_004, bottom = 1_800)

        assertEquals(
            DevicePosture.Flat(
                foldBounds = foldBounds,
                orientation = FoldOrientation.VERTICAL,
                separating = false,
                occluding = false,
            ),
            mapFoldingFeatures(
                features = listOf(
                    feature(
                        bounds = foldBounds,
                        state = FoldFeatureState.FLAT,
                    ),
                ),
                windowBounds = windowBounds,
            ),
        )
    }

    @Test
    fun `vertical half-opened feature maps to separating posture`() {
        val foldBounds = FoldBounds(left = 998, top = 0, right = 1_002, bottom = 1_800)

        assertEquals(
            DevicePosture.HalfOpened(
                foldBounds = foldBounds,
                orientation = FoldOrientation.VERTICAL,
                separating = true,
                occluding = false,
            ),
            mapFoldingFeatures(
                features = listOf(
                    feature(
                        bounds = foldBounds,
                        orientation = FoldOrientation.VERTICAL,
                        state = FoldFeatureState.HALF_OPENED,
                        separating = false,
                    ),
                ),
                windowBounds = windowBounds,
            ),
        )
    }

    @Test
    fun `horizontal half-opened feature maps with horizontal orientation`() {
        val foldBounds = FoldBounds(left = 0, top = 896, right = 2_000, bottom = 904)

        assertEquals(
            DevicePosture.HalfOpened(
                foldBounds = foldBounds,
                orientation = FoldOrientation.HORIZONTAL,
                occluding = false,
            ),
            mapFoldingFeatures(
                features = listOf(
                    feature(
                        bounds = foldBounds,
                        orientation = FoldOrientation.HORIZONTAL,
                        state = FoldFeatureState.HALF_OPENED,
                    ),
                ),
                windowBounds = windowBounds,
            ),
        )
    }

    @Test
    fun `full occlusion maps to occluding posture`() {
        val hingeBounds = FoldBounds(left = 980, top = 0, right = 1_020, bottom = 1_800)

        assertEquals(
            DevicePosture.Flat(
                foldBounds = hingeBounds,
                orientation = FoldOrientation.VERTICAL,
                separating = true,
                occluding = true,
            ),
            mapFoldingFeatures(
                features = listOf(
                    feature(
                        bounds = hingeBounds,
                        state = FoldFeatureState.FLAT,
                        separating = true,
                        occluding = true,
                    ),
                ),
                windowBounds = windowBounds,
            ),
        )
    }

    @Test
    fun `selection is deterministic and prefers separating then occluding then half-opened`() {
        val selectedBounds = FoldBounds(left = 1_600, top = 0, right = 1_610, bottom = 1_800)
        val candidates = listOf(
            feature(
                bounds = FoldBounds(left = 998, top = 0, right = 1_002, bottom = 1_800),
                state = FoldFeatureState.HALF_OPENED,
                separating = false,
            ),
            feature(
                bounds = selectedBounds,
                state = FoldFeatureState.FLAT,
                separating = true,
                occluding = true,
            ),
            feature(
                bounds = FoldBounds(left = 0, top = 898, right = 2_000, bottom = 902),
                orientation = FoldOrientation.HORIZONTAL,
                separating = true,
            ),
        )

        assertEquals(
            DevicePosture.Flat(
                foldBounds = selectedBounds,
                orientation = FoldOrientation.VERTICAL,
                separating = true,
                occluding = true,
            ),
            mapFoldingFeatures(candidates.reversed(), windowBounds),
        )
    }

    @Test
    fun `equivalent vertical folds prefer the one nearest the window center`() {
        val centeredBounds = FoldBounds(left = 995, top = 0, right = 1_005, bottom = 1_800)

        assertEquals(
            DevicePosture.Flat(
                foldBounds = centeredBounds,
                orientation = FoldOrientation.VERTICAL,
            ),
            mapFoldingFeatures(
                features = listOf(
                    feature(bounds = FoldBounds(left = 100, top = 0, right = 110, bottom = 1_800)),
                    feature(bounds = centeredBounds),
                ),
                windowBounds = windowBounds,
            ),
        )
    }

    private fun feature(
        bounds: FoldBounds,
        orientation: FoldOrientation = FoldOrientation.VERTICAL,
        state: FoldFeatureState = FoldFeatureState.FLAT,
        separating: Boolean = false,
        occluding: Boolean = false,
    ): FoldFeatureSnapshot = FoldFeatureSnapshot(
        bounds = bounds,
        orientation = orientation,
        state = state,
        separating = separating,
        occluding = occluding,
    )
}
