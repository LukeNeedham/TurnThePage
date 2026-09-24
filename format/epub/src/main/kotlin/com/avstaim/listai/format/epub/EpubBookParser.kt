package com.avstaim.listai.format.epub

import com.avstaim.listai.core.model.*
import com.avstaim.listai.format.fb2.BookParseException
import com.avstaim.listai.format.fb2.BookParser
import java.io.*
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** Ceilings apply to both ZIP metadata and actual consumed bytes, including image resources. */
data class EpubParserLimits(
    val maxArchiveBytes: Long = 128L * 1024 * 1024,
    val maxArchiveEntries: Int = 4_096,
    val maxArchiveEntryBytes: Long = 64L * 1024 * 1024,
    val maxInflatedArchiveBytes: Long = 256L * 1024 * 1024,
    val maxXmlBytes: Long = 64L * 1024 * 1024,
    val maxStylesheetBytes: Long = 2L * 1024 * 1024,
    val maxTotalStylesheetBytes: Long = 8L * 1024 * 1024,
    val maxXmlCharacters: Long = 96L * 1024 * 1024,
    val maxXmlDepth: Int = 256,
    val maxXmlElements: Long = 2_000_000,
    val maxBlocks: Int = 250_000,
    val maxImageBytes: Long = 24L * 1024 * 1024,
    val maxTotalImageBytes: Long = 64L * 1024 * 1024,
    val maxCompressionRatio: Int = 500,
) {
    init {
        require(listOf(maxArchiveBytes, maxArchiveEntryBytes, maxInflatedArchiveBytes,
            maxXmlBytes, maxXmlCharacters, maxXmlElements, maxImageBytes, maxTotalImageBytes,
            maxStylesheetBytes, maxTotalStylesheetBytes).all { it > 0 })
        require(maxArchiveEntries > 0 && maxXmlDepth > 0 && maxBlocks > 0 && maxCompressionRatio > 0)
    }
}

/** EPUB 2/3 reflow content, parsed with SAX in package spine order. No web resources are fetched. */
class EpubBookParser(
    private val limits: EpubParserLimits = EpubParserLimits(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BookParser {
    override fun supports(fileName: String): Boolean =
        fileName.trim().lowercase(Locale.ROOT).endsWith(".epub")

    override suspend fun parse(input: InputStream, fileName: String?): BookDocument = runInterruptible(ioDispatcher) {
        val temporary = try { File.createTempFile("listai-epub-", ".zip") } catch (error: IOException) {
            checkEpubCancellation()
            throw BookParseException.MalformedDocument(error)
        }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            temporary.outputStream().use { output ->
                input.copyBounded(output, limits.maxArchiveBytes, "EPUB archive") { bytes, count ->
                    digest.update(bytes, 0, count)
                }
            }
            ZipFile(temporary).use { zip ->
                val archive = EpubArchive(zip, limits)
                val xml = EpubXml(limits)
                val container = ContainerHandler()
                archive.xml("META-INF/container.xml", xml, container)
                val packagePath = normalizeArchivePath(container.packagePath ?: malformed())
                val manifest = PackageHandler(packagePath)
                archive.xml(packagePath, xml, manifest)
                val title = manifest.title?.takeIf(String::isNotBlank) ?: throw BookParseException.MissingTitle()
                val state = EpubContentState(limits)
                val imageItems = manifest.items.values.filter { it.mediaType.startsWith("image/") }
                val imagePaths = imageItems.associate { it.path to ImageId("epub-image:${it.path}") }
                var stylesheetBytes = 0L
                val stylesheets = manifest.items.values.filter { it.mediaType == "text/css" }.associate { item ->
                    val bytes = archive.read(item.path, limits.maxStylesheetBytes).use(InputStream::readBytes)
                    stylesheetBytes = checkedSize(stylesheetBytes, bytes.size.toLong(), limits.maxTotalStylesheetBytes, "EPUB stylesheets")
                    item.path to EpubCss.parse(bytes.toString(Charsets.UTF_8))
                }
                manifest.spine.forEach { reference ->
                    val item = manifest.items[reference.id] ?: malformed()
                    if (item.mediaType != "application/xhtml+xml" && item.mediaType != "text/html") {
                        // A fallback can turn a foreign spine item into a standard XHTML resource.
                        var fallback = item.fallback?.let(manifest.items::get)
                        val visited = mutableSetOf(item.id)
                        while (fallback != null && fallback.mediaType != "application/xhtml+xml" && visited.add(fallback.id)) {
                            fallback = fallback.fallback?.let(manifest.items::get)
                        }
                        if (fallback == null || fallback.mediaType != "application/xhtml+xml") malformed()
                        archive.xml(fallback.path, xml, XhtmlHandler(fallback.path, state, imagePaths, reference.linear, stylesheets))
                    } else {
                        archive.xml(item.path, xml, XhtmlHandler(item.path, state, imagePaths, reference.linear, stylesheets))
                    }
                }
                if (state.blocks.isEmpty()) throw BookParseException.MissingReadableBody()
                val navigation = manifest.items.values.firstOrNull { "nav" in it.properties }
                val toc = if (navigation != null) {
                    NavHandler(navigation.path).also { archive.xml(navigation.path, xml, it) }.entries
                } else {
                    val ncx = manifest.ncxId?.let(manifest.items::get)
                        ?: manifest.items.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
                    if (ncx != null) NcxHandler(ncx.path).also { archive.xml(ncx.path, xml, it) }.entries else emptyList()
                }
                var totalImageBytes = 0L
                val coverPath = imageItems.firstOrNull { "cover-image" in it.properties }?.path
                    ?: manifest.coverId?.let(manifest.items::get)?.path
                    ?: manifest.coverPage?.let { state.firstImagesByPath[it] }
                    ?: manifest.spine.firstOrNull()?.let { manifest.items[it.id] }?.let { state.firstImagesByPath[it.path] }
                val usedImages = state.usedImages + listOfNotNull(coverPath)
                val images = imageItems.filter { it.path in usedImages }.distinctBy { it.path }.map { item ->
                    val compressed = ByteArrayOutputStream()
                    DeflaterOutputStream(compressed).use { output ->
                        archive.read(item.path, limits.maxImageBytes).use { resource ->
                            resource.copyBounded(output, limits.maxImageBytes, "EPUB image") { _, count ->
                                totalImageBytes = checkedSize(totalImageBytes, count.toLong(), limits.maxTotalImageBytes, "EPUB images")
                            }
                        }
                    }
                    BookImage(requireNotNull(imagePaths[item.path]), item.mediaType,
                        CompressedImageContent(compressed.toByteArray(), limits.maxImageBytes))
                }
                val locations = state.locations
                val noteIds = state.notes.mapTo(mutableSetOf(), BookNote::id)
                fun resolve(inlines: List<InlineNode>): List<InlineNode> = inlines.map { node ->
                    when (node) {
                        is InlineNode.Emphasis -> node.copy(children = resolve(node.children))
                        is InlineNode.Strong -> node.copy(children = resolve(node.children))
                        is InlineNode.Strikethrough -> node.copy(children = resolve(node.children))
                        is InlineNode.Link -> {
                            val target = node.target
                            val key = (target as? LinkTarget.UnresolvedInternal)?.sourceId
                            val resolved = key?.let { state.noteTargets[it]?.takeIf(noteIds::contains)?.let(LinkTarget::Note) }
                                ?: key?.let { locations[it]?.let(LinkTarget::Internal) } ?: target
                            node.copy(target = resolved, children = resolve(node.children))
                        }
                        else -> node
                    }
                }
                fun resolveBlock(block: BookBlock): BookBlock = when (block) {
                    is BookBlock.Paragraph -> block.copy(content = resolve(block.content))
                    is BookBlock.Heading -> block.copy(content = resolve(block.content))
                    else -> block
                }
                val mainIds = state.blocks.mapTo(mutableSetOf(), BookBlock::id)
                fun resolveToc(entries: List<PendingToc>): List<BookTocEntry> = entries.flatMap { entry ->
                    val children = resolveToc(entry.children)
                    val target = entry.target?.let(locations::get)?.takeIf { it.blockId in mainIds }
                    if (target != null && entry.title.isNotBlank()) {
                        listOf(BookTocEntry(entry.title, target, children))
                    } else children
                }
                val resolvedToc = resolveToc(toc).ifEmpty {
                    state.blocks.filterIsInstance<BookBlock.Heading>().map {
                        BookTocEntry(it.plainText(), state.blockLocations.getValue(it.id))
                    }
                }
                val annotation = manifest.description?.takeIf(String::isNotBlank)
                BookDocument(
                    id = BookId("sha256:" + digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }),
                    metadata = BookMetadata(title, manifest.authors.distinct(), manifest.language,
                        manifest.subjects.distinct(), annotation, coverImageId = imagePaths[coverPath],
                        documentId = manifest.identifier, annotationBlocks = annotation?.let {
                            listOf(BookBlock.Paragraph(BlockId("epub:annotation"), listOf(InlineNode.Text(it))))
                        }.orEmpty()),
                    blocks = state.blocks.map(::resolveBlock),
                    notes = state.notes.map { it.copy(blocks = it.blocks.map(::resolveBlock)) },
                    tableOfContents = resolvedToc,
                    images = images,
                    locationsBySourceId = locations,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            checkEpubCancellation()
            var cause: Throwable? = error
            val visited = hashSetOf<Throwable>()
            while (cause != null && visited.add(cause)) {
                if (cause is BookParseException) throw cause
                cause = cause.cause
            }
            throw BookParseException.MalformedDocument(error)
        } finally {
            if (!temporary.delete()) temporary.deleteOnExit()
        }
    }
}

private class EpubArchive(private val zip: ZipFile, private val limits: EpubParserLimits) {
    private val entries = linkedMapOf<String, ZipEntry>()
    private var consumed = 0L

    init {
        var size = 0L
        var count = 0
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            checkEpubCancellation()
            val entry = enumeration.nextElement()
            if (++count > limits.maxArchiveEntries) limit("EPUB archive entries", limits.maxArchiveEntries.toLong())
            if (entry.isDirectory) continue
            val path = normalizeArchivePath(entry.name)
            if (path != entry.name || entries.put(path, entry) != null) malformed()
            if (entry.size > limits.maxArchiveEntryBytes) limit("EPUB archive entry", limits.maxArchiveEntryBytes)
            size = checkedSize(size, entry.size.coerceAtLeast(0), limits.maxInflatedArchiveBytes, "EPUB inflated archive")
            if (entry.size > 0 && entry.compressedSize > 0 && entry.size / entry.compressedSize > limits.maxCompressionRatio) {
                limit("EPUB compression ratio", limits.maxCompressionRatio.toLong())
            }
        }
    }

    fun read(path: String, maximum: Long): InputStream {
        val entry = entries[path] ?: malformed()
        if (entry.size > maximum) limit("EPUB resource", maximum)
        return object : FilterInputStream(zip.getInputStream(entry)) {
            private var resourceBytes = 0L
            private fun account(count: Int) {
                checkEpubCancellation()
                if (count <= 0) return
                resourceBytes = checkedSize(resourceBytes, count.toLong(), maximum, "EPUB resource")
                consumed = checkedSize(consumed, count.toLong(), limits.maxInflatedArchiveBytes, "EPUB consumed resources")
            }
            override fun read(): Int = `in`.read().also { if (it >= 0) account(1) }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also(::account)
        }
    }

    fun xml(path: String, xml: EpubXml, handler: EpubHandler) {
        read(path, limits.maxXmlBytes).use { xml.parse(it, handler) }
    }
}

/** Retains compressed data only until first use; never holds a deleted temporary ZIP or open file. */
private class CompressedImageContent(private var compressed: ByteArray?, private val limit: Long) : BookBinaryContent {
    private var loaded = false
    private var content: ByteArray? = null
    @Synchronized override fun load(): ByteArray? {
        if (!loaded) {
            loaded = true
            content = try {
                val output = ByteArrayOutputStream()
                InflaterInputStream(ByteArrayInputStream(requireNotNull(compressed))).use { it.copyBounded(output, limit, "EPUB image") }
                output.toByteArray()
            } catch (_: Exception) { null }
            compressed = null
        }
        return content
    }
}

internal fun malformed(): Nothing = throw BookParseException.MalformedDocument()
internal fun limit(resource: String, maximum: Long): Nothing = throw BookParseException.ResourceLimitExceeded(resource, maximum)
internal fun checkedSize(current: Long, delta: Long, maximum: Long, resource: String): Long {
    if (delta > maximum - current) limit(resource, maximum)
    return current + delta
}
internal fun checkEpubCancellation() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("EPUB parsing was cancelled")
}
private fun InputStream.copyBounded(output: OutputStream, maximum: Long, resource: String, onBytes: (ByteArray, Int) -> Unit = { _, _ -> }) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var bytes = 0L
    while (true) {
        checkEpubCancellation()
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        bytes = checkedSize(bytes, count.toLong(), maximum, resource)
        onBytes(buffer, count)
        output.write(buffer, 0, count)
    }
}
