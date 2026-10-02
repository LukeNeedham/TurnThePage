package com.avstaim.listai.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLayoutTest {
    @Test
    fun measuredCaptionHeightCanMakeTheLastShelfScrollableWithoutAddingFakeRows() {
        val normal = calculateLibraryLayout(800f, 2, 228f, rowCaptionHeightsDp = listOf(44f, 44f))
        val largeFont = calculateLibraryLayout(800f, 2, 228f, rowCaptionHeightsDp = listOf(72f, 72f))
        assertFalse(normal.scrollable)
        assertTrue(largeFont.scrollable)
        assertEquals(2, largeFont.rowCount)
        assertEquals(0, largeFont.decorativeRowCount)
    }

    @Test
    fun displayCutoutIsExcludedBeforeDecidingWhetherRealRowsFit() {
        val exactFit = calculateLibraryLayout(viewportHeightDp = 708f, rowCount = 2,
            bookHeightDp = 228f, topInsetDp = 32f)
        assertFalse(exactFit.scrollable)
        assertEquals(0, exactFit.decorativeRowCount)
        val cameraAndBottomInset = calculateLibraryLayout(viewportHeightDp = 708f, rowCount = 2,
            bookHeightDp = 228f, topInsetDp = 32f, bottomInsetDp = 1f)
        assertTrue(cameraAndBottomInset.scrollable)
        assertEquals(0, cameraAndBottomInset.decorativeRowCount)
        assertEquals(exactFit.rowCount, cameraAndBottomInset.rowCount)
    }

    @Test
    fun realRowsThatFitViewportDoNotCreateScrolling() {
        val layout = calculateLibraryLayout(viewportHeightDp = 800f, rowCount = 2, bookHeightDp = 228f)

        assertEquals(2, layout.rowCount)
        assertEquals(676f, layout.contentHeightDp, 0.01f)
        assertFalse(layout.scrollable)
        assertEquals(0, layout.decorativeRowCount)
    }

    @Test
    fun onlyOverflowingRealRowsCreateScrolling() {
        val layout = calculateLibraryLayout(viewportHeightDp = 800f, rowCount = 3, bookHeightDp = 228f)

        assertEquals(3, layout.rowCount)
        assertTrue(layout.contentHeightDp > 800f)
        assertTrue(layout.scrollable)
        assertEquals(0, layout.decorativeRowCount)
    }

    @Test
    fun emptyLibraryHasOneStateRowAndStillFitsWithoutDecorativeRows() {
        val layout = calculateLibraryLayout(
            viewportHeightDp = 640f,
            rowCount = 0,
            bookHeightDp = 228f,
            hasEmptyMessage = true,
        )

        assertEquals(1, layout.rowCount)
        assertFalse(layout.scrollable)
        assertEquals(0, layout.decorativeRowCount)
    }

    @Test
    fun oneRealRowGetsOnlyDecorativeRowsThatFitRemainingViewport() {
        val layout = calculateLibraryLayout(viewportHeightDp = 1_000f, rowCount = 1, bookHeightDp = 228f)

        assertFalse(layout.scrollable)
        assertEquals(2, layout.decorativeRowCount)
    }

    @Test
    fun twoRealRowsCanUseOneDecorativeRowWithoutChangingContentHeight() {
        val layout = calculateLibraryLayout(viewportHeightDp = 1_000f, rowCount = 2, bookHeightDp = 228f)

        assertFalse(layout.scrollable)
        assertEquals(1, layout.decorativeRowCount)
        assertEquals(676f, layout.contentHeightDp, 0.01f)
    }
}
