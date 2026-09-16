package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Rect
import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*

/** Session-only address: source progress remains in the original document. */
internal data class FootnoteVisit(val noteId: NoteId, val origin: BookLocation, val target: BookLocation)

/** A continuous projection of original rich blocks. Its empty notes list disables recursive footers. */
internal class PhysicalNotes private constructor(
    source: BookDocument,
    val title: String,
    noteLabel: (Int) -> String,
    val returnLabel: String,
) {
    val document: BookDocument
    val targets: Map<NoteId, BookLocation>
    private val blockNotes: Map<BlockId, NoteId>
    private val firstCalls: Map<NoteId, BookLocation>

    init {
        val owners = linkedMapOf<BlockId, NoteId>()
        val headings = linkedMapOf<NoteId, BlockId>()
        val occupied = mutableSetOf<BlockId>()
        fun collectIds(block: BookBlock) {
            occupied += block.id
            if (block is BookBlock.Quote) block.blocks.forEach(::collectIds)
        }
        source.blocks.forEach(::collectIds)
        source.notes.forEach { it.blocks.forEach(::collectIds) }
        fun synthetic(value: String): BlockId {
            var id = BlockId("listai:notes:$value")
            while (!occupied.add(id)) id = BlockId("${id.value}:")
            return id
        }
        val blocks = buildList<BookBlock> {
            add(BookBlock.Heading(synthetic("title"), 1, listOf(InlineNode.Text(title))))
            source.noteIndex.entries.forEach { entry ->
                val note = entry.note
                val heading = synthetic("${note.id.value}:heading")
                headings[note.id] = heading
                val noteBlocks = buildList {
                    add(BookBlock.Heading(heading, 2, listOf(InlineNode.Text(noteLabel(entry.ordinal)))))
                    note.title?.trim()?.takeUnless { it.isGeneratedNoteTitle() }
                        ?.takeIf(String::isNotBlank)?.let { meaningful ->
                            add(BookBlock.Paragraph(synthetic("${note.id.value}:subtitle"),
                                listOf(InlineNode.Strong(listOf(InlineNode.Text(meaningful)))), ParagraphRole.SUBTITLE))
                        }
                    addAll(note.contentBlocks())
                }
                fun own(block: BookBlock) {
                    owners[block.id] = note.id
                    if (block is BookBlock.Quote) block.blocks.forEach(::own)
                }
                noteBlocks.forEach(::own)
                addAll(noteBlocks)
            }
        }
        blockNotes = owners
        document = BookDocument(BookId("${source.id.value}:notes"), source.metadata.copy(title = title),
            blocks, images = source.images)
        val flow = document.readingFlow().associateBy { it.source.id }
        targets = headings.mapValues { (_, id) -> BookLocation(id, 0, requireNotNull(flow[id]).globalOffset) }
        firstCalls = source.noteIndex.entries.mapNotNull { entry ->
            entry.references.firstOrNull()?.source?.let { entry.id to it }
        }.toMap()
    }

    fun noteAt(location: BookLocation): NoteId? = blockNotes[location.blockId]
    fun firstCall(noteId: NoteId): BookLocation? = firstCalls[noteId]
    fun returnOrigin(noteId: NoteId, visit: FootnoteVisit?): BookLocation? = visit?.origin ?: firstCall(noteId)

    fun returns(page: PageLayout, key: LayoutKey, visit: FootnoteVisit?): List<PreparedNoteReturn> {
        // A visit has one origin even when this leaf contains several notes or a continuation.
        val noteId = visit?.noteId ?: page.blocks.asSequence().mapNotNull { blockNotes[it.blockId] }
            .firstOrNull { firstCall(it) != null } ?: return emptyList()
        val top = key.contentTop + key.contentHeight
        val width = minOf(key.contentWidth.toFloat(), 180f * key.density)
        val left = (key.pageWidthPx - width) / 2f
        return listOf(PreparedNoteReturn(noteId,
            Rect(left, top, left + width, top + key.bottomContentInset), "↩ $returnLabel"))
    }

    companion object {
        fun create(source: BookDocument, title: String, noteLabel: (Int) -> String, returnLabel: String): PhysicalNotes? =
            if (source.noteIndex.entries.isEmpty()) null else PhysicalNotes(source, title, noteLabel, returnLabel)
    }
}

internal fun LayoutKey.forNotes() = copy(fontSizeSp = fontSizeSp * .90f,
    bottomContentInsetDp = 40f)

/** Numeric/source label headings are already expressed by the localized stable ordinal. */
private fun String.isGeneratedNoteTitle(): Boolean = matches(NumericNoteTitle) || matches(GenericNoteTitle)

private val NumericNoteTitle = Regex("[\\p{N}\\s\\p{Z}.()\\[\\]—–-]+")
private val GenericNoteTitle = Regex(
    "(?:note|footnote|endnote|сноска|примечание)[\\s\\p{Z}]*[№#]?[\\s\\p{Z}]*[\\p{N}]+[.)]?",
    RegexOption.IGNORE_CASE)
