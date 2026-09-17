package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosedBookRestingGeometryTest {
    @Test fun wideSinglePageRestsAsAPortraitBookWithoutChangingItsHeightFit() {
        val bounds = Rect(0f, 0f, 1_000f, 1_000f)
        val scale = restingClosedBookScale(canvasWidthPx = 1_000f, canvasHeightPx = 1_200f,
            density = 1f, bookBounds = bounds)

        val visualAspect = bounds.width * scale.horizontal / (bounds.height * scale.vertical)
        assertEquals(RESTING_CLOSED_BOOK_ASPECT_RATIO, visualAspect, .0001f)
        assertTrue(scale.horizontal < scale.vertical)
        assertTrue(scale.vertical in .60f.. .88f)
    }

    @Test fun alreadyPortraitPhoneCoverKeepsUniformScale() {
        val scale = restingClosedBookScale(
            canvasWidthPx = 412f,
            canvasHeightPx = 915f,
            density = 1f,
            bookBounds = Rect(0f, 0f, 412f, 915f),
        )

        assertEquals(scale.vertical, scale.horizontal, .0001f)
    }

    @Test fun coverArtworkIsPreparedAtTheSameRestingAspectInsteadOfBeingSquashed() {
        assertEquals(IntSize(676, 1_000), closedCoverPreparedSize(IntSize(1_000, 1_000)))
        assertEquals(IntSize(412, 915), closedCoverPreparedSize(IntSize(412, 915)))
    }

    @Test fun missingInitialBoundsFallBackToThePreviousUniformRestingScale() {
        val scale = restingClosedBookScale(canvasWidthPx = 0f, canvasHeightPx = 0f,
            density = Float.NaN, bookBounds = null)
        assertTrue(scale.horizontal <= scale.vertical)
        assertTrue(scale.vertical.isFinite())
    }
}
