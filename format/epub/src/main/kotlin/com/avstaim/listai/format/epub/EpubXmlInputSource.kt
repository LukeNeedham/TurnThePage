package com.avstaim.listai.format.epub

import com.avstaim.listai.format.fb2.BookParseException

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import org.xml.sax.InputSource

/**
 * Android Expat defaults byte streams to UTF-8 and cannot decode all Java charsets itself.
 * Sniff only the bounded XML declaration, then decode incrementally before handing text to SAX.
 * Buffering sits above the byte limit and digest so sniffed bytes are counted and hashed once.
 */
internal fun epubXmlInputSource(input: InputStream): InputSource {
    val buffered = BufferedInputStream(input)
    buffered.mark(MAX_XML_DECLARATION_BYTES)
    val prefix = ByteArray(MAX_XML_DECLARATION_BYTES)
    var count = 0
    fun readByte(): Boolean {
        if (count == prefix.size) {
            throw BookParseException.ResourceLimitExceeded(
                "XML declaration bytes",
                MAX_XML_DECLARATION_BYTES.toLong(),
            )
        }
        val value = buffered.read()
        if (value < 0) return false
        prefix[count++] = value.toByte()
        return true
    }
    while (count < 4 && readByte()) Unit
    val signature = xmlSignature(prefix, count)
    val initialCharset = signature?.charset ?: Charsets.UTF_8
    val bomBytes = signature?.bomBytes ?: 0
    val unitBytes = signature?.unitBytes ?: 1
    while (count < bomBytes + 6 * unitBytes && readByte()) Unit

    var declaration = String(prefix, bomBytes, count - bomBytes, initialCharset)
    if (XML_DECLARATION_START.containsMatchIn(declaration)) {
        val end = "?>".toByteArray(initialCharset)
        fun endsDeclaration(): Boolean = count >= end.size &&
            end.indices.all { prefix[count - end.size + it] == end[it] }
        while (!endsDeclaration() && readByte()) Unit
        declaration = String(prefix, bomBytes, count - bomBytes, initialCharset)
    } else {
        declaration = ""
    }
    val declaredCharset = XML_ENCODING.find(declaration)?.groupValues?.get(2)?.let(Charset::forName)
    if (signature != null && declaredCharset != null &&
        declaredCharset != initialCharset &&
        !(declaredCharset.name() == "UTF-16" && unitBytes == 2) &&
        !(declaredCharset.name() == "UTF-32" && unitBytes == 4)
    ) {
        throw IOException("XML encoding declaration conflicts with its byte signature")
    }
    buffered.reset()
    repeat(bomBytes) { buffered.read() }
    val charset = signature?.charset ?: declaredCharset ?: Charsets.UTF_8
    val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return InputSource(EpubMarkupReader(InputStreamReader(buffered, decoder)))
}

private data class XmlSignature(val charset: Charset, val bomBytes: Int, val unitBytes: Int)

private fun xmlSignature(bytes: ByteArray, count: Int): XmlSignature? {
    fun starts(vararg prefix: Int): Boolean = count >= prefix.size &&
        prefix.indices.all { bytes[it].toInt() and 0xff == prefix[it] }
    return when {
        starts(0x00, 0x00, 0xfe, 0xff) -> XmlSignature(Charset.forName("UTF-32BE"), 4, 4)
        starts(0xff, 0xfe, 0x00, 0x00) -> XmlSignature(Charset.forName("UTF-32LE"), 4, 4)
        starts(0xef, 0xbb, 0xbf) -> XmlSignature(Charsets.UTF_8, 3, 1)
        starts(0xfe, 0xff) -> XmlSignature(Charsets.UTF_16BE, 2, 2)
        starts(0xff, 0xfe) -> XmlSignature(Charsets.UTF_16LE, 2, 2)
        starts(0x00, 0x00, 0x00, 0x3c) -> XmlSignature(Charset.forName("UTF-32BE"), 0, 4)
        starts(0x3c, 0x00, 0x00, 0x00) -> XmlSignature(Charset.forName("UTF-32LE"), 0, 4)
        starts(0x00, 0x3c, 0x00, 0x3f) -> XmlSignature(Charsets.UTF_16BE, 0, 2)
        starts(0x3c, 0x00, 0x3f, 0x00) -> XmlSignature(Charsets.UTF_16LE, 0, 2)
        else -> null
    }
}

/**
 * Legacy EPUBs declare XHTML/NCX DTDs. Remove only known, inert declarations before SAX,
 * which still rejects every DTD/entity declaration it receives. Never load or interpret a DTD.
 * Decoding precedes this guard so UTF-16 and legacy encodings receive the same checks.
 */
private class EpubMarkupReader(reader: Reader) : Reader() {
    private val input = BufferedReader(reader)
    private var pending = ""
    private var pendingOffset = 0
    private var literalEnd: String? = null
    private var literalMatched = 0
    private var prolog = true
    private var doctypeSeen = false
    private var xhtmlEntities = false

    override fun read(): Int {
        while (pendingOffset == pending.length) {
            pending = ""
            pendingOffset = 0
            checkEpubCancellation()
            val value = input.read()
            if (value < 0) return -1
            val char = value.toChar()
            val end = literalEnd
            if (end != null) {
                literalMatched = if (char == end[literalMatched]) literalMatched + 1 else {
                    // The delimiters can overlap: '--->' and ']]]>' still end their literal.
                    if (char == end[0]) (literalMatched + 1).coerceAtMost(end.length - 1) else 0
                }
                if (literalMatched == end.length) { literalEnd = null; literalMatched = 0 }
                return value
            }
            when (char) {
                '<' -> readMarkup()
                '&' -> if (xhtmlEntities) readEntity() else return value
                else -> return value
            }
        }
        return pending[pendingOffset++].code
    }

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        var count = 0
        while (count < length) {
            val value = read()
            if (value < 0) break
            buffer[offset + count++] = value.toChar()
        }
        return if (count == 0) -1 else count
    }

    override fun close() = input.close()

    private fun readMarkup() {
        val token = StringBuilder("<")
        while (MARKUP_STARTS.any { it.startsWith(token.toString()) && it.length > token.length }) {
            val value = input.read()
            if (value < 0) break
            token.append(value.toChar())
        }
        when (val start = token.toString()) {
            "<!--" -> literalEnd = "-->"
            "<![CDATA[" -> { literalEnd = "]]>"; prolog = false }
            "<?" -> literalEnd = "?>"
            DOCTYPE -> {
                if (!prolog || doctypeSeen) throw IOException("Misplaced EPUB DOCTYPE")
                readDoctype()
                doctypeSeen = true
                return
            }
            else -> if (start.length > 1) prolog = false
        }
        pending = token.toString()
    }

    private fun readDoctype() {
        val declaration = StringBuilder()
        var quote: Char? = null
        while (true) {
            checkEpubCancellation()
            val value = input.read()
            if (value < 0) throw IOException("Unterminated EPUB DOCTYPE")
            val char = value.toChar()
            if (quote != null) {
                if (char == quote) quote = null
            } else when (char) {
                '\'', '"' -> quote = char
                '[' -> throw IOException("Internal DTD subsets are forbidden in EPUB documents")
                '>' -> break
            }
            if (declaration.length == MAX_XML_DECLARATION_BYTES) limit("EPUB DOCTYPE characters", MAX_XML_DECLARATION_BYTES.toLong())
            declaration.append(char)
        }
        val match = INERT_DOCTYPE.matchEntire(declaration) ?: throw IOException("Unsupported EPUB DOCTYPE")
        val root = match.groupValues[1]
        val kind = match.groupValues[2]
        val first = match.groupValues[4]
        val second = match.groupValues[6]
        val supported = when {
            root == "html" && kind.isEmpty() -> true
            kind == "PUBLIC" -> (root to (first to second)) in LEGACY_PUBLIC_DOCTYPES
            kind == "SYSTEM" -> match.groups[5] == null && (root to first) in LEGACY_SYSTEM_DOCTYPES
            else -> false
        }
        if (!supported) throw IOException("Unsupported EPUB DOCTYPE")
        xhtmlEntities = root == "html"
    }

    private fun readEntity() {
        val token = StringBuilder("&")
        while (token.length <= MAX_ENTITY_NAME_CHARACTERS) {
            input.mark(1)
            val value = input.read()
            if (value < 0) break
            val char = value.toChar()
            if (char == ';') {
                val codepoint = XHTML_ENTITIES[token.substring(1)]
                pending = codepoint?.let { "&#$it;" } ?: "$token;"
                return
            }
            if (char !in 'a'..'z' && char !in 'A'..'Z' && char !in '0'..'9' && char != '#') {
                input.reset()
                break
            }
            token.append(char)
        }
        pending = token.toString()
    }
}

private const val MAX_XML_DECLARATION_BYTES = 8_192
private const val DOCTYPE = "<!DOCTYPE"
private const val MAX_ENTITY_NAME_CHARACTERS = 32
private val MARKUP_STARTS = listOf("<!--", "<![CDATA[", "<?", DOCTYPE)
private val INERT_DOCTYPE = Regex("""[ \t\r\n]+(html|ncx)(?:[ \t\r\n]+(PUBLIC|SYSTEM)[ \t\r\n]+(['"])([^'"]*)\3(?:[ \t\r\n]+(['"])([^'"]*)\5)?)?[ \t\r\n]*""")
private val LEGACY_PUBLIC_DOCTYPES = setOf(
    "html" to ("-//W3C//DTD XHTML 1.1//EN" to "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd"),
    "html" to ("-//W3C//DTD XHTML 1.0 Strict//EN" to "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd"),
    "html" to ("-//W3C//DTD XHTML 1.0 Transitional//EN" to "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd"),
    "html" to ("-//W3C//DTD XHTML 1.0 Frameset//EN" to "http://www.w3.org/TR/xhtml1/DTD/xhtml1-frameset.dtd"),
    "ncx" to ("-//NISO//DTD ncx 2005-1//EN" to "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd"),
)
private val LEGACY_SYSTEM_DOCTYPES = LEGACY_PUBLIC_DOCTYPES.mapTo(mutableSetOf()) { (root, ids) -> root to ids.second } +
    ("html" to "about:legacy-compat")
private val XML_DECLARATION_START = Regex("^<\\?xml[ \\t\\r\\n]")
private val XML_ENCODING = Regex("[ \\t\\r\\n]encoding[ \\t\\r\\n]*=[ \\t\\r\\n]*(['\"])([A-Za-z][A-Za-z0-9._-]*)\\1")
