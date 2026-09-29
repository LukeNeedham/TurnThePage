package com.avstaim.listai.feature.reader.bookview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.RectF
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
import com.avstaim.listai.core.designsystem.BookOrnament
import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookPart
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
import com.avstaim.listai.reader.pagination.MeasurementMetrics
import com.avstaim.listai.reader.pagination.textContent
import com.avstaim.listai.reader.pagination.readingFlow
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

private const val PART_HEADING_LINE_HEIGHT = 1.08f

internal data class AndroidTextMeasurement(val layout: StaticLayout,
    val chapterHeading: PreparedBlockDecoration? = null, val trailingSpace: Float = 0f,
    val followingDecoration: PreparedBlockDecoration? = null) : BlockMeasurement {
    val textTop: Float get() = chapterHeading?.height ?: 0f
    override val width = layout.width.toFloat()
    override val lines = List(layout.lineCount) { index ->
        MeasuredLine(layout.getLineStart(index), layout.getLineEnd(index),
            if (index == 0) 0f else layout.getLineTop(index).toFloat() + textTop,
            layout.getLineBottom(index).toFloat() + textTop + if (index == layout.lineCount - 1) trailingSpace + (followingDecoration?.height ?: 0f) else 0f)
    }
}

internal interface PreparedBlockDecoration {
    val height: Float
    fun draw(canvas: Canvas)
}

internal class PreparedChapterHeading(number: String, key: LayoutKey, typefaces: PlyosTypefaceFamily) : PreparedBlockDecoration {
    private val paint = textPaint(key, Typeface.BOLD, 1.45f, typefaces)
    private val label = (if (key.localeTag.startsWith("ru")) "Глава " else "Chapter ") + number
    private val layout = StaticLayout.Builder.obtain(label, 0, label.length, paint, key.contentWidth)
        .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
    private val top = minOf(key.contentHeight * .065f, 32f * key.density)
    private val gap = minOf(key.contentHeight * .04f, 24f * key.density)
    private val ornamentHeight = minOf(12f * key.density, key.contentHeight * .03f)
    private val ornamentWidth = minOf(96f * key.density, key.contentWidth * .35f)
    private val ornament = BookOrnament(paint.color)
    override val height = top + layout.height + gap * 2 + ornamentHeight
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(0f, top)
        layout.draw(canvas)
        canvas.restoreToCount(save)
        val left = (layout.width - ornamentWidth) / 2f
        ornament.draw(canvas, RectF(left, top + layout.height + gap,
            left + ornamentWidth, top + layout.height + gap + ornamentHeight))
    }
}

internal class PreparedTitleDecoration(key: LayoutKey, private val opening: Boolean,
    textHeight: Int) : PreparedBlockDecoration {
    private val preferredHeight = minOf(key.contentHeight * if (opening) .11f else .12f,
        (if (opening) 64f else 84f) * key.density)
    // Preserve the chosen type size; spend the decorative whitespace first when a title is long.
    private val availableHeight = (key.contentHeight - textHeight -
        key.fontSizeSp * key.density * key.textScale * if (opening) 3f else 1f).coerceAtLeast(0f)
    override val height = if (opening) minOf(preferredHeight, availableHeight * .25f)
        else minOf(preferredHeight, maxOf(16f * key.density, availableHeight * .2f))
    private val width = minOf(key.contentWidth * .34f, 96f * key.density)
    private val left = (key.contentWidth - width) / 2f
    private val ornamentHeight = minOf(12f * key.density, height * .25f)
    private val ornament = BookOrnament(0xFF64717C.toInt())
    override fun draw(canvas: Canvas) {
        if (!opening) ornament.draw(canvas, RectF(left, (height - ornamentHeight) / 2,
            left + width, (height + ornamentHeight) / 2))
    }
}

/** Prepared once with text; the divider itself remains a vector at every viewport size. */
internal class PreparedPartOrnament(key: LayoutKey) : PreparedBlockDecoration {
    override val height = minOf(key.contentHeight * .095f, 62f * key.density)
    private val width = minOf(key.contentWidth * .36f, 104f * key.density)
    private val markHeight = minOf(12f * key.density, height * .25f)
    private val left = (key.contentWidth - width) / 2f
    private val ornament = BookOrnament(if (key.darkTheme) 0xFFADBBC7.toInt() else 0xFF64717C.toInt())
    override fun draw(canvas: Canvas) {
        ornament.draw(canvas, RectF(left, (height - markHeight) / 2f,
            left + width, (height + markHeight) / 2f))
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
    private val chapterByBlockId = document.chapters.flatMap { chapter -> chapter.headingBlockIds.map { it to chapter } }.toMap()
    private val partByBlockId = document.parts.flatMap { part -> part.blockIds.map { it to part } }.toMap()
    private val partHeadingTexts = document.readingFlow().filter { it.source.id in partByBlockId }
        .associate { it.source.id to it.source.plainText() }
    private val partTypeSizes = mutableMapOf<Pair<BookPart, LayoutKey>, Map<BlockId, Float>>()
    private val titleIds = if (document.isFrontmatter) document.titlePageBlockIds.toSet() else emptySet()
    // API 36's IntDefs name LineBreaker constants; these inline to the identical Layout values
    // supported since APIs 23 (break strategy) and 26 (justification). No API 29 object is used.
    @SuppressLint("InlinedApi")
    override fun measure(block: FlowBlock, key: LayoutKey): BlockMeasurement {
        val source = block.source
        val chapter = chapterByBlockId[source.id]
        val part = partByBlockId[source.id]
        val partHeading = part != null && source.id in part.headingBlockIds
        val title = source.id in titleIds
        val firstTitle = title && source.id == document.titlePageBlockIds.firstOrNull()
        if (source is BookBlock.Image) return image(source, key)
        if (source is BookBlock.FixedPage) {
            val fit = min(key.contentWidth.toFloat() / source.widthPoints,
                key.contentHeight / source.heightPoints)
            return MeasurementMetrics(listOf(MeasuredLine(0, 0, 0f,
                (source.heightPoints * fit).coerceAtLeast(1f))),
                (source.widthPoints * fit).coerceAtLeast(1f))
        }
        val paragraph = source as? BookBlock.Paragraph
        val heading = source is BookBlock.Heading || paragraph?.role == ParagraphRole.SUBTITLE
        val italic = !partHeading && (part != null || chapter != null || title && !firstTitle || block.quoteDepth > 0 || source is BookBlock.Poem || paragraph?.role == ParagraphRole.TEXT_AUTHOR)
        val style = when {
            heading && italic -> Typeface.BOLD_ITALIC
            heading -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val paint = textPaint(key, style, when {
            partHeading -> partHeadingSizes(requireNotNull(part), key).getValue(source.id)
            firstTitle -> 1.65f
            heading -> 1.25f
            title -> 1.15f
            else -> 1f
        }, typefaces)
        val content = source.textContent().toSpannedText(key.darkTheme, typefaces)
        if (chapter?.startBlockId == source.id && chapter.numberPrefixLength > 0) {
            // WORD JOINER preserves source UTF-16 coordinates while the number is printed above.
            val end = chapter.numberPrefixLength.coerceAtMost(content.length)
            content.replace(0, end, "\u2060".repeat(end))
        }
        val inset = (key.contentWidth * min(.22f, block.quoteDepth * .05f)).roundToInt()
        val width = (key.contentWidth - inset * 2).coerceAtLeast(1)
        if (!title && part == null && paragraph?.role == ParagraphRole.BODY && block.quoteDepth == 0 && content.isNotEmpty()) {
            content.setSpan(LeadingMarginSpan.Standard((paint.textSize * 1.3f).toInt(), 0),
                0, content.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val alignment = when {
            heading || title || part != null -> Layout.Alignment.ALIGN_CENTER
            paragraph?.role == ParagraphRole.TEXT_AUTHOR -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val layout = StaticLayout.Builder.obtain(content, 0, content.length, paint, width)
            .setAlignment(alignment)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(true)
            .setLineSpacing(0f, if (partHeading) PART_HEADING_LINE_HEIGHT else key.lineHeightMultiplier)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(when {
                part != null -> Layout.HYPHENATION_FREQUENCY_NONE
                else -> when (key.hyphenationMode) {
                HyphenationMode.NONE -> Layout.HYPHENATION_FREQUENCY_NONE
                HyphenationMode.NORMAL -> Layout.HYPHENATION_FREQUENCY_NORMAL
                HyphenationMode.FULL -> Layout.HYPHENATION_FREQUENCY_FULL
                }
            })
            .setJustificationMode(if (!title && part == null && paragraph?.role == ParagraphRole.BODY && block.quoteDepth == 0)
                LineBreaker.JUSTIFICATION_MODE_INTER_WORD else LineBreaker.JUSTIFICATION_MODE_NONE)
            .build()
        return AndroidTextMeasurement(layout, when {
            firstTitle -> PreparedTitleDecoration(key, opening = true, textHeight = layout.height)
            title && source.id == document.titlePageBlockIds.getOrNull(1) -> PreparedTitleDecoration(key, opening = false, textHeight = layout.height)
            chapter?.startBlockId == source.id -> PreparedChapterHeading(chapter.number ?: chapter.ordinal.toString(), key, typefaces)
            else -> null
        }, trailingSpace = if (chapter != null && chapter.headingBlockIds.last() == source.id)
            minOf(key.contentHeight * .035f, 22f * key.density) else 0f,
            followingDecoration = if (partHeading && source.id == part?.headingBlockIds?.last())
                PreparedPartOrnament(key) else null)
    }

    /** Reduce decorative enlargement before allowing a moderate title to span several leaves.
     * Body/epigraph sizes keep the user's accessibility scale. On a narrow page the decorative
     * hierarchy yields to legibility; the part heading never becomes smaller than body text.
     * Truly oversized source text still flows without clipping. */
    private fun partHeadingSizes(part: BookPart, key: LayoutKey): Map<BlockId, Float> =
        partTypeSizes.getOrPut(part to key) {
            var reduction = 1f
            var sizes = emptyMap<BlockId, Float>()
            repeat(8) {
                sizes = part.headingBlockIds.mapIndexed { index, id ->
                    id to maxOf(1.1f, (if (index == 0) 1.75f else 1.9f) * reduction)
                }.toMap()
                val layouts = part.headingBlockIds.map { id ->
                    val text = partHeadingTexts.getValue(id)
                    StaticLayout.Builder.obtain(text, 0, text.length,
                        textPaint(key, Typeface.BOLD, sizes.getValue(id), typefaces), key.contentWidth)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(true)
                        .setLineSpacing(0f, PART_HEADING_LINE_HEIGHT).build()
                }
                val shortLabelFits = partHeadingTexts.getValue(part.startBlockId).length > 24 || layouts.first().lineCount == 1
                if (shortLabelFits && layouts.sumOf { it.height } <= key.contentHeight * .40f) return@getOrPut sizes
                reduction *= .90f
            }
            sizes
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
