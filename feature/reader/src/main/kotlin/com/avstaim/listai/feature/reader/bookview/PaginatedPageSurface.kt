package com.avstaim.listai.feature.reader.bookview

import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import androidx.core.graphics.withTranslation
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.avstaim.listai.feature.reader.demo.DemoPageKind
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.PageLayout
import java.text.NumberFormat
import java.util.Locale

/** Stage 4 drawing payload; cache/eviction policy belongs to stage 5. */
internal class PaginatedPageSurface(val page: PageLayout, val key: LayoutKey) {
    private val number = NumberFormat.getIntegerInstance(Locale.forLanguageTag(key.localeTag)).format(page.displayNumber)
    val footer: StaticLayout = StaticLayout.Builder.obtain(number, 0, number.length,
        textPaint(key, multiplier = 12f / key.fontSizeSp).apply { color = 0xFF888888.toInt() }, key.pageWidthPx)
        .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
    val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun asBookSurface() = DemoPageSurface(page.id, page.displayNumber, DemoPageKind.TEXT,
        title = null, body = null, footer = null, imageAlternativeText = null, paginated = this)
}

internal fun DrawScope.drawPaginatedPage(surface: PaginatedPageSurface, bounds: Rect, alpha: Float) {
    drawIntoCanvas { composeCanvas ->
        val canvas = composeCanvas.nativeCanvas
        val save = canvas.save()
        canvas.translate(bounds.left, bounds.top)
        canvas.scale(bounds.width / surface.key.pageWidthPx, bounds.height / surface.key.pageHeightPx)
        if (alpha < 1f) canvas.saveLayerAlpha(0f, 0f, surface.key.pageWidthPx.toFloat(),
            surface.key.pageHeightPx.toFloat(), (alpha.coerceIn(0f, 1f) * 255).toInt())
        for (fragment in surface.page.blocks) {
            canvas.withTranslation(fragment.x, fragment.y) {
                canvas.clipRect(0f, 0f, fragment.width, fragment.height)
                canvas.scale(fragment.scale, fragment.scale)
                when (val measured = fragment.measurement) {
                    is AndroidTextMeasurement -> {
                        canvas.translate(0f, -measured.lines[fragment.firstLine].top)
                        measured.layout.draw(canvas)
                    }
                    is AndroidImageMeasurement -> {
                        measured.bitmap?.let { bitmap ->
                            canvas.drawBitmap(bitmap, null, RectF(0f, 0f, measured.width, measured.height), surface.imagePaint)
                        } ?: measured.placeholder?.draw(canvas)
                    }
                }
            }
        }
        canvas.translate(0f, surface.key.pageHeightPx - surface.key.marginY - surface.footer.height)
        surface.footer.draw(canvas)
        canvas.restoreToCount(save)
    }
}
