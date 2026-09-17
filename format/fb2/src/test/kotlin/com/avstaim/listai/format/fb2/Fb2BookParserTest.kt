package com.avstaim.listai.format.fb2

import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.ImageId
import com.avstaim.listai.core.model.InlineNode
import com.avstaim.listai.core.model.LinkTarget
import com.avstaim.listai.core.model.NoteId
import com.avstaim.listai.core.model.ParagraphRole
import com.avstaim.listai.core.model.QuoteRole
import com.avstaim.listai.core.model.plainText
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Fb2BookParserTest {
    private val parser = Fb2BookParser()

    @Test
    fun `inline normalization trims whitespace and merges text around empty nested nodes`() = runBlocking {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <FictionBook><description><title-info><book-title>List compatibility</book-title></title-info></description>
            <body><section>
            <p>   <emphasis>Italic <strong>bold</strong></emphasis>   </p>
            <p>alpha<emphasis></emphasis>beta<strong></strong>gamma</p>
            </section></body></FictionBook>""".trimIndent()
        val document = parser.parse(xml.byteInputStream())
        val paragraphs = document.blocks.filterIsInstance<BookBlock.Paragraph>()

        assertEquals(2, paragraphs.size)
        assertEquals(listOf(InlineNode.Emphasis(listOf(InlineNode.Text("Italic "),
            InlineNode.Strong(listOf(InlineNode.Text("bold")))))), paragraphs.first().content)
        assertEquals(listOf(InlineNode.Text("alphabetagamma")), paragraphs.last().content)
    }

    @Test
    fun `rich fixture maps metadata structure toc notes links and lazy images`() = runBlocking {
        val document = javaClass.getResourceAsStream("/fixtures/rich-book.fb2").use { input ->
            assertNotNull(input)
            parser.parse(requireNotNull(input), "rich-book.fb2")
        }

        assertEquals("Карта тихих страниц", document.metadata.title)
        assertEquals(listOf("Ада М. Лавлейс", "Second Author"), document.metadata.authors)
        assertEquals("ru", document.metadata.language)
        assertEquals(listOf("science_fiction", "prose"), document.metadata.genres)
        assertEquals("Первая строка аннотации.\nВторая строка.", document.metadata.annotation)
        assertEquals("Quiet Atlas", document.metadata.sequence?.name)
        assertEquals(3, document.metadata.sequence?.number)
        assertEquals("rich-document-id", document.metadata.documentId)
        assertEquals(ImageId("cover.png"), document.metadata.coverImageId)
        assertTrue(document.id.value.startsWith("sha256:"))

        val allMainBlocks = document.blocks.flattened()
        assertTrue(allMainBlocks.any { it is BookBlock.Paragraph && it.role == ParagraphRole.SUBTITLE })
        assertTrue(
            document.blocks.any {
                it is BookBlock.Quote && it.role == QuoteRole.EPIGRAPH &&
                    it.plainText().contains("У каждой книги")
            },
        )
        assertTrue(document.blocks.any { it is BookBlock.Quote && it.role == QuoteRole.CITE })

        val poem = document.blocks.filterIsInstance<BookBlock.Poem>().single()
        assertEquals("Песня страницы", poem.title.single().plainText())
        assertEquals("Первая строка", poem.stanzas.single().lines.first().plainText())
        assertEquals("Поэт", poem.textAuthors.single().plainText())

        val opening = allMainBlocks.filterIsInstance<BookBlock.Paragraph>()
            .single { it.content.plainText().contains("Обычный") }
        assertTrue(opening.content.any { it is InlineNode.Emphasis })
        assertTrue(opening.content.any { it is InlineNode.Strong })
        assertTrue(opening.content.any { it is InlineNode.Strikethrough })
        assertTrue(opening.content.plainText().contains("сохранённый текст"))
        assertTrue(opening.content.any { it is InlineNode.Image && it.imageId == ImageId("inline.png") })

        val links = opening.content.filterIsInstance<InlineNode.Link>()
        assertTrue(links.any { it.target == LinkTarget.Note(NoteId("note-one")) })
        assertTrue(
            links.any {
                it.target == LinkTarget.Internal(requireNotNull(document.locationsBySourceId["chapter-two"]))
            },
        )
        assertTrue(links.any { it.target == LinkTarget.UnresolvedInternal("missing-anchor") })
        assertTrue(links.any { it.target == LinkTarget.External("https://example.com/reference") })

        assertEquals(1, document.tableOfContents.size)
        assertEquals("Глава первая", document.tableOfContents.single().title)
        assertEquals("Глава вторая", document.tableOfContents.single().children.single().title)
        assertEquals(
            document.locationsBySourceId["chapter-two"],
            document.tableOfContents.single().children.single().location,
        )

        val note = document.notes.single()
        assertEquals(NoteId("note-one"), note.id)
        assertEquals("Сноска 1", note.title)
        assertTrue(note.plainText().contains("Текст сноски"))
        assertNotNull(document.locationsBySourceId["note-one"])

        assertEquals(4, document.images.size)
        val cover = requireNotNull(document.imagesById[ImageId("cover.png")]).content
        val firstLoad = requireNotNull(cover.load())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), firstLoad)
        assertSame(firstLoad, cover.load())
        val broken = requireNotNull(document.imagesById[ImageId("broken.png")]).content
        assertNull(broken.load())
        assertNull(broken.load())
    }

    @Test
    fun `namespace prefixes BOM and declared non UTF8 encoding are respected`() = runBlocking {
        val xml = """
            <?xml version="1.0" encoding="windows-1251"?>
            <fb:FictionBook xmlns:fb="http://www.gribuser.ru/xml/fictionbook/2.0"
                            xmlns:xlink="http://www.w3.org/1999/xlink">
              <fb:description><fb:title-info>
                <fb:book-title>Кириллица</fb:book-title><fb:lang>ru</fb:lang>
                <fb:coverpage><fb:image xlink:href="#cover"/></fb:coverpage>
              </fb:title-info></fb:description>
              <fb:body><fb:section id="s"><fb:title><fb:p>Начало</fb:p></fb:title>
                <fb:p>Текст ёж</fb:p></fb:section></fb:body>
              <fb:binary id="cover" content-type="image/png">AQI=</fb:binary>
            </fb:FictionBook>
        """.trimIndent()
        val encoded = xml.toByteArray(Charset.forName("windows-1251"))
        val document = parser.parse(ByteArrayInputStream(encoded), "encoded.fb2")

        assertEquals("Кириллица", document.metadata.title)
        assertTrue(document.plainText().contains("Текст ёж"))
        assertEquals(ImageId("cover"), document.metadata.coverImageId)

        val utf8 = minimalFb2(title = "BOM title", text = "BOM body").toByteArray()
        val withBom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + utf8
        assertEquals("BOM title", parser.parse(ByteArrayInputStream(withBom)).metadata.title)

        val utf16Xml = minimalFb2("UTF16 title", "UTF16 body")
            .replace("encoding=\"UTF-8\"", "encoding=\"UTF-16\"")
        val utf16Be = byteArrayOf(0xfe.toByte(), 0xff.toByte()) +
            utf16Xml.toByteArray(Charsets.UTF_16BE)
        assertEquals("UTF16 title", parser.parse(ByteArrayInputStream(utf16Be)).metadata.title)
    }

    @Test
    fun `ZIP is sniffed and lexicographically smallest FB2 entry is selected`() = runBlocking {
        val archive = zipOf(
            "z-last.fb2" to minimalFb2("Z title", "z body").toByteArray(),
            "assets/readme.txt" to "not a book".toByteArray(),
            "a-first.fb2" to minimalFb2("A title", "a body").toByteArray(),
        )

        val parsedWithoutName = parser.parse(ByteArrayInputStream(archive))
        val parsedWithPlainZipName = parser.parse(ByteArrayInputStream(archive), "library.zip")
        assertEquals("A title", parsedWithoutName.metadata.title)
        assertEquals("A title", parsedWithPlainZipName.metadata.title)

        val mixedCaseArchive = zipOf(
            "empty-directory/" to byteArrayOf(),
            "z.fb2" to minimalFb2("lowercase", "body").toByteArray(),
            "A.FB2" to minimalFb2("mixed case", "body").toByteArray(),
        )
        assertEquals(
            "mixed case",
            parser.parse(ByteArrayInputStream(mixedCaseArchive), "mixed.data").metadata.title,
        )
    }

    @Test
    fun `empty ZIP and ZIP without FB2 return a typed error`() {
        assertThrows(BookParseException.ArchiveContainsNoFb2::class.java) {
            runBlocking { parser.parse(ByteArrayInputStream(zipOf())) }
        }
        assertThrows(BookParseException.ArchiveContainsNoFb2::class.java) {
            runBlocking {
                parser.parse(ByteArrayInputStream(zipOf("readme.txt" to byteArrayOf(1))), "x.fb2.zip")
            }
        }
    }

    @Test
    fun `truncated archive is reported as malformed`() {
        val archive = zipOf("book.fb2" to minimalFb2("T", "body").toByteArray())
        val truncated = archive.copyOf(archive.size / 2)
        assertThrows(BookParseException.MalformedDocument::class.java) {
            runBlocking { parser.parse(ByteArrayInputStream(truncated), "broken.fb2.zip") }
        }
    }

    @Test
    fun `nested archive in FB2 entry is rejected`() {
        val inner = zipOf("book.fb2" to minimalFb2("Inner", "body").toByteArray())
        val outer = zipOf("book.fb2" to inner)
        assertThrows(BookParseException.NestedArchive::class.java) {
            runBlocking { parser.parse(ByteArrayInputStream(outer)) }
        }
    }

    @Test
    fun `malformed XML missing title and missing body have distinct contained failures`() {
        assertThrows(BookParseException.MalformedDocument::class.java) {
            runBlocking { parser.parse(ByteArrayInputStream("<FictionBook><body>".toByteArray())) }
        }
        assertThrows(BookParseException.MissingTitle::class.java) {
            runBlocking {
                parser.parse(
                    ByteArrayInputStream(
                        "<FictionBook><body><section><p>body</p></section></body></FictionBook>"
                            .toByteArray(),
                    ),
                )
            }
        }
        assertThrows(BookParseException.MissingReadableBody::class.java) {
            runBlocking {
                parser.parse(
                    ByteArrayInputStream(
                        (
                            "<FictionBook><description><title-info><book-title>T</book-title>" +
                                "</title-info></description><body><empty-line/></body></FictionBook>"
                            ).toByteArray(),
                    ),
                )
            }
        }
    }

    @Test
    fun `DOCTYPE and entities are rejected without external access`() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE FictionBook [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <FictionBook><description><title-info><book-title>T</book-title></title-info></description>
              <body><section><p>&xxe;</p></section></body>
            </FictionBook>
        """.trimIndent()
        assertThrows(BookParseException.MalformedDocument::class.java) {
            runBlocking { parser.parse(ByteArrayInputStream(xml.toByteArray())) }
        }

        val utf16 = xml.replace("encoding=\"UTF-8\"", "encoding=\"UTF-16\"")
            .toByteArray(Charsets.UTF_16LE)
        assertThrows(BookParseException.MalformedDocument::class.java) {
            runBlocking { parser.parse(ByteArrayInputStream(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + utf16)) }
        }
    }

    @Test
    fun `duplicate source note image and block ids remain structurally unique`() = runBlocking {
        val xml = """
            <FictionBook xmlns:l="http://www.w3.org/1999/xlink">
              <description><title-info><book-title>Duplicates</book-title></title-info></description>
              <body><section id="same"><p id="p">first</p></section>
                    <section id="same"><p id="p">second <image l:href="#image"/></p></section></body>
              <body name="notes"><section id="note"><p>one</p></section>
                    <section id="note"><p>two</p></section></body>
              <binary id="image" content-type="image/png">AQ==</binary>
              <binary id="image" content-type="image/png">Ag==</binary>
            </FictionBook>
        """.trimIndent()
        val document = parser.parse(ByteArrayInputStream(xml.toByteArray()))

        assertEquals(document.blocks.flattened().map { it.id }.size, document.blocks.flattened().map { it.id }.distinct().size)
        assertEquals(listOf(NoteId("note"), NoteId("note~2")), document.notes.map { it.id })
        assertEquals(listOf(ImageId("image"), ImageId("image~2")), document.images.map { it.id })
        assertEquals(document.blocks.first().id, document.locationsBySourceId["same"]?.blockId)
    }

    @Test
    fun `negative or malformed sequence number is ignored`() = runBlocking {
        val negative = parser.parse(
            ByteArrayInputStream(minimalFb2("T", "body", "<sequence name=\"S\" number=\"-4\"/>").toByteArray()),
        )
        val malformed = parser.parse(
            ByteArrayInputStream(minimalFb2("T", "body", "<sequence name=\"S\" number=\"nope\"/>").toByteArray()),
        )
        assertNull(negative.metadata.sequence?.number)
        assertNull(malformed.metadata.sequence?.number)
    }

    @Test
    fun `hard limits reject oversized direct XML depth blocks binaries and ZIP entries`() {
        val directLimitParser = Fb2BookParser(Fb2ParserLimits().copy(maxDirectDocumentBytes = 32))
        assertThrows(BookParseException.ResourceLimitExceeded::class.java) {
            runBlocking {
                directLimitParser.parse(ByteArrayInputStream(minimalFb2("T", "body").toByteArray()))
            }
        }


        val selectedSizeParser = Fb2BookParser(
            Fb2ParserLimits().copy(maxSelectedDocumentBytes = 64),
        )
        assertThrows(BookParseException.ResourceLimitExceeded::class.java) {
            runBlocking {
                selectedSizeParser.parse(
                    ByteArrayInputStream(
                        zipOf("book.fb2" to minimalFb2("T", "body").toByteArray()),
                    ),
                )
            }
        }

        val ratioParser = Fb2BookParser(Fb2ParserLimits().copy(maxCompressionRatio = 1))
        assertThrows(BookParseException.ResourceLimitExceeded::class.java) {
            runBlocking {
                ratioParser.parse(
                    ByteArrayInputStream(
                        zipOf("book.fb2" to minimalFb2("T", "repeated ".repeat(200)).toByteArray()),
                    ),
                )
            }
        }

        val depthParser = Fb2BookParser(Fb2ParserLimits().copy(maxXmlDepth = 3))
        assertThrows(BookParseException.ResourceLimitExceeded::class.java) {
            runBlocking { depthParser.parse(ByteArrayInputStream(minimalFb2("T", "body").toByteArray())) }
        }

        val blockParser = Fb2BookParser(Fb2ParserLimits().copy(maxBlocks = 1))
        assertThrows(BookParseException.ResourceLimitExceeded::class.java) {
            runBlocking {
                blockParser.parse(
                    ByteArrayInputStream(minimalFb2("T", "one</p><p>two").toByteArray()),
                )
            }
        }

        val binaryParser = Fb2BookParser(
            Fb2ParserLimits().copy(
                maxBinaryEncodedCharacters = 8,
                maxTotalBinaryEncodedCharacters = 8,
            ),
        )
        val binaryXml = minimalFb2("T", "body")
            .replace("</FictionBook>", "<binary id=\"x\">AQIDBAUGBwg=</binary></FictionBook>")
        assertThrows(BookParseException.ResourceLimitExceeded::class.java) {
            runBlocking { binaryParser.parse(ByteArrayInputStream(binaryXml.toByteArray())) }
        }

        val entryCountParser = Fb2BookParser(Fb2ParserLimits().copy(maxArchiveEntries = 1))
        assertThrows(BookParseException.ResourceLimitExceeded::class.java) {
            runBlocking {
                entryCountParser.parse(
                    ByteArrayInputStream(
                        zipOf(
                            "a.fb2" to minimalFb2("A", "a").toByteArray(),
                            "b.txt" to byteArrayOf(1),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun `invalid ZIP hint and source IO errors are mapped to malformed document`() {
        assertThrows(BookParseException.MalformedDocument::class.java) {
            runBlocking {
                parser.parse(ByteArrayInputStream(minimalFb2("T", "body").toByteArray()), "wrong.zip")
            }
        }

        val brokenInput = object : FilterInputStream(ByteArrayInputStream("<Fic".toByteArray())) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                throw java.io.IOException("source failed")
        }
        assertThrows(BookParseException.MalformedDocument::class.java) {
            runBlocking { parser.parse(brokenInput) }
        }
    }

    @Test
    fun `public parser recognizes supported names and parses off caller thread`() = runBlocking {
        val publicParser: BookParser = Fb2BookParser()
        assertTrue(publicParser.supports("BOOK.FB2"))
        assertTrue(publicParser.supports("book.FB2.ZIP"))
        assertTrue(publicParser.supports("book.zip"))
        assertFalse(publicParser.supports("book.epub"))

        val caller = Thread.currentThread().name
        val readThread = AtomicReference<String>()
        val tracked = object : FilterInputStream(
            ByteArrayInputStream(minimalFb2("Threaded", "body").toByteArray()),
        ) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readThread.compareAndSet(null, Thread.currentThread().name)
                return super.read(buffer, offset, length)
            }
        }
        assertEquals("Threaded", publicParser.parse(tracked).metadata.title)
        assertNotEquals(caller, readThread.get())
    }

    @Test
    fun `semantic ids are stable content based and caller retains stream ownership`() = runBlocking {
        val bytes = minimalFb2("Stable", "same body").replace(
            "<title-info>",
            "<title-info>",
        ).replace(
            "</title-info>",
            "</title-info><document-info><id>reused-id</id></document-info>",
        ).toByteArray()
        val firstInput = CloseTrackingInputStream(bytes)
        val first = parser.parse(firstInput)
        val second = parser.parse(ByteArrayInputStream(bytes))
        val changed = parser.parse(
            ByteArrayInputStream(String(bytes).replace("same body", "different body").toByteArray()),
        )

        assertFalse(firstInput.closed)
        assertEquals(first.id, second.id)
        assertEquals(first.blocks.map { it.id }, second.blocks.map { it.id })
        assertEquals(first.tableOfContents, second.tableOfContents)
        assertNotEquals(first.id, changed.id)
        assertEquals("reused-id", first.metadata.documentId)

        val archiveInput = CloseTrackingInputStream(zipOf("book.fb2" to bytes))
        parser.parse(archiveInput)
        assertFalse(archiveInput.closed)
    }

    private fun minimalFb2(title: String, text: String, titleInfoExtra: String = ""): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <FictionBook>
          <description><title-info><book-title>$title</book-title>$titleInfoExtra</title-info></description>
          <body><section id="main"><title><p>Chapter</p></title><p>$text</p></section></body>
        </FictionBook>
    """.trimIndent()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun List<BookBlock>.flattened(): List<BookBlock> = buildList {
        fun append(block: BookBlock) {
            add(block)
            if (block is BookBlock.Quote) block.blocks.forEach(::append)
        }
        this@flattened.forEach(::append)
    }

    private class CloseTrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
