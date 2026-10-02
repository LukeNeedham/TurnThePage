package com.avstaim.listai.feature.reader

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class BookImageTransitionTest {
    @Test fun `entry and exit use the exact printed rectangle and fitted target endpoints`() {
        val printed = Rect(62f, 150f, 350f, 352f)
        val fitted = Rect(0f, 281f, 412f, 570f)
        assertEquals(printed, bookImageTransitionBounds(printed, fitted, 0f))
        assertEquals(fitted, bookImageTransitionBounds(printed, fitted, 1f))
        assertEquals(Rect(31f, 215.5f, 381f, 461f), bookImageTransitionBounds(printed, fitted, .5f))
        assertEquals(printed, bookImageTransitionBounds(printed, fitted, -1f))
    }

    @Test fun `zoomed dismissal returns the whole original image to its printed bounds`() {
        val fitted = Rect(50f, 100f, 350f, 300f)
        val zoomed = bookImageZoomBounds(fitted, BookImageZoom(2f, -30f, 40f))
        assertEquals(Rect(-130f, 40f, 470f, 440f), zoomed)
        val printed = Rect(80f, 400f, 230f, 500f)
        assertEquals(zoomed, bookImageTransitionBounds(printed, zoomed, 1f))
        assertEquals(printed, bookImageTransitionBounds(printed, zoomed, 0f))
    }

    @Test fun `invalid or absent printed bounds degrade to an image fade without invalid geometry`() {
        val fitted = Rect(50f, 100f, 350f, 300f)
        assertEquals(fitted, bookImageTransitionBounds(null, fitted, .3f))
        assertEquals(fitted, bookImageTransitionBounds(Rect(Float.NaN, 0f, 10f, 10f), fitted, .3f))
        assertEquals(fitted, bookImageTransitionBounds(Rect.Zero, fitted, .3f))
    }

    @Test fun `held zoom restores exact preceding scale and pan at release endpoint`() {
        val viewport = bookImageViewport(400f, 400f, 1000, 1000)
        val preceding = BookImageZoom(2f, 40f, -20f)
        val held = viewport.transform(preceding, 250f, 150f, zoomFactor = 1.8f)
        assertEquals(preceding, interpolateBookImageZoom(preceding, held, 0f))
        assertEquals(held, interpolateBookImageZoom(preceding, held, 1f))
        assertEquals(2.8f, interpolateBookImageZoom(preceding, held, .5f).scale, .001f)
        assertEquals(preceding, interpolateBookImageZoom(preceding, held, 0f))
    }
}
