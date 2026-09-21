package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickBookmarkTabTest {
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
