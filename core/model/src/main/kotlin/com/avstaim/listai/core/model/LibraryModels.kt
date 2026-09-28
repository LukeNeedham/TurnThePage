package com.avstaim.listai.core.model

/** A single atomic, layout-independent reading checkpoint. */
data class ReadingProgress(
    val location: BookLocation? = null,
    val cover: CoverPosition = CoverPosition.OPEN,
    val fraction: Float = 0f,
    /** Semantic item in the supplemental contents, independent of the source text anchor. */
    val tocEntryIndex: Int? = null,
    val tocCharOffset: Int = 0,
    /** Independent front-cover/frontmatter anchor; never substitutes the main source location. */
    val annotationLocation: BookLocation? = null,
    /** Independent title-page anchor; never contributes to source reading progress. */
    val titleLocation: BookLocation? = null,
) {
    init {
        require(fraction.isFinite() && fraction in 0f..1f)
        require(tocEntryIndex == null || tocEntryIndex >= 0)
        require(tocCharOffset >= 0)
    }
}

enum class CoverPosition { OPEN, FRONT, BACK, BOUNDARY }

data class LibraryBook(
    val id: BookId,
    val title: String,
    val authors: String,
    val fileName: String,
    val coverPath: String?,
    val importedAt: Long,
    val lastOpenedAt: Long?,
    val totalCharacters: Long,
    val progress: ReadingProgress,
    val authorNames: List<String> = emptyList(),
    val series: String? = null,
    val genres: List<String> = emptyList(),
    val stackId: String? = null,
)

data class Bookmark(
    val id: String,
    val bookId: BookId,
    val location: BookLocation,
    val createdAt: Long,
    val snippet: String,
)

/** Matches the parser/paginator's flattened UTF-16 flow, including inter-block separators. */
fun BookDocument.readingBlocks(): List<BookBlock> = buildList {
    fun visit(block: BookBlock) {
        if (block is BookBlock.Quote) block.blocks.forEach(::visit) else add(block)
    }
    blocks.forEach(::visit)
}

fun BookDocument.readingLength(): Long = readingBlocks().sumOf { it.plainText().length + 1L }

fun BookDocument.bookmarkSnippet(location: BookLocation): String = readingBlocks()
    .firstOrNull { it.id == location.blockId }?.let {
        if (it is BookBlock.FixedPage) "PDF · ${it.pageIndex + 1}" else it.plainText()
    }
    ?.let { it.drop(location.charOffset.coerceAtMost(it.length)).ifBlank { it.takeLast(160) } }
    ?.replace(Regex("\\s+"), " ")?.trim()?.take(160).orEmpty()
