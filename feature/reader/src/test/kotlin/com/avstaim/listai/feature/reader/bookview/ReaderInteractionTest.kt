package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.PageMode
import org.junit.Assert.*
import org.junit.Test

class ReaderInteractionTest {
    private val viewport = Size(1000f, 700f)
    private fun spread(feature: BookDisplayFeature? = null) = calculateBookCanvasLayout(
        viewport, BookPresentation.SPREAD, 4f, 3f, feature)

    @Test fun `menu belongs to viewport centre instead of either page centre`() {
        val layout = spread()
        val menu = readerMenuTapBounds(viewport)
        assertTrue(menu.contains(Offset(500f, 350f)))
        assertFalse(menu.contains(requireNotNull(layout.leftPage).center))
        assertFalse(menu.contains(layout.rightPage.center))
        assertEquals(TurnDirection.BACKWARD, layout.tapDirection(Offset(250f, 350f), true, true))
        assertEquals(TurnDirection.FORWARD, layout.tapDirection(Offset(750f, 350f), true, true))
    }

    @Test fun `spine gap accepts menu and drags in both directions`() {
        val layout = spread(BookDisplayFeature(Rect(488f, 0f, 512f, 700f),
            BookFoldOrientation.VERTICAL, separating = true, occluding = true, halfOpened = false))
        val spine = Offset(500f, 350f)
        assertFalse(layout.containsPage(spine))
        assertTrue(readerMenuTapBounds(viewport).contains(spine))
        assertEquals(TurnDirection.FORWARD, layout.dragDirection(spine, -80f, true, true, viewport))
        assertEquals(TurnDirection.BACKWARD, layout.dragDirection(spine, 80f, true, true, viewport))
    }

    @Test fun `centre on either side can drag while unavailable boundaries remain unavailable`() {
        val layout = spread()
        assertEquals(TurnDirection.FORWARD, layout.dragDirection(Offset(450f, 350f), -80f, true, true, viewport))
        assertEquals(TurnDirection.BACKWARD, layout.dragDirection(Offset(550f, 350f), 80f, true, true, viewport))
        assertNull(layout.dragDirection(Offset(500f, 350f), 80f, true, false, viewport))
    }

    @Test fun `both closed covers expose blank opposite side as shelf target`() {
        val layout = spread()
        val left = requireNotNull(layout.leftPage).center
        val right = layout.rightPage.center
        assertTrue(layout.isClosedBookBackground(PhysicalBookState.ClosedFront, left))
        assertFalse(layout.isClosedBookBackground(PhysicalBookState.ClosedFront, right))
        assertTrue(layout.isClosedBookBackground(PhysicalBookState.ClosedBack, right))
        assertFalse(layout.isClosedBookBackground(PhysicalBookState.ClosedBack, left))
        assertFalse(layout.isClosedBookBackground(PhysicalBookState.Open(3), right))
        assertFalse(layout.isClosedBookBackground(PhysicalBookState.BackBoundary, right))
    }

    @Test fun `single closed cover margins are also blank background`() {
        val layout = calculateBookCanvasLayout(Size(412f, 900f), BookPresentation.SINGLE, 4f, 3f)
        listOf(PhysicalBookState.ClosedFront, PhysicalBookState.ClosedBack).forEach { state ->
            assertTrue(layout.isClosedBookBackground(state, Offset(1f, 400f)))
            assertFalse(layout.isClosedBookBackground(state, layout.rightPage.center))
        }
    }

    @Test fun `page number target contains footer without overlapping source text`() {
        val key = LayoutKey(412, 900, PageMode.SINGLE)
        val paper = Rect(4f, 4f, 416f, 904f)
        val bounds = pageNumberTapBounds(paper, key)
        assertTrue(bounds.contains(Offset(paper.center.x, paper.bottom - key.marginY - key.footerHeight / 2f)))
        assertEquals(112f, bounds.width, .001f)
        assertEquals(40f, bounds.height, .001f)
        assertTrue(bounds.top >= paper.top + key.contentTop + key.contentHeight)
        assertTrue(bounds.bottom <= paper.bottom)
        assertFalse(bounds.contains(Offset(paper.left + 10f, bounds.center.y)))
    }

    @Test fun `each spread leaf gets its own number target away from spine`() {
        val layout = spread()
        val key = LayoutKey(1000, 700, PageMode.SPREAD,
            pageWidthPx = layout.pageWidth.toInt(), pageHeightPx = layout.pageHeight.toInt())
        val left = pageNumberTapBounds(requireNotNull(layout.leftPage), key)
        val right = pageNumberTapBounds(layout.rightPage, key)
        assertTrue(left.right < layout.spineX)
        assertTrue(right.left > layout.spineX)
        assertFalse(left.overlaps(right))
        assertEquals(layout.leftPage.center.x, left.center.x, .001f)
        assertEquals(layout.rightPage.center.x, right.center.x, .001f)
    }

    @Test fun `number target scales with page and is clipped on very small windows`() {
        val key = LayoutKey(600, 1000, PageMode.SINGLE, density = 2f)
        val native = pageNumberTapBounds(Rect(0f, 0f, 600f, 1000f), key)
        val scaled = pageNumberTapBounds(Rect(100f, 20f, 400f, 520f), key)
        assertEquals(native.width / 2, scaled.width, .001f)
        assertEquals(native.height / 2, scaled.height, .001f)
        assertEquals(native.top / 2 + 20f, scaled.top, .001f)
        val tinyKey = LayoutKey(80, 90, PageMode.SINGLE, density = 3f)
        val tiny = pageNumberTapBounds(Rect(3f, 7f, 83f, 97f), tinyKey)
        assertTrue(tiny.left >= 3f && tiny.right <= 83f && tiny.top >= 7f && tiny.bottom <= 97f)
    }
}
