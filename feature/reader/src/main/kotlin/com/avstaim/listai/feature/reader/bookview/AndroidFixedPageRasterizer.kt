package com.avstaim.listai.feature.reader.bookview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.PageBlockLayout
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Worker-only fixed-page preparation. Draw straight into the bounded destination surface, without
 * a second page bitmap or an encoded-image cache. No native document handles escape this call.
 */
internal fun rasterizeFixedPage(
    source: BookBlock.FixedPage,
    fragment: PageBlockLayout,
    bitmap: Bitmap,
    key: LayoutKey,
) {
    require(source.mediaType == "application/pdf") { "Unsupported fixed-layout source" }
    val descriptor = ParcelFileDescriptor.open(File(source.sourcePath), ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = try { PdfRenderer(descriptor) } catch (error: Throwable) {
        descriptor.close()
        throw error
    }
    renderer.use {
        it.openPage(source.pageIndex).use { page ->
            check(page.width == source.widthPoints && page.height == source.heightPoints) {
                "The private fixed-layout source changed after indexing"
            }
            val pixelScaleX = bitmap.width.toFloat() / key.pageWidthPx
            val pixelScaleY = bitmap.height.toFloat() / key.pageHeightPx
            val left = fragment.x * pixelScaleX
            val top = fragment.y * pixelScaleY
            val width = fragment.width * pixelScaleX
            val height = fragment.height * pixelScaleY
            val clip = Rect(floor(left).toInt().coerceIn(0, bitmap.width - 1),
                floor(top).toInt().coerceIn(0, bitmap.height - 1),
                ceil(left + width).toInt().coerceIn(1, bitmap.width),
                ceil(top + height).toInt().coerceIn(1, bitmap.height))
            // Preserve the PDF's authored colors and white paper in both reader themes.
            Canvas(bitmap).drawRect(clip, Paint().apply { color = Color.WHITE })
            val matrix = Matrix().apply {
                setScale(width / page.width, height / page.height)
                postTranslate(left, top)
            }
            page.render(bitmap, clip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }
}
