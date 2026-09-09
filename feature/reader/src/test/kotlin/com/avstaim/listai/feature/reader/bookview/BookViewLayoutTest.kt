package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.avstaim.listai.reader.rendering.geometry.AffineTransform2D
import com.avstaim.listai.reader.rendering.geometry.PageCurlGeometryEngine
import com.avstaim.listai.reader.rendering.geometry.Point
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookViewLayoutTest {
    @Test
    fun `reading paper is pure white and the supporting palette is neutral`() {
        assertEquals(Color.White, WhitePaper)
        assertEquals(PaperInk.red, PaperInk.green, TOLERANCE)
        assertEquals(PaperInk.green, PaperInk.blue, TOLERANCE)
        assertEquals(PaperEdge.red, PaperEdge.green, TOLERANCE)
        assertEquals(PaperEdge.green, PaperEdge.blue, TOLERANCE)
    }

    @Test
    fun `single page fills padded viewport`() {
        val layout = calculateBookCanvasLayout(
            canvasSize = Size(412f, 915f),
            presentation = BookPresentation.SINGLE,
            outerPaddingPx = 14f,
            coverInsetPx = 7f,
        )

        assertEquals(null, layout.leftPage)
        assertEquals(14f, layout.rightPage.left, TOLERANCE)
        assertEquals(14f, layout.rightPage.top, TOLERANCE)
        assertEquals(398f, layout.rightPage.right, TOLERANCE)
        assertEquals(901f, layout.rightPage.bottom, TOLERANCE)
        assertEquals(206f, layout.rightPage.center.x, TOLERANCE)
        assertEquals(457.5f, layout.rightPage.center.y, TOLERANCE)
    }

    @Test
    fun `fullscreen padding leaves only a one pixel cover margin`() {
        val layout = calculateBookCanvasLayout(
            canvasSize = Size(412f, 915f),
            presentation = BookPresentation.SINGLE,
            outerPaddingPx = 4f,
            coverInsetPx = 3f,
        )

        assertEquals(4f, layout.rightPage.left, TOLERANCE)
        assertEquals(4f, layout.rightPage.top, TOLERANCE)
        assertEquals(408f, layout.rightPage.right, TOLERANCE)
        assertEquals(911f, layout.rightPage.bottom, TOLERANCE)
        assertEquals(1f, layout.cover.left, TOLERANCE)
        assertEquals(1f, layout.cover.top, TOLERANCE)
        assertEquals(411f, layout.cover.right, TOLERANCE)
        assertEquals(914f, layout.cover.bottom, TOLERANCE)
    }

    @Test
    fun `spread has equal pages joined at one spine`() {
        val layout = calculateBookCanvasLayout(
            canvasSize = Size(1_000f, 700f),
            presentation = BookPresentation.SPREAD,
            outerPaddingPx = 14f,
            coverInsetPx = 7f,
        )
        val left = requireNotNull(layout.leftPage)

        assertEquals(left.width, layout.rightPage.width, TOLERANCE)
        assertEquals(left.right, layout.rightPage.left, TOLERANCE)
        assertEquals(layout.spineX, left.right, TOLERANCE)
        assertEquals(14f, left.left, TOLERANCE)
        assertEquals(986f, layout.rightPage.right, TOLERANCE)
        assertEquals(14f, left.top, TOLERANCE)
        assertEquals(686f, left.bottom, TOLERANCE)
        assertTrue(layout.cover.contains(left.topLeft))
        assertTrue(layout.cover.contains(layout.rightPage.bottomRight))
    }

    @Test
    fun `vertical occluding hinge separates leaves and owns the physical spine`() {
        val hinge = BookDisplayFeature(
            bounds = androidx.compose.ui.geometry.Rect(488f, 0f, 512f, 700f),
            orientation = BookFoldOrientation.VERTICAL,
            separating = true,
            occluding = true,
            halfOpened = false,
        )
        val layout = calculateBookCanvasLayout(
            canvasSize = Size(1_000f, 700f),
            presentation = BookPresentation.SPREAD,
            outerPaddingPx = 14f,
            coverInsetPx = 7f,
            displayFeature = hinge,
        )
        val left = requireNotNull(layout.leftPage)

        assertEquals(488f, left.right, TOLERANCE)
        assertEquals(512f, layout.rightPage.left, TOLERANCE)
        assertEquals(500f, layout.spineX, TOLERANCE)
        assertEquals(hinge.bounds.left, requireNotNull(layout.spineBounds).left, TOLERANCE)
        assertEquals(hinge.bounds.right, layout.spineBounds.right, TOLERANCE)
        assertEquals(left.width, layout.rightPage.width, TOLERANCE)
        assertEquals(14f, left.left, TOLERANCE)
        assertEquals(986f, layout.rightPage.right, TOLERANCE)
        assertEquals(14f, left.top, TOLERANCE)
        assertEquals(686f, layout.rightPage.bottom, TOLERANCE)
        assertEquals(
            left.left.toDouble(),
            layout.completionPointer(TurnDirection.FORWARD).x,
            TOLERANCE.toDouble(),
        )
        assertEquals(
            layout.rightPage.right.toDouble(),
            layout.completionPointer(TurnDirection.BACKWARD).x,
            TOLERANCE.toDouble(),
        )
    }

    @Test
    fun `off-center fold anchors book instead of canvas center`() {
        val hinge = BookDisplayFeature(
            bounds = androidx.compose.ui.geometry.Rect(440f, 0f, 464f, 700f),
            orientation = BookFoldOrientation.VERTICAL,
            separating = true,
            occluding = true,
            halfOpened = false,
        )
        val layout = calculateBookCanvasLayout(
            canvasSize = Size(1_000f, 700f),
            presentation = BookPresentation.SPREAD,
            outerPaddingPx = 14f,
            coverInsetPx = 7f,
            displayFeature = hinge,
        )

        assertEquals(452f, layout.spineX, TOLERANCE)
        assertTrue(layout.spineX < 500f)
        assertEquals(14f, requireNotNull(layout.leftPage).left, TOLERANCE)
        assertTrue(layout.rightPage.right < 1_000f - 14f)
        assertEquals(14f, layout.rightPage.top, TOLERANCE)
        assertEquals(686f, layout.rightPage.bottom, TOLERANCE)
    }

    @Test
    fun `single page stays inside safe pane of horizontal hinge`() {
        val hinge = BookDisplayFeature(
            bounds = androidx.compose.ui.geometry.Rect(0f, 338f, 1_000f, 362f),
            orientation = BookFoldOrientation.HORIZONTAL,
            separating = true,
            occluding = true,
            halfOpened = true,
        )
        val layout = calculateBookCanvasLayout(
            canvasSize = Size(1_000f, 700f),
            presentation = BookPresentation.SINGLE,
            outerPaddingPx = 14f,
            coverInsetPx = 7f,
            displayFeature = hinge,
        )

        assertEquals(14f, layout.rightPage.left, TOLERANCE)
        assertEquals(986f, layout.rightPage.right, TOLERANCE)
        assertEquals(14f, layout.rightPage.top, TOLERANCE)
        assertEquals(324f, layout.rightPage.bottom, TOLERANCE)
    }

    @Test
    fun `single page uses larger pane when vertical hinge cannot form spread`() {
        val hinge = BookDisplayFeature(
            bounds = androidx.compose.ui.geometry.Rect(250f, 0f, 274f, 700f),
            orientation = BookFoldOrientation.VERTICAL,
            separating = true,
            occluding = true,
            halfOpened = false,
        )
        val layout = calculateBookCanvasLayout(
            canvasSize = Size(1_000f, 700f),
            presentation = BookPresentation.SINGLE,
            outerPaddingPx = 14f,
            coverInsetPx = 7f,
            displayFeature = hinge,
        )

        assertEquals(288f, layout.rightPage.left, TOLERANCE)
        assertEquals(986f, layout.rightPage.right, TOLERANCE)
        assertEquals(14f, layout.rightPage.top, TOLERANCE)
        assertEquals(686f, layout.rightPage.bottom, TOLERANCE)
    }

    @Test
    fun `completion pointers produce a full mirrored sheet`() {
        val layout = calculateBookCanvasLayout(
            canvasSize = Size(1_000f, 700f),
            presentation = BookPresentation.SPREAD,
            outerPaddingPx = 14f,
            coverInsetPx = 7f,
        )
        val engine = PageCurlGeometryEngine()

        TurnDirection.entries.forEach { direction ->
            val source = layout.sourcePage(direction)
            val geometry = engine.calculate(
                pageBounds = source.toCurlRect(),
                pointer = layout.completionPointer(direction),
                direction = direction,
            )
            assertTrue(geometry.isFinite)
            assertEquals(1.0, geometry.progress, DOUBLE_TOLERANCE)
            assertEquals(source.width.toDouble() * source.height, geometry.reflectedBack.area, 0.1)
        }
    }

    @Test
    fun `edge completion preserves the grabbed height`() {
        val layout = calculateBookCanvasLayout(
            canvasSize = Size(1_000f, 700f),
            presentation = BookPresentation.SPREAD,
            outerPaddingPx = 14f,
            coverInsetPx = 7f,
        )
        val anchorY = layout.rightPage.top + layout.pageHeight * 0.42f

        TurnDirection.entries.forEach { direction ->
            val completion = layout.completionPointer(direction, anchorY.toDouble())
            assertEquals(anchorY.toDouble(), completion.y, DOUBLE_TOLERANCE)
        }
    }

    @Test
    fun `affine transform uses Compose matrix coordinate convention`() {
        val affine = AffineTransform2D(
            m00 = 2.0,
            m01 = 3.0,
            m02 = 5.0,
            m10 = 7.0,
            m11 = 11.0,
            m12 = 13.0,
        )
        val point = Point(x = 17.0, y = 19.0)

        val expected = affine.map(point)
        val actual = affine.toComposeMatrix().map(point.toOffset())

        assertEquals(expected.x, actual.x.toDouble(), DOUBLE_TOLERANCE)
        assertEquals(expected.y, actual.y.toDouble(), DOUBLE_TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.001f
        const val DOUBLE_TOLERANCE = 1e-6
    }
}
