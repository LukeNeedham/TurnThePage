package com.avstaim.listai.reader.pagination

import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookChapterKind
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.PageId
import com.avstaim.listai.core.model.plainText

/** Part furniture changes paper, never the source blocks or their UTF-16 coordinates. */
internal fun BookDocument.withPartBreaks(): BookDocument {
    val flow = readingFlow()
    val indices = flow.mapIndexed { index, block -> block.source.id to index }.toMap()
    val breaks = pageBreakBefore.toMutableSet()
    for (part in parts) {
        breaks += part.startBlockId
        val last = part.blockIds.mapNotNull(indices::get).maxOrNull() ?: continue
        flow.getOrNull(last + 1)?.let { breaks += it.source.id }
    }
    return copy(pageBreakBefore = breaks)
}

/** Shared by plain and footnote pagination, including the Android measurement adapter. */
internal class PartLeafProjector(document: BookDocument, location: BookLocation?) {
    private val source = document.readingFlow()
    private val partByBlock = document.parts.flatMap { part -> part.blockIds.map { it to part } }.toMap()
    private val lengths = source.associate { it.source.id to it.source.plainText().length }
    private val positions = source.mapIndexed { index, block -> block.source.id to index }.toMap()
    private val chapterStarts = document.chapters.map { it.startBlockId }
    private val firstChapters = document.parts.mapNotNull { part ->
        val start = positions[part.startBlockId] ?: return@mapNotNull null
        val nextPart = document.parts.mapNotNull { positions[it.startBlockId] }.firstOrNull { it > start } ?: Int.MAX_VALUE
        chapterStarts.firstOrNull { (positions[it] ?: -1) in (start + 1) until nextPart }
    }.toSet()
    // In a book divided into parts, the concluding unnumbered chapter also starts recto.
    // Its text continues normally: unlike a part title it does not get an empty reverse.
    private val epilogueStarts = document.chapters.filter { it.kind == BookChapterKind.EPILOGUE }
        .mapTo(hashSetOf()) { it.startBlockId }
    private val anchor = source.takeIf { it.isNotEmpty() }?.resolveLocation(location)

    fun project(snapshot: PaginationSnapshot): PaginationSnapshot = with(snapshot) {
        if (pages.isEmpty()) return snapshot
        val leaves = mutableListOf<PageLayout>()
        fun append(page: PageLayout) {
            leaves += page.copy(id = PageId(leaves.size.toLong()), displayNumber = key.leadingPageCount + leaves.size + 1)
        }
        fun blank(anchor: BookLocation) {
            // Recto alignment and empty reverses belong to a physical two-page spread.
            // Single-page reading goes straight to the next source leaf in either direction.
            if (key.pageMode == PageMode.SPREAD) append(PageLayout(PageId(0), anchor, anchor,
                emptyList(), 0, kind = PageKind.BLANK))
        }
        for (page in pages) {
            val part = page.blocks.firstOrNull()?.blockId?.let(partByBlock::get)
            if (part == null) {
                if (page.blocks.firstOrNull()?.let {
                        (it.blockId in firstChapters || it.blockId in epilogueStarts) && it.startCharOffset == 0
                    } == true &&
                    (key.leadingPageCount + leaves.size) % 2 != 0) blank(page.start)
                append(page)
                continue
            }
            val opening = page.blocks.first().let { it.blockId == part.startBlockId && it.startCharOffset == 0 }
            if (opening && (key.leadingPageCount + leaves.size) % 2 != 0) blank(page.start)
            val last = page.blocks.last()
            val finished = last.blockId == part.blockIds.last() && last.endCharOffsetExclusive == lengths[last.blockId]
            // A short title and its epigraph share one airy composition. Oversized source content
            // retains every line on following leaves instead of being shrunk or silently discarded.
            val inkBottom = page.blocks.maxOf { it.y + it.height }
            val availableBottom = minOf(key.contentTop + key.contentHeight,
                page.footnotes.minOfOrNull { it.y - key.paragraphGap } ?: Float.MAX_VALUE)
            val free = (availableBottom - inkBottom).coerceAtLeast(0f)
            val offset = if (opening && finished) minOf(key.contentHeight * .19f, free * .42f) else 0f
            append(page.copy(blocks = page.blocks.map { it.copy(y = it.y + offset) },
                kind = if (opening) PageKind.PART_TITLE else PageKind.PART_CONTINUATION))
            if (finished && (key.leadingPageCount + leaves.size) % 2 != 0) blank(page.endExclusive)
        }
        copy(pages = leaves, anchorPageIndex = leaves.pageIndexContaining(requireNotNull(anchor)))
    }
}
