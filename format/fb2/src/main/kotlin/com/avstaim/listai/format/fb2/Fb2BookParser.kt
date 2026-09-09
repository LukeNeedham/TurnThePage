package com.avstaim.listai.format.fb2

import com.avstaim.listai.core.model.BookDocument
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.XMLReader
import org.xml.sax.ext.EntityResolver2

/** Hard resource ceilings used while reading untrusted FB2 and FB2.ZIP files. */
data class Fb2ParserLimits(
    val maxDirectDocumentBytes: Long = 64L * 1024L * 1024L,
    val maxArchiveBytes: Long = 128L * 1024L * 1024L,
    val maxArchiveEntries: Int = 4_096,
    val maxArchiveEntryBytes: Long = 128L * 1024L * 1024L,
    val maxInflatedArchiveBytes: Long = 256L * 1024L * 1024L,
    val maxSelectedDocumentBytes: Long = 64L * 1024L * 1024L,
    val maxExpandedXmlCharacters: Long = 96L * 1024L * 1024L,
    val maxBinaryEncodedCharacters: Int = 32 * 1024 * 1024,
    val maxTotalBinaryEncodedCharacters: Long = 64L * 1024L * 1024L,
    val maxXmlDepth: Int = 256,
    val maxXmlElements: Long = 2_000_000L,
    val maxBlocks: Int = 250_000,
    val maxBinaries: Int = 20_000,
    val maxCompressionRatio: Int = 500,
) {
    init {
        require(maxDirectDocumentBytes > 0)
        require(maxArchiveBytes > 0)
        require(maxArchiveEntries > 0)
        require(maxArchiveEntryBytes > 0)
        require(maxInflatedArchiveBytes > 0)
        require(maxSelectedDocumentBytes > 0)
        require(maxExpandedXmlCharacters > 0)
        require(maxBinaryEncodedCharacters > 0)
        require(maxTotalBinaryEncodedCharacters > 0)
        require(maxXmlDepth > 0)
        require(maxXmlElements > 0)
        require(maxBlocks > 0)
        require(maxBinaries > 0)
        require(maxCompressionRatio > 0)
    }
}

/**
 * Streaming, namespace-aware FB2 parser.
 *
 * XML is consumed with SAX and never converted to a DOM. ZIP input is first copied to a bounded
 * temporary file so entries can be selected lexicographically without retaining a complete FB2 in
 * memory. Parsing always runs on [ioDispatcher], including callers that invoke it from the UI
 * thread.
 */
class Fb2BookParser(
    private val limits: Fb2ParserLimits = Fb2ParserLimits(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BookParser {
    override fun supports(fileName: String): Boolean {
        val normalized = fileName.trim().lowercase(Locale.ROOT)
        return normalized.endsWith(".fb2") || normalized.endsWith(".fb2.zip") ||
            normalized.endsWith(".zip")
    }

    override suspend fun parse(input: InputStream, fileName: String?): BookDocument =
        runInterruptible(ioDispatcher) {
            try {
                parseBlocking(input, fileName)
            } catch (error: BookParseException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                if (Thread.currentThread().isInterrupted) {
                    throw CancellationException("FB2 parsing was cancelled", error)
                }
                throw BookParseException.MalformedDocument(error)
            }
        }

    private fun parseBlocking(input: InputStream, fileName: String?): BookDocument {
        // Some SAX implementations close InputSource.byteStream after parsing. Keep ownership at
        // the API boundary by shielding the caller's stream from that implementation detail.
        val buffered = BufferedInputStream(NonClosingInputStream(input))
        val zipBySignature = buffered.peekHeader().isZipSignature()
        val zipByName = fileName?.trim()?.lowercase(Locale.ROOT)?.let { name ->
            name.endsWith(".zip")
        } == true
        return if (zipBySignature || zipByName) {
            parseArchive(buffered)
        } else {
            parseXml(buffered, limits.maxDirectDocumentBytes)
        }
    }

    private fun parseArchive(input: InputStream): BookDocument {
        val archive = try {
            File.createTempFile("listai-fb2-", ".zip")
        } catch (error: IOException) {
            throwMalformedUnlessCancelled(error)
        }
        try {
            try {
                FileOutputStream(archive).use { output ->
                    input.copyToWithLimit(output, limits.maxArchiveBytes, "archive input")
                }
            } catch (error: IOException) {
                throwMalformedUnlessCancelled(error)
            }

            return try {
                ZipFile(archive).use { zip ->
                    val entries = mutableListOf<IndexedZipEntry>()
                    val enumeration = zip.entries()
                    var index = 0
                    while (enumeration.hasMoreElements()) {
                        checkCancellation()
                        if (index >= limits.maxArchiveEntries) {
                            throw BookParseException.ResourceLimitExceeded(
                                resource = "archive entry count",
                                limit = limits.maxArchiveEntries.toLong(),
                            )
                        }
                        entries += IndexedZipEntry(index++, enumeration.nextElement())
                    }

                    val selected = entries
                        .asSequence()
                        .filterNot { it.entry.isDirectory }
                        .filter { it.entry.name.lowercase(Locale.ROOT).endsWith(".fb2") }
                        .sortedWith(compareBy<IndexedZipEntry> { it.entry.name }.thenBy { it.index })
                        .firstOrNull()
                        ?: throw BookParseException.ArchiveContainsNoFb2()

                    validateArchiveMetadata(entries, selected)

                    zip.getInputStream(selected.entry).use { selectedInput ->
                        val buffered = BufferedInputStream(selectedInput)
                        if (buffered.peekHeader().isZipSignature()) {
                            throw BookParseException.NestedArchive()
                        }
                        parseXml(buffered, limits.maxSelectedDocumentBytes)
                    }
                }
            } catch (error: BookParseException) {
                throw error
            } catch (error: ZipException) {
                throw BookParseException.MalformedDocument(error)
            } catch (error: IOException) {
                throwMalformedUnlessCancelled(error)
            }
        } finally {
            if (!archive.delete()) archive.deleteOnExit()
        }
    }

    private fun validateArchiveMetadata(
        entries: List<IndexedZipEntry>,
        selected: IndexedZipEntry,
    ) {
        var knownInflatedBytes = 0L
        entries.asSequence().filterNot { it.entry.isDirectory }.forEach { indexed ->
            val entry = indexed.entry
            if (entry.size > limits.maxArchiveEntryBytes) {
                throw BookParseException.ResourceLimitExceeded(
                    resource = "archive entry '${entry.name}'",
                    limit = limits.maxArchiveEntryBytes,
                )
            }
            if (indexed == selected && entry.size > limits.maxSelectedDocumentBytes) {
                throw BookParseException.ResourceLimitExceeded(
                    resource = "selected FB2 entry",
                    limit = limits.maxSelectedDocumentBytes,
                )
            }
            if (entry.size > 0L && entry.compressedSize > 0L &&
                entry.size / entry.compressedSize > limits.maxCompressionRatio
            ) {
                throw BookParseException.ResourceLimitExceeded(
                    resource = "archive compression ratio",
                    limit = limits.maxCompressionRatio.toLong(),
                )
            }
            if (entry.size >= 0L) {
                knownInflatedBytes = checkedAdd(
                    current = knownInflatedBytes,
                    delta = entry.size,
                    limit = limits.maxInflatedArchiveBytes,
                    resource = "declared inflated archive",
                )
            }
        }
    }

    private fun parseXml(input: InputStream, byteLimit: Long): BookDocument {
        val digest = MessageDigest.getInstance("SHA-256")
        val limited = LimitedInputStream(input, byteLimit, "FB2 document")
        val guarded = RejectingDoctypeInputStream(limited)
        val digested = DigestInputStream(guarded, digest)
        val handler = Fb2SaxHandler(limits)

        try {
            val reader = createSecureXmlReader(handler)
            reader.parse(InputSource(digested))
            return handler.toBookDocument(digest.digest().toHexString())
        } catch (error: BookParseException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: SAXException) {
            error.findBookParseException()?.let { throw it }
            throw BookParseException.MalformedDocument(error)
        } catch (error: IOException) {
            if (Thread.currentThread().isInterrupted) {
                throw CancellationException("FB2 parsing was cancelled", error)
            }
            error.findBookParseException()?.let { throw it }
            throw BookParseException.MalformedDocument(error)
        } catch (error: RuntimeException) {
            error.findBookParseException()?.let { throw it }
            throw BookParseException.MalformedDocument(error)
        } catch (error: Exception) {
            throw BookParseException.MalformedDocument(error)
        }
    }

    private fun createSecureXmlReader(handler: Fb2SaxHandler): XMLReader {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
        }
        val reader = factory.newSAXParser().xmlReader
        reader.trySetFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        reader.trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        reader.trySetFeature("http://xml.org/sax/features/external-general-entities", false)
        reader.trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
        reader.trySetFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        reader.entityResolver = RejectingEntityResolver
        reader.contentHandler = handler
        reader.errorHandler = handler
        try {
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        } catch (_: SAXException) {
            // Android's SAX implementation may not expose this optional property. The raw stream
            // guard still rejects DOCTYPE in ASCII-compatible, UTF-16 and UTF-32 XML.
        }
        return reader
    }
}

private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
    override fun close() = Unit
}

private data class IndexedZipEntry(
    val index: Int,
    val entry: ZipEntry,
)

private object RejectingEntityResolver : EntityResolver2 {
    override fun getExternalSubset(name: String?, baseURI: String?): InputSource? = null

    override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
        throw SAXException("External XML entities are forbidden")

    override fun resolveEntity(
        name: String?,
        publicId: String?,
        baseURI: String?,
        systemId: String?,
    ): InputSource = throw SAXException("External XML entities are forbidden")
}

private fun XMLReader.trySetFeature(name: String, enabled: Boolean) {
    try {
        setFeature(name, enabled)
    } catch (_: SAXException) {
        // Feature support differs between the JDK and Android API 26. Security does not depend on
        // these optional flags alone: DOCTYPE is rejected in the byte stream and entities have a
        // rejecting resolver.
    }
}

private class LimitedInputStream(
    input: InputStream,
    private val limit: Long,
    private val resource: String,
) : FilterInputStream(input) {
    private var consumed = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) account(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) account(count.toLong())
        return count
    }

    private fun account(count: Long) {
        consumed = checkedAdd(consumed, count, limit, resource)
    }
}

/** Detects the only construct that can declare XML entities before SAX sees the full token. */
private class RejectingDoctypeInputStream(input: InputStream) : FilterInputStream(input) {
    private val matchers = DOCTYPE_PATTERNS.map(::BytePatternMatcher)

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) inspect(value.toByte())
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) {
            for (index in offset until offset + count) inspect(buffer[index])
        }
        return count
    }

    private fun inspect(value: Byte) {
        if (matchers.any { it.accept(value) }) {
            throw IOException("DOCTYPE is forbidden in FB2 documents")
        }
    }
}

private class BytePatternMatcher(private val pattern: ByteArray) {
    private var matched = 0

    fun accept(value: Byte): Boolean {
        matched = when {
            value == pattern[matched] -> matched + 1
            value == pattern[0] -> 1
            else -> 0
        }
        if (matched == pattern.size) {
            matched = 0
            return true
        }
        return false
    }
}

private val DOCTYPE_PATTERNS = listOf(
    "<!DOCTYPE".toByteArray(Charsets.US_ASCII),
    "<!DOCTYPE".toByteArray(Charsets.UTF_16LE),
    "<!DOCTYPE".toByteArray(Charsets.UTF_16BE),
    asciiAsUtf32("<!DOCTYPE", littleEndian = true),
    asciiAsUtf32("<!DOCTYPE", littleEndian = false),
)

private fun asciiAsUtf32(value: String, littleEndian: Boolean): ByteArray =
    ByteArray(value.length * 4).also { bytes ->
        value.forEachIndexed { index, character ->
            bytes[index * 4 + if (littleEndian) 0 else 3] = character.code.toByte()
        }
    }

private fun BufferedInputStream.peekHeader(): ByteArray {
    mark(8)
    val header = ByteArray(4)
    var count = 0
    while (count < header.size) {
        val read = read(header, count, header.size - count)
        if (read < 0) break
        count += read
    }
    reset()
    return if (count == header.size) header else header.copyOf(count)
}

private fun ByteArray.isZipSignature(): Boolean = size >= 4 &&
    this[0] == 0x50.toByte() && this[1] == 0x4b.toByte() &&
    ((this[2] == 0x03.toByte() && this[3] == 0x04.toByte()) ||
        (this[2] == 0x05.toByte() && this[3] == 0x06.toByte()) ||
        (this[2] == 0x07.toByte() && this[3] == 0x08.toByte()))

private fun InputStream.copyToWithLimit(
    output: FileOutputStream,
    limit: Long,
    resource: String,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        checkCancellation()
        val count = read(buffer)
        if (count < 0) break
        copied = checkedAdd(copied, count.toLong(), limit, resource)
        output.write(buffer, 0, count)
    }
}

private fun checkCancellation() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("FB2 parsing was cancelled")
}

private fun throwMalformedUnlessCancelled(error: IOException): Nothing {
    if (Thread.currentThread().isInterrupted) {
        throw CancellationException("FB2 parsing was cancelled", error)
    }
    throw BookParseException.MalformedDocument(error)
}

private fun checkedAdd(current: Long, delta: Long, limit: Long, resource: String): Long {
    if (delta > limit - current) {
        throw BookParseException.ResourceLimitExceeded(resource, limit)
    }
    return current + delta
}

private fun Throwable.findBookParseException(): BookParseException? {
    var current: Throwable? = this
    val visited = HashSet<Throwable>()
    while (current != null && visited.add(current)) {
        if (current is BookParseException) return current
        current = current.cause
    }
    return null
}

private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
    "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
}
