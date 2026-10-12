package com.avstaim.listai.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageZoomTest {
    @Test fun `ordinary PDF retains four pixels per source point`() {
        assertEquals(PdfPageRenderSize(1600, 2400), pdfPageRenderSize(400, 600))
        assertEquals(PdfPageRenderSize(2400, 1600), pdfPageRenderSize(600, 400))
    }

    @Test fun `huge and extreme source pages respect both allocation limits`() {
        for ((width, height) in listOf(595 to 842, 8000 to 6000, 60_000 to 120_000,
            Int.MAX_VALUE to Int.MAX_VALUE, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
            val size = pdfPageRenderSize(width, height)
            assertTrue(size.width in 1..BOOK_IMAGE_MAX_AXIS)
            assertTrue(size.height in 1..BOOK_IMAGE_MAX_AXIS)
            assertTrue(size.width.toLong() * size.height <= BOOK_IMAGE_MAX_PIXELS)
        }
    }

    @Test fun `bounded raster retains source aspect ratio to within a pixel`() {
        for ((width, height) in listOf(595 to 842, 8000 to 6000, 60_000 to 120_000)) {
            val size = pdfPageRenderSize(width, height)
            assertEquals(width.toDouble() / height, size.width.toDouble() / size.height,
                2.0 / minOf(size.width, size.height))
        }
    }

    @Test fun `tall phone fills page height from left edge and allows bounded horizontal panning`() {
        val viewport = bookImageViewport(400f, 900f, 400, 600)
        val initial = pdfPageInitialZoom(viewport)
        assertEquals(1.5f, initial.scale, .001f)
        assertEquals(viewport.height, viewport.fittedHeight * initial.scale, .001f)
        assertEquals(100f, initial.x, .001f)
        assertEquals(0f, viewport.width / 2f + initial.x - viewport.fittedWidth * initial.scale / 2f, .001f)
        assertEquals(100f, viewport.transform(initial, 200f, 450f, panX = 1000f).x, .001f)
        assertEquals(-100f, viewport.transform(initial, 200f, 450f, panX = -1000f).x, .001f)
        assertEquals(0f, viewport.transform(initial, 200f, 450f, panY = 1000f).y, .001f)
        assertEquals(BookImageZoom(), viewport.doubleTap(initial, 200f, 450f))
    }

    @Test fun `landscape and upper Fold pane preserve the whole height fitted page`() {
        for ((width, height) in listOf(900f to 400f, 800f to 340f)) {
            val viewport = bookImageViewport(width, height, 400, 600)
            assertEquals(BookImageZoom(), pdfPageInitialZoom(viewport))
        }
    }

    @Test fun `fit height stays finite and obeys zoom cap for extreme landscape pages`() {
        assertEquals(BookImageZoom(), pdfPageInitialZoom(bookImageViewport(0f, 0f, 400, 600)))
        assertEquals(BookImageZoom(), pdfPageInitialZoom(BookImageViewport(0f, Float.NaN, 0f, Float.NaN)))
        assertEquals(BOOK_IMAGE_MAX_ZOOM,
            pdfPageInitialZoom(bookImageViewport(400f, 900f, 8000, 100)).scale, .001f)
    }
}
