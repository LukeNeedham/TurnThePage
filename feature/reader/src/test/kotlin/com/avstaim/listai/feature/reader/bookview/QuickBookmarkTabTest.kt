package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.avstaim.listai.feature.reader.ReaderCutoutInsets
import com.avstaim.listai.feature.reader.readerTopControlLayout
import org.junit.Test

class QuickBookmarkTabTest {
    @Test fun `clock and bookmark share the top edge without covering each other`() {
        val paper = Rect(4f, 4f, 408f, 850f)
        val cutout = Rect(192f, 0f, 220f, 32f)
        val controls = readerTopControlLayout(412f, ReaderCutoutInsets(top = 32f, cutouts = listOf(cutout)),
            1f, listOf(paper))
        val clock = requireNotNull(controls.clockBounds)
        val placement = requireNotNull(quickBookmarkSingleAtTop(paper, 48f, 3f, 4f,
            controls.buttons, listOf(cutout), clockBounds = clock, minimumTargetSize = 32f))
        assertEquals("A compact target fits beside the camera", 32f, placement.bounds.width, 0f)
        assertFalse(placement.bounds.overlaps(clock))
        assertFalse(placement.bounds.overlaps(cutout))
        assertTrue(controls.buttons.none { it.overlaps(placement.bounds) })
        assertTrue(placement.bounds.left >= paper.left && placement.bounds.right <= paper.right)
    }

    @Test fun `narrow leaf preserves the bookmark action when an optional clock cannot share the row`() {
        val paper = Rect(14f, 14f, 306f, 626f)
        val controls = readerTopControlLayout(320f, ReaderCutoutInsets(), 1f, listOf(paper))
        val placement = requireNotNull(quickBookmarkSingleAtTop(paper, 48f, 3f, 4f,
            controls.buttons, emptyList(), controls.clockBounds, minimumTargetSize = 32f))
        assertEquals(48f, placement.bounds.width, 0f)
        assertTrue("The caller must suppress the optional clock at this width",
            requireNotNull(controls.clockBounds).overlaps(placement.bounds))
        assertTrue(controls.buttons.none { it.overlaps(placement.bounds) })
    }

    @Test fun `left leaf hangs bookmark from top inside its spine corner`() {
        val placement = quickBookmarkAtTop(
            pageBounds = Rect(20f, 10f, 500f, 810f),
            targetSize = 48f,
            topOverhang = 3f,
            slot = QuickBookmarkTopSlot.SPREAD_LEFT,
        )

        assertEquals(Rect(452f, 7f, 500f, 55f), placement.bounds)
    }

    @Test fun `right leaf hangs bookmark from top inside its spine corner`() {
        val placement = quickBookmarkAtTop(
            pageBounds = Rect(520f, 10f, 1_000f, 810f),
            targetSize = 48f,
            topOverhang = 3f,
            slot = QuickBookmarkTopSlot.SPREAD_RIGHT,
        )

        assertEquals(Rect(520f, 7f, 568f, 55f), placement.bounds)
    }

    @Test fun `single leaf hangs bookmark immediately after its two left controls`() {
        val placement = quickBookmarkSingleAtTop(
            pageBounds = Rect(4f, 4f, 408f, 850f),
            targetSize = 48f,
            topOverhang = 3f,
            gap = 4f,
            controlBounds = listOf(
                Rect(16f, 8f, 48f, 40f),
                Rect(56f, 8f, 88f, 40f),
                Rect(280f, 8f, 312f, 40f),
                Rect(320f, 8f, 352f, 40f),
                Rect(360f, 8f, 392f, 40f),
            ),
            cutoutBounds = listOf(Rect(190f, 0f, 222f, 32f)),
        )

        assertEquals(Rect(92f, 1f, 140f, 49f), placement?.bounds)
    }

    @Test fun `single leaf never puts bookmark under an off centre camera cutout`() {
        val placement = quickBookmarkSingleAtTop(
            pageBounds = Rect(4f, 4f, 408f, 850f),
            targetSize = 48f,
            topOverhang = 3f,
            gap = 4f,
            controlBounds = listOf(
                Rect(16f, 8f, 48f, 40f),
                Rect(56f, 8f, 88f, 40f),
                Rect(280f, 8f, 312f, 40f),
                Rect(320f, 8f, 352f, 40f),
                Rect(360f, 8f, 392f, 40f),
            ),
            cutoutBounds = listOf(Rect(90f, 0f, 150f, 36f)),
        )

        assertEquals(Rect(154f, 1f, 202f, 49f), placement?.bounds)
    }
}
