package com.avstaim.listai.feature.reader.bookview

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.designsystem.BookCoverFace
import com.avstaim.listai.core.designsystem.BookCoverSurface
import com.avstaim.listai.core.designsystem.bindingColor
import com.avstaim.listai.core.designsystem.drawBookCover
import com.avstaim.listai.reader.rendering.geometry.PageCurlGeometryEngine
import com.avstaim.listai.reader.rendering.geometry.Point
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OriginalBookCoverSurfaceTest {
    @Test fun completeArtworkFillsTheBoardInBothThemesAtEveryAspectRatio() {
        for (imageSize in imageSizes) for (board in listOf(IntSize(154, 228), IntSize(398, 786))) {
            val light = surface(imageSize, board)
            val dark = light.copy(darkTheme = true)
            val before = draw(board) { drawBookCover(light, Rect(0f, 0f, size.width, size.height)) }
            val after = draw(board) { drawBookCover(dark, Rect(0f, 0f, size.width, size.height)) }
            assertTrue(before.sameAs(after))
            assertEquals(WarmInk, before.getPixel(board.width / 2, board.height / 2))
            // Distinct bands reach the top and bottom of the board; a uniform letterbox cannot
            // pass these checks. All samples avoid the narrow physical spine and edge shading.
            for (x in listOf(.2f, .8f)) {
                for ((y, color) in listOf(.05f to TopBand, .35f to UpperBand,
                    .65f to LowerBand, .95f to BottomBand)) {
                    assertEquals("Source band at $x,$y must fill the board for $imageSize on $board",
                        color, before.getPixel((board.width * x).toInt(), (board.height * y).toInt()))
                }
            }
            // These source corner marks would be lost by center-cropping the wide cover.
            assertEquals(TopMark, before.getPixel((board.width * .08f).toInt(), (board.height * .05f).toInt()))
            assertEquals(BottomMark, before.getPixel((board.width * .92f).toInt(), (board.height * .95f).toInt()))
            assertNoBlueMaterial(before)
            before.recycle()
            after.recycle()
        }
    }

    @Test fun exposedBindingUsesMatteGraphiteEvenWhenOnlyPaperIsVisible() {
        for (dark in listOf(false, true)) for (presentation in BookPresentation.entries) {
            val canvasSize = IntSize(if (presentation == BookPresentation.SINGLE) 420 else 840, 660)
            val layout = calculateBookCanvasLayout(Size(canvasSize.width.toFloat(), 660f), presentation,
                outerPaddingPx = 10f, coverInsetPx = 6f)
            val artwork = surface(IntSize(420, 210), IntSize(layout.pageWidth.toInt(), layout.pageHeight.toInt()))
            assertEquals(Graphite, requireNotNull(artwork.bindingColor).toArgb())
            val paper = if (dark) BookVisualSurface.DarkPlainPaper else BookVisualSurface.PlainPaper
            val result = draw(canvasSize) { drawPhysicalBook(layout,
                VisibleBookSurfaces(paper.takeIf { presentation == BookPresentation.SPREAD }, paper),
                .4f, PhysicalBookPose.OPEN, coverSurface = artwork) }
            assertEquals(Graphite, result.getPixel(layout.rightPage.center.x.toInt(), layout.cover.top.toInt() + 2))
            assertNoBlueMaterial(result)
            result.recycle()
        }
    }

    @Test fun backCoverUsesGraphiteInsteadOfArtworkOrSampledEdgeColors() {
        val board = IntSize(398, 786)
        for (dark in listOf(false, true)) {
            val artwork = surface(IntSize(420, 210), board).copy(darkTheme = dark)
            val result = draw(board) {
                drawBookCover(artwork, Rect(0f, 0f, size.width, size.height), BookCoverFace.BACK)
            }
            for (x in listOf(.2f, .5f, .8f)) for (y in listOf(.1f, .5f, .9f)) {
                assertEquals(Graphite, result.getPixel((board.width * x).toInt(), (board.height * y).toInt()))
            }
            assertNoBlueMaterial(result)
            result.recycle()
        }
    }

    @Test fun innerCoverKeepsItsBindingMaterialInBothThemesAndPageModes() {
        for (presentation in BookPresentation.entries) for (dark in listOf(false, true)) {
            val canvasSize = IntSize(if (presentation == BookPresentation.SINGLE) 420 else 840, 660)
            val layout = calculateBookCanvasLayout(Size(canvasSize.width.toFloat(), 660f), presentation,
                outerPaddingPx = 10f, coverInsetPx = 6f)
            val board = IntSize(layout.pageWidth.toInt(), layout.pageHeight.toInt())
            for (artwork in listOf(surface(IntSize(420, 210), board), BookCoverSurface(null, null, board))) {
                val cover = artwork.copy(darkTheme = dark)
                val outerBack = BookVisualSurface.Cover(cover, BookCoverFace.BACK)
                val innerBack = outerBack.copy(isInside = true)
                val paper = if (dark) BookVisualSurface.DarkPlainPaper else BookVisualSurface.PlainPaper
                fun visible(back: BookVisualSurface.Cover) = if (presentation == BookPresentation.SPREAD)
                    VisibleBookSurfaces(back, paper) else VisibleBookSurfaces(null, back)
                val expected = draw(canvasSize) {
                    drawPhysicalBook(layout, visible(outerBack), 0f, PhysicalBookPose.OPEN, coverSurface = cover)
                }
                val actual = draw(canvasSize) {
                    drawPhysicalBook(layout, visible(innerBack), 0f, PhysicalBookPose.OPEN, coverSurface = cover)
                }
                assertTrue("Inner cover must retain printed or generated binding, regardless of paper theme",
                    expected.sameAs(actual))
                val bounds = layout.leftPage ?: layout.rightPage
                val boardPixel = actual.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt())
                assertNotEquals("A rigid inner board must not become themed paper",
                    if (dark) DarkPaper.toArgb() else android.graphics.Color.WHITE, boardPixel)
                expected.recycle()
                actual.recycle()
            }
        }
    }

    @Test fun innerCoverKeepsItsBindingMaterialThroughoutRigidOpeningAndClosing() {
        val canvasSize = IntSize(840, 660)
        val layout = calculateBookCanvasLayout(Size(840f, 660f), BookPresentation.SPREAD,
            outerPaddingPx = 10f, coverInsetPx = 6f)
        val board = IntSize(layout.pageWidth.toInt(), layout.pageHeight.toInt())
        for (dark in listOf(false, true)) for (artwork in listOf(
            surface(IntSize(420, 210), board), BookCoverSurface(null, null, board))) {
            val cover = artwork.copy(darkTheme = dark)
            val front = BookVisualSurface.Cover(cover, BookCoverFace.FRONT)
            val back = BookVisualSurface.Cover(cover, BookCoverFace.BACK)
            val paper = if (dark) BookVisualSurface.DarkPlainPaper else BookVisualSurface.PlainPaper
            for (direction in TurnDirection.entries) for (progress in listOf(.15f, .35f, .65f, .85f, 1f)) {
                val forward = direction == TurnDirection.FORWARD
                val source = if (forward) layout.rightPage else requireNotNull(layout.leftPage)
                val destination = if (forward) requireNotNull(layout.leftPage) else layout.rightPage
                val geometry = PageCurlGeometryEngine().calculate(source.toCurlRect(),
                    Point(source.right.toDouble(), source.bottom.toDouble()), direction,
                    destinationBounds = destination.toCurlRect()).copy(progress = progress.toDouble())
                fun frame(inner: Boolean) = CurlFrame(direction, geometry,
                    if (forward) front else back.copy(isInside = inner), source,
                    if (forward) PageSide.RIGHT else PageSide.LEFT,
                    if (forward) back.copy(isInside = inner) else front, destination)
                val expected = draw(canvasSize) { drawPhysicalBook(layout, VisibleBookSurfaces(paper, paper),
                    0f, PhysicalBookPose.OPEN, frame(false), cover) }
                val actual = draw(canvasSize) { drawPhysicalBook(layout, VisibleBookSurfaces(paper, paper),
                    0f, PhysicalBookPose.OPEN, frame(true), cover) }
                assertTrue("Inside binding must remain attached during $direction at $progress",
                    expected.sameAs(actual))
                expected.recycle()
                actual.recycle()
            }
        }
    }

    @Test fun transparentArtworkUsesWhiteStockInBothThemes() {
        val board = IntSize(154, 228)
        val transparent = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888).asImageBitmap()
        for (dark in listOf(false, true)) {
            val artwork = BookCoverSurface(null, null, board, transparent, darkTheme = dark)
            val result = draw(board) { drawBookCover(artwork, Rect(0f, 0f, size.width, size.height)) }
            assertEquals(android.graphics.Color.WHITE, result.getPixel(board.width / 2, board.height / 2))
            assertNoBlueMaterial(result)
            result.recycle()
        }
    }

    @Test fun forwardAndBackwardRigidTurnsHaveNoGeneratedBlueAtAnyPhaseInEitherTheme() {
        val canvasSize = IntSize(840, 660)
        val layout = calculateBookCanvasLayout(Size(840f, 660f), BookPresentation.SPREAD,
            outerPaddingPx = 10f, coverInsetPx = 6f)
        for (imageSize in imageSizes) for (dark in listOf(false, true)) {
            val artwork = surface(imageSize, IntSize(layout.pageWidth.toInt(), layout.pageHeight.toInt())).copy(darkTheme = dark)
            for (direction in TurnDirection.entries) for (progress in listOf(0f, .3f, .5f, .7f, 1f)) {
                val forward = direction == TurnDirection.FORWARD
                val source = if (forward) layout.rightPage else requireNotNull(layout.leftPage)
                val destination = if (forward) requireNotNull(layout.leftPage) else layout.rightPage
                val geometry = PageCurlGeometryEngine().calculate(source.toCurlRect(),
                    Point(source.right.toDouble(), source.bottom.toDouble()), direction,
                    destinationBounds = destination.toCurlRect()).copy(progress = progress.toDouble())
                val front = BookVisualSurface.Cover(artwork, BookCoverFace.FRONT)
                val back = BookVisualSurface.Cover(artwork, BookCoverFace.BACK)
                val frame = CurlFrame(direction, geometry, if (forward) front else back, source,
                    if (forward) PageSide.RIGHT else PageSide.LEFT, if (forward) back else front, destination)
                val paper = if (dark) BookVisualSurface.DarkPlainPaper else BookVisualSurface.PlainPaper
                val result = draw(canvasSize) { drawPhysicalBook(layout, VisibleBookSurfaces(paper, paper),
                    0f, PhysicalBookPose.OPEN, frame, artwork) }
                assertNoBlueMaterial(result)
                val projection = calculateRigidCoverProjection(source, destination, direction, progress)
                if ((forward && progress < .5f) || (!forward && progress > .5f)) {
                    assertEquals(WarmInk, result.getPixel(projection.projectedBounds.center.x.toInt(),
                        projection.projectedBounds.center.y.toInt()))
                    val projected = projection.projectedBounds
                    for ((fraction, color) in listOf(.05f to TopBand, .95f to BottomBand)) {
                        assertEquals("A rotating cover must retain its full-height source bands",
                            color, result.getPixel((projected.left + projected.width * .2f).toInt(),
                                (projected.top + projected.height * fraction).toInt()))
                    }
                }
                result.recycle()
            }
        }
    }

    @Test fun preparedCoverAndBindingRemainUnchangedWhenAnotherSurfaceIsPrepared() {
        val board = IntSize(398, 786)
        val pinned = surface(IntSize(280, 280), board)
        val before = draw(board) { drawBookCover(pinned, Rect(0f, 0f, size.width, size.height)) }
        val later = surface(IntSize(240, 360), IntSize(486, 642))
        assertNotSame(pinned, later)
        val after = draw(board) { drawBookCover(pinned, Rect(0f, 0f, size.width, size.height)) }
        assertTrue(before.sameAs(after))
        assertEquals(board, pinned.preparedSize)
        assertTrue(pinned.isReady)
        before.recycle()
        after.recycle()
    }

    private fun surface(imageSize: IntSize, board: IntSize): BookCoverSurface {
        val bitmap = Bitmap.createBitmap(imageSize.width, imageSize.height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val paint = Paint()
        listOf(TopBand, UpperBand, LowerBand, BottomBand).forEachIndexed { index, color ->
            paint.color = color
            canvas.drawRect(0f, imageSize.height * index / 4f,
                imageSize.width.toFloat(), imageSize.height * (index + 1) / 4f, paint)
        }
        paint.color = WarmInk
        canvas.drawCircle(imageSize.width / 2f, imageSize.height / 2f,
            minOf(imageSize.width, imageSize.height) * .2f, paint)
        paint.color = TopMark
        canvas.drawRect(imageSize.width * .055f, imageSize.height * .025f,
            imageSize.width * .12f, imageSize.height * .09f, paint)
        paint.color = BottomMark
        canvas.drawRect(imageSize.width * .88f, imageSize.height * .91f,
            imageSize.width * .945f, imageSize.height * .975f, paint)
        val image = bitmap.asImageBitmap()
        return BookCoverSurface(null, null, board, image)
    }

    private fun draw(size: IntSize, content: DrawScope.() -> Unit): Bitmap {
        val result = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(result.asImageBitmap()),
            Size(size.width.toFloat(), size.height.toFloat()), content)
        return result
    }

    private fun assertNoBlueMaterial(bitmap: Bitmap) {
        val row = IntArray(bitmap.width)
        repeat(bitmap.height) { y ->
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            row.forEachIndexed { x, pixel ->
                // Graphite binding has B-R = 17 and graphite paper B-R = 16. Generated blue
                // cloth and its outline exceed 22; the fixture itself contains only warm ink.
                // Unpremultiplication amplifies rounding at translucent anti-aliased edges.
                // Compare visible chroma on neutral stock; the opaque threshold stays 22.
                val visibleBlueExcess = (android.graphics.Color.blue(pixel) - android.graphics.Color.red(pixel)) *
                    android.graphics.Color.alpha(pixel)
                if (visibleBlueExcess > 22 * 255) {
                    fail("Unexpected blue material at $x,$y: ${pixel.toUInt().toString(16)}")
                }
            }
        }
    }

    private val imageSizes = listOf(IntSize(240, 360), IntSize(420, 210), IntSize(280, 280))
    private companion object {
        const val WarmInk = 0xff8a492d.toInt()
        const val TopBand = 0xffd7bd99.toInt()
        const val UpperBand = 0xffcfab7d.toInt()
        const val LowerBand = 0xffba936c.toInt()
        const val BottomBand = 0xffa77c55.toInt()
        const val TopMark = 0xffc07b46.toInt()
        const val BottomMark = 0xff6c643b.toInt()
        const val Graphite = 0xff29313a.toInt()
    }
}
