package com.avstaim.listai.feature.reader.bookview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Trace
import android.text.Layout
import android.text.StaticLayout
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.core.graphics.withTranslation
import androidx.core.graphics.createBitmap
import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.PageId
import com.avstaim.listai.core.model.plainText
import com.avstaim.listai.feature.reader.demo.DemoPageKind
import com.avstaim.listai.reader.pagination.BlockMeasurement
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.PageLayout
import com.avstaim.listai.reader.pagination.PageMode
import com.avstaim.listai.reader.pagination.readingFlow
import com.avstaim.listai.reader.rendering.cache.WeightedLruCache
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal const val PAGE_CACHE_BYTES = 64L * 1024 * 1024
internal const val MEASUREMENT_CACHE_BYTES = 4L * 1024 * 1024
private const val HOT_SURFACES_BYTES = 48L * 1024 * 1024

/** Published bitmaps are immutable by ownership. Never recycle a bitmap retained by a scene/GPU. */
internal class PaginatedPageSurface(val pageId: PageId, val bitmap: Bitmap, val accessibleText: String) {
    val byteCount: Long get() = bitmap.allocationByteCount.toLong()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun drawTo(canvas: Canvas, bounds: RectF, alpha: Float = 1f) {
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        canvas.drawBitmap(bitmap, null, bounds, paint)
    }

    fun asBookSurface(number: Int) = DemoPageSurface(pageId, number, DemoPageKind.TEXT,
        title = null, body = null, footer = null, imageAlternativeText = null, paginated = this)
}

/** Only background rendering calls this object; the page index contains lightweight metrics. */
internal class AndroidPageRasterizer(
    private val document: BookDocument,
    private val key: LayoutKey,
    missingImageLabel: String,
) {
    private val sources by lazy { document.readingFlow().associateBy { it.source.id } }
    private val measurer = AndroidBookMeasurer(document, missingImageLabel, decodeImages = true)
    private val measurements = WeightedLruCache<BlockId, BlockMeasurement>(MEASUREMENT_CACHE_BYTES) {
        when (it) {
            is AndroidImageMeasurement -> (it.bitmap?.allocationByteCount?.toLong() ?: 0L) + 1024
            is AndroidTextMeasurement -> it.layout.text.length * 8L + it.lines.size * 160L + 1024
            else -> 1024
        }
    }
    // Keep native viewport pixels on ordinary phones/Folds. Cap only extreme dimensions:
    // three single pages or six spread pages always fit within 48 MiB.
    private val maxSurfacePixels = HOT_SURFACES_BYTES / 4 / if (key.pageMode == PageMode.SPREAD) 6 else 3
    private val resolutionScale = min(1.0, sqrt(maxSurfacePixels.toDouble() /
        (key.pageWidthPx.toLong() * key.pageHeightPx)))
    private val width = floor(key.pageWidthPx * resolutionScale).toInt().coerceAtLeast(1)
    private val height = floor(key.pageHeightPx * resolutionScale).toInt().coerceAtLeast(1)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    var renderCount: Long = 0; private set
    var measureCount: Long = 0; private set

    fun clearMeasurements() = measurements.clear()

    suspend fun render(page: PageLayout): PaginatedPageSurface {
        val context = currentCoroutineContext()
        context.ensureActive()
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Trace.beginSection("Listai.pageRaster")
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(width.toFloat() / key.pageWidthPx, height.toFloat() / key.pageHeightPx)
            for (fragment in page.blocks) {
                context.ensureActive()
                val measured = measurements[fragment.blockId] ?: measurer.measure(
                    requireNotNull(sources[fragment.blockId]), key,
                ).also {
                    measureCount++
                    measurements.put(fragment.blockId, it)
                }
                // Refuse mismatched source/layout fragments rather than drawing an incorrect leaf.
                check(measured.lines[fragment.firstLine].start == fragment.startCharOffset)
                check(measured.lines[fragment.endLineExclusive - 1].endExclusive == fragment.endCharOffsetExclusive)
                canvas.withTranslation(fragment.x, fragment.y) {
                    canvas.clipRect(0f, 0f, fragment.width, fragment.height)
                    canvas.scale(fragment.scale, fragment.scale)
                    when (measured) {
                        is AndroidTextMeasurement -> {
                            canvas.translate(0f, -measured.lines[fragment.firstLine].top)
                            measured.layout.draw(canvas)
                        }
                        is AndroidImageMeasurement -> {
                            measured.bitmap?.let {
                                canvas.drawBitmap(it, null, RectF(0f, 0f, measured.width, measured.height), imagePaint)
                            } ?: measured.placeholder?.draw(canvas)
                        }
                    }
                }
            }
            val number = NumberFormat.getIntegerInstance(Locale.forLanguageTag(key.localeTag)).format(page.displayNumber)
            val footer = StaticLayout.Builder.obtain(number, 0, number.length,
                textPaint(key, multiplier = 12f / key.fontSizeSp).apply { color = 0xFF888888.toInt() }, key.pageWidthPx)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            canvas.translate(0f, key.pageHeightPx - key.marginY - footer.height)
            footer.draw(canvas)
            context.ensureActive()
            bitmap.prepareToDraw()
            renderCount++
            val text = page.blocks.joinToString("\n") { fragment ->
                requireNotNull(sources[fragment.blockId]).source.plainText()
                    .substring(fragment.startCharOffset, fragment.endCharOffsetExclusive)
            }
            return PaginatedPageSurface(page.id, bitmap, text)
        } catch (error: Throwable) {
            // This bitmap has never been published and cannot be referenced by the renderer.
            bitmap.recycle()
            throw error
        } finally {
            Trace.endSection()
        }
    }
}

/** Curl/idle draws only a prepared bitmap. No text measurement, decoding or rasterization here. */
internal fun DrawScope.drawPaginatedPage(surface: PaginatedPageSurface, bounds: Rect, alpha: Float) {
    drawIntoCanvas {
        // Align the local texture to physical pixels: a fractional static quad would filter
        // native-resolution text a second time. Curl still applies its continuous outer matrix.
        val left = bounds.left.roundToInt().toFloat()
        val top = bounds.top.roundToInt().toFloat()
        surface.drawTo(it.nativeCanvas,
            RectF(left, top, left + bounds.width.roundToInt(), top + bounds.height.roundToInt()), alpha)
    }
}
