package com.avstaim.listai.feature.library

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class LibraryReturnToBookTest {
    private val viewport = Rect(0f, 50f, 400f, 620f)

    @Test fun onlyFullyVisibleMeasuredCoversAreReturnDestinations() {
        val cover = Rect(120f, 80f, 220f, 228f)
        assertEquals(cover, visibleReturnBookBounds(cover, viewport))
        assertNull(visibleReturnBookBounds(null, viewport))
        assertNull(visibleReturnBookBounds(Rect.Zero, viewport))
        assertNull(visibleReturnBookBounds(cover.translate(0f, -60f), viewport))
        assertNull(visibleReturnBookBounds(cover.translate(0f, 450f), viewport))
        assertNull(visibleReturnBookBounds(cover.translate(400f, 0f), viewport))
    }

    @Test fun floatingControlsAreExcludedFromTheDestinationViewport() {
        val behindControls = Rect(120f, 560f, 220f, 708f)
        assertNull(visibleReturnBookBounds(behindControls, viewport))
        assertEquals(behindControls, visibleReturnBookBounds(behindControls, viewport.copy(bottom = 800f)))
    }
}
