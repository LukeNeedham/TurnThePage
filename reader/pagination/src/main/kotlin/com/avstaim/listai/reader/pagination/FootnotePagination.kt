package com.avstaim.listai.reader.pagination

import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.InlineNode
import com.avstaim.listai.core.model.NoteId
import com.avstaim.listai.core.model.NoteReference
import com.avstaim.listai.core.model.PageId
import com.avstaim.listai.core.model.plainText
import com.avstaim.listai.core.model.sliceInlineContent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield

private const val MAX_REFLOWS = 8
private const val MAX_PREVIEW_CHARS = 4096

/** Shrinking a candidate is monotonic: discarded source lines never return to that page. */
internal fun paginateWithFootnotes(
    document: BookDocument,
    location: BookLocation?,
    key: LayoutKey,
    measurer: BlockMeasurer,
): Flow<PaginationSnapshot> = flow {
    val readingFlow = document.readingFlow()
    val anchor = readingFlow.resolveLocation(location)
    val measurements = mutableMapOf<Int, MeasurementMetrics>()
    val notes = FootnotePlanner(document, key, measurer)
    var chapter: BlockId? = null
    val chapters = readingFlow.map {
        if (it.source is BookBlock.Heading) chapter = it.source.id
        chapter
    }
    suspend fun measured(index: Int): MeasurementMetrics {
        currentCoroutineContext().ensureActive()
        return measurements.getOrPut(index) {
            val measurement = measurer.measure(readingFlow[index], key)
            require(measurement.lines.isNotEmpty() && measurement.width.isFinite() && measurement.width > 0)
            MeasurementMetrics(measurement.lines.toList(), measurement.width)
        }
    }
    suspend fun candidate(start: SourceCursor): List<PageLine> {
        val lines = mutableListOf<PageLine>()
        var cursor = start
        var y = 0f
        while (cursor.block < readingFlow.size) {
            val block = readingFlow[cursor.block]
            val metrics = measured(cursor.block)
            if (cursor.line == 0 && block.source is BookBlock.Heading) {
                val following = if (cursor.block + 1 < readingFlow.size) measured(cursor.block + 1) else null
                val required = metrics.lines.last().bottom - metrics.lines.first().top + key.paragraphGap +
                    (following?.lines?.firstOrNull()?.height ?: 0f)
                if (lines.isNotEmpty() && required <= key.contentHeight && y + required > key.contentHeight) break
            }
            val line = metrics.lines[cursor.line]
            if (lines.isNotEmpty() && y + line.height > key.contentHeight) break
            val scale = minOf(1f, key.contentHeight / line.height)
            val next = if (cursor.line + 1 == metrics.lines.size) SourceCursor(cursor.block + 1, 0)
                else SourceCursor(cursor.block, cursor.line + 1)
            lines += PageLine(block, cursor, next, metrics, y, scale)
            y += line.height * scale
            if (next.block != cursor.block) y += key.paragraphGap
            cursor = next
        }
        return lines
    }
    fun references(lines: List<PageLine>): List<NoteReference> {
        val found = linkedMapOf<NoteId, NoteReference>()
        lines.forEach { line ->
            document.noteIndex.referencesIn(line.block.source.id, line.metric.start, line.metric.endExclusive).forEach { reference ->
                found.putIfAbsent(reference.noteId, reference)
            }
        }
        return found.values.toList()
    }
    fun trim(lines: List<PageLine>, height: Float): List<PageLine> {
        val kept = lines.takeWhile { it.bottom <= height + .001f }
        return kept.ifEmpty {
            // A giant line/image still advances exactly once, even in a one-pixel viewport.
            listOf(lines.first().copy(scale = minOf(1f, height / lines.first().metric.height)))
        }
    }
    val pages = mutableListOf<PageLayout>()
    var cursor = SourceCursor(0, 0)
    var lastPublished = 0
    var resolvedAnchorIndex: Int? = null
    while (cursor.block < readingFlow.size) {
        currentCoroutineContext().ensureActive()
        var lines = candidate(cursor)
        var plan = notes.plan(references(lines), allowOverflow = false)
        var stable = false
        for (iteration in 0 until MAX_REFLOWS) {
            currentCoroutineContext().ensureActive()
            if (plan.overflows) {
                val last = plan.references.last()
                val lastCallLine = lines.indexOfFirst { it.block.source.id == last.source.blockId &&
                    it.metric.start <= last.source.charOffset && last.source.charOffset < it.metric.endExclusive }
                if (lastCallLine > 0) {
                    lines = lines.take(lastCallLine)
                    plan = notes.plan(references(lines), allowOverflow = false)
                    continue
                }
                // Calls sharing the first line cannot be moved apart; one overflow link is enough.
                plan = notes.plan(references(lines), allowOverflow = true)
            }
            val fitted = trim(lines, key.contentHeight - plan.reservedHeight)
            if (fitted == lines) { stable = true; break }
            lines = fitted
            plan = notes.plan(references(lines), allowOverflow = false)
        }
        if (!stable) {
            // A bounded, conservative last pass handles adversarial marker density without oscillation.
            lines = trim(lines, key.contentHeight * .60f)
            plan = notes.plan(references(lines), allowOverflow = true)
            lines = trim(lines, key.contentHeight - plan.reservedHeight)
            plan = notes.plan(references(lines), allowOverflow = true)
        }
        val fragments = lines.fragments(key)
        val first = lines.first()
        val last = lines.last()
        pages += PageLayout(PageId(pages.size.toLong()),
            BookLocation(first.block.source.id, first.metric.start, first.block.globalOffset + first.metric.start),
            BookLocation(last.block.source.id, last.metric.endExclusive, last.block.globalOffset + last.metric.endExclusive),
            fragments, pages.size + 1, chapters[first.cursor.block], plan.layouts)
        cursor = last.next
        if (resolvedAnchorIndex == null && pages.last().endExclusive.fallbackGlobalOffset > anchor.fallbackGlobalOffset) {
            resolvedAnchorIndex = pages.pageIndexContaining(anchor)
        }
        val anchorIndex = resolvedAnchorIndex
        if (anchorIndex != null && pages.size >= anchorIndex + 4 &&
            (lastPublished == 0 || pages.size - lastPublished >= 8)) {
            emit(PaginationSnapshot(key, pages.toList(), anchorIndex, complete = false))
            lastPublished = pages.size
            yield()
        }
    }
    emit(PaginationSnapshot(key, pages.toList(), pages.pageIndexContaining(anchor), complete = true))
}

private data class SourceCursor(val block: Int, val line: Int)

private data class PageLine(
    val block: FlowBlock,
    val cursor: SourceCursor,
    val next: SourceCursor,
    val measurement: MeasurementMetrics,
    val y: Float,
    val scale: Float,
) {
    val metric: MeasuredLine get() = measurement.lines[cursor.line]
    val bottom: Float get() = y + metric.height * scale
}

private fun List<PageLine>.fragments(key: LayoutKey): List<PageBlockLayout> = buildList {
    var index = 0
    while (index < this@fragments.size) {
        val first = this@fragments[index]
        var end = index + 1
        while (end < this@fragments.size && this@fragments[end].cursor.block == first.cursor.block &&
            this@fragments[end].scale == first.scale) end++
        val last = this@fragments[end - 1]
        val width = first.measurement.width * first.scale
        add(PageBlockLayout(first.block.source.id, first.metric.start, last.metric.endExclusive,
            key.marginX + (key.contentWidth - width) / 2f, key.contentTop + first.y, width,
            last.bottom - first.y, first.cursor.line, last.cursor.line + 1, first.scale, first.measurement))
        index = end
    }
}

private data class PreviewMeasurement(
    val content: List<InlineNode>,
    val metrics: MeasurementMetrics,
    val clippedSource: Boolean,
)

private data class PlannedNote(
    val reference: NoteReference,
    val preview: PreviewMeasurement,
    val lineCount: Int,
    val hiddenCount: Int = 0,
) {
    val height: Float get() = preview.metrics.lines[lineCount - 1].bottom - preview.metrics.lines.first().top
    val truncated: Boolean get() = hiddenCount == 0 && (preview.clippedSource || lineCount < preview.metrics.lines.size)
}

private data class FootnotePlan(
    val references: List<NoteReference>,
    val layouts: List<PageFootnoteLayout>,
    val reservedHeight: Float,
    val overflows: Boolean,
)

private class FootnotePlanner(
    private val document: BookDocument,
    private val key: LayoutKey,
    private val measurer: BlockMeasurer,
) {
    private val measurements = mutableMapOf<NoteId, PreviewMeasurement>()
    private val previewKey = key.footnoteLayoutKey()
    private val padding = (12f * key.density).coerceAtMost(key.contentHeight * .06f)
    private val gap = (4f * key.density).coerceAtMost(key.contentHeight * .02f)

    private suspend fun measure(reference: NoteReference): PreviewMeasurement {
        currentCoroutineContext().ensureActive()
        return measurements.getOrPut(reference.noteId) {
            val original = document.noteIndex.byId.getValue(reference.noteId).previewContent
                .ifEmpty { listOf(InlineNode.Text(if (key.localeTag.startsWith("ru")) "Иллюстрация" else "Illustration")) }
            val text = original.plainText()
            var end = minOf(text.length, MAX_PREVIEW_CHARS)
            if (end in 1 until text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            val content = if (end < text.length) original.sliceInlineContent(0, end) else original
            val measured = measurer.measure(footnoteFlowBlock(reference.noteId, content), previewKey)
            require(measured.lines.isNotEmpty() && measured.width.isFinite() && measured.width > 0)
            PreviewMeasurement(content, MeasurementMetrics(measured.lines.toList(), measured.width), end < text.length)
        }
    }

    suspend fun plan(references: List<NoteReference>, allowOverflow: Boolean): FootnotePlan {
        if (references.isEmpty()) return FootnotePlan(references, emptyList(), 0f, false)
        var rows = references.map { reference ->
            val preview = measure(reference)
            PlannedNote(reference, preview, minOf(4, preview.metrics.lines.size))
        }
        fun height(items: List<PlannedNote>) = padding + items.sumOf { it.height.toDouble() }.toFloat() + gap * (items.size - 1)
        val allShort = rows.all { !it.truncated }
        val budget = key.contentHeight * if (rows.size > 1 && allShort) .40f else .30f
        if (height(rows) > budget && !allShort) rows = rows.map {
            if (it.truncated) it.copy(lineCount = minOf(it.lineCount, 3)) else it
        }
        if (height(rows) > budget && !allowOverflow) return FootnotePlan(references, emptyList(), budget, true)
        if (height(rows) > budget && rows.size > 1) {
            // Keep the earliest previews which fit alongside a single link to the first hidden note.
            val kept = mutableListOf<PlannedNote>()
            for ((index, row) in rows.withIndex()) {
                val overflow = overflowRow(references[index], rows.size - index)
                if (index == rows.lastIndex || height(kept + row + overflowRow(references[index + 1], rows.size - index - 1)) > budget) {
                    kept += overflow
                    break
                }
                kept += row
            }
            rows = kept
        }
        val fullHeight = height(rows)
        val scale = minOf(1f, budget / fullHeight)
        val reserved = fullHeight * scale
        var y = key.contentTop + key.contentHeight - reserved + padding * scale
        val layouts = rows.map { row ->
            val h = row.height * scale
            val layout = PageFootnoteLayout(row.reference.noteId, row.reference.ordinal, row.reference.source,
                key.marginX + key.footnoteTextIndent, y, row.preview.metrics.width * scale, h,
                row.preview.content, 0, row.preview.metrics.lines[row.lineCount - 1].endExclusive,
                row.truncated, row.preview.metrics, row.hiddenCount, scale)
            y += h + gap * scale
            layout
        }
        return FootnotePlan(references, layouts, reserved, false)
    }

    private fun overflowRow(reference: NoteReference, count: Int): PlannedNote {
        val label = if (key.localeTag.startsWith("ru")) "Ещё $count примечаний" else "$count more notes"
        val content = listOf(InlineNode.Text(label))
        val measured = measurer.measure(footnoteFlowBlock(reference.noteId, content), previewKey)
        return PlannedNote(reference, PreviewMeasurement(content,
            MeasurementMetrics(measured.lines.toList(), measured.width), false), 1, count)
    }
}
