package com.avstaim.listai.core.model

/** A part divider projects existing source blocks without adding source characters or IDs. */
data class BookPart(
    val headingBlockIds: List<BlockId>,
    /** Includes source spacers between the title and its explicit epigraph, when present. */
    val epigraphBlockIds: List<BlockId> = emptyList(),
) {
    init {
        require(headingBlockIds.isNotEmpty())
        require(blockIds.distinct().size == blockIds.size)
    }
    val startBlockId: BlockId get() = headingBlockIds.first()
    val blockIds: List<BlockId> get() = headingBlockIds + epigraphBlockIds
}

private const val ENGLISH_PART_NUMBERS =
    "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|" +
        "twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth"
private const val RUSSIAN_PART_NUMBERS =
    "перв(?:ая|ый)|втор(?:ая|ой)|треть(?:я|ий)|четв[её]рт(?:ая|ый)|пят(?:ая|ый)|шест(?:ая|ой)|седьм(?:ая|ой)|восьм(?:ая|ой)|девят(?:ая|ый)|десят(?:ая|ый)|" +
        "одиннадцат(?:ая|ый)|двенадцат(?:ая|ый)|тринадцат(?:ая|ый)|четырнадцат(?:ая|ый)|пятнадцат(?:ая|ый)|шестнадцат(?:ая|ый)|семнадцат(?:ая|ый)|восемнадцат(?:ая|ый)|девятнадцат(?:ая|ый)|" +
        "двадцат(?:ая|ый)|тридцат(?:ая|ый)|сороков(?:ая|ой)|пятидесят(?:ая|ый)|шестидесят(?:ая|ый)|семидесят(?:ая|ый)|восьмидесят(?:ая|ый)|девяност(?:ая|ый)|сот(?:ая|ый)|" +
        "двадцать|тридцать|сорок|пятьдесят|шестьдесят|семьдесят|восемьдесят|девяносто|сто"
private val partNumber = Regex(
    "^\\s*(?:part|book|volume|часть|книга|том)\\s+(?:[0-9]+|[IVXLCDM]+|" +
        "(?:$ENGLISH_PART_NUMBERS)(?:[ -]+(?:$ENGLISH_PART_NUMBERS))*|" +
        "(?:$RUSSIAN_PART_NUMBERS)(?:[ -]+(?:$RUSSIAN_PART_NUMBERS))*)(?=\\s|[.:—–-]|$)",
    RegexOption.IGNORE_CASE,
)

/** A source label on its own can be paired with a sibling subtitle in an EPUB header. */
fun isStandalonePartLabel(text: String): Boolean = partNumber.find(text)?.let { match ->
    text.drop(match.range.last + 1).trim().trim('.', ':', '—', '–', '-').isEmpty()
} == true

/** Explicit format semantics win; plain headings need a recognisable numbered part label. */
fun partOpenings(
    blocks: List<BookBlock>,
    groups: List<BookHeadingGroup>,
    explicitEpigraphBlockIds: Set<BlockId> = emptySet(),
): List<BookPart> {
    val epigraphIds = explicitEpigraphBlockIds.toMutableSet()
    val flat = buildList {
        fun visit(block: BookBlock, epigraph: Boolean) {
            if (block is BookBlock.Quote) block.blocks.forEach { visit(it, epigraph || block.role == QuoteRole.EPIGRAPH) }
            else {
                add(block)
                if (epigraph) epigraphIds += block.id
            }
        }
        blocks.forEach { visit(it, false) }
    }
    val positions = flat.mapIndexed { index, block -> block.id to index }.toMap()
    val sources = flat.associateBy(BookBlock::id)
    return groups.filter { group ->
        group.blockIds.isNotEmpty() && group.blockIds.all { sources[it] is BookBlock.Heading } &&
            group.blockIds.none { it in epigraphIds } &&
            (group.role == BookHeadingRole.PART || group.role == BookHeadingRole.UNSPECIFIED &&
                partNumber.containsMatchIn(group.blockIds.joinToString(" ") { sources.getValue(it).plainText() }))
    }.sortedBy { positions[it.blockIds.first()] }.map { group ->
        val next = requireNotNull(positions[group.blockIds.last()]) + 1
        val epigraph = flat.subList(next, flat.size)
            .takeWhile { it.id in epigraphIds || it is BookBlock.Spacer }
            .dropLastWhile { it.id !in epigraphIds }
            .map(BookBlock::id)
        BookPart(group.blockIds, epigraph)
    }
}
