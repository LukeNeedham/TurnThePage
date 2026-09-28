package com.avstaim.listai.feature.reader.bookview

import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.BookMetadata
import com.avstaim.listai.core.model.InlineNode
import com.avstaim.listai.core.model.plainText
import com.avstaim.listai.core.model.readingBlocks
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.PaginationSnapshot

/** White-paper frontmatter. Description and title retain independent durable anchors. */
internal class PhysicalAnnotation private constructor(val document: BookDocument,
    val titleBlockIds: Set<BlockId>, val hasDescription: Boolean) {
    companion object {
        fun create(source: BookDocument, descriptionLabel: String = "Description"): PhysicalAnnotation? {
            if (source.blocks.any { it is BookBlock.FixedPage }) return null
            val rich = source.metadata.annotationBlocks
            val blocks = if (rich.any { it.plainText().isNotBlank() }) rich else {
                val text = source.metadata.annotation?.takeIf(String::isNotBlank)
                text?.lines()?.mapIndexed { index, line ->
                    val id = BlockId("listai:annotation:$index")
                    if (line.isBlank()) BookBlock.Spacer(id)
                    else BookBlock.Paragraph(id, listOf(InlineNode.Text(line)))
                }.orEmpty()
            }
            val description = if (blocks.isEmpty()) emptyList() else listOf(
                BookBlock.Heading(BlockId("listai:annotation:heading"), 1, listOf(InlineNode.Text(descriptionLabel)))) + blocks
            val nativeIds = source.titlePageBlockIds.toSet()
            val nativeTitle = source.readingBlocks().filter { it.id in nativeIds }
            val title = nativeTitle.ifEmpty {
                buildList {
                    add(BookBlock.Heading(BlockId("listai:title:name"), 1, listOf(InlineNode.Text(source.metadata.title))))
                    if (source.metadata.authors.isNotEmpty()) add(BookBlock.Paragraph(BlockId("listai:title:authors"),
                        listOf(InlineNode.Text(source.metadata.authors.joinToString("\n"))), com.avstaim.listai.core.model.ParagraphRole.TEXT_AUTHOR))
                }
            }.let { content -> if (content.size == 1) content + BookBlock.Spacer(BlockId("listai:title:ornament")) else content }
            return PhysicalAnnotation(BookDocument(BookId("${source.id.value}:annotation"),
                BookMetadata(source.metadata.title, language = source.metadata.language), description + title,
                images = source.images, titlePageBlockIds = title.map { it.id },
                pageBreakBefore = setOf(title.first().id), isFrontmatter = true), title.mapTo(hashSetOf()) { it.id }, description.isNotEmpty())
        }
    }
}

internal fun LayoutKey.forAnnotation() = copy(
    horizontalMarginsDp = maxOf(horizontalMarginsDp, 32f),
    verticalMarginsDp = maxOf(verticalMarginsDp, 40f),
)

/** All frontmatter uses white paper, including a Fold spread in the dark reader theme. */
internal fun LayoutKey.forWhitePaperAnnotation() = forAnnotation().copy(darkTheme = false)

internal val PaginationSnapshot?.annotationPaperCount: Int
    get() = this?.pages?.size ?: 0
