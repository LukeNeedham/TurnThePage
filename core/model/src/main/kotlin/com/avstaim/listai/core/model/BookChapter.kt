package com.avstaim.listai.core.model

/** The source heading may span several blocks, but is one physical chapter opening. */
data class BookChapter(
    val headingBlockIds: List<BlockId>,
    val ordinal: Int,
    val number: String? = null,
    /** Prefix of the first original block displayed in the separate number row. */
    val numberPrefixLength: Int = 0,
) {
    init {
        require(headingBlockIds.isNotEmpty() && headingBlockIds.distinct().size == headingBlockIds.size)
        require(ordinal > 0 && numberPrefixLength >= 0)
        require(number == null || number.isNotBlank())
    }
    val startBlockId: BlockId get() = headingBlockIds.first()
}

enum class BookHeadingRole { UNSPECIFIED, CHAPTER, OTHER }

/** Parser-owned grouping; never combines or rewrites original blocks. */
data class BookHeadingGroup(
    val blockIds: List<BlockId>,
    val level: Int,
    val role: BookHeadingRole = BookHeadingRole.UNSPECIFIED,
)

private val otherOpening = Regex("^(?:part|book|volume|prologue|epilogue|preface|foreword|introduction|contents|часть|том|книга|пролог|эпилог|предисловие|введение|оглавление)(?:\\s|[.:—–-]|$)", RegexOption.IGNORE_CASE)
private val explicitNumber = Regex(
    "^\\s*(?:(?:chapter|глава)\\s+)?((?:[0-9]+|[IVXLCDM]+)(?=\\s|[.:—–-]|$)|" +
        "(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred)(?:[ -]+(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred))*\\b|" +
        "(?:первая|вторая|третья|четв[её]ртая|пятая|шестая|седьмая|восьмая|девятая|десятая|одиннадцатая|двенадцатая|тринадцатая|четырнадцатая|пятнадцатая|шестнадцатая|семнадцатая|восемнадцатая|девятнадцатая|двадцатая|тридцатая|сороковая|пятидесятая|шестидесятая|семидесятая|восьмидесятая|девяностая|сотая))(?=\\s|[.:—–-]|$)[\\s.:—–-]*",
    RegexOption.IGNORE_CASE,
)
private val chapterWord = Regex("^\\s*(chapter|глава)\\s+", RegexOption.IGNORE_CASE)

/** Conservative structural projection: parts/frontmatter are not numbered as chapters. */
fun chapterOpenings(blocks: List<BookBlock>, groups: List<BookHeadingGroup>): List<BookChapter> {
    val flat = buildList {
        fun visit(block: BookBlock) { if (block is BookBlock.Quote) block.blocks.forEach(::visit) else add(block) }
        blocks.forEach(::visit)
    }
    val positions = flat.mapIndexed { index, block -> block.id to index }.toMap()
    val sources = flat.associateBy(BookBlock::id)
    val eligible = groups.filter { group ->
        group.blockIds.isNotEmpty() && group.blockIds.all { sources[it] is BookBlock.Heading } &&
            group.role != BookHeadingRole.OTHER && !otherOpening.containsMatchIn(sources.getValue(group.blockIds.first()).plainText().trim())
    }.sortedBy { positions[it.blockIds.first()] }
    val level = eligible.minOfOrNull(BookHeadingGroup::level) ?: return emptyList()
    val selected = eligible.filter { it.level == level || chapterWord.containsMatchIn(sources.getValue(it.blockIds.first()).plainText()) }
    return selected.mapIndexed { index, group ->
        val firstText = sources.getValue(group.blockIds.first()).plainText()
        val number = explicitNumber.find(firstText)?.takeIf {
            // A bare number is unambiguous only when it occupies the whole source line.
            // Titles such as "1984: A journey" and "I remember" are not chapter numbers.
            chapterWord.containsMatchIn(firstText) || it.range.last + 1 == firstText.length
        }
        // Consecutive h1(number) + h2(title) inside an EPUB header form one opening.
        val following = eligible.firstOrNull { candidate -> candidate.level > group.level &&
            positions[candidate.blockIds.first()] == positions[group.blockIds.last()]?.plus(1) &&
            candidate !in selected }
        val ids = if (number != null && number.range.last + 1 == firstText.length && following != null)
            group.blockIds + following.blockIds else group.blockIds
        BookChapter(ids, index + 1, number?.groupValues?.get(1), number?.range?.last?.plus(1) ?: 0)
    }
}
