package com.avstaim.listai.reader.pagination

import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.InlineNode
import com.avstaim.listai.core.model.plainText

fun BookDocument.readingFlow(): List<FlowBlock> = buildList {
    var offset = 0L
    fun visit(block: BookBlock, depth: Int) {
        if (block is BookBlock.Quote) {
            block.blocks.forEach { visit(it, depth + 1) }
        } else {
            add(FlowBlock(block, offset, depth))
            offset += block.plainText().length + 1L
        }
    }
    blocks.forEach { visit(it, 0) }
}

/** Preserve the model's UTF-16 space, including poem newlines and inline-image alt text. */
fun BookBlock.textContent(): List<InlineNode> = when (this) {
    is BookBlock.Paragraph -> content
    is BookBlock.Heading -> content
    is BookBlock.Poem -> buildList {
        val lines = title.map { listOf(InlineNode.Strong(it)) } + stanzas.flatMap { it.lines } +
            textAuthors.map { listOf(InlineNode.Emphasis(it)) }
        lines.forEachIndexed { index, line ->
            if (index > 0) add(InlineNode.Text("\n"))
            addAll(line)
        }
    }
    else -> listOf(InlineNode.Text(plainText()))
}

fun List<FlowBlock>.resolveLocation(location: BookLocation?): BookLocation {
    require(isNotEmpty()) { "Cannot paginate an empty reading flow" }
    val exact = location?.let { target -> firstOrNull { it.source.id == target.blockId } }
    val block = exact ?: lastOrNull { it.globalOffset <= (location?.fallbackGlobalOffset ?: 0L) } ?: first()
    val offset = if (exact != null) requireNotNull(location).charOffset else
        ((location?.fallbackGlobalOffset ?: 0L) - block.globalOffset).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    val clamped = offset.coerceIn(0, block.source.plainText().length)
    return BookLocation(block.source.id, clamped, block.globalOffset + clamped)
}

/** A line boundary belongs to the next page, except at the terminal end of a block. */
fun List<PageLayout>.pageIndexContaining(location: BookLocation): Int {
    require(isNotEmpty())
    val exact = indexOfFirst { page -> page.blocks.any { block ->
        block.blockId == location.blockId && location.charOffset >= block.startCharOffset &&
            (location.charOffset < block.endCharOffsetExclusive ||
                block.startCharOffset == block.endCharOffsetExclusive && location.charOffset == block.startCharOffset)
    } }
    if (exact >= 0) return exact
    val terminal = indexOfLast { page -> page.blocks.any {
        it.blockId == location.blockId && it.endCharOffsetExclusive == location.charOffset
    } }
    if (terminal >= 0) return terminal
    return indexOfLast { it.start.fallbackGlobalOffset <= location.fallbackGlobalOffset }.coerceAtLeast(0)
}
