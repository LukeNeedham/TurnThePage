package com.avstaim.listai.feature.reader.bookview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.BookMetadata
import com.avstaim.listai.reader.pagination.IncrementalPaginationEngine
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.MeasurementMetrics
import com.avstaim.listai.reader.pagination.PageMode
import com.avstaim.listai.reader.rendering.cache.SurfaceStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FixedPageSurfaceTest {
    @Test fun pdfInkIsPreparedBeforeTurningAndKeepsAllCornersInBothThemes() = runBlocking {
        for (dark in listOf(false, true)) {
            val file = fixture()
            try {
                val document = document(file)
                val key = LayoutKey(600, 900, PageMode.SINGLE, darkTheme = dark)
                val pages = IncrementalPaginationEngine(AndroidBookMeasurer(document, "Missing"))
                    .paginate(document, null, key).last().pages
                assertEquals(2, pages.size)
                assertTrue(pages.all { it.blocks.single().measurement is MeasurementMetrics })
                val fragment = pages.first().blocks.single()
                assertEquals(2f / 3f, fragment.width / fragment.height, .001f)
                val rasterizer = AndroidPageRasterizer(document, key, "Missing")
                val surface = rasterizer.render(pages.first())
                fun at(x: Float, y: Float) = surface.bitmap.getPixel(
                    (fragment.x + fragment.width * x).toInt(), (fragment.y + fragment.height * y).toInt())
                assertEquals(Color.RED, at(.05f, .05f))
                assertEquals(Color.BLUE, at(.95f, .95f))
                assertEquals(Color.WHITE, at(.5f, .5f))
                assertEquals("PDF · 1", surface.accessibleText)
                assertEquals(0L, rasterizer.measureCount)
                assertTrue(file.delete())
                // No file access, PDF render, bitmap decode or text measurement on curl frames.
                val target = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(target)
                    repeat(120) { surface.drawTo(canvas, RectF(0f, 0f, 600f, 900f)) }
                    assertEquals(1L, rasterizer.renderCount)
                    assertEquals(0L, rasterizer.measureCount)
                    assertFalse(surface.bitmap.isRecycled)
                } finally { target.recycle() }
            } finally { file.delete() }
        }
    }

    @Test fun oversizedPdfViewportSharesBoundedCacheAndWarmRequestsReusePixels() = runBlocking {
        val file = fixture()
        try {
            val document = document(file)
            val key = LayoutKey(8000, 6000, PageMode.SPREAD, pageWidthPx = 4000, pageHeightPx = 6000)
            val snapshot = IncrementalPaginationEngine(AndroidBookMeasurer(document, "Missing"))
                .paginate(document, BookLocation(BlockId("pdf-page-2"), 0, 1), key).last()
            assertEquals(1, snapshot.anchorPageIndex)
            val rasterizer = AndroidPageRasterizer(document, key, "Missing")
            val store = SurfaceStore<Int, PaginatedPageSurface>(this, Dispatchers.Default, PAGE_CACHE_BYTES,
                { it.byteCount }) { rasterizer.render(snapshot.pages[it]) }
            try {
                store.request(listOf(0, 1))
                val state = withTimeout(15_000) { store.state.first { it.values.size == 2 } }
                assertTrue(state.failedKeys.isEmpty())
                assertTrue(state.bytes <= PAGE_CACHE_BYTES)
                state.values.values.forEach { assertTrue(it.byteCount <= 8L * 1024 * 1024) }
                val first = state.values.getValue(0)
                repeat(120) { store.request(listOf(0, 1)) }
                assertEquals(2L, rasterizer.renderCount)
                assertSame(first, store.state.value.values[0])
                store.close()
                assertEquals(0L, store.state.value.bytes)
                assertFalse(first.bitmap.isRecycled)
            } finally { store.close() }
        } finally { file.delete() }
    }

    private fun document(file: File) = BookDocument(BookId("pdf-surface"), BookMetadata("PDF"),
        List(2) { index -> BookBlock.FixedPage(BlockId("pdf-page-${index + 1}"), file.absolutePath,
            "application/pdf", index, 400, 600) })

    private fun fixture(): File {
        val file = File.createTempFile("listai-surface-", ".pdf",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        val document = PdfDocument()
        try {
            repeat(2) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
                page.canvas.drawRect(0f, 0f, 80f, 120f, Paint().apply { color = Color.RED })
                page.canvas.drawRect(320f, 480f, 400f, 600f, Paint().apply { color = Color.BLUE })
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        return file
    }
}
