package com.avstaim.listai.feature.reader.bookview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.floor

@RunWith(AndroidJUnit4::class)
class PageFooterSurfaceTest {
    @Test fun completedTotalReusesSourceBitmapAndPinnedFooterStaysUnchanged() = runBlocking {
        val document = BookDocument(BookId("footer"), BookMetadata("Footer"), listOf(
            BookBlock.Paragraph(BlockId("text"), listOf(InlineNode.Text("The page keeps its place. ".repeat(150))))))
        val key = LayoutKey(412, 600, PageMode.SINGLE)
        val pages = IncrementalPaginationEngine(AndroidBookMeasurer(document, "Missing"))
            .paginate(document, null, key).last().pages
        val rasterizer = AndroidPageRasterizer(document, key, "Missing")
        val source = rasterizer.render(pages.first())
        val pending = source.withFooter(sourcePageFooter(1, null, 0, document.readingLength()))
        val complete = source.withFooter(sourcePageFooter(1, pages.size, 0, document.readingLength()))
        assertSame(source.bitmap, pending.bitmap)
        assertSame(source.bitmap, complete.bitmap)
        assertEquals("1", pending.footerText)
        assertEquals("1 / ${pages.size}", complete.footerText)
        assertEquals(1L, rasterizer.renderCount)
        assertEquals(source.byteCount, complete.byteCount)

        val target = Bitmap.createBitmap(824, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        // This is the native transform used by a reflected/moving page. Footer ink must move
        // with that page rather than remain in a stationary screen overlay.
        canvas.translate(824f, 0f)
        canvas.scale(-1f, 1f)
        complete.drawTo(canvas, RectF(0f, 0f, 412f, 600f))
        val footerStart = (key.pageHeightPx - key.marginY - 24f).toInt()
        assertTrue((footerStart until 600).any { y -> (412 until 824).any { x -> target.getPixel(x, y) != 0 } })
        assertTrue((footerStart until 600).all { y -> (0 until 412).all { x -> target.getPixel(x, y) == 0 } })
        assertEquals(1L, rasterizer.renderCount)
        assertEquals(source.accessibleText, complete.accessibleText)
        assertEquals("1", pending.footerText)
    }

    @Test fun changedSourceProgressWithTheSamePageNumberReplacesOnlyItsFooter() {
        val key = LayoutKey(412, 600, PageMode.SINGLE)
        val source = blankPage(key)
        val firstValue = sourcePageFooter(3, 12, 10, 100)
        val first = source.withFooter(firstValue)
        val before = draw(first, key)
        val next = first.withFooter(sourcePageFooter(3, 12, 70, 100))
        val after = draw(next, key)

        assertNotSame(first, next)
        assertSame(source.bitmap, first.bitmap)
        assertSame(source.bitmap, next.bitmap)
        assertSame(first, first.withFooter(firstValue))
        assertEquals(first.footerText, next.footerText)
        assertFalse(before.sameAs(after))
        assertTrue(before.sameAs(draw(first, key)))

        // With equal labels, only the prepared dot segment may change. It stays separate
        // from both the page body and the page-number baseline above it.
        val dotTop = floor(key.pageHeightPx - key.marginY - 1.8f * key.density).toInt()
        for (y in 0 until dotTop) for (x in 0 until key.pageWidthPx) {
            assertEquals(before.getPixel(x, y), after.getPixel(x, y))
        }
    }

    @Test fun footerFitsItsReservedSpaceOnShortNarrowPagesWithLargeTypeInBothThemes() {
        for (dark in listOf(false, true)) for (key in listOf(
            LayoutKey(320, 480, PageMode.SINGLE, textScale = 2f, darkTheme = dark),
            LayoutKey(320, 240, PageMode.SPREAD, textScale = 1.6f, darkTheme = dark),
        )) {
            val source = blankPage(key)
            val result = draw(source.withFooter(sourcePageFooter(9999, 10000, 50, 100)), key)
            val firstFooterRow = floor(key.pageHeightPx - key.marginY - key.footerHeight).toInt()
            val bottom = key.pageHeightPx - key.marginY
            for (y in 0 until firstFooterRow) for (x in 0 until key.pageWidthPx) {
                assertEquals("Footer overlaps the text area at $x,$y", 0, result.getPixel(x, y))
            }
            for (y in ceil(bottom).toInt() until key.pageHeightPx) for (x in 0 until key.pageWidthPx) {
                assertEquals("Footer leaves the paper margin at $x,$y", 0, result.getPixel(x, y))
            }
            val row = sourcePageFooter(9999, 10000, 50, 100).progressDots(key.contentWidth.toFloat(), key.density)!!
            val dotY = floor(bottom - row.radius).toInt()
            val dotLeft = (key.pageWidthPx - row.width) / 2
            val filled = result.getPixel((dotLeft + row.centerX(0)).toInt(), dotY)
            val remaining = result.getPixel((dotLeft + row.centerX(row.count - 1)).toInt(), dotY)
            assertTrue(android.graphics.Color.alpha(filled) > 0)
            assertTrue(android.graphics.Color.alpha(remaining) > 0)
            assertNotEquals(filled, remaining)
        }
    }

    @Test fun aReflectedScaledFooterMovesAsOneSurfaceAndRestoresItsOpacity() {
        val key = LayoutKey(320, 480, PageMode.SINGLE)
        val value = sourcePageFooter(7, 12, 50, 100)
        val source = blankPage(key)
        val footer = source.withFooter(value)
        val before = draw(footer, key)
        val target = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.translate(640f, 0f)
        canvas.scale(-1f, 1f)
        footer.drawTo(canvas, RectF(0f, 0f, 160f, 240f), alpha = .4f)
        val row = value.progressDots(key.contentWidth.toFloat(), key.density)!!
        val left = (key.pageWidthPx - row.width) / 2
        val dotY = ((key.pageHeightPx - key.marginY - row.radius) / 2).toInt()
        for (index in listOf(0, row.count - 1)) {
            val dotX = (640f - (left + row.centerX(index)) / 2).toInt()
            assertTrue((dotY - 1..dotY + 1).any { y -> (dotX - 1..dotX + 1).any { x ->
                android.graphics.Color.alpha(target.getPixel(x, y)) in 1..102
            } })
        }
        for (y in 0 until 480) for (x in 0 until 480) assertEquals(0, target.getPixel(x, y))
        assertTrue(before.sameAs(draw(footer, key)))
    }

    @Test fun physicalContentsKeepTheirNumberingWithoutASourceProgressRow() {
        val key = LayoutKey(320, 480, PageMode.SINGLE)
        val source = blankPage(key)
        val contents = source.withFooter(PageReadingFooter(2, 3, null, "Contents"))
        val drawn = draw(contents, key)
        assertEquals("Contents · 2 / 3", contents.footerText)
        val bottom = (key.pageHeightPx - key.marginY).toInt()
        // The text is centered; source dots would also put ink near both side margins.
        for (y in bottom - 4 until bottom) {
            for (x in key.marginX.toInt() until key.marginX.toInt() + 20) {
                assertEquals(0, drawn.getPixel(x, y))
            }
        }
    }

    private fun blankPage(key: LayoutKey) = PaginatedPageSurface(PageId(1),
        Bitmap.createBitmap(key.pageWidthPx, key.pageHeightPx, Bitmap.Config.ARGB_8888),
        "", key.darkTheme, key)

    private fun draw(surface: PaginatedPageSurface, key: LayoutKey): Bitmap {
        val target = Bitmap.createBitmap(key.pageWidthPx, key.pageHeightPx, Bitmap.Config.ARGB_8888)
        surface.drawTo(Canvas(target), RectF(0f, 0f, key.pageWidthPx.toFloat(), key.pageHeightPx.toFloat()))
        return target
    }
}
