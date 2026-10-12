package com.avstaim.listai.feature.reader

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookBlock
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfPageViewerRenderTest {
    @Test fun preparedViewerPageKeepsSourceColorsAndDetailsWithinItsPixelBudget() = runBlocking {
        val file = fixture()
        try {
            val bitmap = requireNotNull(renderPdfViewerPage(source(file)))
            try {
                assertEquals(1600, bitmap.width)
                assertEquals(2400, bitmap.height)
                assertTrue(bitmap.width.toLong() * bitmap.height <= BOOK_IMAGE_MAX_PIXELS)
                assertEquals(Color.RED, bitmap.getPixel(bitmap.width / 20, bitmap.height / 20))
                assertEquals(Color.BLUE, bitmap.getPixel(bitmap.width * 19 / 20, bitmap.height * 19 / 20))
                assertEquals(Color.WHITE, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            } finally { bitmap.recycle() }
        } finally { file.delete() }
    }

    @Test fun changedMissingAndUnsupportedSourcesFailWithoutPublishingPixels() = runBlocking {
        val file = fixture()
        try {
            assertNull(renderPdfViewerPage(source(file).copy(widthPoints = 401)))
            assertNull(renderPdfViewerPage(source(file).copy(pageIndex = 1)))
            assertNull(renderPdfViewerPage(source(file).copy(mediaType = "application/unknown")))
            assertTrue(file.delete())
            assertNull(renderPdfViewerPage(source(file)))
        } finally { file.delete() }
    }

    private fun source(file: File) = BookBlock.FixedPage(BlockId("pdf-viewer-page-1"),
        file.absolutePath, "application/pdf", 0, 400, 600)

    private fun fixture(): File {
        val file = File.createTempFile("listai-viewer-", ".pdf",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(400, 600, 1).create())
            page.canvas.drawRect(0f, 0f, 80f, 120f, Paint().apply { color = Color.RED })
            page.canvas.drawRect(320f, 480f, 400f, 600f, Paint().apply { color = Color.BLUE })
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        return file
    }
}
