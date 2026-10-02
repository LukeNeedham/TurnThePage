package com.avstaim.listai.feature.reader.bookview

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.geometry.Rect
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import com.avstaim.listai.core.designsystem.PlyosTypefaceFamily
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.NoteId
import com.avstaim.listai.core.model.NoteReference
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.PageBlockLayout
import com.avstaim.listai.reader.pagination.PageFootnoteLayout
import com.avstaim.listai.reader.pagination.footnoteFlowBlock
import com.avstaim.listai.reader.pagination.footnoteLayoutKey
import kotlin.math.max
import kotlin.math.min

internal enum class NoteLinkKind { INLINE, FOOTER }

/** Immutable native-page coordinates, prepared before publication and shared by footer wrappers. */
internal data class PreparedNoteLink(
    val noteId: NoteId,
    val origin: BookLocation,
    val bounds: Rect,
    val kind: NoteLinkKind,
)

internal data class PreparedNoteReturn(val noteId: NoteId, val bounds: Rect, val label: String)

/** A return mark is printed on the leaf and uses its exact bitmap/curl transform. */
internal class PreparedNoteReturnInk(
    val value: PreparedNoteReturn,
    key: LayoutKey,
    typefaces: PlyosTypefaceFamily = PlyosTypefaceFamily.systemSerif,
) {
    private val paint = textPaint(key, multiplier = 13f / key.fontSizeSp, typefaces = typefaces)
        .apply { color = noteInkColor(key.darkTheme) }
    private val text: StaticLayout

    init {
        fun layout() = StaticLayout.Builder.obtain(value.label, 0, value.label.length, paint,
            value.bounds.width.toInt().coerceAtLeast(1))
            .setIncludePad(false).setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        val natural = layout()
        text = if (natural.height > value.bounds.height && value.bounds.height > 0f) {
            paint.textSize *= value.bounds.height / natural.height
            layout()
        } else natural
    }

    fun drawTo(canvas: Canvas, alpha: Int) {
        paint.alpha = alpha
        canvas.withSave {
            canvas.clipRect(value.bounds.left, value.bounds.top, value.bounds.right, value.bounds.bottom)
            canvas.translate(value.bounds.left, value.bounds.top + (value.bounds.height - text.height) / 2f)
            text.draw(canvas)
        }
    }
}

internal fun inlineNoteLinks(
    fragment: PageBlockLayout,
    layout: StaticLayout,
    references: List<NoteReference>,
    textTop: Float = 0f,
): List<PreparedNoteLink> = buildList {
    val path = Path()
    val selection = RectF()
    val firstLineTop = fragment.measurement.lines[fragment.firstLine].top - textTop
    for (reference in references) {
        val start = max(fragment.startCharOffset, reference.source.charOffset)
        val end = min(fragment.endCharOffsetExclusive, reference.endCharOffsetExclusive)
        if (start >= end) continue
        // Keep distinct visual line rectangles. A wrapped link must not acquire a hit box over
        // unrelated ink between the end of one line and the start of the next.
        for (line in layout.getLineForOffset(start)..layout.getLineForOffset(end - 1)) {
            val lineStart = max(start, layout.getLineStart(line))
            val lineEnd = min(end, layout.getLineEnd(line))
            path.reset()
            layout.getSelectionPath(lineStart, lineEnd, path)
            path.computeBounds(selection, true)
            if (selection.isEmpty) continue
            val left = fragment.x + selection.left * fragment.scale
            val top = fragment.y + (selection.top - firstLineTop) * fragment.scale
            val right = fragment.x + selection.right * fragment.scale
            val bottom = fragment.y + (selection.bottom - firstLineTop) * fragment.scale
            add(PreparedNoteLink(reference.noteId, reference.source,
                Rect(left.coerceAtLeast(fragment.x), top.coerceAtLeast(fragment.y),
                    right.coerceAtMost(fragment.x + fragment.width),
                    bottom.coerceAtMost(fragment.y + fragment.height)), NoteLinkKind.INLINE))
        }
    }
}

internal data class RasterizedFootnotes(val links: List<PreparedNoteLink>, val accessibleText: String)

/** The paginator reserves one overflow line even when the localized label naturally wraps. */
internal fun prepareFootnoteOverflowLine(original: StaticLayout): StaticLayout {
    val paint = TextPaint(original.paint)
    val width = original.width
    val shown = if (Layout.getDesiredWidth(original.text, paint) > width) {
        val suffix = "… →"
        val suffixWidth = paint.measureText(suffix)
        // A pathological viewport must still show a complete continuation mark.
        if (suffixWidth > width) paint.textSize *= width / suffixWidth
        val head = TextUtils.ellipsize(original.text, paint,
            (width - paint.measureText(suffix)).coerceAtLeast(0f), TextUtils.TruncateAt.END)
        SpannableStringBuilder(head).apply {
            while (isNotEmpty() && last() == '…') delete(length - 1, length)
            append(suffix)
        }
    } else original.text
    return StaticLayout.Builder.obtain(shown, 0, shown.length, paint, width)
        .setIncludePad(false).setMaxLines(1).build()
}

/** Short notes become source pixels once; layout and clipping never run during a gesture. */
internal fun rasterizeFootnotes(
    canvas: Canvas,
    footnotes: List<PageFootnoteLayout>,
    key: LayoutKey,
    measurer: AndroidBookMeasurer,
): RasterizedFootnotes {
    if (footnotes.isEmpty()) return RasterizedFootnotes(emptyList(), "")
    val footnoteKey = key.footnoteLayoutKey()
    val ink = textPaint(footnoteKey, typefaces = measurer.typefaces)
    val labelInk = textPaint(footnoteKey, typefaces = measurer.typefaces)
        .apply { color = noteInkColor(key.darkTheme) }
    val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (key.darkTheme) 0xFF6D7D8B.toInt() else 0xFFA4AFB8.toInt()
        strokeWidth = (.55f * key.density).coerceAtLeast(.5f)
    }
    val separatorGap = min(7f * key.density, key.contentHeight * .036f)
    val ruleY = (footnotes.first().y - separatorGap * footnotes.first().scale)
        .coerceAtLeast(key.contentTop)
    canvas.drawLine(key.marginX, ruleY, key.marginX + key.contentWidth * .25f, ruleY, rule)
    val links = mutableListOf<PreparedNoteLink>()
    val accessible = mutableListOf<String>()
    for (entry in footnotes) {
        val measured = measurer.measure(footnoteFlowBlock(entry.noteId, entry.content), footnoteKey)
            as AndroidTextMeasurement
        val layout = measured.layout
        val end = entry.endCharOffsetExclusive.coerceIn(0, layout.text.length)
        val lastLine = layout.getLineForOffset((end - 1).coerceAtLeast(0))
        val lineStart = layout.getLineStart(lastLine)
        var shownText: CharSequence = layout.text.subSequence(0, end)
        canvas.withSave {
            canvas.clipRect(entry.x, entry.y, entry.x + entry.width, entry.y + entry.height)
            canvas.translate(entry.x, entry.y)
            canvas.scale(entry.scale, entry.scale)
            if (entry.hiddenNoteCount > 0) {
                val overflow = prepareFootnoteOverflowLine(layout)
                canvas.withTranslation(0f, (layout.getLineBaseline(0) - overflow.getLineBaseline(0)).toFloat()) {
                    overflow.draw(canvas)
                }
                // Accessibility keeps the complete localized count, including text elided in ink.
                shownText = layout.text
            } else if (entry.truncated) {
                // Draw the original line breaks. Rebuilding a high-quality paragraph after
                // truncation can change preceding lines and invalidate pagination metrics.
                canvas.withSave {
                    canvas.clipRect(0f, 0f, layout.width.toFloat(), layout.getLineTop(lastLine).toFloat())
                    layout.draw(canvas)
                }
                val suffix = "… →"
                val lastText = layout.text.subSequence(lineStart, end).trimEnd()
                val ellipsized = TextUtils.ellipsize(lastText, ink,
                    (layout.width - ink.measureText(suffix)).coerceAtLeast(1f), TextUtils.TruncateAt.END)
                // TextUtils already appends an ellipsis when it shortens the tail.
                val tailText = SpannableStringBuilder(ellipsized)
                while (tailText.isNotEmpty() && tailText.last() == '…') tailText.delete(tailText.length - 1, tailText.length)
                tailText.append(suffix)
                val tail = StaticLayout.Builder.obtain(tailText, 0, tailText.length, ink,
                    layout.width).setIncludePad(false).setMaxLines(1).build()
                canvas.withTranslation(0f, (layout.getLineBaseline(lastLine) - tail.getLineBaseline(0)).toFloat()) {
                    tail.draw(canvas)
                }
                shownText = SpannableStringBuilder(layout.text.subSequence(0, lineStart)).append(tailText)
            } else {
                layout.draw(canvas)
                shownText = layout.text.subSequence(0, end)
            }
        }
        if (entry.hiddenNoteCount == 0) {
            val label = entry.ordinal.toString()
            labelInk.textSize = ink.textSize
            val available = (entry.x - key.marginX - 5f * key.density).coerceAtLeast(1f)
            val naturalWidth = labelInk.measureText(label)
            if (naturalWidth * entry.scale > available) labelInk.textSize *= available / (naturalWidth * entry.scale)
            val labelX = (entry.x - 5f * key.density - labelInk.measureText(label) * entry.scale).coerceAtLeast(key.marginX)
            canvas.withTranslation(labelX, entry.y) {
                canvas.scale(entry.scale, entry.scale)
                canvas.drawText(label, 0f, layout.getLineBaseline(0).toFloat(), labelInk)
            }
        }
        links += PreparedNoteLink(entry.noteId, entry.source,
            Rect(key.marginX, entry.y, entry.x + entry.width, entry.y + entry.height), NoteLinkKind.FOOTER)
        accessible += if (entry.hiddenNoteCount > 0) shownText.toString() else "${entry.ordinal}. $shownText"
    }
    return RasterizedFootnotes(links.toList(), accessible.joinToString("\n"))
}
