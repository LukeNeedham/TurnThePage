package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.avstaim.listai.core.designsystem.bookCoverArtworkRect
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import org.junit.Assert.*
import org.junit.Test

class BookCoverArtworkTest {
    @Test fun portraitWideAndSquareArtworkFillsEveryBoardWithoutCroppingSourceEdges() {
        for (image in listOf(IntSize(240, 360), IntSize(420, 210), IntSize(280, 280))) {
            for (board in listOf(Rect(0f, 0f, 154f, 228f), Rect(7f, 7f, 405f, 793f),
                Rect(510f, 4f, 996f, 646f))) {
                val art = bookCoverArtworkRect(image, board)
                assertEquals("The complete image must map to every edge of the board", board, art)
            }
        }
    }

    @Test fun rigidTurnsProjectTheEntireNativePrintWithStableArtworkCoordinates() {
        val left = Rect(4f, 4f, 400f, 646f)
        val right = Rect(412f, 4f, 808f, 646f)
        for (image in listOf(IntSize(240, 360), IntSize(420, 210), IntSize(280, 280))) {
            for (direction in TurnDirection.entries) for (progress in listOf(0f, .3f, .7f, 1f)) {
                val projection = calculateRigidCoverProjection(
                    if (direction == TurnDirection.FORWARD) right else left,
                    if (direction == TurnDirection.FORWARD) left else right, direction, progress)
                val art = bookCoverArtworkRect(image, projection.nativeBounds)
                val projectedWidth = art.width * projection.horizontalScale
                assertEquals(art.width / projection.nativeBounds.width,
                    projectedWidth / projection.projectedBounds.width, .0001f)
                assertEquals(projection.nativeBounds.width / projection.nativeBounds.height * projection.horizontalScale,
                    projectedWidth / art.height, .0001f)
                for (fraction in listOf(.08f, .25f, .75f, .92f)) {
                    val projectedLandmark = (art.left - projection.nativeBounds.left + art.width * fraction) *
                        projection.horizontalScale
                    assertEquals("Artwork must not slide or refit while the rigid board rotates",
                        fraction, projectedLandmark / projection.projectedBounds.width, .0001f)
                }
            }
        }
    }

    @Test fun emptyArtworkOrBoardHasNoDrawableRectangle() {
        assertEquals(Rect.Zero, bookCoverArtworkRect(IntSize.Zero, Rect(0f, 0f, 300f, 500f)))
        assertEquals(Rect.Zero, bookCoverArtworkRect(IntSize(240, 360), Rect.Zero))
        assertEquals(Rect.Zero, bookCoverArtworkRect(IntSize(240, 360), Rect(1f, 0f, 0f, 500f)))
    }
}
