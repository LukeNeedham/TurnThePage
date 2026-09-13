package com.avstaim.listai.format.fb2

import com.avstaim.listai.core.model.plainText
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Fb2XmlEncodingTest {
    private val parser = Fb2BookParser()

    @Test
    fun `legacy declarations support single quotes whitespace and short non markable reads`() = runBlocking {
        for (encoding in listOf("windows-1251", "KOI8-R", "ISO-8859-5")) {
            val bytes = xml("<?xml version='1.0'\n encoding = '$encoding' ?>")
                .toByteArray(Charset.forName(encoding))
            val input = object : FilterInputStream(ByteArrayInputStream(bytes)) {
                override fun markSupported(): Boolean = false
                override fun reset(): Unit = throw IOException("not markable")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    super.read(buffer, offset, minOf(length, 1))
            }
            val document = parser.parse(input)

            assertEquals(encoding, "Тихая книга", document.metadata.title)
            assertEquals(encoding, "Русский текст ёж", document.blocks.single().plainText())
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals("sha256:$hash", document.id.value)
        }
    }

    @Test
    fun `Unicode byte order marks and signatures preserve text and original byte identity`() = runBlocking {
        val encodings = listOf(
            Triple("UTF-8", "UTF-8", byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())),
            Triple("UTF-16BE", "UTF-16", byteArrayOf(0xfe.toByte(), 0xff.toByte())),
            Triple("UTF-16LE", "UTF-16", byteArrayOf(0xff.toByte(), 0xfe.toByte())),
            Triple("UTF-32BE", "UTF-32", byteArrayOf(0, 0, 0xfe.toByte(), 0xff.toByte())),
            Triple("UTF-32LE", "UTF-32", byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 0)),
            Triple("UTF-16BE", "UTF-16BE", byteArrayOf()),
            Triple("UTF-16LE", "UTF-16LE", byteArrayOf()),
        )
        for ((encoding, declaration, bom) in encodings) {
            val bytes = bom + xml("<?xml version='1.0' encoding='$declaration'?>")
                .toByteArray(Charset.forName(encoding))
            val document = parser.parse(ByteArrayInputStream(bytes))

            assertEquals(encoding, "Тихая книга", document.metadata.title)
            assertEquals(encoding, "Русский текст ёж", document.blocks.single().plainText())
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals("sha256:$hash", document.id.value)
        }
        assertEquals(
            "Тихая книга",
            parser.parse(ByteArrayInputStream(xml("").toByteArray())).metadata.title,
        )
    }

    @Test
    fun `legacy ZIP entries use the same decoder and byte limits as direct FB2`() = runBlocking<Unit> {
        val bytes = xml("<?xml version='1.0' encoding='windows-1251'?>")
            .toByteArray(Charset.forName("windows-1251"))
        val archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("book.fb2"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }.toByteArray()
        val direct = parser.parse(ByteArrayInputStream(bytes))
        val zipped = parser.parse(ByteArrayInputStream(archive))
        assertEquals("Тихая книга", zipped.metadata.title)
        assertEquals("Русский текст ёж", zipped.blocks.single().plainText())
        assertEquals(direct.id, zipped.id)

        val limited = Fb2BookParser(Fb2ParserLimits(maxDirectDocumentBytes = bytes.size - 1L))
        assertThrows(BookParseException.ResourceLimitExceeded::class.java) {
            runBlocking { limited.parse(ByteArrayInputStream(bytes)) }
        }
    }

    @Test
    fun `invalid byte sequences unknown charsets and conflicting BOM declarations fail`() {
        val invalidUtf8 = xml("<?xml version='1.0' encoding='UTF-8'?>")
            .replace("Русский текст ёж", "bad~text").toByteArray()
        invalidUtf8[invalidUtf8.indexOf('~'.code.toByte())] = 0xff.toByte()
        val unsupported = xml("<?xml version='1.0' encoding='not-a-charset'?>").toByteArray()
        val mismatched = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
            xml("<?xml version='1.0' encoding='windows-1251'?>").toByteArray()
        val truncatedUtf16 = (
            byteArrayOf(0xff.toByte(), 0xfe.toByte()) +
                xml("<?xml version='1.0' encoding='UTF-16'?>").toByteArray(Charsets.UTF_16LE)
            ).dropLast(1).toByteArray()

        for (bytes in listOf(invalidUtf8, unsupported, mismatched, truncatedUtf16)) {
            assertThrows(BookParseException.MalformedDocument::class.java) {
                runBlocking { parser.parse(ByteArrayInputStream(bytes)) }
            }
        }
    }

    @Test
    fun `decoded stream guard rejects DOCTYPE independently of SAX features`() {
        for (encoding in listOf("windows-1251", "UTF-16LE", "UTF-32BE")) {
            val bytes = xml("<?xml version='1.0' encoding='$encoding'?>")
                .replace("<FictionBook>", "<!DOCTYPE FictionBook><FictionBook>")
                .toByteArray(Charset.forName(encoding))
            assertThrows(IOException::class.java) {
                fb2XmlInputSource(ByteArrayInputStream(bytes)).characterStream.readText()
            }
            assertThrows(BookParseException.MalformedDocument::class.java) {
                runBlocking { parser.parse(ByteArrayInputStream(bytes)) }
            }
        }
    }

    @Test
    fun `XML declaration sniffing is bounded`() {
        val bytes = xml("<?xml version='1.0' ${" ".repeat(8_192)} encoding='UTF-8'?>")
            .toByteArray()
        assertThrows(BookParseException.ResourceLimitExceeded::class.java) {
            runBlocking { parser.parse(ByteArrayInputStream(bytes)) }
        }
    }

    private fun xml(declaration: String): String = """
        $declaration
        <FictionBook>
          <description><title-info><book-title>Тихая книга</book-title></title-info></description>
          <body><section><p>Русский текст ёж</p></section></body>
        </FictionBook>
    """.trimIndent().trimStart()
}
