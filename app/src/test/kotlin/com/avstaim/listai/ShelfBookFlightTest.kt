package com.avstaim.listai

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.avstaim.listai.core.designsystem.bookCoverArtworkRect
import com.avstaim.listai.feature.library.shelfBookFlightRect
import com.avstaim.listai.feature.library.shelfBookSourceRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfBookFlightTest {
    private val source = Rect(20f, 54f, 174f, 282f)
    private val destination = Rect(7f, 7f, 405f, 793f)

    @Test fun flightRetainsExactShelfAndReaderEndpointsInBothDirections() {
        assertEquals(source, shelfBookFlightRect(source, destination, 0f))
        assertEquals(destination, shelfBookFlightRect(source, destination, 1f))
        assertEquals(source, shelfBookFlightRect(source, destination, -1f))
        assertEquals(destination, shelfBookFlightRect(source, destination, 2f))
        val widths = listOf(0f, .25f, .5f, .75f, 1f).map { shelfBookFlightRect(source, destination, it).width }
        assertTrue(widths.zipWithNext().all { (before, after) -> before < after })
    }

    @Test fun flightLiftsAboveLinearPathAndKeepsPositiveBoardGeometry() {
        val midway = shelfBookFlightRect(source, destination, .5f)
        assertTrue(midway.top < (source.top + destination.top) / 2f)
        assertEquals((source.width + destination.width) / 2f, midway.width, .001f)
        assertEquals((source.height + destination.height) / 2f, midway.height, .001f)
        for (step in 0..100) {
            assertTrue("The printed title must remain inside the viewport during the lift",
                shelfBookFlightRect(source, destination, step / 100f).top >= 0f)
        }
    }

    @Test fun missingOrOffscreenBookUsesFiniteLowerShelfTrajectoryAfterResize() {
        assertEquals(source, shelfBookSourceRect(source, Size(412f, 800f), 1f))
        val missing = shelfBookSourceRect(null, Size(800f, 412f), 2f)
        assertTrue(missing.top > 412f)
        assertTrue(missing.left >= 0f && missing.right <= 800f)
        assertEquals(missing, shelfBookSourceRect(Rect(0f, -280f, 154f, -52f), Size(800f, 412f), 2f))
    }

    @Test fun portraitWideAndSquareArtworkFillTheFlyingBoardContinuouslyAtEveryKeyframe() {
        for (image in listOf(IntSize(240, 360), IntSize(420, 210), IntSize(280, 280))) {
            for (progress in listOf(0f, .3f, .7f, 1f)) {
                val board = shelfBookFlightRect(source, destination, progress)
                val imageRect = bookCoverArtworkRect(image, board)
                assertEquals("The artwork must reach the same board edges on the shelf, in flight and in the reader",
                    board, imageRect)
                for (fraction in listOf(.08f, .5f, .92f)) {
                    val landmarkX = imageRect.left + imageRect.width * fraction
                    val landmarkY = imageRect.top + imageRect.height * fraction
                    assertEquals(fraction, (landmarkX - board.left) / board.width, .0001f)
                    assertEquals(fraction, (landmarkY - board.top) / board.height, .0001f)
                }
            }
        }
    }
}
