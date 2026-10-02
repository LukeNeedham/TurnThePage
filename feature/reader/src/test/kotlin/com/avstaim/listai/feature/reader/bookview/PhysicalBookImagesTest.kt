package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhysicalBookImagesTest {
    @Test fun mapsIllustrationToThePaintedSideOfASpreadWithoutExpandingIntoText() {
        val image = Rect(20f, 50f, 180f, 150f)
        val left = imageTapBounds(image, Rect(10f, 30f, 410f, 630f), 200, 300)
        val right = imageTapBounds(image, Rect(430f, 30f, 830f, 630f), 200, 300)
        assertEquals(Rect(50f, 130f, 370f, 330f), left)
        assertEquals(Rect(470f, 130f, 790f, 330f), right)
        assertFalse(left.contains(Offset(420f, 230f)))
        assertFalse(right.contains(Offset(500f, 129f)))
    }

    @Test fun usesTheSurfaceDimensionsForSupplementalLeavesAndClipsToPaper() {
        val leaf = Rect(20f, 40f, 320f, 640f)
        assertEquals(Rect(50f, 100f, 290f, 400f),
            imageTapBounds(Rect(60f, 120f, 540f, 720f), leaf, 600, 1200))
        assertEquals(leaf, imageTapBounds(Rect(-10f, -10f, 610f, 1210f), leaf, 600, 1200))
    }

    @Test fun cropsTheRetainedRasterAtItsActualResolutionWithoutLosingEdgePixels() {
        assertEquals(IntRect(30, 60, 271, 361),
            imagePreviewCrop(Rect(60.2f, 120.2f, 540.2f, 720.2f), 600, 1200, 300, 600))
        assertEquals(IntRect(0, 0, 300, 600),
            imagePreviewCrop(Rect(-10f, -10f, 610f, 1210f), 600, 1200, 300, 600))
    }
}
