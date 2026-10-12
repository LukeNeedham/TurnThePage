package com.avstaim.listai.core.model

/** The source heading may span several blocks, but is one physical chapter opening. */
data class BookChapter(
    val headingBlockIds: List<BlockId>,
    /** Only ordinary chapters consume a number; an epilogue remains unnumbered. */
    val ordinal: Int?,
    val number: String? = null,
    /** Prefix of the first original block displayed in the separate number row. */
    val numberPrefixLength: Int = 0,
    val kind: BookChapterKind = BookChapterKind.CHAPTER,
) {
    init {
        require(headingBlockIds.isNotEmpty() && headingBlockIds.distinct().size == headingBlockIds.size)
        require(numberPrefixLength >= 0)
        require(if (kind == BookChapterKind.CHAPTER) ordinal != null && ordinal > 0
            else ordinal == null && number == null && numberPrefixLength == 0)
        require(number == null || number.isNotBlank())
    }
    val startBlockId: BlockId get() = headingBlockIds.first()
}

enum class BookChapterKind { CHAPTER, EPILOGUE }

enum class BookHeadingRole { UNSPECIFIED, CHAPTER, PART, EPILOGUE, OTHER }

/** Parser-owned grouping; never combines or rewrites original blocks. */
data class BookHeadingGroup(
    val blockIds: List<BlockId>,
    val level: Int,
    val role: BookHeadingRole = BookHeadingRole.UNSPECIFIED,
)

private val otherOpening = Regex("^(?:part|book|volume|teil|partie|parte|prologue|epilogue|epilog|épilogue|epilogo|epílogo|preface|foreword|introduction|contents|часть|том|книга|пролог|эпилог|предисловие|введение|оглавление)(?:\\s|[.:—–-]|$)", RegexOption.IGNORE_CASE)
private val explicitNumber = Regex(
    "^\\s*(?:(?:chapter|глава|kapitel|chapitre|capitolo|capítulo)\\s+)?((?:[0-9]+|[IVXLCDM]+)(?=\\s|[.:—–-]|$)|" +
        "(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred)(?:[ -]+(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred))*\\b|" +
        "(?:первая|вторая|третья|четв[её]ртая|пятая|шестая|седьмая|восьмая|девятая|десятая|одиннадцатая|двенадцатая|тринадцатая|четырнадцатая|пятнадцатая|шестнадцатая|семнадцатая|восемнадцатая|девятнадцатая|двадцатая|тридцатая|сороковая|пятидесятая|шестидесятая|семидесятая|восьмидесятая|девяностая|сотая))(?=\\s|[.:—–-]|$)[\\s.:—–-]*",
    RegexOption.IGNORE_CASE,
)
private val chapterWord = Regex("^\\s*(chapter|глава|kapitel|chapitre|capitolo|capítulo)\\s+", RegexOption.IGNORE_CASE)
private val standaloneEpilogue = Regex("^\\s*(?:epilogue|эпилог|epilog|épilogue|epilogo|epílogo)\\s*[.:]?\\s*$", RegexOption.IGNORE_CASE)

/** A prose mention or a heading such as "Epilogue of a journey" is not a division label. */
fun isStandaloneEpilogueLabel(text: String): Boolean = standaloneEpilogue.matches(text)

/** Conservative structural projection: parts/frontmatter are not numbered as chapters. */
fun chapterOpenings(blocks: List<BookBlock>, groups: List<BookHeadingGroup>): List<BookChapter> {
    val flat = buildList {
        fun visit(block: BookBlock) { if (block is BookBlock.Quote) block.blocks.forEach(::visit) else add(block) }
        blocks.forEach(::visit)
    }
    val positions = flat.mapIndexed { index, block -> block.id to index }.toMap()
    val sources = flat.associateBy(BookBlock::id)
    val partIds = partOpenings(blocks, groups).flatMapTo(mutableSetOf()) { it.blockIds }
    val sourceHeadings = groups.filter { group ->
        group.blockIds.isNotEmpty() && group.blockIds.all { sources[it] is BookBlock.Heading } &&
            group.role !in setOf(BookHeadingRole.OTHER, BookHeadingRole.PART) && group.blockIds.none { it in partIds }
    }.sortedBy { positions[it.blockIds.first()] }
    // The top-level epilogue must not change the chapter level in a book whose chapters
    // live one level deeper inside parts, or consume a generated chapter number.
    val eligible = sourceHeadings.filter { group -> group.role != BookHeadingRole.EPILOGUE &&
        !otherOpening.containsMatchIn(sources.getValue(group.blockIds.first()).plainText().trim()) }
    val level = eligible.minOfOrNull(BookHeadingGroup::level)
    val selected = eligible.filter { it.level == level || chapterWord.containsMatchIn(sources.getValue(it.blockIds.first()).plainText()) }
    val epilogueLevel = level ?: sourceHeadings.minOfOrNull(BookHeadingGroup::level)
    val epilogues = sourceHeadings.filter { group -> group.role == BookHeadingRole.EPILOGUE ||
        group.role in setOf(BookHeadingRole.UNSPECIFIED, BookHeadingRole.CHAPTER) && epilogueLevel != null && group.level <= epilogueLevel &&
        isStandaloneEpilogueLabel(sources.getValue(group.blockIds.first()).plainText()) }
    val chapters = selected.mapIndexed { index, group ->
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
    return (chapters + epilogues.map { BookChapter(it.blockIds, null, kind = BookChapterKind.EPILOGUE) })
        .sortedBy { positions[it.startBlockId] }
}
