package com.avstaim.listai.feature.reader

import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*
import kotlin.math.roundToLong

data class ReaderNavigationPreview(
    val location: BookLocation,
    val fraction: Float,
    val sectionTitle: String?,
    val pageNumber: Int?,
    val totalPages: Int?,
)

/** Source indexes are shared across the successive numeric pagination snapshots. */
internal class ReaderNavigationSource(document: BookDocument) {
    val flow = document.readingFlow()
    val length = flow.lastOrNull()?.let { it.globalOffset + it.source.plainText().length + 1 }?.coerceAtLeast(1) ?: 1
    val chapters = buildList {
        fun visit(entries: List<BookTocEntry>) { entries.forEach { add(it); visit(it.children) } }
        visit(document.tableOfContents)
    }.sortedBy { it.location.fallbackGlobalOffset }
}

/** Previewing never changes a reader checkpoint; only the host's confirm callback does. */
class ReaderNavigationState internal constructor(
    private val source: ReaderNavigationSource,
    private val snapshot: PaginationSnapshot,
    location: BookLocation?,
) {
    internal constructor(document: BookDocument, snapshot: PaginationSnapshot, location: BookLocation?) :
        this(ReaderNavigationSource(document), snapshot, location)

    private val flow get() = source.flow
    private val length get() = source.length
    private val chapters get() = source.chapters
    val firstAvailablePage: Int get() = snapshot.pages.firstOrNull()?.displayNumber ?: 1
    val availablePages: Int get() = snapshot.pages.lastOrNull()?.displayNumber ?: 0
    val complete: Boolean get() = snapshot.complete
    val current = preview(flow.resolveLocation(location))

    fun previewFraction(fraction: Float): ReaderNavigationPreview {
        val offset = (fraction.coerceIn(0f, 1f) * length.toDouble()).roundToLong()
        val block = flow.lastOrNull { it.globalOffset <= offset } ?: flow.first()
        return preview(flow.resolveLocation(BookLocation(block.source.id,
            (offset - block.globalOffset).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), offset)))
    }

    fun previewPage(number: Int): ReaderNavigationPreview? = snapshot.pages.firstOrNull { it.displayNumber == number }?.let { preview(it.start) }

    private fun preview(location: BookLocation): ReaderNavigationPreview {
        val known = snapshot.complete || location.fallbackGlobalOffset < snapshot.pages.last().endExclusive.fallbackGlobalOffset
        val atEnd = location == flow.last().let { BookLocation(it.source.id, it.source.plainText().length,
            it.globalOffset + it.source.plainText().length) }
        return ReaderNavigationPreview(location, if (atEnd) 1f else
            (location.fallbackGlobalOffset.toDouble() / length).toFloat().coerceIn(0f, 1f),
            chapters.lastOrNull { it.location.fallbackGlobalOffset <= location.fallbackGlobalOffset }?.title,
            if (known) snapshot.pages[snapshot.pages.pageIndexContaining(location)].displayNumber else null,
            snapshot.pages.lastOrNull()?.displayNumber?.takeIf { snapshot.complete })
    }
}
