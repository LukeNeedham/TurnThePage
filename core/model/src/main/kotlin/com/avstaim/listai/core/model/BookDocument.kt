package com.avstaim.listai.core.model

data class BookDocument(
    val id: BookId,
    val metadata: BookMetadata,
    val blocks: List<BookBlock>,
) {
    init {
        require(blocks.map(BookBlock::id).distinct().size == blocks.size) {
            "Book block identifiers must be unique"
        }
    }
}

data class BookMetadata(
    val title: String,
    val authors: List<String> = emptyList(),
    val language: String? = null,
    val genres: List<String> = emptyList(),
    val annotation: String? = null,
    val sequence: String? = null,
    val coverImageId: ImageId? = null,
    val documentId: String? = null,
)

sealed interface BookBlock {
    val id: BlockId

    data class Paragraph(
        override val id: BlockId,
        val content: List<InlineNode>,
    ) : BookBlock

    data class Heading(
        override val id: BlockId,
        val level: Int,
        val content: List<InlineNode>,
    ) : BookBlock {
        init {
            require(level in 1..6) { "Heading level must be in 1..6" }
        }
    }

    data class Image(
        override val id: BlockId,
        val imageId: ImageId,
        val alternativeText: String? = null,
    ) : BookBlock

    data class Spacer(
        override val id: BlockId,
    ) : BookBlock
}

sealed interface InlineNode {
    data class Text(val value: String) : InlineNode

    data class Emphasis(val children: List<InlineNode>) : InlineNode

    data class Strong(val children: List<InlineNode>) : InlineNode

    data class Strikethrough(val children: List<InlineNode>) : InlineNode

    data class Link(
        val target: String,
        val children: List<InlineNode>,
    ) : InlineNode

    data class Image(
        val imageId: ImageId,
        val alternativeText: String? = null,
    ) : InlineNode
}

fun List<InlineNode>.plainText(): String = buildString {
    fun appendNodes(nodes: List<InlineNode>) {
        nodes.forEach { node ->
            when (node) {
                is InlineNode.Text -> append(node.value)
                is InlineNode.Emphasis -> appendNodes(node.children)
                is InlineNode.Strong -> appendNodes(node.children)
                is InlineNode.Strikethrough -> appendNodes(node.children)
                is InlineNode.Link -> appendNodes(node.children)
                is InlineNode.Image -> node.alternativeText?.let(::append)
            }
        }
    }

    appendNodes(this@plainText)
}

fun BookBlock.plainText(): String = when (this) {
    is BookBlock.Heading -> content.plainText()
    is BookBlock.Paragraph -> content.plainText()
    is BookBlock.Image -> alternativeText.orEmpty()
    is BookBlock.Spacer -> ""
}

