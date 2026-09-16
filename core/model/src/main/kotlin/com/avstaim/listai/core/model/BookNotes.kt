package com.avstaim.listai.core.model

/** A source call, in the same UTF-16 coordinates as [BookLocation]. */
data class NoteReference(
    val noteId: NoteId,
    val ordinal: Int,
    val source: BookLocation,
    val endCharOffsetExclusive: Int,
    val marker: String,
)

data class IndexedBookNote(
    val note: BookNote,
    val ordinal: Int,
    val previewContent: List<InlineNode>,
    val references: List<NoteReference>,
) {
    val id: NoteId get() = note.id
}

/**
 * Some formats retain the note title as its first heading as well as [BookNote.title].
 * Reader projections print their own title; remove only that matching leading heading and retain
 * the original source blocks and rich content unchanged.
 */
fun BookNote.contentBlocks(): List<BookBlock> {
    val heading = blocks.firstOrNull() as? BookBlock.Heading ?: return blocks
    val title = title?.normalizedNoteTitle() ?: return blocks
    return if (heading.plainText().normalizedNoteTitle() == title) blocks.drop(1) else blocks
}

private val noteTitleWhitespace = Regex("[\\s\\p{Z}]+")
private fun String.normalizedNoteTitle(): String = trim().replace(noteTitleWhitespace, " ")

/** Format-neutral immutable projections; no platform layouts, images or session state. */
class BookNoteIndex internal constructor(document: BookDocument) {
    val entries: List<IndexedBookNote>
    val byId: Map<NoteId, IndexedBookNote>
    val referencesByBlock: Map<BlockId, List<NoteReference>>
    private val maximumEndsByBlock: Map<BlockId, IntArray>

    init {
        fun hasContent(block: BookBlock): Boolean = when (block) {
            is BookBlock.Quote -> block.blocks.any(::hasContent)
            is BookBlock.Image -> block.alternativeText?.isNotBlank() == true || block.imageId in document.imagesById
            else -> block.plainText().isNotBlank() || block.inlineContent().hasImage(document.imagesById.keys)
        }
        val notes = document.notes.filter { it.contentBlocks().any(::hasContent) }
        val ordinals = notes.mapIndexed { index, note -> note.id to index + 1 }.toMap()
        val references = mutableListOf<NoteReference>()
        var globalOffset = 0L
        fun visitBlock(block: BookBlock) {
            if (block is BookBlock.Quote) {
                block.blocks.forEach(::visitBlock)
                return
            }
            var offset = 0
            fun visit(nodes: List<InlineNode>) {
                nodes.forEach { node ->
                    when (node) {
                        is InlineNode.Text -> offset += node.value.length
                        is InlineNode.Image -> offset += node.alternativeText.orEmpty().length
                        is InlineNode.Emphasis -> visit(node.children)
                        is InlineNode.Strong -> visit(node.children)
                        is InlineNode.Strikethrough -> visit(node.children)
                        is InlineNode.Link -> {
                            val target = node.target as? LinkTarget.Note
                            val ordinal = target?.let { ordinals[it.noteId] }
                            val marker = node.children.plainText()
                            if (target != null && ordinal != null && marker.isNotBlank()) {
                                references += NoteReference(target.noteId, ordinal,
                                    BookLocation(block.id, offset, globalOffset + offset), offset + marker.length, marker)
                            }
                            visit(node.children)
                        }
                    }
                }
            }
            visit(block.inlineContent())
            globalOffset += block.plainText().length + 1L
        }
        if (ordinals.isNotEmpty()) document.blocks.forEach(::visitBlock)
        val byNote = references.groupBy(NoteReference::noteId)
        entries = notes.mapIndexed { index, note ->
            IndexedBookNote(note, index + 1, note.contentBlocks().previewContent(), byNote[note.id].orEmpty())
        }
        byId = entries.associateBy(IndexedBookNote::id)
        referencesByBlock = references.groupBy { it.source.blockId }
        maximumEndsByBlock = referencesByBlock.mapValues { (_, calls) ->
            var maximum = 0
            IntArray(calls.size) { index ->
                maximum = maxOf(maximum, calls[index].endCharOffsetExclusive)
                maximum
            }
        }
    }

    /** Indexed interval lookup also handles nested links and markers wrapping across a page. */
    fun referencesIn(blockId: BlockId, start: Int, endExclusive: Int): List<NoteReference> {
        if (endExclusive <= start) return emptyList()
        val calls = referencesByBlock[blockId] ?: return emptyList()
        val ends = maximumEndsByBlock.getValue(blockId)
        var low = 0
        var high = calls.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ends[middle] <= start) low = middle + 1 else high = middle
        }
        return buildList {
            var index = low
            while (index < calls.size && calls[index].source.charOffset < endExclusive) {
                if (calls[index].endCharOffsetExclusive > start) add(calls[index])
                index++
            }
        }
    }
}

/** Shared flattening keeps poetry newlines and image alternatives in source coordinate space. */
fun BookBlock.inlineContent(): List<InlineNode> = when (this) {
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

/** Slice rich text without changing its UTF-16 indexing. Used before bounded preview measurement. */
fun List<InlineNode>.sliceInlineContent(start: Int, endExclusive: Int): List<InlineNode> {
    require(start >= 0 && endExclusive >= start)
    var offset = 0
    fun slice(nodes: List<InlineNode>): List<InlineNode> = buildList {
        nodes.forEach { node ->
            when (node) {
                is InlineNode.Text -> {
                    val from = (start - offset).coerceIn(0, node.value.length)
                    val to = (endExclusive - offset).coerceIn(from, node.value.length)
                    if (to > from) add(InlineNode.Text(node.value.substring(from, to)))
                    offset += node.value.length
                }
                is InlineNode.Image -> {
                    val text = node.alternativeText.orEmpty()
                    val from = (start - offset).coerceIn(0, text.length)
                    val to = (endExclusive - offset).coerceIn(from, text.length)
                    if (to > from) add(InlineNode.Text(text.substring(from, to)))
                    offset += text.length
                }
                is InlineNode.Emphasis -> slice(node.children).takeIf { it.isNotEmpty() }?.let { add(InlineNode.Emphasis(it)) }
                is InlineNode.Strong -> slice(node.children).takeIf { it.isNotEmpty() }?.let { add(InlineNode.Strong(it)) }
                is InlineNode.Strikethrough -> slice(node.children).takeIf { it.isNotEmpty() }?.let { add(InlineNode.Strikethrough(it)) }
                is InlineNode.Link -> slice(node.children).takeIf { it.isNotEmpty() }?.let { add(InlineNode.Link(node.target, it)) }
            }
        }
    }
    return slice(this)
}

private fun List<InlineNode>.hasImage(images: Set<ImageId>): Boolean = any { node ->
    when (node) {
        is InlineNode.Image -> node.imageId in images
        is InlineNode.Emphasis -> node.children.hasImage(images)
        is InlineNode.Strong -> node.children.hasImage(images)
        is InlineNode.Strikethrough -> node.children.hasImage(images)
        is InlineNode.Link -> node.children.hasImage(images)
        is InlineNode.Text -> false
    }
}

private fun List<BookBlock>.previewContent(): List<InlineNode> = buildList {
    fun append(block: BookBlock) {
        if (block is BookBlock.Quote) block.blocks.forEach(::append)
        else {
            val content = block.inlineContent()
            if (content.plainText().isNotBlank()) {
                if (isNotEmpty()) add(InlineNode.Text("\n"))
                addAll(content)
            }
        }
    }
    this@previewContent.forEach(::append)
}
