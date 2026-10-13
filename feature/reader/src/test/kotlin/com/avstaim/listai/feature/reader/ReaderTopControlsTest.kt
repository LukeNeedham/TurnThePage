package com.avstaim.listai.feature.reader

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class ReaderTopControlsTest {
    private fun verify(width: Float, insets: ReaderCutoutInsets, density: Float = 1f,
        paper: List<Rect> = listOf(Rect(4f * density, 4f * density, width - 4f * density, 850f * density)),
    ): ReaderTopControlLayout {
        val layout = readerTopControlLayout(width, insets, density, paper)
        assertEquals(5, layout.buttons.size)
        layout.buttons.forEach { button ->
            assertTrue("Control $button is outside paper $paper", paper.any {
                button.left >= it.left && button.right <= it.right && button.top >= it.top && button.bottom <= it.bottom
            })
            assertTrue(button.bottom + 14f * density <= layout.contentTop)
            insets.cutouts.forEach { assertFalse("Button $button overlaps cutout $it", button.overlaps(it)) }
        }
        layout.buttons.zipWithNext().forEach { (a, b) -> assertFalse(a.overlaps(b)) }
        layout.touchBounds.forEachIndexed { index, target ->
            val ink = layout.buttons[index]
            assertTrue(target.left <= ink.left && target.right >= ink.right &&
                target.top <= ink.top && target.bottom >= ink.bottom)
            assertTrue(target.width <= ink.width + 4f * density + .001f)
            assertTrue(target.height <= ink.height + 4f * density + .001f)
            assertTrue(paper.any { target.left >= it.left && target.right <= it.right &&
                target.top >= it.top && target.bottom <= it.bottom })
            assertTrue(insets.cutouts.none(target::overlaps))
            assertTrue(layout.clockBounds?.overlaps(target) != true)
        }
        layout.touchBounds.zipWithNext().forEach { (a, b) -> assertFalse(a.overlaps(b)) }
        layout.clockBounds?.let { clock ->
            assertTrue(paper.any { clock.left >= it.left && clock.right <= it.right && clock.top >= it.top && clock.bottom <= it.bottom })
            assertTrue("Clock must preserve the gap above the text", clock.bottom + 14f * density <= layout.contentTop)
            assertEquals("Clock belongs to the top control row", layout.buttons.first().center.y, clock.center.y, .001f)
            assertTrue(layout.buttons.none { it.overlaps(clock) })
            assertTrue(insets.cutouts.none { it.overlaps(clock) })
        }
        return layout
    }

    @Test fun `centred camera leaves five ink marks on the paper beside it`() {
        val result = verify(412f, ReaderCutoutInsets(top = 32f, cutouts = listOf(Rect(192f, 0f, 220f, 32f))))
        assertEquals(8f, result.buttons.first().top, 0f)
        assertTrue(result.buttons[1].right < 130f)
        assertTrue(result.buttons[2].left > 220f)
    }

    @Test fun `off centre notch uses actual bounding rectangle`() {
        verify(412f, ReaderCutoutInsets(top = 44f, cutouts = listOf(Rect(38f, 0f, 158f, 44f))))
    }

    @Test fun `wide notch moves ink lower within the page not outside book`() {
        val result = verify(240f, ReaderCutoutInsets(top = 36f, cutouts = listOf(Rect(25f, 0f, 215f, 36f))))
        assertTrue(result.buttons.first().top >= 36f)
    }

    @Test fun `landscape uses paper safe side`() {
        verify(900f, ReaderCutoutInsets(left = 40f, cutouts = listOf(Rect(0f, 165f, 40f, 245f))),
            paper = listOf(Rect(44f, 4f, 896f, 436f)))
    }

    @Test fun `no cutout groups actions and leaves middle of page empty`() {
        val result = verify(412f, ReaderCutoutInsets())
        assertTrue(result.buttons[2].left - result.buttons[1].right > 140f)
        assertEquals(54f, result.contentTop, 0f)
        val clock = requireNotNull(result.clockBounds)
        assertEquals(206f, clock.center.x, 0f)
        assertEquals(18f, clock.top, 0f)
    }

    @Test fun `multiple cutouts and density keep all targets disjoint`() {
        verify(1080f, ReaderCutoutInsets(top = 96f,
            cutouts = listOf(Rect(0f, 0f, 85f, 80f), Rect(500f, 0f, 582f, 96f))), 3f)
    }

    @Test fun `spread controls never sit on spine gap or cloth`() {
        val pages = listOf(Rect(4f, 4f, 490f, 650f), Rect(510f, 4f, 996f, 650f))
        val layout = verify(1000f, ReaderCutoutInsets(), paper = pages)
        assertTrue(layout.buttons.take(2).all { it.right < 200f })
        assertTrue(layout.buttons.takeLast(2).all { it.left > 800f })
        verify(1000f, ReaderCutoutInsets(), paper = listOf(pages.last()))
    }

    @Test fun `no paper means no controls`() {
        assertTrue(readerTopControlLayout(412f, ReaderCutoutInsets(), 1f, emptyList()).buttons.isEmpty())
    }

    @Test fun `forced narrow spread keeps actions on its lone first leaf`() {
        val layout = verify(320f, ReaderCutoutInsets(), paper = listOf(Rect(160f, 4f, 316f, 650f)))
        assertNull("An optional clock must not be squeezed between controls or pushed into text", layout.clockBounds)
    }

    @Test fun `clock stays near the edge beside a centered camera`() {
        val layout = verify(412f, ReaderCutoutInsets(top = 32f,
            cutouts = listOf(Rect(192f, 0f, 220f, 32f))))
        val clock = requireNotNull(layout.clockBounds)
        assertTrue("The clock uses free paper beside the camera", clock.right <= 188f)
        assertEquals(18f, clock.top, 0f)
    }

    @Test fun `clock follows the safe upper row below a wide notch`() {
        val layout = verify(320f, ReaderCutoutInsets(top = 36f,
            cutouts = listOf(Rect(25f, 0f, 295f, 36f))))
        val clock = requireNotNull(layout.clockBounds)
        assertTrue(clock.top >= 36f)
        assertEquals(layout.buttons.first().center.y, clock.center.y, 0f)
    }

    @Test fun `clock is centered on a complete page never across the Fold hinge`() {
        val pages = listOf(Rect(4f, 4f, 490f, 650f), Rect(510f, 4f, 996f, 650f))
        val spreadClock = requireNotNull(verify(1000f, ReaderCutoutInsets(), paper = pages).clockBounds)
        assertEquals(pages.first().center.x, spreadClock.center.x, 0f)
        val loneClock = requireNotNull(verify(1000f, ReaderCutoutInsets(), paper = listOf(pages.last())).clockBounds)
        assertEquals(pages.last().center.x, loneClock.center.x, 0f)
    }

    @Test fun `clock size and top edge distance follow density not paragraph metrics`() {
        listOf(1f, 1.5f, 2f, 3f).forEach { density ->
            val clock = requireNotNull(verify(360f * density, ReaderCutoutInsets(), density).clockBounds)
            assertEquals(82f * density, clock.width, .001f)
            assertEquals(12f * density, clock.height, .001f)
            assertEquals(18f * density, clock.top, .001f)
        }
    }

    @Test fun `narrow phone keeps a clock in the remaining top row width`() {
        val clock = requireNotNull(verify(320f, ReaderCutoutInsets(),
            paper = listOf(Rect(14f, 14f, 306f, 626f))).clockBounds)
        assertTrue(clock.width >= 64f)
        assertTrue(clock.width < 82f)
    }
}
