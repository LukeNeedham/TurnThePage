package com.avstaim.listai.feature.reader

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosedBookControlsTest {
    @Test fun `front cover on the right puts a vertical control stack entirely on the left`() {
        val cover = Rect(510f, 20f, 990f, 980f)
        val placement = requireNotNull(closedBookControlPlacement(
            Rect(0f, 0f, 1_000f, 1_000f), cover, density = 1f))

        assertTrue(placement.bounds.right < cover.left)
        assertEquals(6 * placement.itemHeightPx + 5 * placement.spacingPx,
            placement.bounds.height, .001f)
        assertTrue(placement.itemHeightPx >= 48f)
        assertFalse(placement.overlaysCover)
    }

    @Test fun `back cover on the left uses the free right half`() {
        val cover = Rect(10f, 20f, 490f, 980f)
        val placement = requireNotNull(closedBookControlPlacement(
            Rect(0f, 0f, 1_000f, 1_000f), cover, density = 1f))

        assertTrue(placement.bounds.left > cover.right)
    }

    @Test fun `single cover keeps native size and receives a left aligned overlay`() {
        val cover = Rect(4f, 4f, 408f, 911f)
        val placement = requireNotNull(closedBookControlPlacement(
            Rect(0f, 0f, 412f, 915f), cover, density = 1f, overlayOnCover = true))

        assertTrue(placement.overlaysCover)
        assertTrue(cover.contains(placement.bounds.topLeft))
        assertTrue(cover.contains(placement.bounds.bottomRight))
        assertEquals(cover.left + 12f, placement.bounds.left, .001f)
    }
}
