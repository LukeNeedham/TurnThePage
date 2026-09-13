package com.avstaim.listai.feature.reader.bookview

import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.PaginationSnapshot
import com.avstaim.listai.reader.pagination.readingFlow
import com.avstaim.listai.reader.pagination.resolveLocation

/** Supplemental paper: the parsed book and its SHA/source offsets never change. */
internal class PhysicalContents(source: BookDocument, title: String, beginText: String) {
    val targets: Map<BlockId, BookLocation>
    val document: BookDocument

    init {
        val links = linkedMapOf<BlockId, BookLocation>()
        val blocks = buildList<BookBlock> {
            add(BookBlock.Heading(id(0), 1, listOf(InlineNode.Text(title))))
            fun append(entries: List<BookTocEntry>, depth: Int) {
                entries.forEach { entry ->
                    val id = id(size)
                    links[id] = entry.location
                    val text = listOf(InlineNode.Text("\u2003".repeat(depth.coerceAtMost(5)) + entry.title))
                    add(BookBlock.Paragraph(id, if (depth == 0) listOf(InlineNode.Strong(text)) else text,
                        ParagraphRole.VERSE))
                    append(entry.children, depth + 1)
                }
            }
            append(source.tableOfContents, 0)
            if (links.isEmpty()) source.readingBlocks().firstOrNull()?.let { first ->
                val id = id(1)
                val target = BookLocation(first.id, 0, 0)
                links[id] = target
                add(BookBlock.Paragraph(id, listOf(InlineNode.Text(beginText)), ParagraphRole.VERSE))
            }
        }
        targets = links
        document = BookDocument(BookId("${source.id.value}:contents"), BookMetadata(title), blocks)
    }

    fun location(entry: Int, offset: Int) = document.readingFlow().resolveLocation(
        BookLocation(id(entry.coerceIn(0, document.blocks.lastIndex)), offset, 0))

    fun entryIndex(location: BookLocation): Int = document.blocks.indexOfFirst { it.id == location.blockId }.coerceAtLeast(0)

    companion object { private fun id(index: Int) = BlockId("listai:contents:$index") }
}

internal fun PaginationSnapshot.contentsLinkAt(x: Float, y: Float, page: Int, contents: PhysicalContents): BookLocation? =
    pages.getOrNull(page)?.blocks?.firstOrNull { fragment ->
        x >= fragment.x && x <= fragment.x + fragment.width && y >= fragment.y && y <= fragment.y + fragment.height
    }?.let { contents.targets[it.blockId] }
