package com.avstaim.listai.core.files

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.avstaim.listai.core.model.BookBinaryContent
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookImage
import com.avstaim.listai.core.model.ImageId
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/** Adds the optional shelf artwork at the storage boundary, outside the metadata-only parser. */
internal fun BookDocument.withPdfCover(file: File): BookDocument {
    val first = blocks.firstOrNull() as? BookBlock.FixedPage ?: return this
    if (first.mediaType != "application/pdf" || metadata.coverImageId != null) return this
    val coverId = ImageId("pdf-cover")
    return copy(metadata = metadata.copy(coverImageId = coverId),
        images = images + BookImage(coverId, "image/png", PdfCoverContent(file)))
}

/** A single bounded shelf cover, loaded once; source pages are never cached as encoded images. */
private class PdfCoverContent(private val file: File) : BookBinaryContent {
    private val bytes: ByteArray? by lazy {
        try {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(descriptor) } catch (error: Throwable) {
                descriptor.close()
                throw error
            }
            renderer.use {
                it.openPage(0).use { page ->
                    val scale = 512f / maxOf(page.width, page.height)
                    val bitmap = Bitmap.createBitmap((page.width * scale).roundToInt().coerceAtLeast(1),
                        (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        ByteArrayOutputStream().use { output ->
                            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null
                        }
                    } finally { bitmap.recycle() }
                }
            }
        } catch (_: Exception) { null }
    }

    override fun load(): ByteArray? = bytes
}
