package com.avstaim.listai.core.model

data class BookDocument(
    val id: BookId,
    val metadata: BookMetadata,
    val blocks: List<BookBlock>,
    val tableOfContents: List<BookTocEntry> = emptyList(),
    val notes: List<BookNote> = emptyList(),
    val images: List<BookImage> = emptyList(),
    val locationsBySourceId: Map<String, BookLocation> = emptyMap(),
) {
    init {
        val mainBlocks = blocks.flattenedBlocks()
        val allBlocks = mainBlocks + notes.flatMap { it.blocks.flattenedBlocks() }

        require(allBlocks.map(BookBlock::id).distinct().size == allBlocks.size) {
            "Book block identifiers must be unique"
        }
        require(notes.map(BookNote::id).distinct().size == notes.size) {
            "Book note identifiers must be unique"
        }
        require(images.map(BookImage::id).distinct().size == images.size) {
            "Book image identifiers must be unique"
        }
        require(
            locationsBySourceId.keys.all { sourceId ->
                sourceId.isNotBlank() && sourceId == sourceId.trim() && '#' !in sourceId
            },
        ) {
            "Source location identifiers must be normalized and must not contain '#'"
        }
        val allBlockIds = allBlocks.mapTo(mutableSetOf(), BookBlock::id)
        require(locationsBySourceId.values.all { it.blockId in allBlockIds }) {
            "Source locations must point to document or note blocks"
        }
        val mainBlockIds = mainBlocks.mapTo(mutableSetOf(), BookBlock::id)
        fun validateToc(entries: List<BookTocEntry>) {
            entries.forEach { entry ->
                require(entry.location.blockId in mainBlockIds) {
                    "Table-of-contents locations must point to main document blocks"
                }
                validateToc(entry.children)
            }
        }
        validateToc(tableOfContents)
    }

    /** Immutable, eagerly-built lookup indexes for hot-path note and image resolution. */
    val notesById: Map<NoteId, BookNote> = notes.associateBy(BookNote::id)
    val imagesById: Map<ImageId, BookImage> = images.associateBy(BookImage::id)
    val noteIndex: BookNoteIndex = BookNoteIndex(this)
}

data class BookMetadata(
    val title: String,
    val authors: List<String> = emptyList(),
    val language: String? = null,
    val genres: List<String> = emptyList(),
    val annotation: String? = null,
    val sequence: BookSequence? = null,
    val coverImageId: ImageId? = null,
    val documentId: String? = null,
    /** Supplemental description, outside the main reading flow and its stable offsets. */
    val annotationBlocks: List<BookBlock> = emptyList(),
) {
    init {
        require(title.isNotBlank()) { "Book title must not be blank" }
        require(authors.none(String::isBlank)) { "Book authors must not be blank" }
        require(genres.none(String::isBlank)) { "Book genres must not be blank" }
        require(language == null || language.isNotBlank()) { "Book language must not be blank" }
        require(annotation == null || annotation.isNotBlank()) { "Book annotation must not be blank" }
        require(documentId == null || documentId.isNotBlank()) { "Book document id must not be blank" }
    }
}

data class BookSequence(
    val name: String,
    val number: Int? = null,
) {
    init {
        require(name.isNotBlank()) { "Book sequence name must not be blank" }
        require(number == null || number >= 0) { "Book sequence number must not be negative" }
    }
}

data class BookTocEntry(
    val title: String,
    val location: BookLocation,
    val children: List<BookTocEntry> = emptyList(),
) {
    init {
        require(title.isNotBlank()) { "Table-of-contents title must not be blank" }
    }
}

data class BookNote(
    val id: NoteId,
    val blocks: List<BookBlock>,
    val title: String? = null,
) {
    init {
        require(title == null || title.isNotBlank()) { "Book note title must not be blank" }
    }
}

data class BookImage(
    val id: ImageId,
    val mediaType: String? = null,
    val content: BookBinaryContent,
) {
    init {
        require(mediaType == null || mediaType.isNotBlank()) { "Book image media type must not be blank" }
    }
}

/**
 * A deferred binary payload. Creating a [BookDocument] must not load or decode the payload.
 * Implementations should cache both successful loads and failures, and contain decoding failures by
 * returning `null`.
 */
fun interface BookBinaryContent {
    fun load(): ByteArray?
}

enum class ParagraphRole {
    BODY,
    SUBTITLE,
    TEXT_AUTHOR,
    VERSE,
}

enum class QuoteRole {
    CITE,
    EPIGRAPH,
}

sealed interface BookBlock {
    val id: BlockId

    data class Paragraph(
        override val id: BlockId,
        val content: List<InlineNode>,
        val role: ParagraphRole = ParagraphRole.BODY,
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

    /**
     * One indivisible page from an app-owned, seekable source. This contains only lightweight
     * metadata: the Android adapter prepares pixels before a page enters the physical book.
     * The stable block id is the durable anchor; it never changes with typography or viewport.
     */
    data class FixedPage(
        override val id: BlockId,
        val sourcePath: String,
        val mediaType: String,
        val pageIndex: Int,
        val widthPoints: Int,
        val heightPoints: Int,
    ) : BookBlock {
        init {
            require(sourcePath.isNotBlank() && mediaType.isNotBlank())
            require(pageIndex >= 0 && widthPoints > 0 && heightPoints > 0)
        }
    }

    data class Quote(
        override val id: BlockId,
        val blocks: List<BookBlock>,
        val role: QuoteRole = QuoteRole.CITE,
    ) : BookBlock

    data class Poem(
        override val id: BlockId,
        val stanzas: List<BookPoemStanza>,
        val title: List<List<InlineNode>> = emptyList(),
        val textAuthors: List<List<InlineNode>> = emptyList(),
    ) : BookBlock {
        init {
            require(stanzas.isNotEmpty()) { "A poem must contain at least one stanza" }
        }
    }

    data class Spacer(
        override val id: BlockId,
    ) : BookBlock
}

data class BookPoemStanza(
    val lines: List<List<InlineNode>>,
) {
    init {
        require(lines.isNotEmpty()) { "A poem stanza must contain at least one line" }
    }
}

sealed interface LinkTarget {
    data class Note(val noteId: NoteId) : LinkTarget

    data class Internal(val location: BookLocation) : LinkTarget

    data class UnresolvedInternal(val sourceId: String) : LinkTarget {
        init {
            require(
                sourceId.isNotBlank() && sourceId == sourceId.trim() && '#' !in sourceId,
            ) {
                "Unresolved internal link id must be normalized and must not contain '#'"
            }
        }
    }

    data class External(val uri: String) : LinkTarget {
        init {
            require(uri.isNotBlank()) { "External link URI must not be blank" }
        }
    }
}

sealed interface InlineNode {
    data class Text(val value: String) : InlineNode

    data class Emphasis(val children: List<InlineNode>) : InlineNode

    data class Strong(val children: List<InlineNode>) : InlineNode

    data class Strikethrough(val children: List<InlineNode>) : InlineNode

    data class Link(
        val target: LinkTarget,
        val children: List<InlineNode>,
    ) : InlineNode

    data class Image(
        val imageId: ImageId,
        val alternativeText: String? = null,
    ) : InlineNode
}

fun InlineNode.plainText(): String = when (this) {
    is InlineNode.Text -> value
    is InlineNode.Emphasis -> children.plainText()
    is InlineNode.Strong -> children.plainText()
    is InlineNode.Strikethrough -> children.plainText()
    is InlineNode.Link -> children.plainText()
    is InlineNode.Image -> alternativeText.orEmpty()
}

fun List<InlineNode>.plainText(): String = joinToString(separator = "") { it.plainText() }

fun BookPoemStanza.plainText(): String = lines.joinToString(separator = "\n") { it.plainText() }

fun BookBlock.plainText(): String = when (this) {
    is BookBlock.Heading -> content.plainText()
    is BookBlock.Paragraph -> content.plainText()
    is BookBlock.Image -> alternativeText.orEmpty()
    is BookBlock.FixedPage -> ""
    is BookBlock.Quote -> blocks.joinToString(separator = "\n") { it.plainText() }
    is BookBlock.Poem -> buildList {
        title.forEach { add(it.plainText()) }
        stanzas.forEach { add(it.plainText()) }
        textAuthors.forEach { add(it.plainText()) }
    }.joinToString(separator = "\n")
    is BookBlock.Spacer -> ""
}

fun BookNote.plainText(): String = blocks.joinToString(separator = "\n") { it.plainText() }

fun BookDocument.plainText(): String = blocks.joinToString(separator = "\n") { it.plainText() }

fun BookTocEntry.plainText(): String = buildList {
    add(title)
    children.forEach { add(it.plainText()) }
}.joinToString(separator = "\n")

private fun List<BookBlock>.flattenedBlocks(): List<BookBlock> = buildList {
    fun appendBlock(block: BookBlock) {
        add(block)
        if (block is BookBlock.Quote) {
            block.blocks.forEach(::appendBlock)
        }
    }

    this@flattenedBlocks.forEach(::appendBlock)
}
