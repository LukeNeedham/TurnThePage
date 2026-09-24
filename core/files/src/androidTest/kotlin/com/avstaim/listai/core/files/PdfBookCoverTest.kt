package com.avstaim.listai.core.files

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.format.pdf.PdfBookParser
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfBookCoverTest {
    @Test fun coverIsBoundedDeferredAndCachesSuccessAfterSourceDisappears() = runBlocking {
        val file = fixture()
        try {
            val source = PdfBookParser(InstrumentationRegistry.getInstrumentation().targetContext).parse(file)
            val document = source.withPdfCover(file)
            assertEquals(source.id, document.id)
            assertEquals(source.blocks, document.blocks)
            assertSame(document, document.withPdfCover(file))
            val content = document.imagesById.getValue(requireNotNull(document.metadata.coverImageId)).content
            val bytes = requireNotNull(content.load())
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                assertTrue(bitmap.width <= 512 && bitmap.height <= 512)
                assertEquals(Color.BLUE, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            } finally { bitmap.recycle() }
            assertTrue(file.delete())
            assertSame(bytes, content.load())
        } finally { file.delete() }

        val deferredFile = fixture()
        try {
            val deferredDocument = PdfBookParser(InstrumentationRegistry.getInstrumentation().targetContext).parse(deferredFile).withPdfCover(deferredFile)
            assertTrue(deferredFile.delete())
            assertNull("Decoration must defer rasterization", deferredDocument.images.single().content.load())
        } finally { deferredFile.delete() }
    }

    private fun fixture(): File {
        val file = File.createTempFile("listai-pdf-cover-", ".pdf",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(300, 600, 1).create())
            page.canvas.drawColor(Color.BLUE)
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        return file
    }
}
