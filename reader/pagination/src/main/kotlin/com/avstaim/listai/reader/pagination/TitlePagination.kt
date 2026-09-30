package com.avstaim.listai.reader.pagination

import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.PageId

/** Align only the opening leaf; long title continuations retain their source order. */
internal fun PaginationSnapshot.withRectoTitle(titleStart: BlockId): PaginationSnapshot {
    if (key.pageMode != PageMode.SPREAD) return this
    val titleIndex = pages.indexOfFirst { page -> page.blocks.any {
        it.blockId == titleStart && it.startCharOffset == 0
    } }
    if (titleIndex < 0 || (key.leadingPageCount + titleIndex) % 2 == 0) return this
    val title = pages[titleIndex]
    val leaves = pages.take(titleIndex) +
        PageLayout(PageId(titleIndex.toLong()), title.start, title.start, emptyList(),
            title.displayNumber, kind = PageKind.BLANK) +
        pages.drop(titleIndex).map { it.copy(id = PageId(it.id.value + 1), displayNumber = it.displayNumber + 1) }
    return copy(pages = leaves, anchorPageIndex = anchorPageIndex + if (anchorPageIndex >= titleIndex) 1 else 0)
}
