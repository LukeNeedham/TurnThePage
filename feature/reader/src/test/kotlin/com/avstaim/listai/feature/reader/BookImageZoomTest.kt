package com.avstaim.listai.feature.reader

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.avstaim.listai.core.adaptive.DevicePosture
import com.avstaim.listai.core.adaptive.FoldBounds
import com.avstaim.listai.core.adaptive.FoldOrientation
import com.avstaim.listai.feature.reader.bookview.singlePageViewport
import com.avstaim.listai.feature.reader.bookview.toBookDisplayFeatureInWindow
import com.avstaim.listai.feature.reader.bookview.toLocalViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookImageZoomTest {
    private val square = bookImageViewport(400f, 400f, 1000, 1000)

    @Test fun `fit preserves portrait and landscape aspect ratios`() {
        val portrait = bookImageViewport(400f, 600f, 1000, 2000)
        assertEquals(300f, portrait.fittedWidth, .001f)
        assertEquals(600f, portrait.fittedHeight, .001f)
        val landscape = bookImageViewport(400f, 600f, 2000, 1000)
        assertEquals(400f, landscape.fittedWidth, .001f)
        assertEquals(200f, landscape.fittedHeight, .001f)
    }

    @Test fun `pinch keeps the image point beneath centroid fixed`() {
        val previous = BookImageZoom(2f, 40f, -20f)
        val next = square.transform(previous, 240f, 170f, zoomFactor = 1.5f)
        assertEquals((240f - 200f - previous.x) / previous.scale,
            (240f - 200f - next.x) / next.scale, .001f)
        assertEquals((170f - 200f - previous.y) / previous.scale,
            (170f - 200f - next.y) / next.scale, .001f)
    }

    @Test fun `scale limits preserve actual focal ratio and clamp panning`() {
        val next = square.transform(BookImageZoom(4f, 10f, -20f), 240f, 170f,
            zoomFactor = 3f)
        assertEquals(BOOK_IMAGE_MAX_ZOOM, next.scale, .001f)
        assertEquals(2.5f, next.x, .001f)
        assertEquals(-17.5f, next.y, .001f)
        assertEquals(BookImageZoom(2f, 200f, -200f),
            square.transform(BookImageZoom(2f), 200f, 200f, panX = 5000f, panY = -5000f))
    }

    @Test fun `letterbox axis stays centered until enlarged image exceeds viewport`() {
        val portrait = bookImageViewport(800f, 600f, 1000, 2000)
        assertEquals(BookImageZoom(2f, 0f, 300f), portrait.constrain(BookImageZoom(2f, 100f, 500f)))
        assertEquals(BookImageZoom(5f, 350f, 1200f), portrait.constrain(BookImageZoom(5f, 900f, 2000f)))
    }

    @Test fun `zooming out to fit removes all offset and double tap resets`() {
        assertEquals(BookImageZoom(), square.transform(BookImageZoom(2f, 100f, -100f),
            20f, 20f, zoomFactor = .2f))
        val enlarged = square.doubleTap(BookImageZoom(), 100f, 200f)
        assertEquals(2.5f, enlarged.scale, .001f)
        assertEquals(150f, enlarged.x, .001f)
        assertEquals(BookImageZoom(), square.doubleTap(enlarged, 100f, 200f))
    }

    @Test fun `invalid transforms recover to finite fit state`() {
        assertEquals(BookImageZoom(), square.constrain(BookImageZoom(Float.NaN, Float.NaN, Float.POSITIVE_INFINITY)))
        assertEquals(BookImageZoom(), square.transform(BookImageZoom(), Float.NaN,
            Float.POSITIVE_INFINITY, zoomFactor = Float.NaN))
    }

    @Test fun `fit canonicalizes signed zero offsets`() {
        val fitted = square.constrain(BookImageZoom(1f, -0f, -0f))
        assertEquals(BookImageZoom(), fitted)
        assertEquals(0, fitted.x.toRawBits())
        assertEquals(0, fitted.y.toRawBits())
    }

    @Test fun `vertical hinge maps window coordinates into complete viewer segment`() {
        val safeViewportInWindow = Rect(80f, 50f, 1080f, 750f)
        val feature = DevicePosture.Flat(FoldBounds(570, 50, 590, 750), FoldOrientation.VERTICAL,
            separating = true, occluding = true).toBookDisplayFeatureInWindow()
            ?.toLocalViewport(safeViewportInWindow)
        val segment = singlePageViewport(Size(1000f, 700f), feature, 0f)
        assertEquals(Rect(510f, 0f, 1000f, 700f), segment)
        val fitted = bookImageViewport(segment.width, segment.height, 1200, 840)
        assertEquals(490f, fitted.fittedWidth, .001f)
        assertEquals(343f, fitted.fittedHeight, .001f)
    }

    @Test fun `tabletop viewer uses upper complete pane and ordinary crease uses whole screen`() {
        val window = Rect(80f, 50f, 1080f, 750f)
        val obstruction = DevicePosture.HalfOpened(FoldBounds(80, 390, 1080, 410),
            FoldOrientation.HORIZONTAL, occluding = true).toBookDisplayFeatureInWindow()
            ?.toLocalViewport(window)
        assertEquals(Rect(0f, 0f, 1000f, 340f), singlePageViewport(Size(1000f, 700f), obstruction, 0f))
        val crease = DevicePosture.Flat(FoldBounds(580, 50, 580, 750), FoldOrientation.VERTICAL)
            .toBookDisplayFeatureInWindow()?.toLocalViewport(window)
        assertEquals(Rect(0f, 0f, 1000f, 700f), singlePageViewport(Size(1000f, 700f), crease, 0f))
    }

    @Test fun `decode keeps original pixels when bounded and limits very large inputs`() {
        assertEquals(1, bookImageDecodeSample(2000, 3000))
        for ((width, height) in listOf(40_000 to 20_000, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE,
            Int.MAX_VALUE to Int.MAX_VALUE, 8193 to 1)) {
            val sample = bookImageDecodeSample(width, height)
            val decodedWidth = (width.toLong() + sample - 1) / sample
            val decodedHeight = (height.toLong() + sample - 1) / sample
            assertTrue(decodedWidth * decodedHeight <= BOOK_IMAGE_MAX_PIXELS)
            assertTrue(decodedWidth <= BOOK_IMAGE_MAX_AXIS)
            assertTrue(decodedHeight <= BOOK_IMAGE_MAX_AXIS)
            assertEquals(0, sample and (sample - 1))
        }
    }
}
