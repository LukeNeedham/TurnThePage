package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Rect
import com.avstaim.listai.core.adaptive.DevicePosture
import com.avstaim.listai.core.adaptive.FoldBounds
import com.avstaim.listai.core.adaptive.FoldOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePostureMappingTest {
    @Test
    fun `unknown posture has no display feature`() {
        assertNull(DevicePosture.Unknown.toBookDisplayFeatureInWindow())
        assertNull(DevicePosture.Flat().toBookDisplayFeatureInWindow())
    }

    @Test
    fun `flat vertical hinge keeps window bounds and safety flags`() {
        val feature = requireNotNull(
            DevicePosture.Flat(
                foldBounds = FoldBounds(488, 0, 512, 700),
                orientation = FoldOrientation.VERTICAL,
                separating = true,
                occluding = true,
            ).toBookDisplayFeatureInWindow(),
        )

        assertEquals(Rect(488f, 0f, 512f, 700f), feature.bounds)
        assertEquals(BookFoldOrientation.VERTICAL, feature.orientation)
        assertTrue(feature.avoidsContent)
    }

    @Test
    fun `half-opened horizontal fold remains distinguishable`() {
        val feature = requireNotNull(
            DevicePosture.HalfOpened(
                foldBounds = FoldBounds(0, 338, 1_000, 362),
                orientation = FoldOrientation.HORIZONTAL,
                occluding = false,
            ).toBookDisplayFeatureInWindow(),
        )

        assertEquals(BookFoldOrientation.HORIZONTAL, feature.orientation)
        assertTrue(feature.halfOpened)
        assertTrue(feature.separating)
    }
}
