package com.avstaim.listai.feature.reader.bookview

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.model.PageId
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.PageMode
import com.avstaim.listai.reader.rendering.geometry.PageCurlGeometryEngine
import com.avstaim.listai.reader.rendering.geometry.Point
import com.avstaim.listai.reader.rendering.turn.CurlOrigin
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookMaterialSurfaceTest {
    private val layout = calculateBookCanvasLayout(Size(840f, 660f), BookPresentation.SPREAD, 10f, 6f)

    @Test fun paperInteriorStaysPureWhiteAtEveryStackDepth() {
        for (progress in listOf(0f, .25f, .5f, .75f, 1f)) {
            val result = draw { drawPhysicalBook(layout,
                VisibleBookSurfaces(BookVisualSurface.PlainPaper, BookVisualSurface.PlainPaper),
                progress, PhysicalBookPose.OPEN) }
            for (page in listOf(requireNotNull(layout.leftPage), layout.rightPage)) {
                for (x in listOf(.2f, .5f, .8f)) for (y in listOf(.1f, .5f, .9f)) {
                    assertEquals(android.graphics.Color.WHITE,
                        result.getPixel((page.left + page.width * x).toInt(), (page.top + page.height * y).toInt()))
                }
            }
        }
    }

    @Test fun finalCurlHasTheSameOpaqueArtworkAndFooterInkAsTheRestingVerso() {
        for (dark in listOf(false, true)) for (direction in TurnDirection.entries) {
            val sourceBounds = layout.sourcePage(direction)
            val destination = requireNotNull(layout.destinationPage(direction))
            val verso = preparedInkSurface(dark)
            val front = if (dark) BookVisualSurface.DarkPlainPaper else BookVisualSurface.PlainPaper
            val baseline = if (direction == TurnDirection.FORWARD) VisibleBookSurfaces(verso, front)
                else VisibleBookSurfaces(front, verso)
            val start = PageCurlGeometryEngine().calculate(sourceBounds.toCurlRect(),
                Point(if (direction == TurnDirection.FORWARD) sourceBounds.right.toDouble() else sourceBounds.left.toDouble(),
                    sourceBounds.center.y.toDouble()), direction, CurlOrigin.OUTER_EDGE, sourceBounds.center.y.toDouble(),
                destinationBounds = destination.toCurlRect())
            val settled = PageCurlGeometryEngine().calculate(sourceBounds.toCurlRect(), start.completionPointer,
                direction, CurlOrigin.OUTER_EDGE, sourceBounds.center.y.toDouble(),
                destinationBounds = destination.toCurlRect())
            val frame = CurlFrame(direction, settled, front, sourceBounds,
                if (direction == TurnDirection.FORWARD) PageSide.RIGHT else PageSide.LEFT, verso, destination)
            val expected = draw { drawPhysicalBook(layout, baseline, .5f, PhysicalBookPose.OPEN) }
            val actual = draw { drawPhysicalBook(layout, baseline, .5f, PhysicalBookPose.OPEN, frame) }
            // Include source-color illustration pixels, footer ink, and untouched paper; exclude
            // only the physical edge/spine whose occlusion legitimately changes layer order.
            for (y in 50 until 610) for (x in 90 until 320) {
                val px = destination.left.toInt() + x
                val py = destination.top.toInt() + y
                assertEquals("Settled leaf must not pop or retain a curl lighting field at $px,$py",
                    expected.getPixel(px, py), actual.getPixel(px, py))
            }
            assertEquals(ARTWORK_COLOR, actual.getPixel(destination.center.x.toInt(), destination.center.y.toInt()))
            assertEquals(if (dark) DarkPaper.toArgb() else android.graphics.Color.WHITE,
                actual.getPixel((destination.left + 100).toInt(), (destination.top + 100).toInt()))
        }
    }

    private fun preparedInkSurface(dark: Boolean): BookVisualSurface.Page {
        val width = layout.pageWidth.toInt()
        val height = layout.pageHeight.toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val paint = Paint().apply { color = ARTWORK_COLOR }
        canvas.drawRect(width * .35f, height * .35f, width * .65f, height * .65f, paint)
        paint.color = if (dark) 0xFFE3EAF0.toInt() else 0xFF242A30.toInt()
        canvas.drawRect(width * .15f, height * .88f, width * .85f, height * .885f, paint)
        val key = LayoutKey(width, height, PageMode.SINGLE, darkTheme = dark)
        return BookVisualSurface.Page(PaginatedPageSurface(PageId(1), bitmap, "Printed verso", dark, key).asBookSurface(1))
    }

    private fun draw(block: DrawScope.() -> Unit): Bitmap =
        Bitmap.createBitmap(840, 660, Bitmap.Config.ARGB_8888).also {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(AndroidCanvas(it)), Size(840f, 660f), block)
        }

    private companion object { const val ARTWORK_COLOR = 0xFFB2744E.toInt() }
}
