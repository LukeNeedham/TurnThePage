package com.avstaim.listai.format.epub

import com.avstaim.listai.core.model.*
import org.xml.sax.Attributes

internal class EpubContentState(val limits: EpubParserLimits) {
    val blocks = mutableListOf<BookBlock>()
    val notes = mutableListOf<BookNote>()
    val locations = linkedMapOf<String, BookLocation>()
    val blockLocations = linkedMapOf<BlockId, BookLocation>()
    val noteTargets = linkedMapOf<String, NoteId>()
    val usedImages = mutableSetOf<String>()
    val firstImagesByPath = mutableMapOf<String, String>()
    val headingGroups = mutableListOf<BookHeadingGroup>()
    val titlePageBlockIds = mutableListOf<BlockId>()
    val epigraphBlockIds = mutableSetOf<BlockId>()
    private var blockCount = 0
    private var globalOffset = 0L
    private var noteOffset = 0L
    fun append(block: BookBlock, note: MutableList<BookBlock>?): BookLocation {
        if (++blockCount > limits.maxBlocks) limit("EPUB blocks", limits.maxBlocks.toLong())
        val location = BookLocation(block.id, 0, if (note == null) globalOffset else noteOffset)
        val length = block.plainText().length.toLong() + 1
        if (note == null) { blocks += block; globalOffset += length } else { note += block; noteOffset += length }
        blockLocations[block.id] = location
        return location
    }
}

private data class InlineStyle(val kind: String, val link: LinkTarget? = null)
private class HtmlFrame(val tag: String, val styles: List<InlineStyle>, val types: Set<String>) {
    var partHeadingGroup: Int? = null
    var partHeadingContainer: HtmlFrame? = null
    var lastHeadingGroup: Int? = null
}
private data class PendingAnchor(val key: String, val offset: Int)

internal class XhtmlHandler(
    private val path: String,
    private val state: EpubContentState,
    private val images: Map<String, ImageId>,
    private val linear: Boolean,
    private val stylesheets: Map<String, EpubCss> = emptyMap(),
    private val isTitlePage: Boolean = false,
) : EpubHandler() {
    private var depth = 0
    private var bodyDepth = -1
    private var skipDepth = -1
    private val frames = mutableListOf<HtmlFrame>()
    private var buffer = InlineBuffer()
    private val anchors = mutableListOf<PendingAnchor>()
    private var headingLevel: Int? = null
    private var preformatted = false
    private var ordinal = 0
    private var noteDepth = -1
    private var noteId: NoteId? = null
    private var noteBlocks: MutableList<BookBlock>? = null
    private var rootSeen = false
    private val activeStylesheets = mutableListOf<EpubCss>()
    private var styleDepth = -1
    private val embeddedCss = StringBuilder()
    private var outerAnchors: List<PendingAnchor> = emptyList()
    private val readable: Boolean get() = linear || noteBlocks != null

    override fun startElement(uri: String, local: String, name: String, attrs: Attributes) {
        depth++
        val tag = elementName(local, name)
        if (depth == 1) {
            if (tag != "html" || (uri.isNotEmpty() && uri != "http://www.w3.org/1999/xhtml")) malformed()
            rootSeen = true
        }
        if (tag == "link" && attrs.value("rel").orEmpty().split(Regex("\\s+")).contains("stylesheet")) {
            attrs.value("href")?.let { resolveReference(path, it)?.path }?.let(stylesheets::get)?.let(activeStylesheets::add)
        }
        if (tag == "style" && bodyDepth < 0) { styleDepth = depth; embeddedCss.setLength(0) }
        val styles = mutableListOf<InlineStyle>()
        when (tag) {
            "em", "i", "cite" -> styles += InlineStyle("em")
            "strong", "b" -> styles += InlineStyle("strong")
            "s", "strike", "del" -> styles += InlineStyle("strike")
            "a" -> attrs.value("href")?.let { href ->
                val target = if (isExternalReference(href)) LinkTarget.External(href) else
                    resolveReference(path, href)?.let { LinkTarget.UnresolvedInternal(it.sourceId) }
                if (target != null) styles += InlineStyle("link", target)
            }
        }
        val classes = attrs.value("class").orEmpty().split(Regex("\\s+")).toSet()
        val css = (activeStylesheets.joinToString(";") { it.declarations(tag, attrs.value("id"), classes) } +
            ";" + attrs.value("style").orEmpty()).lowercase()
        val properties = css.split(';').mapNotNull { property ->
            if (':' !in property) null else property.substringBefore(':').trim() to property.substringAfter(':').trim()
        }.toMap()
        if (properties["font-style"] in setOf("italic", "oblique") && styles.none { it.kind == "em" }) styles += InlineStyle("em")
        if (properties["font-weight"] in setOf("bold", "600", "700", "800", "900") && styles.none { it.kind == "strong" }) styles += InlineStyle("strong")
        if ((properties["text-decoration-line"] ?: properties["text-decoration"]).orEmpty().split(' ').contains("line-through") && styles.none { it.kind == "strike" }) styles += InlineStyle("strike")
        frames += HtmlFrame(tag, styles, attrs.epubType())
        if (tag == "body") {
            bodyDepth = depth
            if (linear) anchors += PendingAnchor(sourceKey(path), 0)
        }
        if (bodyDepth < 0 || skipDepth >= 0) return
        if (tag in setOf("script", "style", "noscript", "iframe", "object") || attrs.getValue("hidden") != null ||
            (properties["display"] == "none" || properties["visibility"] == "hidden")) {
            skipDepth = depth
            return
        }
        val startsNote = attrs.epubType().any { it == "footnote" || it == "endnote" }
        if (startsNote && noteDepth < 0) {
            flush()
            outerAnchors = anchors.toList()
            anchors.clear()
            noteDepth = depth
            noteId = NoteId(sourceKey(path, attrs.value("id") ?: "note-$ordinal"))
            noteBlocks = mutableListOf()
        }
        if (!readable) return
        if (tag in BLOCK_TAGS) {
            flush()
            headingLevel = tag.takeIf { it.length == 2 && it[0] == 'h' && it[1] in '1'..'6' }?.get(1)?.digitToInt()
            preformatted = tag == "pre"
        }
        val id = attrs.value("id") ?: attrs.value("xml:id") ?: if (tag == "a") attrs.value("name") else null
        if (id != null) {
            val key = sourceKey(path, id)
            anchors += PendingAnchor(key, buffer.anchorOffset)
            noteId?.let { state.noteTargets[key] = it }
        }
        when (tag) {
            "br" -> buffer.lineBreak(currentStyles())
            "hr" -> { flush(); append(BookBlock.Spacer(nextId())) }
            "img", "image" -> {
                val href = attrs.value("src") ?: attrs.getValue("http://www.w3.org/1999/xlink", "href") ?: attrs.value("href")
                val imagePath = href?.let { resolveReference(path, it)?.path }
                val imageId = imagePath?.let(images::get)
                val alt = attrs.value("alt") ?: attrs.value("title")
                if (imageId != null) {
                    state.usedImages += imagePath
                    state.firstImagesByPath.putIfAbsent(path, imagePath)
                    if (buffer.isEmpty && frames.none { it.tag in TEXT_BLOCK_TAGS }) {
                        append(BookBlock.Image(nextId(), imageId, alt))
                    } else buffer.image(InlineNode.Image(imageId, alt), currentStyles())
                } else if (alt != null) buffer.text(alt, currentStyles(), preformatted)
            }
        }
    }

    override fun characters(chars: CharArray, start: Int, length: Int) {
        if (styleDepth >= 0) embeddedCss.append(chars, start, length)
        if (bodyDepth >= 0 && skipDepth < 0 && readable) {
            buffer.text(String(chars, start, length), currentStyles(), preformatted)
        }
    }

    override fun endElement(uri: String, local: String, name: String) {
        val tag = elementName(local, name)
        if (depth == styleDepth) {
            activeStylesheets += EpubCss.parse(embeddedCss.toString())
            styleDepth = -1
        }
        if (bodyDepth >= 0 && skipDepth < 0 && readable && tag in BLOCK_TAGS) {
            flush()
            headingLevel = null
            preformatted = false
        }
        if (depth == noteDepth) {
            flush()
            val blocks = requireNotNull(noteBlocks)
            val id = requireNotNull(noteId)
            if (blocks.isNotEmpty()) {
                state.notes += BookNote(id, blocks.toList(), (blocks.firstOrNull() as? BookBlock.Heading)?.plainText())
                state.noteTargets.putIfAbsent(id.value, id)
            }
            // A note's dangling IDs must not attach to the following main paragraph.
            val last = blocks.lastOrNull()
            if (last != null) {
                val location = state.blockLocations.getValue(last.id)
                anchors.forEach { state.locations.putIfAbsent(it.key, location.copy(charOffset = last.plainText().length,
                    fallbackGlobalOffset = location.fallbackGlobalOffset + last.plainText().length)) }
            }
            anchors.clear()
            anchors += outerAnchors
            outerAnchors = emptyList()
            noteBlocks = null
            noteId = null
            noteDepth = -1
        }
        if (depth == skipDepth) skipDepth = -1
        if (depth == bodyDepth) {
            flush()
            // Trailing empty anchors resolve to the preceding block, never an invented page.
            val previous = (if (linear) state.blocks else state.notes.lastOrNull()?.blocks)?.lastOrNull()
            previous?.let { block ->
                val location = state.blockLocations.getValue(block.id)
                anchors.forEach { state.locations.putIfAbsent(it.key, location.copy(charOffset = block.plainText().length,
                    fallbackGlobalOffset = location.fallbackGlobalOffset + block.plainText().length)) }
            }
            anchors.clear()
            bodyDepth = -1
        }
        frames.removeAt(frames.lastIndex)
        depth--
    }

    override fun endDocument() { if (!rootSeen) malformed() }
    private fun currentStyles(): List<InlineStyle> = frames.flatMap(HtmlFrame::styles)
    private fun nextId(): BlockId = BlockId("epub:$path:${ordinal++}")
    private fun flush() {
        if (buffer.isEmpty) return
        val content = buffer.nodes()
        val level = headingLevel
        val block = if (level == null) BookBlock.Paragraph(nextId(), content,
            if (preformatted) ParagraphRole.VERSE else ParagraphRole.BODY) else BookBlock.Heading(nextId(), level, content)
        append(block)
        buffer = InlineBuffer()
    }
    private fun append(block: BookBlock) {
        val location = state.append(block, noteBlocks)
        if (noteBlocks == null) {
            val types = frames.flatMap { it.types }.toSet()
            if ("epigraph" in types) state.epigraphBlockIds += block.id
            if (isTitlePage || "titlepage" in types) state.titlePageBlockIds += block.id
            else if (block is BookBlock.Heading) {
                // The nearest division owns the heading: a nested chapter must not inherit PART.
                val owner = frames.lastOrNull { frame -> frame.types.any { it in DIVISION_TYPES } }
                val ownerTypes = owner?.types.orEmpty()
                val role = when {
                    "epigraph" in types -> BookHeadingRole.OTHER
                    "chapter" in ownerTypes -> BookHeadingRole.CHAPTER
                    ownerTypes.any { it == "part" || it == "volume" } && owner?.partHeadingGroup == null -> BookHeadingRole.PART
                    ownerTypes.any { it in setOf("prologue", "epilogue", "preface", "foreword", "introduction") } -> BookHeadingRole.OTHER
                    else -> BookHeadingRole.UNSPECIFIED
                }
                val container = frames.dropLast(1).lastOrNull()
                val lexicalPartIndex = container?.lastHeadingGroup?.takeIf { index ->
                    val group = state.headingGroups[index]
                    group.role == BookHeadingRole.UNSPECIFIED && group.blockIds.size == 1 &&
                        state.blocks.getOrNull(state.blocks.lastIndex - 1)?.let { previous ->
                            previous.id == group.blockIds.last() && isStandalonePartLabel(previous.plainText())
                        } == true
                }
                val existingIndex = owner?.partHeadingGroup ?: lexicalPartIndex
                val previous = existingIndex?.let(state.headingGroups::get)
                val attachedSubtitle = previous != null && existingIndex == state.headingGroups.lastIndex &&
                    (existingIndex == lexicalPartIndex || owner?.partHeadingContainer === container) && block.level > previous.level &&
                    role == BookHeadingRole.UNSPECIFIED &&
                    state.blocks.getOrNull(state.blocks.lastIndex - 1)?.id == previous.blockIds.last() &&
                    !DIVISION_LABEL.containsMatchIn(block.plainText())
                if (attachedSubtitle) {
                    state.headingGroups[requireNotNull(existingIndex)] = requireNotNull(previous).copy(blockIds = previous.blockIds + block.id)
                } else {
                    state.headingGroups += BookHeadingGroup(listOf(block.id), block.level, role)
                    container?.lastHeadingGroup = state.headingGroups.lastIndex
                    if (role == BookHeadingRole.PART) {
                        requireNotNull(owner).partHeadingGroup = state.headingGroups.lastIndex
                        owner.partHeadingContainer = container
                    }
                }
            }
        }
        val length = block.plainText().length
        anchors.forEach { anchor ->
            val offset = anchor.offset.coerceAtMost(length)
            state.locations.putIfAbsent(anchor.key, location.copy(charOffset = offset, fallbackGlobalOffset = location.fallbackGlobalOffset + offset))
        }
        anchors.clear()
    }
}

/** Whitespace normalization happens before source offsets are recorded; offsets remain UTF-16. */
private class InlineBuffer {
    private data class Run(val styles: List<InlineStyle>, val text: StringBuilder = StringBuilder(), val image: InlineNode.Image? = null)
    private val runs = mutableListOf<Run>()
    private var pendingSpace = false
    private var length = 0
    val isEmpty: Boolean get() = runs.isEmpty()
    val anchorOffset: Int get() = length + if (pendingSpace && length > 0) 1 else 0
    fun text(value: String, styles: List<InlineStyle>, preserve: Boolean) {
        value.forEach { char ->
            if (!preserve && char in " \t\r\n") {
                pendingSpace = length > 0
            } else {
                if (pendingSpace && length > 0) append(' ', styles)
                pendingSpace = false
                append(char, styles)
            }
        }
    }
    fun lineBreak(styles: List<InlineStyle>) {
        pendingSpace = false
        if (length > 0) append('\n', styles)
    }
    fun image(image: InlineNode.Image, styles: List<InlineStyle>) {
        if (pendingSpace && length > 0) append(' ', styles)
        pendingSpace = false
        runs += Run(styles, image = image)
        length += image.alternativeText.orEmpty().length
    }
    private fun append(char: Char, styles: List<InlineStyle>) {
        val last = runs.lastOrNull()
        if (last != null && last.image == null && last.styles == styles) last.text.append(char)
        else runs += Run(styles, StringBuilder().append(char))
        length++
    }
    fun nodes(): List<InlineNode> {
        val root = mutableListOf<InlineNode>()
        val lists = mutableListOf(root)
        val openStyles = mutableListOf<InlineStyle>()
        fun closeStyle() {
            val children = lists.removeAt(lists.lastIndex).toList()
            val style = openStyles.removeAt(openStyles.lastIndex)
            val node = when (style.kind) {
                "em" -> InlineNode.Emphasis(children)
                "strong" -> InlineNode.Strong(children)
                "strike" -> InlineNode.Strikethrough(children)
                "link" -> InlineNode.Link(requireNotNull(style.link), children)
                else -> error("Unknown inline style")
            }
            lists.last().add(node)
        }
        // Build one tree in linear time. Repeatedly merging immutable sibling trees would make
        // alternating emphasis in a long paragraph quadratic in the number of inline elements.
        for (run in runs) {
            var common = 0
            while (common < openStyles.size && common < run.styles.size && openStyles[common] == run.styles[common]) common++
            while (openStyles.size > common) closeStyle()
            for (index in common until run.styles.size) {
                openStyles += run.styles[index]
                lists.add(mutableListOf())
            }
            lists.last().add(run.image ?: InlineNode.Text(run.text.toString()))
        }
        while (openStyles.isNotEmpty()) closeStyle()
        return root
    }
}
private val TEXT_BLOCK_TAGS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "li", "dt", "dd")
private val BLOCK_TAGS = TEXT_BLOCK_TAGS + setOf("body", "section", "article", "div", "blockquote", "ul", "ol", "dl", "table", "tr", "td", "th", "figure", "figcaption", "aside", "header", "footer", "nav")
private val DIVISION_TYPES = setOf("part", "volume", "chapter", "prologue", "epilogue", "preface", "foreword", "introduction")
private val DIVISION_LABEL = Regex("^\\s*(?:chapter|глава|part|часть|volume|том|book|книга)(?:\\s|[.:—–-]|$)", RegexOption.IGNORE_CASE)
