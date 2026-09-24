package com.avstaim.listai.format.epub

import com.avstaim.listai.format.fb2.BookParseException

import java.io.BufferedInputStream
import java.io.FilterReader
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
    return InputSource(RejectingDoctypeReader(InputStreamReader(buffered, decoder)))
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

/** Reject after decoding too, so alternate encodings cannot hide a DOCTYPE from the guard. */
private class RejectingDoctypeReader(reader: Reader) : FilterReader(reader) {
    private var matched = 0

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) inspect(value.toChar())
        return value
    }

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        for (index in offset until offset + count) inspect(buffer[index])
        return count
    }

    private fun inspect(value: Char) {
        matched = when {
            value == DOCTYPE[matched] -> matched + 1
            value == DOCTYPE[0] -> 1
            else -> 0
        }
        if (matched == DOCTYPE.length) throw IOException("DOCTYPE is forbidden in EPUB documents")
    }
}

private const val MAX_XML_DECLARATION_BYTES = 8_192
private const val DOCTYPE = "<!DOCTYPE"
private val XML_DECLARATION_START = Regex("^<\\?xml[ \\t\\r\\n]")
private val XML_ENCODING = Regex("[ \\t\\r\\n]encoding[ \\t\\r\\n]*=[ \\t\\r\\n]*(['\"])([A-Za-z][A-Za-z0-9._-]*)\\1")
