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
class PhysicalContentsSurfaceTest {
    @Test fun actualSourceNumbersAndLeadersAppearOnlyWhenIndexedWithoutChangingPinnedPaper() = runBlocking {
        val key = LayoutKey(360, 740, PageMode.SINGLE, topContentInsetDp = 72f, localeTag = "ru")
        val document = document("Глава IV\nТихий город")
        val source = IncrementalPaginationEngine(AndroidBookMeasurer(document, "Missing"))
            .paginate(document, null, key).last()
        val contents = PhysicalContents(document, "Оглавление", "Начало текста")
        val toc = paginatePhysicalContents(contents, key, AndroidContentsMeasurer())
        val row = toc.pages.single().blocks.last()
        val pendingNumbers = ContentsPageNumbers.forPage(toc.pages.first(),
            contents.sourcePageNumbers(source.copy(pages = source.pages.take(1), complete = false)))
        val completeNumbers = ContentsPageNumbers.forPage(toc.pages.first(), contents.sourcePageNumbers(source))
        assertNull(pendingNumbers.values[row.blockId])
        val sourceNumber = requireNotNull(completeNumbers.values[row.blockId])
        assertTrue("The reference is a source page, not this first contents leaf", sourceNumber > 1)
        val rasterizer = AndroidContentsRasterizer(contents, key)
        val pending = rasterizer.render(toc.pages.first(), pendingNumbers)
        val pinned = requireNotNull(pending.bitmap.copy(Bitmap.Config.ARGB_8888, false))
        val complete = rasterizer.render(toc.pages.first(), completeNumbers)
        val geometry = ContentsGeometry(key)
        val gutter = RectF(row.x + row.width - geometry.numberGutter, row.y, row.x + row.width, row.y + row.height)
        assertFalse(pending.bitmap.hasInk(gutter))
        assertTrue(complete.bitmap.hasInk(gutter))
        // The inner gutter contains leaders; the outer end contains the independently aligned number.
        assertTrue(complete.bitmap.hasInk(RectF(gutter.left + 2f, gutter.top, gutter.right - 40f, gutter.bottom)))
        assertTrue(complete.bitmap.hasInk(RectF(gutter.right - 28f, gutter.top, gutter.right, gutter.bottom)))
        assertTrue(pending.bitmap.sameAs(pinned))
        assertFalse(pending.bitmap.sameAs(complete.bitmap))
        assertEquals("Оглавление\nГлава IV\nТихий город", pending.accessibleText)
        assertEquals("Оглавление\nГлава IV\nТихий город  $sourceNumber", complete.accessibleText)
    }

    @Test fun largeWrappedTitlesLeaveThePageNumberColumnClearUntilTheirLastLineInBothThemes() = runBlocking {
        for (dark in listOf(false, true)) {
            val key = LayoutKey(360, 900, PageMode.SINGLE, fontSizeSp = 26f, topContentInsetDp = 72f, darkTheme = dark)
            val document = document("Chapter IX\nA long journey along the river toward the distant observatory above the quiet city")
            val source = IncrementalPaginationEngine(AndroidBookMeasurer(document, "Missing"))
                .paginate(document, null, key).last()
            val contents = PhysicalContents(document, "Contents", "Begin reading")
            val toc = paginatePhysicalContents(contents, key, AndroidContentsMeasurer())
            val page = toc.pages.single()
            val row = page.blocks.last()
            assertTrue(row.endLineExclusive > 2)
            val geometry = ContentsGeometry(key)
            val measurement = AndroidContentsMeasurer().measure(contents.entries.single().title,
                geometry.textWidth(0), ContentsTextStyle.CHAPTER, key)
            val padding = (row.height - measurement.lines.last().bottom) / 2f
            val surface = AndroidContentsRasterizer(contents, key).render(page,
                ContentsPageNumbers.forPage(page, contents.sourcePageNumbers(source)))
            val gutterLeft = row.x + row.width - geometry.numberGutter
            val firstLineBottom = row.y + padding + measurement.lines.first().bottom - 2f
            assertFalse(surface.bitmap.hasInk(RectF(gutterLeft, row.y + padding, row.x + row.width, firstLineBottom)))
            assertTrue(surface.bitmap.hasInk(RectF(gutterLeft,
                row.y + padding + measurement.lines.last().top - 2f, row.x + row.width, row.y + row.height)))
            assertEquals(dark, surface.darkTheme)
        }
    }

    @Test fun contentsPageReferencesMoveWithTheReflectedPaperSurface() = runBlocking {
        val key = LayoutKey(360, 640, PageMode.SINGLE)
        val document = document("Глава V\nДорога домой")
        val source = IncrementalPaginationEngine(AndroidBookMeasurer(document, "Missing"))
            .paginate(document, null, key).last()
        val contents = PhysicalContents(document, "Оглавление", "Начало текста")
        val page = paginatePhysicalContents(contents, key, AndroidContentsMeasurer()).pages.single()
        val surface = AndroidContentsRasterizer(contents, key).render(page,
            ContentsPageNumbers.forPage(page, contents.sourcePageNumbers(source)))
        val reflected = Bitmap.createBitmap(key.pageWidthPx * 2, key.pageHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(reflected)
        canvas.translate(reflected.width.toFloat(), 0f)
        canvas.scale(-1f, 1f)
        surface.drawTo(canvas, RectF(0f, 0f, key.pageWidthPx.toFloat(), key.pageHeightPx.toFloat()))
        assertFalse(reflected.hasInk(RectF(0f, 0f, key.pageWidthPx.toFloat(), key.pageHeightPx.toFloat())))
        val row = page.blocks.last()
        assertTrue(reflected.hasInk(RectF(reflected.width - row.x - row.width, row.y,
            reflected.width - row.x - row.width + 28f, row.y + row.height)))
    }

    private fun Bitmap.hasInk(bounds: RectF): Boolean =
        (floor(bounds.top).toInt().coerceAtLeast(0) until ceil(bounds.bottom).toInt().coerceAtMost(height)).any { y ->
            (floor(bounds.left).toInt().coerceAtLeast(0) until ceil(bounds.right).toInt().coerceAtMost(width)).any { x ->
                android.graphics.Color.alpha(getPixel(x, y)) > 0
            }
        }

    private fun document(title: String): BookDocument {
        val blocks = List(8) { index -> BookBlock.Paragraph(BlockId("source-$index"), listOf(
            InlineNode.Text("The library waited for the morning beside a quiet river. ".repeat(45)))) }
        val source = BookDocument(BookId("printed-surface"), BookMetadata("Book"), blocks)
        val last = source.readingFlow().last()
        return source.copy(tableOfContents = listOf(BookTocEntry(title,
            BookLocation(last.source.id, 0, last.globalOffset))))
    }
}
