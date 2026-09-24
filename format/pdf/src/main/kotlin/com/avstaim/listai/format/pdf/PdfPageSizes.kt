package com.avstaim.listai.format.pdf

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.avstaim.listai.format.fb2.BookParseException
import java.io.File
import kotlinx.coroutines.CancellationException

internal fun readPdfPageSizes(file: File, maxPages: Int): IntArray = readPdfPageSizes(
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), maxPages)

/** Takes ownership of the descriptor, including when the native constructor fails. */
internal fun readPdfPageSizes(descriptor: ParcelFileDescriptor, maxPages: Int): IntArray {
    val renderer = try { PdfRenderer(descriptor) } catch (error: Throwable) {
        descriptor.close()
        throw error
    }
    return renderer.use {
        if (it.pageCount == 0) throw BookParseException.MissingReadableBody()
        if (it.pageCount > maxPages) throw BookParseException.ResourceLimitExceeded("PDF pages", maxPages.toLong())
        val sizes = IntArray(it.pageCount * 2)
        repeat(it.pageCount) { index ->
            if (Thread.currentThread().isInterrupted) throw CancellationException("PDF parsing was cancelled")
            it.openPage(index).use { page ->
                if (page.width <= 0 || page.height <= 0) throw BookParseException.MalformedDocument()
                sizes[index * 2] = page.width
                sizes[index * 2 + 1] = page.height
            }
        }
        sizes
    }
}
