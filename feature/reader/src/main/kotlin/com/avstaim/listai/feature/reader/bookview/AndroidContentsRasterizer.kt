package com.avstaim.listai.feature.reader.bookview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import com.avstaim.listai.core.designsystem.PlyosTypefaceFamily
import com.avstaim.listai.reader.pagination.BlockMeasurement
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.PageLayout
import com.avstaim.listai.reader.pagination.PageMode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

internal class AndroidContentsMeasurer(
    private val typefaces: PlyosTypefaceFamily = PlyosTypefaceFamily.systemSerif,
) : ContentsTextMeasurer {
    override fun measure(text: String, width: Int, style: ContentsTextStyle, key: LayoutKey): BlockMeasurement {
        val paint = textPaint(key, if (style == ContentsTextStyle.TITLE) Typeface.BOLD else Typeface.NORMAL,
            when (style) { ContentsTextStyle.TITLE -> 1.16f; ContentsTextStyle.NESTED -> .94f; else -> 1f },
            typefaces)
        return AndroidTextMeasurement(StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(if (style == ContentsTextStyle.TITLE) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(false)
            .setLineSpacing(0f, key.lineHeightMultiplier.coerceAtMost(1.3f))
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build())
    }
}

/** Full contents leaves are immutable paper textures, including leaders and source-page labels. */
internal class AndroidContentsRasterizer(
    private val contents: PhysicalContents,
    private val key: LayoutKey,
    private val typefaces: PlyosTypefaceFamily = PlyosTypefaceFamily.systemSerif,
) {
    private val geometry = ContentsGeometry(key)
    private val measurer = AndroidContentsMeasurer(typefaces)
    private val rows = contents.entries.associateBy { it.blockId }
    private val numberFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag(key.localeTag)).apply {
        isGroupingUsed = false
    }
    private val maxPixels = 48L * 1024 * 1024 / 4 / if (key.pageMode == PageMode.SPREAD) 6 else 3
    private val resolution = min(1.0, sqrt(maxPixels.toDouble() / (key.pageWidthPx.toLong() * key.pageHeightPx)))

    suspend fun render(page: PageLayout, numbers: ContentsPageNumbers): PaginatedPageSurface {
        val context = currentCoroutineContext()
        context.ensureActive()
        val bitmap = createBitmap(floor(key.pageWidthPx * resolution).toInt().coerceAtLeast(1),
            floor(key.pageHeightPx * resolution).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(bitmap.width.toFloat() / key.pageWidthPx, bitmap.height.toFloat() / key.pageHeightPx)
            val heading = measurer.measure(contents.title, key.contentWidth, ContentsTextStyle.TITLE, key) as AndroidTextMeasurement
            val titleHeight = min(heading.layout.height.toFloat(), key.contentHeight * .25f)
            canvas.withTranslation(key.marginX, key.contentTop) {
                canvas.scale(1f, titleHeight / heading.layout.height)
                heading.layout.draw(canvas)
            }
            val text = StringBuilder(contents.title)
            for (fragment in page.blocks) {
                context.ensureActive()
                val entry = rows[fragment.blockId] ?: continue
                val style = if (entry.depth == 0) ContentsTextStyle.CHAPTER else ContentsTextStyle.NESTED
                val measured = measurer.measure(entry.title, geometry.textWidth(entry.depth), style, key) as AndroidTextMeasurement
                check(measured.lines[fragment.firstLine].start == fragment.startCharOffset)
                check(measured.lines[fragment.endLineExclusive - 1].endExclusive == fragment.endCharOffsetExclusive)
                val textHeight = (measured.lines[fragment.endLineExclusive - 1].bottom -
                    measured.lines[fragment.firstLine].top) * fragment.scale
                val padding = (fragment.height - textHeight) / 2
                val indent = geometry.indent(entry.depth)
                canvas.withTranslation(fragment.x + indent, fragment.y + padding) {
                    canvas.withSave {
                        canvas.clipRect(0f, 0f, fragment.width - indent - geometry.numberGutter, textHeight)
                        canvas.scale(fragment.scale, fragment.scale)
                        canvas.translate(0f, -measured.lines[fragment.firstLine].top)
                        measured.layout.draw(canvas)
                    }
                    val lastLine = fragment.endLineExclusive - 1
                    val baseline = (measured.layout.getLineBaseline(lastLine) - measured.lines[fragment.firstLine].top) * fragment.scale
                    val number = numbers.values[entry.blockId]
                    if (number != null) {
                        val numberPaint = textPaint(key, multiplier = if (entry.depth == 0) 1f else .94f,
                            typefaces = typefaces).apply {
                            textSize *= fragment.scale
                            textAlign = Paint.Align.RIGHT
                        }
                        val label = numberFormat.format(number)
                        val numberRight = fragment.width - indent
                        // Adapt very large source-page numbers within the fixed reserved gutter.
                        val maximumNumberWidth = (geometry.numberGutter - 8f * key.density).coerceAtLeast(1f)
                        val numberWidth = numberPaint.measureText(label)
                        if (numberWidth > maximumNumberWidth) numberPaint.textSize *= maximumNumberWidth / numberWidth
                        canvas.drawText(label, numberRight, baseline, numberPaint)
                        val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = if (key.darkTheme) 0xFF94A7B8.toInt() else 0xFF768796.toInt()
                        }
                        val start = measured.layout.getLineRight(lastLine) * fragment.scale + 7f * key.density
                        val end = numberRight - numberPaint.measureText(label) - 7f * key.density
                        val step = 4.5f * key.density
                        var x = start
                        while (x + step < end) {
                            canvas.drawCircle(x, baseline - numberPaint.textSize * .12f, .65f * key.density, dots)
                            x += step
                        }
                    }
                }
                text.append('\n').append(entry.title.substring(fragment.startCharOffset, fragment.endCharOffsetExclusive))
                numbers.values[entry.blockId]?.let { text.append("  ").append(numberFormat.format(it)) }
            }
            context.ensureActive()
            bitmap.prepareToDraw()
            return PaginatedPageSurface(page.id, bitmap, text.toString(), key.darkTheme, key,
                typefaces = typefaces)
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }
}
