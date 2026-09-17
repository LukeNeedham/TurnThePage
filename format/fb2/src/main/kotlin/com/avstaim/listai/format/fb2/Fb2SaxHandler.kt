package com.avstaim.listai.format.fb2

import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookBinaryContent
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.BookImage
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.BookMetadata
import com.avstaim.listai.core.model.BookNote
import com.avstaim.listai.core.model.BookPoemStanza
import com.avstaim.listai.core.model.BookSequence
import com.avstaim.listai.core.model.BookTocEntry
import com.avstaim.listai.core.model.ImageId
import com.avstaim.listai.core.model.InlineNode
import com.avstaim.listai.core.model.LinkTarget
import com.avstaim.listai.core.model.NoteId
import com.avstaim.listai.core.model.ParagraphRole
import com.avstaim.listai.core.model.QuoteRole
import java.util.ArrayDeque
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.CancellationException
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2

internal class Fb2SaxHandler(
    private val limits: Fb2ParserLimits,
) : DefaultHandler2() {
    private val elements = mutableListOf<String>()
    private val mainBlocks = mutableListOf<RawBlock>()
    private val tableOfContents = mutableListOf<RawTocEntry>()
    private val notes = mutableListOf<RawNote>()
    private val binaries = mutableListOf<RawBinary>()
    private val anchorsToBlockKey = linkedMapOf<String, String>()
    private val noteIdBySourceId = linkedMapOf<String, String>()
    private val imageIdBySourceId = linkedMapOf<String, String>()
    private val usedNoteIds = UniqueValueAllocator("note")
    private val usedImageIds = UniqueValueAllocator("image")
    private val blockIds = SequentialBlockIdAllocator(limits.maxBlocks)

    private var rootSeen = false
    private var bodySeen = false
    private var body: BodyContext? = null
    private val sections = ArrayDeque<SectionContext>()
    private val quotes = ArrayDeque<QuoteContext>()
    private var poem: PoemContext? = null
    private var activeTitle: TitleContext? = null
    private var inlineCapture: InlineCapture? = null

    private var scalarCapture: ScalarCapture? = null
    private var author: AuthorContext? = null
    private var annotationDepth: Int? = null
    private val annotationText = StringBuilder()
    private var binary: BinaryContext? = null
    private var expandedCharacterCount = 0L
    private var elementCount = 0L
    private var totalBinaryEncodedCharacters = 0L

    private var title: String? = null
    private val authors = mutableListOf<String>()
    private var language: String? = null
    private val genres = mutableListOf<String>()
    private var annotation: String? = null
    private var sequence: RawSequence? = null
    private var coverSourceId: String? = null
    private var documentId: String? = null

    override fun startDocument() {
        resetTransientState()
    }

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        checkCancellation()
        val name = elementName(localName, qName)
        elements += name
        val depth = elements.size
        if (depth > limits.maxXmlDepth) {
            throw SAXException(
                BookParseException.ResourceLimitExceeded("XML nesting depth", limits.maxXmlDepth.toLong()),
            )
        }
        elementCount++
        if (elementCount > limits.maxXmlElements) {
            throw SAXException(
                BookParseException.ResourceLimitExceeded("XML element count", limits.maxXmlElements),
            )
        }

        if (depth == 1) {
            if (name != "fictionbook") {
                throw SAXException("The XML root is not FictionBook")
            }
            rootSeen = true
        }

        binary?.let { return }

        val currentBody = body
        if (currentBody == null) {
            when {
                name == "body" -> startBody(depth, attributes)
                name == "binary" -> startBinary(depth, attributes)
                else -> startMetadataElement(name, depth, attributes)
            }
            return
        }

        inlineCapture?.let { capture ->
            capture.startNested(name, depth, attributes)
            return
        }

        poem?.let { currentPoem ->
            startPoemElement(currentPoem, name, depth, attributes)
            return
        }

        when (name) {
            "section" -> sections.addLast(
                SectionContext(
                    depth = depth,
                    sourceId = attributes.sourceId(),
                ),
            )

            "title" -> activeTitle = TitleContext(
                depth = depth,
                level = sections.size.coerceIn(1, 6),
                sourceId = attributes.sourceId(),
            )

            "p" -> startInline(
                rootName = name,
                depth = depth,
                sourceId = attributes.sourceId(),
                destination = activeTitle?.let(InlineDestination::Title)
                    ?: InlineDestination.Block(ParagraphRole.BODY),
            )

            "subtitle" -> startInline(
                rootName = name,
                depth = depth,
                sourceId = attributes.sourceId(),
                destination = activeTitle?.let(InlineDestination::Title)
                    ?: InlineDestination.Block(ParagraphRole.SUBTITLE),
            )

            "text-author" -> startInline(
                rootName = name,
                depth = depth,
                sourceId = attributes.sourceId(),
                destination = InlineDestination.Block(ParagraphRole.TEXT_AUTHOR),
            )

            "v" -> startInline(
                rootName = name,
                depth = depth,
                sourceId = attributes.sourceId(),
                destination = InlineDestination.Block(ParagraphRole.VERSE),
            )

            "cite", "epigraph" -> quotes.addLast(
                QuoteContext(
                    depth = depth,
                    sourceId = attributes.sourceId(),
                    role = if (name == "epigraph") QuoteRole.EPIGRAPH else QuoteRole.CITE,
                ),
            )

            "poem" -> poem = PoemContext(depth = depth, sourceId = attributes.sourceId())
            "empty-line" -> addBlock(RawBlock.Spacer(blockIds.next(), attributes.sourceIds()))
            "image" -> addBlockImage(attributes)
        }
    }

    override fun characters(characters: CharArray, start: Int, length: Int) {
        checkCancellation()
        if (length <= 0) return
        expandedCharacterCount = checkedCharacterCount(expandedCharacterCount, length)

        val value = String(characters, start, length)
        binary?.append(value, limits.maxBinaryEncodedCharacters)
        scalarCapture?.text?.append(value)
        if (annotationDepth != null) annotationText.append(value)
        inlineCapture?.appendText(value)
        if (inlineCapture == null) {
            poem?.activeTitle?.looseText?.append(value)
                ?: activeTitle?.looseText?.append(value)
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        val name = elementName(localName, qName)
        val depth = elements.size

        binary?.let { currentBinary ->
            if (depth == currentBinary.depth && name == "binary") finishBinary(currentBinary)
            elements.removeAt(elements.lastIndex)
            return
        }

        inlineCapture?.let { capture ->
            if (depth == capture.rootDepth && name == capture.rootName) {
                finishInline(capture)
                inlineCapture = null
            } else {
                capture.endNested(name, depth)
            }
            elements.removeAt(elements.lastIndex)
            return
        }

        if (body == null) {
            finishMetadataElement(name, depth)
            elements.removeAt(elements.lastIndex)
            return
        }

        poem?.let { currentPoem ->
            if (finishPoemElement(currentPoem, name, depth)) {
                elements.removeAt(elements.lastIndex)
                return
            }
        }

        when {
            activeTitle?.let { it.depth == depth && name == "title" } == true -> {
                finishTitle(requireNotNull(activeTitle))
                activeTitle = null
            }

            (name == "cite" || name == "epigraph") && quotes.lastOrNull()?.depth == depth ->
                finishQuote(quotes.removeLast())

            name == "section" && sections.lastOrNull()?.depth == depth ->
                finishSection(sections.removeLast())

            name == "body" && body?.depth == depth -> finishBody(requireNotNull(body))
        }

        elements.removeAt(elements.lastIndex)
    }

    override fun endDocument() {
        if (!rootSeen) throw SAXException("Missing FictionBook root")
    }

    override fun error(exception: SAXParseException) {
        throw exception
    }

    override fun fatalError(exception: SAXParseException) {
        throw exception
    }

    override fun startDTD(name: String?, publicId: String?, systemId: String?) {
        throw SAXException("DOCTYPE is forbidden in FB2 documents")
    }

    fun toBookDocument(contentDigest: String): BookDocument {
        val normalizedTitle = title.normalizedText()
        if (normalizedTitle == null) throw BookParseException.MissingTitle()
        if (!bodySeen || mainBlocks.none(RawBlock::isReadable)) {
            throw BookParseException.MissingReadableBody()
        }

        val allBlocks = buildList {
            addAll(mainBlocks)
            notes.forEach { addAll(it.blocks) }
        }
        val locationsByKey = calculateBlockLocations(allBlocks)
        val locationsBySourceId = linkedMapOf<String, BookLocation>()
        anchorsToBlockKey.forEach { (sourceId, key) ->
            locationsByKey[key]?.let { location -> locationsBySourceId[sourceId] = location }
        }

        val converter = ModelConverter(
            locationsByKey = locationsByKey,
            locationsBySourceId = locationsBySourceId,
            noteIdBySourceId = noteIdBySourceId,
            imageIdBySourceId = imageIdBySourceId,
        )
        val modelBlocks = mainBlocks.map(converter::block)
        val modelNotes = notes.map { note ->
            BookNote(
                id = NoteId(note.id),
                blocks = note.blocks.map(converter::block),
                title = note.title.normalizedText(),
            )
        }
        val modelToc = tableOfContents.mapNotNull { converter.toc(it) }
        val modelImages = binaries.map { image ->
            BookImage(
                id = ImageId(image.id),
                mediaType = image.mediaType.normalizedText(),
                content = LazyBase64Content(image.encoded),
            )
        }
        val normalizedCoverId = coverSourceId?.let(converter::imageId)
        val normalizedDocumentId = documentId.normalizedText()

        return BookDocument(
            id = BookId("sha256:$contentDigest"),
            metadata = BookMetadata(
                title = normalizedTitle,
                authors = authors.mapNotNull(String::normalizedText).distinct(),
                language = language.normalizedText(),
                genres = genres.mapNotNull(String::normalizedText).distinct(),
                annotation = annotation.normalizedMultilineText(),
                sequence = sequence?.name.normalizedText()?.let { sequenceName ->
                    BookSequence(sequenceName, sequence?.number)
                },
                coverImageId = normalizedCoverId,
                documentId = normalizedDocumentId,
            ),
            blocks = modelBlocks,
            tableOfContents = modelToc,
            notes = modelNotes,
            images = modelImages,
            locationsBySourceId = locationsBySourceId,
        )
    }

    private fun startMetadataElement(name: String, depth: Int, attributes: Attributes) {
        when {
            name == "annotation" && hasAncestor("title-info") -> {
                annotationDepth = depth
                annotationText.clear()
            }

            name == "author" && hasAncestor("title-info") -> author = AuthorContext(depth)
            name == "book-title" && hasAncestor("title-info") -> startScalar(ScalarKind.BOOK_TITLE, depth)
            name == "genre" && hasAncestor("title-info") -> startScalar(ScalarKind.GENRE, depth)
            name == "lang" && hasAncestor("title-info") -> startScalar(ScalarKind.LANGUAGE, depth)
            name == "id" && parentName() == "document-info" -> startScalar(ScalarKind.DOCUMENT_ID, depth)
            author != null && name == "first-name" -> startScalar(ScalarKind.AUTHOR_FIRST, depth)
            author != null && name == "middle-name" -> startScalar(ScalarKind.AUTHOR_MIDDLE, depth)
            author != null && name == "last-name" -> startScalar(ScalarKind.AUTHOR_LAST, depth)
            author != null && name == "nickname" -> startScalar(ScalarKind.AUTHOR_NICKNAME, depth)
            name == "sequence" && hasAncestor("title-info") && sequence == null -> {
                val sequenceName = attributes.value("name").normalizedText()
                if (sequenceName != null) {
                    sequence = RawSequence(
                        name = sequenceName,
                        number = attributes.value("number")?.trim()?.toIntOrNull()?.takeIf { it >= 0 },
                    )
                }
            }

            name == "image" && hasAncestor("coverpage") -> {
                coverSourceId = attributes.href()?.normalizedReference()
            }
        }
    }

    private fun finishMetadataElement(name: String, depth: Int) {
        scalarCapture?.takeIf { it.depth == depth }?.let { capture ->
            val value = capture.text.toString().normalizedText()
            when (capture.kind) {
                ScalarKind.BOOK_TITLE -> title = value
                ScalarKind.GENRE -> value?.let(genres::add)
                ScalarKind.LANGUAGE -> language = value
                ScalarKind.DOCUMENT_ID -> documentId = value
                ScalarKind.AUTHOR_FIRST -> author?.first = value
                ScalarKind.AUTHOR_MIDDLE -> author?.middle = value
                ScalarKind.AUTHOR_LAST -> author?.last = value
                ScalarKind.AUTHOR_NICKNAME -> author?.nickname = value
            }
            scalarCapture = null
        }

        if (name == "p" && annotationDepth != null) annotationText.append('\n')
        if (name == "annotation" && annotationDepth == depth) {
            annotation = annotationText.toString().normalizedMultilineText()
            annotationDepth = null
        }
        if (name == "author" && author?.depth == depth) {
            author?.displayName()?.let(authors::add)
            author = null
        }
    }

    private fun startScalar(kind: ScalarKind, depth: Int) {
        if (scalarCapture == null) scalarCapture = ScalarCapture(kind, depth)
    }

    private fun startBody(depth: Int, attributes: Attributes) {
        bodySeen = true
        val bodyName = attributes.value("name").normalizedText()
        body = BodyContext(
            depth = depth,
            sourceId = attributes.sourceId(),
            isNotes = bodyName?.lowercase(Locale.ROOT) in NOTE_BODY_NAMES,
        )
    }

    private fun finishBody(currentBody: BodyContext) {
        if (sections.isNotEmpty() || quotes.isNotEmpty() || poem != null) {
            throw SAXException("Unbalanced FB2 body structure")
        }
        if (currentBody.isNotes) {
            if (currentBody.blocks.isNotEmpty()) {
                addNote(
                    sourceIds = listOfNotNull(currentBody.sourceId),
                    title = currentBody.blocks.firstHeadingText(),
                    blocks = currentBody.blocks,
                )
            }
        } else {
            mainBlocks += currentBody.blocks
            tableOfContents += currentBody.toc
        }
        body = null
        activeTitle = null
    }

    private fun finishSection(section: SectionContext) {
        val firstBlockKey = section.blocks.firstOrNull()?.key
        if (firstBlockKey != null) {
            section.sourceId?.let { anchorsToBlockKey.putIfAbsent(it, firstBlockKey) }
        }
        val ownToc = if (!requireNotNull(body).isNotes && section.title.normalizedText() != null &&
            firstBlockKey != null
        ) {
            RawTocEntry(
                title = requireNotNull(section.title.normalizedText()),
                targetBlockKey = firstBlockKey,
                children = section.toc,
            )
        } else {
            null
        }

        val parent = sections.lastOrNull()
        if (parent != null) {
            parent.blocks += section.blocks
            parent.noteAliases += section.noteAliases
            section.sourceId?.let(parent.noteAliases::add)
            if (!requireNotNull(body).isNotes) {
                if (ownToc != null) parent.toc += ownToc else parent.toc += section.toc
            }
        } else if (requireNotNull(body).isNotes) {
            addNote(
                sourceIds = buildList {
                    section.sourceId?.let(::add)
                    addAll(section.noteAliases)
                },
                title = section.title,
                blocks = section.blocks,
            )
        } else {
            requireNotNull(body).blocks += section.blocks
            if (ownToc != null) requireNotNull(body).toc += ownToc
            else requireNotNull(body).toc += section.toc
        }
    }

    private fun finishTitle(currentTitle: TitleContext) {
        val loose = currentTitle.looseText.toString().normalizedText()
        if (loose != null && currentTitle.lines.isEmpty()) {
            currentTitle.lines += RawLine(listOf(RawInline.Text(loose)), currentTitle.sourceId)
        }
        currentTitle.lines.forEachIndexed { index, line ->
            val normalized = normalizeInline(line.content)
            if (normalized.isNotEmpty()) {
                addBlock(
                    RawBlock.Heading(
                        key = blockIds.next(),
                        sourceIds = listOfNotNull(line.sourceId, currentTitle.sourceId.takeIf { index == 0 }),
                        level = currentTitle.level,
                        content = normalized,
                    ),
                )
            }
        }
        sections.lastOrNull()?.let { section ->
            if (section.title == null) {
                section.title = currentTitle.lines
                    .joinToString(separator = " ") { it.content.rawPlainText() }
                    .normalizedText()
                    ?: loose
            }
        }
    }

    private fun finishQuote(quote: QuoteContext) {
        addBlock(
            RawBlock.Quote(
                key = blockIds.next(),
                sourceIds = listOfNotNull(quote.sourceId),
                blocks = quote.blocks,
                role = quote.role,
            ),
        )
    }

    private fun startPoemElement(
        currentPoem: PoemContext,
        name: String,
        depth: Int,
        attributes: Attributes,
    ) {
        when (name) {
            "title" -> currentPoem.activeTitle = TitleContext(
                depth = depth,
                level = sections.size.coerceIn(1, 6),
                sourceId = attributes.sourceId(),
            )

            "stanza" -> currentPoem.stanza = StanzaContext(depth)
            "v" -> startInline(
                rootName = name,
                depth = depth,
                sourceId = attributes.sourceId(),
                destination = InlineDestination.PoemVerse(currentPoem),
            )

            "p" -> startInline(
                rootName = name,
                depth = depth,
                sourceId = attributes.sourceId(),
                destination = currentPoem.activeTitle?.let(InlineDestination::PoemTitle)
                    ?: InlineDestination.PoemVerse(currentPoem),
            )

            "subtitle" -> startInline(
                rootName = name,
                depth = depth,
                sourceId = attributes.sourceId(),
                destination = InlineDestination.PoemStandaloneTitle(currentPoem),
            )

            "text-author" -> startInline(
                rootName = name,
                depth = depth,
                sourceId = attributes.sourceId(),
                destination = InlineDestination.PoemAuthor(currentPoem),
            )

            "empty-line" -> currentPoem.stanza?.lines?.add(RawLine(emptyList(), attributes.sourceId()))
        }
    }

    /** Returns true when the end event was fully handled by the poem state machine. */
    private fun finishPoemElement(currentPoem: PoemContext, name: String, depth: Int): Boolean {
        val poemTitle = currentPoem.activeTitle
        if (poemTitle != null && poemTitle.depth == depth && name == "title") {
            poemTitle.looseText.toString().normalizedText()?.let { loose ->
                if (poemTitle.lines.isEmpty()) {
                    poemTitle.lines += RawLine(listOf(RawInline.Text(loose)), poemTitle.sourceId)
                }
            }
            currentPoem.title += poemTitle.lines.map { RawLine(normalizeInline(it.content), it.sourceId) }
            currentPoem.anchorSourceIds += poemTitle.lines.mapNotNull(RawLine::sourceId)
            poemTitle.sourceId?.let(currentPoem.anchorSourceIds::add)
            currentPoem.activeTitle = null
            return true
        }
        if (name == "stanza" && currentPoem.stanza?.depth == depth) {
            val stanza = requireNotNull(currentPoem.stanza)
            if (stanza.lines.isNotEmpty()) currentPoem.stanzas += stanza.lines
            currentPoem.stanza = null
            return true
        }
        if (name == "poem" && currentPoem.depth == depth) {
            currentPoem.stanza?.takeIf { it.lines.isNotEmpty() }?.let { currentPoem.stanzas += it.lines }
            if (currentPoem.stanzas.isNotEmpty()) {
                addBlock(
                    RawBlock.Poem(
                        key = blockIds.next(),
                        sourceIds = buildList {
                            currentPoem.sourceId?.let(::add)
                            addAll(currentPoem.anchorSourceIds)
                        }.distinct(),
                        stanzas = currentPoem.stanzas,
                        title = currentPoem.title,
                        textAuthors = currentPoem.textAuthors,
                    ),
                )
            }
            poem = null
            return true
        }
        return false
    }

    private fun startInline(
        rootName: String,
        depth: Int,
        sourceId: String?,
        destination: InlineDestination,
    ) {
        inlineCapture = InlineCapture(rootName, depth, sourceId, destination)
    }

    private fun finishInline(capture: InlineCapture) {
        val content = normalizeInline(capture.finish())
        when (val destination = capture.destination) {
            is InlineDestination.Block -> addBlock(
                RawBlock.Paragraph(
                    key = blockIds.next(),
                    sourceIds = listOfNotNull(capture.sourceId),
                    content = content,
                    role = destination.role,
                ),
            )

            is InlineDestination.Title -> destination.title.lines += RawLine(content, capture.sourceId)
            is InlineDestination.PoemTitle ->
                destination.title.lines += RawLine(content, capture.sourceId)

            is InlineDestination.PoemStandaloneTitle -> {
                destination.poem.title += RawLine(content, capture.sourceId)
                capture.sourceId?.let(destination.poem.anchorSourceIds::add)
            }

            is InlineDestination.PoemVerse -> {
                val stanza = destination.poem.stanza
                    ?: StanzaContext(capture.rootDepth).also { destination.poem.stanza = it }
                stanza.lines += RawLine(content, capture.sourceId)
                capture.sourceId?.let(destination.poem.anchorSourceIds::add)
            }

            is InlineDestination.PoemAuthor -> {
                destination.poem.textAuthors += RawLine(content, capture.sourceId)
                capture.sourceId?.let(destination.poem.anchorSourceIds::add)
            }
        }
    }

    private fun addBlockImage(attributes: Attributes) {
        val reference = attributes.href()?.normalizedReference() ?: return
        addBlock(
            RawBlock.Image(
                key = blockIds.next(),
                sourceIds = attributes.sourceIds(),
                sourceImageId = reference,
                alternativeText = attributes.value("alt").normalizedText()
                    ?: attributes.value("title").normalizedText(),
            ),
        )
    }

    private fun addBlock(block: RawBlock) {
        block.sourceIds.forEach { sourceId -> anchorsToBlockKey.putIfAbsent(sourceId, block.key) }
        when {
            quotes.isNotEmpty() -> quotes.last.blocks += block
            sections.isNotEmpty() -> sections.last.blocks += block
            else -> requireNotNull(body).blocks += block
        }
    }

    private fun addNote(sourceIds: List<String>, title: String?, blocks: List<RawBlock>) {
        if (blocks.isEmpty()) return
        val normalizedSourceIds = sourceIds.mapNotNull(String::normalizedSourceId).distinct()
        val noteId = usedNoteIds.allocate(normalizedSourceIds.firstOrNull())
        normalizedSourceIds.forEach { noteIdBySourceId.putIfAbsent(it, noteId) }
        notes += RawNote(noteId, normalizedSourceIds, title, blocks.toList())
    }

    private fun startBinary(depth: Int, attributes: Attributes) {
        binary = BinaryContext(
            depth = depth,
            sourceId = attributes.sourceId(),
            mediaType = attributes.value("content-type").normalizedText(),
        )
    }

    private fun finishBinary(currentBinary: BinaryContext) {
        if (binaries.size >= limits.maxBinaries) {
            throw SAXException(
                BookParseException.ResourceLimitExceeded("binary count", limits.maxBinaries.toLong()),
            )
        }
        val encodedLength = currentBinary.text.length.toLong()
        if (encodedLength > limits.maxTotalBinaryEncodedCharacters - totalBinaryEncodedCharacters) {
            throw SAXException(
                BookParseException.ResourceLimitExceeded(
                    "total encoded binary payload",
                    limits.maxTotalBinaryEncodedCharacters,
                ),
            )
        }
        totalBinaryEncodedCharacters += encodedLength
        val id = usedImageIds.allocate(currentBinary.sourceId)
        currentBinary.sourceId?.let { imageIdBySourceId.putIfAbsent(it, id) }
        binaries += RawBinary(id, currentBinary.mediaType, currentBinary.text.toString())
        binary = null
    }

    private fun checkedCharacterCount(current: Long, additional: Int): Long {
        val next = current + additional.toLong()
        if (next < current || next > limits.maxExpandedXmlCharacters) {
            throw SAXException(
                BookParseException.ResourceLimitExceeded(
                    "expanded XML character data",
                    limits.maxExpandedXmlCharacters,
                ),
            )
        }
        return next
    }

    private fun hasAncestor(name: String): Boolean = elements.dropLast(1).contains(name)

    private fun parentName(): String? = elements.getOrNull(elements.lastIndex - 1)

    private fun resetTransientState() {
        elements.clear()
        rootSeen = false
        bodySeen = false
        expandedCharacterCount = 0L
        elementCount = 0L
        totalBinaryEncodedCharacters = 0L
    }
}

private data class BodyContext(
    val depth: Int,
    val sourceId: String?,
    val isNotes: Boolean,
    val blocks: MutableList<RawBlock> = mutableListOf(),
    val toc: MutableList<RawTocEntry> = mutableListOf(),
)

private data class SectionContext(
    val depth: Int,
    val sourceId: String?,
    val blocks: MutableList<RawBlock> = mutableListOf(),
    val toc: MutableList<RawTocEntry> = mutableListOf(),
    val noteAliases: MutableList<String> = mutableListOf(),
    var title: String? = null,
)

private data class QuoteContext(
    val depth: Int,
    val sourceId: String?,
    val role: QuoteRole,
    val blocks: MutableList<RawBlock> = mutableListOf(),
)

private data class PoemContext(
    val depth: Int,
    val sourceId: String?,
    val stanzas: MutableList<List<RawLine>> = mutableListOf(),
    val title: MutableList<RawLine> = mutableListOf(),
    val textAuthors: MutableList<RawLine> = mutableListOf(),
    val anchorSourceIds: MutableList<String> = mutableListOf(),
    var stanza: StanzaContext? = null,
    var activeTitle: TitleContext? = null,
)

private data class StanzaContext(
    val depth: Int,
    val lines: MutableList<RawLine> = mutableListOf(),
)

private data class TitleContext(
    val depth: Int,
    val level: Int,
    val sourceId: String?,
    val lines: MutableList<RawLine> = mutableListOf(),
    val looseText: StringBuilder = StringBuilder(),
)

private data class RawLine(
    val content: List<RawInline>,
    val sourceId: String?,
)

private sealed interface InlineDestination {
    data class Block(val role: ParagraphRole) : InlineDestination

    data class Title(val title: TitleContext) : InlineDestination

    data class PoemTitle(val title: TitleContext) : InlineDestination

    data class PoemStandaloneTitle(val poem: PoemContext) : InlineDestination

    data class PoemVerse(val poem: PoemContext) : InlineDestination

    data class PoemAuthor(val poem: PoemContext) : InlineDestination
}

private class InlineCapture(
    val rootName: String,
    val rootDepth: Int,
    val sourceId: String?,
    val destination: InlineDestination,
) {
    private val root = mutableListOf<RawInline>()
    private val frames = ArrayDeque<InlineFrame>()

    fun appendText(value: String) {
        if (value.isEmpty()) return
        currentNodes().appendText(value)
    }

    fun startNested(name: String, depth: Int, attributes: Attributes) {
        when (name) {
            "emphasis", "strong", "strikethrough", "a", "link" -> frames.addLast(
                InlineFrame(
                    name = name,
                    depth = depth,
                    href = if (name == "a" || name == "link") attributes.href() else null,
                ),
            )

            "image" -> attributes.href()?.normalizedReference()?.let { imageId ->
                currentNodes() += RawInline.Image(
                    sourceImageId = imageId,
                    alternativeText = attributes.value("alt").normalizedText()
                        ?: attributes.value("title").normalizedText(),
                )
            }
        }
    }

    fun endNested(name: String, depth: Int) {
        val frame = frames.lastOrNull() ?: return
        if (frame.depth != depth || frame.name != name) return
        frames.removeLast()
        val child = when (name) {
            "emphasis" -> RawInline.Emphasis(frame.nodes)
            "strong" -> RawInline.Strong(frame.nodes)
            "strikethrough" -> RawInline.Strikethrough(frame.nodes)
            "a", "link" -> RawInline.Link(frame.href, frame.nodes)
            else -> return
        }
        currentNodes() += child
    }

    fun finish(): List<RawInline> {
        while (frames.isNotEmpty()) {
            val frame = frames.removeLast()
            currentNodes() += RawInline.Text(frame.nodes.rawPlainText())
        }
        return root
    }

    private fun currentNodes(): MutableList<RawInline> = frames.lastOrNull()?.nodes ?: root
}

private data class InlineFrame(
    val name: String,
    val depth: Int,
    val href: String?,
    val nodes: MutableList<RawInline> = mutableListOf(),
)

private enum class ScalarKind {
    BOOK_TITLE,
    GENRE,
    LANGUAGE,
    DOCUMENT_ID,
    AUTHOR_FIRST,
    AUTHOR_MIDDLE,
    AUTHOR_LAST,
    AUTHOR_NICKNAME,
}

private data class ScalarCapture(
    val kind: ScalarKind,
    val depth: Int,
    val text: StringBuilder = StringBuilder(),
)

private data class AuthorContext(
    val depth: Int,
    var first: String? = null,
    var middle: String? = null,
    var last: String? = null,
    var nickname: String? = null,
) {
    fun displayName(): String? {
        val structured = listOfNotNull(first, middle, last).joinToString(" ").normalizedText()
        return structured ?: nickname.normalizedText()
    }
}

private data class RawSequence(val name: String, val number: Int?)

private data class BinaryContext(
    val depth: Int,
    val sourceId: String?,
    val mediaType: String?,
    val text: StringBuilder = StringBuilder(),
) {
    fun append(value: String, limit: Int) {
        if (value.length > limit - text.length) {
            throw SAXException(
                BookParseException.ResourceLimitExceeded(
                    "encoded binary payload",
                    limit.toLong(),
                ),
            )
        }
        text.append(value)
    }
}

private sealed interface RawBlock {
    val key: String
    val sourceIds: List<String>

    data class Paragraph(
        override val key: String,
        override val sourceIds: List<String>,
        val content: List<RawInline>,
        val role: ParagraphRole,
    ) : RawBlock

    data class Heading(
        override val key: String,
        override val sourceIds: List<String>,
        val level: Int,
        val content: List<RawInline>,
    ) : RawBlock

    data class Image(
        override val key: String,
        override val sourceIds: List<String>,
        val sourceImageId: String,
        val alternativeText: String?,
    ) : RawBlock

    data class Quote(
        override val key: String,
        override val sourceIds: List<String>,
        val blocks: List<RawBlock>,
        val role: QuoteRole,
    ) : RawBlock

    data class Poem(
        override val key: String,
        override val sourceIds: List<String>,
        val stanzas: List<List<RawLine>>,
        val title: List<RawLine>,
        val textAuthors: List<RawLine>,
    ) : RawBlock

    data class Spacer(
        override val key: String,
        override val sourceIds: List<String>,
    ) : RawBlock
}

private sealed interface RawInline {
    data class Text(val value: String) : RawInline
    data class Emphasis(val children: List<RawInline>) : RawInline
    data class Strong(val children: List<RawInline>) : RawInline
    data class Strikethrough(val children: List<RawInline>) : RawInline
    data class Link(val href: String?, val children: List<RawInline>) : RawInline
    data class Image(val sourceImageId: String, val alternativeText: String?) : RawInline
}

private data class RawTocEntry(
    val title: String,
    val targetBlockKey: String,
    val children: List<RawTocEntry>,
)

private data class RawNote(
    val id: String,
    val sourceIds: List<String>,
    val title: String?,
    val blocks: List<RawBlock>,
)

private data class RawBinary(
    val id: String,
    val mediaType: String?,
    val encoded: String,
)

private class SequentialBlockIdAllocator(private val limit: Int) {
    private var next = 1L

    fun next(): String {
        if (next > limit) {
            throw SAXException(BookParseException.ResourceLimitExceeded("book block count", limit.toLong()))
        }
        return "fb2-block-${next++.toString().padStart(8, '0')}"
    }
}

private class UniqueValueAllocator(private val fallbackPrefix: String) {
    private val used = mutableSetOf<String>()
    private var generated = 1

    fun allocate(preferred: String?): String {
        val base = preferred.normalizedSourceId() ?: "$fallbackPrefix-${generated++}"
        if (used.add(base)) return base
        var suffix = 2
        while (!used.add("$base~$suffix")) suffix++
        return "$base~$suffix"
    }
}

private class ModelConverter(
    private val locationsByKey: Map<String, BookLocation>,
    private val locationsBySourceId: Map<String, BookLocation>,
    private val noteIdBySourceId: Map<String, String>,
    private val imageIdBySourceId: Map<String, String>,
) {
    fun block(raw: RawBlock): BookBlock = when (raw) {
        is RawBlock.Paragraph -> BookBlock.Paragraph(
            id = BlockId(raw.key),
            content = inline(raw.content),
            role = raw.role,
        )

        is RawBlock.Heading -> BookBlock.Heading(
            id = BlockId(raw.key),
            level = raw.level,
            content = inline(raw.content),
        )

        is RawBlock.Image -> BookBlock.Image(
            id = BlockId(raw.key),
            imageId = imageId(raw.sourceImageId),
            alternativeText = raw.alternativeText,
        )

        is RawBlock.Quote -> BookBlock.Quote(
            id = BlockId(raw.key),
            blocks = raw.blocks.map(::block),
            role = raw.role,
        )

        is RawBlock.Poem -> BookBlock.Poem(
            id = BlockId(raw.key),
            stanzas = raw.stanzas.map { stanza ->
                BookPoemStanza(stanza.map { line -> inline(line.content) })
            },
            title = raw.title.map { inline(it.content) },
            textAuthors = raw.textAuthors.map { inline(it.content) },
        )

        is RawBlock.Spacer -> BookBlock.Spacer(BlockId(raw.key))
    }

    fun toc(raw: RawTocEntry): BookTocEntry? {
        val location = locationsByKey[raw.targetBlockKey] ?: return null
        return BookTocEntry(
            title = raw.title,
            location = location,
            children = raw.children.mapNotNull(::toc),
        )
    }

    fun imageId(sourceId: String): ImageId =
        ImageId(imageIdBySourceId[sourceId] ?: sourceId)

    private fun inline(nodes: List<RawInline>): List<InlineNode> = nodes.flatMap(::inline)

    private fun inline(raw: RawInline): List<InlineNode> = when (raw) {
        is RawInline.Text -> listOf(InlineNode.Text(raw.value))
        is RawInline.Emphasis -> listOf(InlineNode.Emphasis(inline(raw.children)))
        is RawInline.Strong -> listOf(InlineNode.Strong(inline(raw.children)))
        is RawInline.Strikethrough -> listOf(InlineNode.Strikethrough(inline(raw.children)))
        is RawInline.Image -> listOf(
            InlineNode.Image(imageId(raw.sourceImageId), raw.alternativeText),
        )

        is RawInline.Link -> {
            val children = inline(raw.children)
            val href = raw.href?.trim().orEmpty()
            if (href.isEmpty()) {
                children
            } else {
                listOf(InlineNode.Link(linkTarget(href), children))
            }
        }
    }

    private fun linkTarget(href: String): LinkTarget {
        if (!href.startsWith('#')) return LinkTarget.External(href)
        val sourceId = href.normalizedReference()
            ?: return LinkTarget.External(href)
        noteIdBySourceId[sourceId]?.let { return LinkTarget.Note(NoteId(it)) }
        locationsBySourceId[sourceId]?.let { return LinkTarget.Internal(it) }
        return LinkTarget.UnresolvedInternal(sourceId)
    }
}

private class LazyBase64Content(encoded: String) : BookBinaryContent {
    @Volatile
    private var state: Any = Pending(encoded)

    override fun load(): ByteArray? {
        when (val snapshot = state) {
            is Loaded -> return snapshot.bytes
            Failed -> return null
        }
        return synchronized(this) {
            when (val snapshot = state) {
                is Loaded -> snapshot.bytes
                Failed -> null
                is Pending -> decode(snapshot.encoded).also { decoded ->
                    state = if (decoded == null) Failed else Loaded(decoded)
                }

                else -> null
            }
        }
    }

    private fun decode(encoded: String): ByteArray? = try {
        val compact = buildString(encoded.length) {
            encoded.forEach { character ->
                if (!character.isWhitespace()) append(character)
            }
        }
        Base64.getDecoder().decode(compact)
    } catch (_: IllegalArgumentException) {
        null
    }

    private data class Pending(val encoded: String)
    private data class Loaded(val bytes: ByteArray)
    private data object Failed
}

private fun calculateBlockLocations(blocks: List<RawBlock>): Map<String, BookLocation> {
    val result = linkedMapOf<String, BookLocation>()
    var offset = 0L

    fun visit(block: RawBlock) {
        result[block.key] = BookLocation(BlockId(block.key), 0, offset)
        if (block is RawBlock.Quote && block.blocks.isNotEmpty()) {
            block.blocks.forEach(::visit)
        } else {
            val delta = block.rawPlainText().length.toLong() + 1L
            offset = if (Long.MAX_VALUE - offset < delta) Long.MAX_VALUE else offset + delta
        }
    }

    blocks.forEach(::visit)
    return result
}

private fun RawBlock.rawPlainText(): String = when (this) {
    is RawBlock.Paragraph -> content.rawPlainText()
    is RawBlock.Heading -> content.rawPlainText()
    is RawBlock.Image -> alternativeText.orEmpty()
    is RawBlock.Quote -> blocks.joinToString("\n", transform = RawBlock::rawPlainText)
    is RawBlock.Poem -> buildList {
        title.forEach { add(it.content.rawPlainText()) }
        stanzas.forEach { stanza -> stanza.forEach { add(it.content.rawPlainText()) } }
        textAuthors.forEach { add(it.content.rawPlainText()) }
    }.joinToString("\n")

    is RawBlock.Spacer -> ""
}

private fun RawBlock.isReadable(): Boolean = when (this) {
    is RawBlock.Image -> true
    is RawBlock.Quote -> blocks.any(RawBlock::isReadable)
    is RawBlock.Poem -> stanzas.any { stanza -> stanza.any { it.content.rawPlainText().isNotBlank() } }
    is RawBlock.Spacer -> false
    else -> rawPlainText().isNotBlank()
}

private fun List<RawBlock>.firstHeadingText(): String? =
    asSequence().filterIsInstance<RawBlock.Heading>().firstOrNull()?.content?.rawPlainText()

private fun List<RawInline>.rawPlainText(): String = buildString {
    fun appendNodes(nodes: List<RawInline>) {
        nodes.forEach { node ->
            when (node) {
                is RawInline.Text -> append(node.value)
                is RawInline.Emphasis -> appendNodes(node.children)
                is RawInline.Strong -> appendNodes(node.children)
                is RawInline.Strikethrough -> appendNodes(node.children)
                is RawInline.Link -> appendNodes(node.children)
                is RawInline.Image -> append(node.alternativeText.orEmpty())
            }
        }
    }
    appendNodes(this@rawPlainText)
}

private fun normalizeInline(
    nodes: List<RawInline>,
    trimBoundaries: Boolean = true,
): List<RawInline> {
    val normalized = nodes.mapNotNull { node ->
        when (node) {
            is RawInline.Text -> RawInline.Text(node.value.replace(XML_WHITESPACE, " "))
            is RawInline.Emphasis -> normalizeInline(node.children, trimBoundaries = false)
                .takeIf(List<*>::isNotEmpty)
                ?.let(RawInline::Emphasis)

            is RawInline.Strong -> normalizeInline(node.children, trimBoundaries = false)
                .takeIf(List<*>::isNotEmpty)
                ?.let(RawInline::Strong)

            is RawInline.Strikethrough -> normalizeInline(node.children, trimBoundaries = false)
                .takeIf(List<*>::isNotEmpty)
                ?.let(RawInline::Strikethrough)

            is RawInline.Link -> normalizeInline(node.children, trimBoundaries = false)
                .let { RawInline.Link(node.href, it) }
            is RawInline.Image -> node
        }
    }.toMutableList()

    if (trimBoundaries) {
        while (normalized.firstOrNull() is RawInline.Text) {
            val first = normalized.first() as RawInline.Text
            val trimmed = first.value.trimStart()
            if (trimmed.isEmpty()) normalized.removeAt(0)
            else {
                normalized[0] = RawInline.Text(trimmed)
                break
            }
        }
        while (normalized.lastOrNull() is RawInline.Text) {
            val lastIndex = normalized.lastIndex
            val last = normalized[lastIndex] as RawInline.Text
            val trimmed = last.value.trimEnd()
            if (trimmed.isEmpty()) normalized.removeAt(lastIndex)
            else {
                normalized[lastIndex] = RawInline.Text(trimmed)
                break
            }
        }
    }

    val merged = mutableListOf<RawInline>()
    normalized.forEach { node ->
        if (node is RawInline.Text && merged.lastOrNull() is RawInline.Text) {
            val previous = merged.removeAt(merged.lastIndex) as RawInline.Text
            merged += RawInline.Text(previous.value + node.value)
        } else {
            merged += node
        }
    }
    return merged
}

private fun MutableList<RawInline>.appendText(value: String) {
    val previous = lastOrNull()
    if (previous is RawInline.Text) {
        this[lastIndex] = RawInline.Text(previous.value + value)
    } else {
        add(RawInline.Text(value))
    }
}

private fun Attributes.sourceIds(): List<String> = listOfNotNull(sourceId())

private fun Attributes.sourceId(): String? = value("id").normalizedSourceId()

private fun Attributes.href(): String? = value("href")

private fun Attributes.value(expectedLocalName: String): String? {
    for (index in 0 until length) {
        val local = getLocalName(index).orEmpty()
        val qualified = getQName(index).orEmpty().substringAfterLast(':')
        if (local.equals(expectedLocalName, ignoreCase = true) ||
            qualified.equals(expectedLocalName, ignoreCase = true)
        ) {
            return getValue(index)
        }
    }
    return null
}

private fun elementName(localName: String?, qName: String?): String =
    localName?.takeIf(String::isNotBlank)?.lowercase(Locale.ROOT)
        ?: qName.orEmpty().substringAfterLast(':').lowercase(Locale.ROOT)

private fun String?.normalizedReference(): String? = this
    ?.trim()
    ?.removePrefix("#")
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private fun String?.normalizedSourceId(): String? = normalizedReference()

private fun String?.normalizedText(): String? = this
    ?.replace(XML_WHITESPACE, " ")
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private fun String?.normalizedMultilineText(): String? = this
    ?.lineSequence()
    ?.mapNotNull { it.normalizedText() }
    ?.joinToString("\n")
    ?.takeIf(String::isNotEmpty)

private val XML_WHITESPACE = Regex("[\\t\\n\\r ]+")

private val NOTE_BODY_NAMES = setOf("notes", "footnotes", "comments")

private fun checkCancellation() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("FB2 parsing was cancelled")
}
