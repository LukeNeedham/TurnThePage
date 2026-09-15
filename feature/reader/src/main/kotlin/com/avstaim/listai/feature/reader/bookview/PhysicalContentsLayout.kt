package com.avstaim.listai.feature.reader.bookview

import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.PageId
import com.avstaim.listai.reader.pagination.*
import kotlin.math.min

internal enum class ContentsTextStyle { TITLE, CHAPTER, NESTED }

internal fun interface ContentsTextMeasurer {
    fun measure(text: String, width: Int, style: ContentsTextStyle, key: LayoutKey): BlockMeasurement
}

/** Shared by measurement and rasterization; the number gutter never depends on index progress. */
internal class ContentsGeometry(val key: LayoutKey) {
    val width = key.contentWidth.toFloat()
    val rowPadding = 8f * key.density
    val minimumRowHeight = 48f * key.density
    val titleGap = 26f * key.density
    val chapterGap = 8f * key.density
    val numberGutter = min(width * .30f, (key.fontSizeSp * key.textScale * 3.8f + 12f) * key.density)
    fun indent(depth: Int) = min(width * .22f, depth.coerceAtMost(5) * 18f * key.density)
    fun textWidth(depth: Int) = (width - indent(depth) - numberGutter).toInt().coerceAtLeast(1)
}

/** Normal book contents: complete rows stay together; only over-height titles split into leaves.
 * Metrics and semantic UTF-16 anchors are independent of changing source page-number labels. */
internal fun paginatePhysicalContents(contents: PhysicalContents, key: LayoutKey,
    measurer: ContentsTextMeasurer): PaginationSnapshot {
    val geometry = ContentsGeometry(key)
    val flow = contents.document.readingFlow().associateBy { it.source.id }
    val titleBlock = contents.document.blocks.first()
    val measuredTitle = measurer.measure(contents.title, key.contentWidth, ContentsTextStyle.TITLE, key)
    val titleMetrics = MeasurementMetrics(measuredTitle.lines, measuredTitle.width)
    // Reserve space for a repeated, printed heading on subsequent contents leaves too.
    val titleHeight = min(measuredTitle.lines.last().bottom - measuredTitle.lines.first().top,
        key.contentHeight * .25f)
    val headingSpace = min(titleHeight + geometry.titleGap, key.contentHeight * .38f)
    val available = (key.contentHeight - headingSpace).coerceAtLeast(1f)
    val pages = mutableListOf<PageLayout>()
    val fragments = mutableListOf<PageBlockLayout>()
    var y = key.contentTop + headingSpace

    fun location(id: com.avstaim.listai.core.model.BlockId, offset: Int) =
        BookLocation(id, offset, requireNotNull(flow[id]).globalOffset + offset)

    fun finish() {
        if (fragments.isEmpty()) return
        val first = fragments.first()
        val last = fragments.last()
        pages += PageLayout(PageId(pages.size.toLong()), location(first.blockId, first.startCharOffset),
            location(last.blockId, last.endCharOffsetExclusive), fragments.toList(), pages.size + 1)
        fragments.clear()
        y = key.contentTop + headingSpace
    }

    // Index zero remains the first-leaf checkpoint. Continuation leaves anchor to their first row.
    fragments += PageBlockLayout(titleBlock.id, 0, contents.title.length, key.marginX, key.contentTop,
        geometry.width, titleHeight, 0, measuredTitle.lines.size,
        titleHeight / (measuredTitle.lines.last().bottom - measuredTitle.lines.first().top), titleMetrics)
    for (entry in contents.entries) {
        val measured = measurer.measure(entry.title, geometry.textWidth(entry.depth),
            if (entry.depth == 0) ContentsTextStyle.CHAPTER else ContentsTextStyle.NESTED, key)
        require(measured.lines.isNotEmpty() && measured.width > 0)
        val metrics = MeasurementMetrics(measured.lines, measured.width)
        val lineHeight = measured.lines.last().bottom - measured.lines.first().top
        val wholeHeight = maxOf(geometry.minimumRowHeight, lineHeight + geometry.rowPadding * 2)
        val gap = if (entry.depth == 0 && fragments.any { it.blockId != titleBlock.id }) geometry.chapterGap else 0f
        if (wholeHeight <= available && y + gap + wholeHeight > key.contentTop + key.contentHeight) finish()
        else if (y + gap + min(wholeHeight, available) <= key.contentTop + key.contentHeight) y += gap
        var firstLine = 0
        while (firstLine < measured.lines.size) {
            val first = measured.lines[firstLine]
            val remaining = key.contentTop + key.contentHeight - y
            if (remaining < min(available, maxOf(geometry.minimumRowHeight, first.height + geometry.rowPadding * 2))) finish()
            val capacity = key.contentTop + key.contentHeight - y
            // Tiny landscape/high-text-scale viewports still progress by fitting one whole line.
            val padding = min(geometry.rowPadding, capacity * .1f)
            val textCapacity = (capacity - padding * 2).coerceAtLeast(1f)
            val scale = min(1f, textCapacity / first.height)
            var endLine = firstLine + 1
            while (endLine < measured.lines.size &&
                (measured.lines[endLine].bottom - first.top) * scale <= textCapacity) endLine++
            val last = measured.lines[endLine - 1]
            val height = maxOf(min(geometry.minimumRowHeight, capacity),
                (last.bottom - first.top) * scale + padding * 2).coerceAtMost(capacity)
            fragments += PageBlockLayout(entry.blockId, first.start, last.endExclusive, key.marginX, y,
                geometry.width, height, firstLine, endLine, scale, metrics)
            y += height
            firstLine = endLine
            if (firstLine < measured.lines.size) finish()
        }
    }
    finish()
    return PaginationSnapshot(key, pages, 0, complete = true)
}
