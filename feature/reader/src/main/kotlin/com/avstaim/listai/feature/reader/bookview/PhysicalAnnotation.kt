package com.avstaim.listai.feature.reader.bookview

import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.BookMetadata
import com.avstaim.listai.core.model.InlineNode
import com.avstaim.listai.core.model.plainText
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.PaginationSnapshot

/** The first page is boundary matter: cover ink in a spread, a white leaf in single mode. */
internal class PhysicalAnnotation private constructor(val document: BookDocument) {
    companion object {
        fun create(source: BookDocument): PhysicalAnnotation? {
            val rich = source.metadata.annotationBlocks
            val blocks = if (rich.any { it.plainText().isNotBlank() }) rich else {
                val text = source.metadata.annotation?.takeIf(String::isNotBlank) ?: return null
                text.lines().mapIndexed { index, line ->
                    val id = BlockId("listai:annotation:$index")
                    if (line.isBlank()) BookBlock.Spacer(id)
                    else BookBlock.Paragraph(id, listOf(InlineNode.Text(line)))
                }
            }
            return PhysicalAnnotation(BookDocument(BookId("${source.id.value}:annotation"),
                BookMetadata(source.metadata.title, language = source.metadata.language), blocks,
                images = source.images))
        }
    }
}

internal fun LayoutKey.forAnnotation() = copy(
    horizontalMarginsDp = maxOf(horizontalMarginsDp, 32f),
    verticalMarginsDp = maxOf(verticalMarginsDp, 40f),
)

/** A narrow book prints its description on a real white leaf, even in the dark reader theme. */
internal fun LayoutKey.forWhitePaperAnnotation() = forAnnotation().copy(darkTheme = false)

/** The description printed on dark cover stock uses light ink; overflow stays on themed paper. */
internal fun LayoutKey.forInsideCoverAnnotation() = forAnnotation().copy(darkTheme = true)

internal val PaginationSnapshot?.annotationPaperCount: Int
    get() = ((this?.pages?.size ?: 0) - 1).coerceAtLeast(0)
