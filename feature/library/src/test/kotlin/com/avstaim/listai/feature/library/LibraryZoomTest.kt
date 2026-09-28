package com.avstaim.listai.feature.library

import com.avstaim.listai.core.model.ShelfZoom
import org.junit.Assert.*
import org.junit.Test

class LibraryZoomTest {
    @Test fun mediumExactlyMatchesTheExistingGeometryOnPhoneAndFold() {
        listOf(320f, 412f, 840f, 1200f).forEach { width ->
            val columns = (width / 164f).toInt().coerceIn(1, 6)
            val expected = ((width - 40f - 24f * (columns - 1)) / columns).coerceAtMost(154f)
            assertEquals(LibraryShelfGeometry(columns, expected), libraryShelfGeometry(width, ShelfZoom.MEDIUM))
        }
    }
    @Test fun pinchCrossesOnlyOneAdjacentLevelAndDoesNotOscillate() {
        val pinch = LibraryPinchGesture(ShelfZoom.MEDIUM)
        assertNull(pinch.update(100f)); assertNull(pinch.update(110f))
        assertEquals(ShelfZoom.LARGE, pinch.update(125f))
        assertNull(pinch.update(60f)); assertNull(pinch.update(300f))
        val shrinking = LibraryPinchGesture(ShelfZoom.MEDIUM)
        shrinking.update(200f)
        assertEquals(ShelfZoom.SMALL, shrinking.update(160f))
    }
    @Test fun extremaAndInvalidSpanDoNotChangeZoom() {
        val pinch = LibraryPinchGesture(ShelfZoom.SMALL)
        assertNull(pinch.update(Float.NaN)); assertNull(pinch.update(0f)); pinch.update(100f)
        assertNull(pinch.update(50f))
    }
    @Test fun scrollingToTopIsNotANewPullAndSecondFingerPreventsSearch() {
        assertFalse(shouldOpenPullSearch(false, false, 0f, 100f, 72f))
        assertFalse(shouldOpenPullSearch(true, true, 0f, 100f, 72f))
        assertFalse(shouldOpenPullSearch(true, false, 100f, 100f, 72f))
        assertTrue(shouldOpenPullSearch(true, false, 4f, 80f, 72f))
    }
}
