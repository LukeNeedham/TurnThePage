package com.avstaim.listai.feature.reader.bookview

import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.PaginationSnapshot
import com.avstaim.listai.reader.pagination.PageLayout
import com.avstaim.listai.reader.pagination.readingFlow
import com.avstaim.listai.reader.pagination.resolveLocation
import com.avstaim.listai.reader.pagination.pageIndexContaining

/** Supplemental paper: the parsed book and its SHA/source offsets never change. */
internal class PhysicalContents(source: BookDocument, title: String, beginText: String,
    val notes: PhysicalNotes? = null) {
    val title = title
    val entries: List<PhysicalContentsEntry>
    val targets: Map<BlockId, BookLocation>
    val document: BookDocument
    private val sourceBlockLengths = source.readingBlocks().associate { it.id to it.plainText().length }

    init {
        val links = linkedMapOf<BlockId, BookLocation>()
        val rows = mutableListOf<PhysicalContentsEntry>()
        val blocks = buildList<BookBlock> {
            add(BookBlock.Heading(id(0), 1, listOf(InlineNode.Text(title))))
            fun append(entries: List<BookTocEntry>, depth: Int) {
                entries.forEach { entry ->
                    val id = id(size)
                    links[id] = entry.location
                    rows += PhysicalContentsEntry(size, id, entry.title, depth, entry.location)
                    // The document supplies stable supplemental anchors only. Dedicated contents
                    // layout owns indentation, row geometry and the page-number column.
                    add(BookBlock.Paragraph(id, listOf(InlineNode.Text(entry.title))))
                    append(entry.children, depth + 1)
                }
            }
            append(source.tableOfContents, 0)
            if (links.isEmpty()) source.readingBlocks().firstOrNull()?.let { first ->
                val id = id(1)
                val target = BookLocation(first.id, 0, 0)
                links[id] = target
                rows += PhysicalContentsEntry(1, id, beginText, 0, target)
                add(BookBlock.Paragraph(id, listOf(InlineNode.Text(beginText))))
            }
            notes?.let {
                val id = id(size)
                val target = it.targets.values.first()
                links[id] = target
                rows += PhysicalContentsEntry(size, id, it.title, 0, target)
                add(BookBlock.Paragraph(id, listOf(InlineNode.Text(it.title))))
            }
        }
        targets = links
        entries = rows
        document = BookDocument(BookId("${source.id.value}:contents"), BookMetadata(title), blocks)
    }

    fun location(entry: Int, offset: Int) = document.readingFlow().resolveLocation(
        BookLocation(id(entry.coerceIn(0, document.blocks.lastIndex)), offset, 0))

    fun entryIndex(location: BookLocation): Int = document.blocks.indexOfFirst { it.id == location.blockId }.coerceAtLeast(0)

    /** Exact source fragments only: the fallback lookup would mislabel an unfinished prefix. */
    fun sourcePageNumbers(snapshot: PaginationSnapshot, notesSnapshot: PaginationSnapshot? = null): Map<BlockId, Int?> {
        val fragments = snapshot.pages.flatMap { page -> page.blocks.map { it.blockId to (page to it) } }
            .groupBy({ it.first }, { it.second })
        return entries.associate { entry ->
            val target = entry.target
            if (notes?.noteAt(target) != null) return@associate entry.blockId to
                notesSnapshot?.pages?.pageIndexContaining(target)?.plus(1)
            val candidates = fragments[target.blockId].orEmpty()
            val exact = candidates.firstOrNull { (_, block) ->
                target.charOffset >= block.startCharOffset &&
                    (target.charOffset < block.endCharOffsetExclusive ||
                        block.startCharOffset == block.endCharOffsetExclusive && target.charOffset == block.startCharOffset)
            }
            // A real terminal block offset belongs to its final fragment. An intermediate page
            // boundary must wait for the next fragment, even if the fallback offset is stale.
            val terminal = candidates.lastOrNull { (_, block) -> block.endCharOffsetExclusive == target.charOffset }
                ?.takeIf { target.charOffset == sourceBlockLengths[target.blockId] }
            entry.blockId to (exact ?: terminal)?.first?.displayNumber
        }
    }

    companion object { private fun id(index: Int) = BlockId("listai:contents:$index") }
}

internal data class PhysicalContentsEntry(val index: Int, val blockId: BlockId, val title: String,
    val depth: Int, val target: BookLocation)

/** A cache identity captures the labels rendered into one immutable supplemental leaf. */
internal data class ContentsPageNumbers(val values: Map<BlockId, Int?>) {
    companion object {
        fun forPage(page: PageLayout, numbers: Map<BlockId, Int?>) = ContentsPageNumbers(
            page.blocks.filter { it.blockId in numbers }.associate { it.blockId to numbers[it.blockId] })
    }
}

internal fun PaginationSnapshot.contentsLinkAt(x: Float, y: Float, page: Int, contents: PhysicalContents): BookLocation? =
    pages.getOrNull(page)?.blocks?.firstOrNull { fragment ->
        x >= fragment.x && x <= fragment.x + fragment.width && y >= fragment.y && y <= fragment.y + fragment.height
    }?.let { contents.targets[it.blockId] }
