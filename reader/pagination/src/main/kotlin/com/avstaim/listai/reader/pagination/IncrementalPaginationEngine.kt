package com.avstaim.listai.reader.pagination

import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.PageId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield
import kotlin.math.min

/** Deterministic index; publishes as soon as the requested neighbourhood is available. */
class IncrementalPaginationEngine(private val measurer: BlockMeasurer) : PaginationEngine {
    override suspend fun pageContaining(document: BookDocument, location: BookLocation, layoutKey: LayoutKey): PageLayout {
        val snapshot = paginate(document, location, layoutKey).first()
        return snapshot.pages[snapshot.anchorPageIndex]
    }

    override fun paginate(document: BookDocument, location: BookLocation?, layoutKey: LayoutKey): Flow<PaginationSnapshot> =
        if (document.noteIndex.referencesByBlock.isNotEmpty()) paginateWithFootnotes(document, location, layoutKey, measurer)
        else flow {
        val flow = document.readingFlow()
        if (flow.isEmpty() && document.titlePageBlockIds.isNotEmpty()) {
            emit(PaginationSnapshot(layoutKey, emptyList(), 0, complete = true))
            return@flow
        }
        val chapterStarts = document.chapters.associateBy { it.startBlockId }
        val anchor = flow.resolveLocation(location)
        val pages = mutableListOf<PageLayout>()
        val fragments = mutableListOf<PageBlockLayout>()
        var y = 0f
        var chapter: BlockId? = null
        var pageChapter: BlockId? = null
        var pageStart: BookLocation? = null
        var pageEnd: BookLocation? = null
        var lastPublished = 0
        var resolvedAnchorIndex: Int? = null
        var nextMeasurement: BlockMeasurement? = null

        suspend fun finishPage() {
            if (fragments.isEmpty()) return
            currentCoroutineContext().ensureActive()
            pages += PageLayout(PageId(pages.size.toLong()), requireNotNull(pageStart), requireNotNull(pageEnd),
                fragments.toList(), pages.size + 1, pageChapter)
            fragments.clear()
            y = 0f
            pageStart = null
            val anchorReached = pages.last().endExclusive.fallbackGlobalOffset > anchor.fallbackGlobalOffset
            if (resolvedAnchorIndex == null && anchorReached) resolvedAnchorIndex = pages.pageIndexContaining(anchor)
            val anchorIndex = resolvedAnchorIndex
            if (anchorIndex != null && pages.size >= anchorIndex + 4 &&
                (lastPublished == 0 || pages.size - lastPublished >= 8)) {
                emit(PaginationSnapshot(layoutKey, pages.toList(), anchorIndex, complete = false))
                lastPublished = pages.size
                yield()
            }
        }

        for ((blockIndex, block) in flow.withIndex()) {
            currentCoroutineContext().ensureActive()
            // Fixed-layout source pages occupy separate leaves even when their aspect ratio
            // leaves unused paper. Neither font changes nor a short landscape page may merge them.
            if (block.source is BookBlock.FixedPage) finishPage()
            // Source metadata marks chapter boundaries; an ordinary nested heading does not.
            if (block.source.id in chapterStarts || block.source.id in document.pageBreakBefore) finishPage()
            val measured = nextMeasurement ?: measurer.measure(block, layoutKey)
            nextMeasurement = null
            require(measured.lines.isNotEmpty() && measured.width.isFinite() && measured.width > 0)
            val metrics = MeasurementMetrics(measured.lines, measured.width)
            if (block.source is BookBlock.Heading) {
                val following = flow.getOrNull(blockIndex + 1)?.let { measurer.measure(it, layoutKey) }
                nextMeasurement = following
                val headingHeight = measured.lines.last().bottom - measured.lines.first().top
                val required = headingHeight + layoutKey.paragraphGap + (following?.lines?.firstOrNull()?.height ?: 0f)
                if (fragments.isNotEmpty() && required <= layoutKey.contentHeight && y + required > layoutKey.contentHeight) finishPage()
                if (block.source.id in chapterStarts || document.chapters.isEmpty()) chapter = block.source.id
            }
            var lineIndex = 0
            while (lineIndex < measured.lines.size) {
                currentCoroutineContext().ensureActive()
                val first = measured.lines[lineIndex]
                if (fragments.isNotEmpty() && y + first.height > layoutKey.contentHeight) finishPage()
                val scale = min(1f, layoutKey.contentHeight / first.height)
                var endLine = lineIndex + 1
                while (endLine < measured.lines.size &&
                    y + (measured.lines[endLine].bottom - first.top) * scale <= layoutKey.contentHeight) endLine++
                val last = measured.lines[endLine - 1]
                val height = (last.bottom - first.top) * scale
                val start = BookLocation(block.source.id, first.start, block.globalOffset + first.start)
                if (pageStart == null) {
                    pageStart = start
                    pageChapter = chapter
                }
                pageEnd = BookLocation(block.source.id, last.endExclusive, block.globalOffset + last.endExclusive)
                fragments += PageBlockLayout(block.source.id, first.start, last.endExclusive,
                    layoutKey.marginX + (layoutKey.contentWidth - measured.width * scale) / 2f,
                    layoutKey.contentTop + y, measured.width * scale, height,
                    lineIndex, endLine, scale, metrics)
                y += height
                lineIndex = endLine
                if (lineIndex < measured.lines.size) finishPage()
            }
            if (block.source is BookBlock.FixedPage) finishPage() else y += layoutKey.paragraphGap
        }
        finishPage()
        emit(PaginationSnapshot(layoutKey, pages.toList(), pages.pageIndexContaining(anchor), complete = true))
    }
}
