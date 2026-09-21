package com.avstaim.listai.feature.reader.bookview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextDirectionHeuristics
import android.text.style.LeadingMarginSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SuperscriptSpan
import android.text.style.UnderlineSpan
import com.avstaim.listai.core.designsystem.PlyosTypefaceFamily
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.InlineNode
import com.avstaim.listai.core.model.LinkTarget
import com.avstaim.listai.core.model.ParagraphRole
import com.avstaim.listai.core.model.plainText
import com.avstaim.listai.reader.pagination.BlockMeasurement
import com.avstaim.listai.reader.pagination.BlockMeasurer
import com.avstaim.listai.reader.pagination.FlowBlock
import com.avstaim.listai.reader.pagination.HyphenationMode
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.MeasuredLine
import com.avstaim.listai.reader.pagination.textContent
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

internal data class AndroidTextMeasurement(val layout: StaticLayout) : BlockMeasurement {
    override val width = layout.width.toFloat()
    override val lines = List(layout.lineCount) { index ->
        MeasuredLine(layout.getLineStart(index), layout.getLineEnd(index),
            layout.getLineTop(index).toFloat(), layout.getLineBottom(index).toFloat())
    }
}

internal data class AndroidImageMeasurement(
    val bitmap: Bitmap?,
    override val width: Float,
    val height: Float,
    val sourceLength: Int,
    val placeholder: StaticLayout?,
) : BlockMeasurement {
    override val lines = listOf(MeasuredLine(0, sourceLength, 0f, height))
}

/** Worker-only layout. Pagination keeps metrics; the rasterizer briefly caches native objects. */
internal class AndroidBookMeasurer(
    private val document: BookDocument,
    private val missingImageLabel: String,
    private val decodeImages: Boolean = false,
    internal val typefaces: PlyosTypefaceFamily = PlyosTypefaceFamily.systemSerif,
) : BlockMeasurer {
    // API 36's IntDefs name LineBreaker constants; these inline to the identical Layout values
    // supported since APIs 23 (break strategy) and 26 (justification). No API 29 object is used.
    @SuppressLint("InlinedApi")
    override fun measure(block: FlowBlock, key: LayoutKey): BlockMeasurement {
        val source = block.source
        if (source is BookBlock.Image) return image(source, key)
        val paragraph = source as? BookBlock.Paragraph
        val heading = source is BookBlock.Heading || paragraph?.role == ParagraphRole.SUBTITLE
        val italic = block.quoteDepth > 0 || source is BookBlock.Poem || paragraph?.role == ParagraphRole.TEXT_AUTHOR
        val style = when {
            heading && italic -> Typeface.BOLD_ITALIC
            heading -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val paint = textPaint(key, style, if (heading) 1.25f else 1f, typefaces)
        val content = source.textContent().toSpannedText(key.darkTheme, typefaces)
        val inset = (key.contentWidth * min(.22f, block.quoteDepth * .05f)).roundToInt()
        val width = (key.contentWidth - inset * 2).coerceAtLeast(1)
        if (paragraph?.role == ParagraphRole.BODY && block.quoteDepth == 0 && content.isNotEmpty()) {
            content.setSpan(LeadingMarginSpan.Standard((paint.textSize * 1.3f).toInt(), 0),
                0, content.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val alignment = when {
            heading -> Layout.Alignment.ALIGN_CENTER
            paragraph?.role == ParagraphRole.TEXT_AUTHOR -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val layout = StaticLayout.Builder.obtain(content, 0, content.length, paint, width)
            .setAlignment(alignment)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(true)
            .setLineSpacing(0f, key.lineHeightMultiplier)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(when (key.hyphenationMode) {
                HyphenationMode.NONE -> Layout.HYPHENATION_FREQUENCY_NONE
                HyphenationMode.NORMAL -> Layout.HYPHENATION_FREQUENCY_NORMAL
                HyphenationMode.FULL -> Layout.HYPHENATION_FREQUENCY_FULL
            })
            .setJustificationMode(if (paragraph?.role == ParagraphRole.BODY && block.quoteDepth == 0)
                LineBreaker.JUSTIFICATION_MODE_INTER_WORD else LineBreaker.JUSTIFICATION_MODE_NONE)
            .build()
        return AndroidTextMeasurement(layout)
    }

    private fun image(source: BookBlock.Image, key: LayoutKey): AndroidImageMeasurement {
        val bytes = document.imagesById[source.imageId]?.content?.load()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val valid = bounds.outWidth > 0 && bounds.outHeight > 0
        val fit = if (valid) min(key.contentWidth.toFloat() / bounds.outWidth,
            key.contentHeight / bounds.outHeight).coerceAtMost(1f) else 1f
        val targetWidth = if (valid) (bounds.outWidth * fit).coerceAtLeast(1f) else key.contentWidth.toFloat()
        val targetHeight = if (valid) (bounds.outHeight * fit).coerceAtLeast(1f) else min(key.contentHeight, 100 * key.density)
        var sample = 1
        if (valid) while (sample < (1 shl 29) && bounds.outWidth / (sample * 2) >= targetWidth &&
            bounds.outHeight / (sample * 2) >= targetHeight) sample *= 2
        while (valid && bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 2_000_000L) sample *= 2
        val bitmap = if (decodeImages && valid && bytes != null) runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() else null
        val placeholder = if (decodeImages && bitmap == null) {
            val text = source.alternativeText?.takeIf { it.isNotBlank() } ?: missingImageLabel
            StaticLayout.Builder.obtain(text, 0, text.length,
                textPaint(key, Typeface.ITALIC, .85f, typefaces), key.contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).build()
        } else null
        return AndroidImageMeasurement(bitmap, targetWidth, targetHeight, source.plainText().length, placeholder)
    }
}

internal fun textPaint(
    key: LayoutKey,
    style: Int = Typeface.NORMAL,
    multiplier: Float = 1f,
    typefaces: PlyosTypefaceFamily = PlyosTypefaceFamily.systemSerif,
) =
    TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = if (key.darkTheme) 0xFFE3EAF0.toInt() else 0xFF242A30.toInt()
        textSize = key.fontSizeSp * key.density * key.textScale * multiplier
        typeface = typefaces.resolve(style)
        textLocale = Locale.forLanguageTag(key.localeTag)
    }

internal fun noteInkColor(darkTheme: Boolean): Int =
    if (darkTheme) 0xFFA9BECE.toInt() else 0xFF536E82.toInt()

private class PlyosStyleSpan(
    style: Int,
    private val typefaces: PlyosTypefaceFamily,
) : StyleSpan(style) {
    private fun apply(paint: TextPaint) {
        val inherited = paint.typeface?.style ?: Typeface.NORMAL
        paint.typeface = typefaces.resolve(inherited or style)
    }

    override fun updateDrawState(textPaint: TextPaint) = apply(textPaint)
    override fun updateMeasureState(textPaint: TextPaint) = apply(textPaint)
}

internal fun List<InlineNode>.toSpannedText(
    darkTheme: Boolean = false,
    typefaces: PlyosTypefaceFamily = PlyosTypefaceFamily.systemSerif,
): SpannableStringBuilder = SpannableStringBuilder().also { result ->
    fun append(node: InlineNode) {
        val start = result.length
        val span = when (node) {
            is InlineNode.Text -> { result.append(node.value); null }
            is InlineNode.Image -> { result.append(node.alternativeText.orEmpty()); null }
            is InlineNode.Emphasis -> { node.children.forEach(::append); PlyosStyleSpan(Typeface.ITALIC, typefaces) }
            is InlineNode.Strong -> { node.children.forEach(::append); PlyosStyleSpan(Typeface.BOLD, typefaces) }
            is InlineNode.Strikethrough -> { node.children.forEach(::append); StrikethroughSpan() }
            is InlineNode.Link -> {
                node.children.forEach(::append)
                if (node.target is LinkTarget.Note && result.length > start) {
                    val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    result.setSpan(RelativeSizeSpan(.72f), start, result.length, flags)
                    result.setSpan(SuperscriptSpan(), start, result.length, flags)
                    result.setSpan(ForegroundColorSpan(noteInkColor(darkTheme)), start, result.length, flags)
                    // A zero-width ReplacementSpan behaves as an inline object and can orphan
                    // the marker on the next line. Replace only the display's wrapping bracket
                    // code units with WORD JOINER: this binds the call to its preceding word and
                    // following punctuation while preserving every source UTF-16 offset.
                    if (result.length - start > 2 && result[start] == '[' && result.last() == ']') {
                        result.replace(start, start + 1, "\u2060")
                        result.replace(result.length - 1, result.length, "\u2060")
                    }
                    null
                } else UnderlineSpan()
            }
        }
        if (span != null && result.length > start) result.setSpan(span, start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    forEach(::append)
}
