package com.avstaim.listai.format.epub

import com.avstaim.listai.core.model.*
import com.avstaim.listai.format.fb2.BookParseException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EpubBookParserTest {
    private val parser = EpubBookParser()

    @Test fun `EPUB3 metadata spine navigation rich text and deferred image survive archive close`() = runBlocking {
        val image = byteArrayOf(1, 2, 3, 4)
        val archive = book(
            chapters = linkedMapOf(
                "second" to xhtml("<h1 id='two'>Chapter two</h1><p>Last paragraph</p>"),
                "first" to xhtml("<h1 id='one'>Глава первая</h1><p id='body'>Text <em>italic <b>bold</b></em> <s>gone</s>.</p><div><img src='cover.png' alt='Cover'/></div>"),
            ),
            spine = "<itemref idref='first'/><itemref idref='second'/>",
            extraManifest = "<item id='cover' href='cover.png' media-type='image/png' properties='cover-image'/><item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/>",
            extras = mapOf("OPS/cover.png" to image, "OPS/nav.xhtml" to xhtml("""<nav epub:type='toc'><ol><li><a href='first.xhtml#one'>One</a><ol><li><a href='second.xhtml#two'>Two</a></li></ol></li></ol></nav>""").toByteArray()),
        )
        val document = parser.parse(archive.inputStream(), "sample.epub")
        assertEquals("Quiet book", document.metadata.title)
        assertEquals(listOf("Author One", "Автор Два"), document.metadata.authors)
        assertEquals("ru", document.metadata.language)
        assertEquals(listOf("Fiction"), document.metadata.genres)
        assertEquals("An annotation.", document.metadata.annotation)
        assertEquals("test-book", document.metadata.documentId)
        assertEquals("Глава первая", document.blocks.first().plainText())
        assertEquals("Last paragraph", document.blocks.last().plainText())
        val paragraph = document.blocks.filterIsInstance<BookBlock.Paragraph>().first()
        assertEquals("Text italic bold gone.", paragraph.plainText())
        val emphasis = paragraph.content.filterIsInstance<InlineNode.Emphasis>().single()
        assertTrue(emphasis.children.any { it is InlineNode.Strong })
        assertTrue(paragraph.content.any { it is InlineNode.Strikethrough })
        assertEquals("One", document.tableOfContents.single().title)
        val child = document.tableOfContents.single().children.single()
        assertEquals("Two", child.title)
        assertEquals(document.locationsBySourceId[sourceKey("OPS/second.xhtml", "two")], child.location)
        val cover = document.imagesById.getValue(requireNotNull(document.metadata.coverImageId)).content
        assertArrayEquals(image, cover.load())
        assertSame(cover.load(), cover.load())
        assertTrue(document.id.value.startsWith("sha256:"))
    }

    @Test fun `EPUB2 NCX nesting and legacy cover metadata are supported`() = runBlocking {
        val ncx = """<ncx xmlns='http://www.daisy.org/z3986/2005/ncx/'><navMap><navPoint><navLabel><text>Opening</text></navLabel><content src='chapter.xhtml#a'/><navPoint><navLabel><text>Middle</text></navLabel><content src='chapter.xhtml#b'/></navPoint></navPoint></navMap></ncx>"""
        val document = parser.parse(book(
            chapters = mapOf("chapter" to xhtml("<p id='a'>Opening</p><p id='b'>Middle</p>")),
            extraManifest = "<item id='ncx' href='toc.ncx' media-type='application/x-dtbncx+xml'/><item id='cover' href='cover.jpg' media-type='image/jpeg'/>",
            extraMetadata = "<meta name='cover' content='cover'/>",
            spineAttributes = "toc='ncx'",
            extras = mapOf("OPS/toc.ncx" to ncx.toByteArray(), "OPS/cover.jpg" to byteArrayOf(4, 3)),
        ).inputStream())
        assertEquals("Opening", document.tableOfContents.single().title)
        assertEquals("Middle", document.tableOfContents.single().children.single().title)
        assertEquals(ImageId("epub-image:OPS/cover.jpg"), document.metadata.coverImageId)
    }

    @Test fun `cross-file and inline UTF16 anchors resolve after all spine files`() = runBlocking {
        val document = parser.parse(book(chapters = linkedMapOf(
            "a" to xhtml("<p id='a'>A😀 <span id='inline'>target</span> <a href='b.xhtml#b'>next</a></p>"),
            "b" to xhtml("<p id='b'><a href='a.xhtml#inline'>back</a> <a href='https://example.com'>web</a></p>"),
        )).inputStream())
        val inline = document.locationsBySourceId.getValue(sourceKey("OPS/a.xhtml", "inline"))
        assertEquals(4, inline.charOffset)
        assertEquals("target", document.blocks.first().plainText().substring(inline.charOffset, inline.charOffset + 6))
        val firstLink = (document.blocks.first() as BookBlock.Paragraph).content.filterIsInstance<InlineNode.Link>().single()
        assertEquals(LinkTarget.Internal(document.locationsBySourceId.getValue(sourceKey("OPS/b.xhtml", "b"))), firstLink.target)
        val links = (document.blocks.last() as BookBlock.Paragraph).content.filterIsInstance<InlineNode.Link>()
        assertEquals(LinkTarget.Internal(inline), links.first().target)
        assertEquals(LinkTarget.External("https://example.com"), links.last().target)
    }

    @Test fun `notes remain outside main flow and non linear note document is available`() = runBlocking {
        val document = parser.parse(book(
            chapters = linkedMapOf(
                "main" to xhtml("<p>Main<a epub:type='noteref' href='notes.xhtml#n'>1</a></p><aside epub:type='footnote' id='inline-note'><p>Inline note</p></aside><p>End</p>"),
                "notes" to xhtml("<p>Supplementary frontmatter</p><aside epub:type='footnote' id='n'><h2>1</h2><p>Note body</p></aside>"),
            ), spine = "<itemref idref='main'/><itemref idref='notes' linear='no'/>",
        ).inputStream())
        assertEquals("Main1\nEnd", document.plainText())
        assertEquals(2, document.notes.size)
        assertEquals("1\nNote body", document.notes.last().plainText())
        val target = ((document.blocks.first() as BookBlock.Paragraph).content.filterIsInstance<InlineNode.Link>().single().target as LinkTarget.Note)
        assertEquals(document.notes.last().id, target.noteId)
        assertFalse(document.tableOfContents.any { it.title == "1" })
    }

    @Test fun `stable identity and anchors survive reimport different filename and archive entry order`() = runBlocking {
        val original = book()
        val first = parser.parse(original.inputStream(), "original.epub")
        val renamed = parser.parse(original.inputStream(), "stored.book")
        assertEquals(first.id, renamed.id)
        assertEquals(first.blocks, renamed.blocks)
        assertEquals(first.locationsBySourceId, renamed.locationsBySourceId)
        val changed = parser.parse(book(chapters = mapOf("chapter" to xhtml("<p>Changed text</p>"))).inputStream())
        assertNotEquals(first.id, changed.id)
    }

    @Test fun `nested encoded resource paths and source IDs do not collide`() = runBlocking {
        val archive = book(chapters = mapOf("part/chapter+one" to xhtml("<p id='a@b'>One<a href='#a%40b'>again</a></p><p id='a%40b'>Two</p>")))
        val document = parser.parse(archive.inputStream())
        assertEquals(3, document.locationsBySourceId.size)
        val target = (document.blocks.first() as BookBlock.Paragraph).content.filterIsInstance<InlineNode.Link>().single().target
        assertEquals(LinkTarget.Internal(document.locationsBySourceId.getValue(sourceKey("OPS/part/chapter+one.xhtml", "a@b"))), target)
    }

    @Test fun `hidden active content is ignored and inline CSS typography is retained`() = runBlocking {
        val document = parser.parse(book(chapters = mapOf("chapter" to xhtml("""
            <script>malicious text</script><p hidden='hidden'>hidden</p><div style='display: none'>also hidden</div>
            <p><span style='font-style: italic; font-weight: 700; text-decoration: line-through'>Styled</span><br/>next</p>
        """.trimIndent()))).inputStream())
        assertEquals("Styled\nnext", document.plainText())
        val em = (document.blocks.single() as BookBlock.Paragraph).content.first() as InlineNode.Emphasis
        assertTrue(em.children.first() is InlineNode.Strong)
        assertTrue((em.children.first() as InlineNode.Strong).children.first() is InlineNode.Strikethrough)
    }

    @Test fun `linked and embedded styles retain typography without executing CSS imports or media rules`() = runBlocking {
        val chapter = """<html xmlns='http://www.w3.org/1999/xhtml'><head><link rel='stylesheet' href='style.css'/><style>.inline { font-weight: bold; }</style></head><body><p><span class='italic'>Italic</span> <span class='inline'>Bold</span> <span class='italic' style='font-style: normal'>Normal</span></p></body></html>"""
        val document = parser.parse(book(chapters = mapOf("chapter" to chapter),
            extraManifest = "<item id='css' href='style.css' media-type='text/css'/>",
            extras = mapOf("OPS/style.css" to """@import url('https://example.com/remote.css');
                .unused { color: black; }
                .italic { font-style: italic; }
                @media print { p { display: none; } }
            """.toByteArray()),
        ).inputStream())
        val content = (document.blocks.single() as BookBlock.Paragraph).content
        assertEquals("Italic Bold Normal", document.plainText())
        assertEquals("Italic", content.filterIsInstance<InlineNode.Emphasis>().single().plainText())
        assertEquals(" Bold", content.filterIsInstance<InlineNode.Strong>().single().plainText())
        assertTrue(content.last() is InlineNode.Text)
    }

    @Test fun `empty notes and notes preceding the first paragraph do not steal main anchors`() = runBlocking {
        val document = parser.parse(book(chapters = mapOf("chapter" to xhtml("""
            <aside epub:type='footnote' id='empty'/>
            <aside epub:type='footnote' id='note'><p>Note before main</p></aside>
            <p id='main'>Actual main <a href='#empty'>empty</a></p>
        """.trimIndent()))).inputStream())
        assertEquals(1, document.notes.size)
        assertEquals(document.blocks.single().id, document.locationsBySourceId.getValue(sourceKey("OPS/chapter.xhtml")).blockId)
        assertEquals(document.blocks.single().id, document.locationsBySourceId.getValue(sourceKey("OPS/chapter.xhtml", "main")).blockId)
        assertFalse(document.locationsBySourceId.containsKey(sourceKey("OPS/chapter.xhtml", "empty")))
        assertTrue((document.blocks.single() as BookBlock.Paragraph).content.filterIsInstance<InlineNode.Link>().single().target is LinkTarget.UnresolvedInternal)
    }

    @Test fun `source stream ownership remains with caller`() = runBlocking {
        var closed = false
        val input = object : ByteArrayInputStream(book()) { override fun close() { closed = true; super.close() } }
        parser.parse(input)
        assertFalse(closed)
    }

    @Test fun `container missing package missing title and missing readable body return typed errors`() {
        malformed(zip(mapOf("mimetype" to "application/epub+zip".toByteArray())))
        malformed(book(spine = "<itemref idref='absent'/>"))
        assertThrows(BookParseException.MissingTitle::class.java) {
            runBlocking { parser.parse(book(title = "").inputStream()) }
        }
        assertThrows(BookParseException.MissingReadableBody::class.java) {
            runBlocking { parser.parse(book(chapters = mapOf("chapter" to xhtml(""))).inputStream()) }
        }
    }

    @Test fun `DTD and external entity declarations are rejected including UTF16`() {
        val hostile = """<!DOCTYPE html [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><html xmlns='http://www.w3.org/1999/xhtml'><body><p>&xxe;</p></body></html>"""
        malformed(book(chapters = mapOf("chapter" to hostile)))
        val utf16 = "<?xml version='1.0' encoding='UTF-16'?>$hostile".toByteArray(Charsets.UTF_16)
        malformed(book(extras = mapOf("OPS/chapter.xhtml" to utf16)))
    }

    @Test fun `archive traversal and package traversal are rejected`() {
        malformed(book(extras = mapOf("../outside" to byteArrayOf(1))))
        malformed(book(extraManifest = "<item id='bad' href='../../outside.xhtml' media-type='application/xhtml+xml'/>"))
    }

    @Test fun `actual archive XML image block depth and inflation budgets are enforced`() {
        fun exceeds(limits: EpubParserLimits, archive: ByteArray = book()) {
            assertThrows(BookParseException.ResourceLimitExceeded::class.java) {
                runBlocking { EpubBookParser(limits).parse(archive.inputStream()) }
            }
        }
        exceeds(EpubParserLimits(maxArchiveBytes = 10))
        exceeds(EpubParserLimits(maxArchiveEntries = 1))
        exceeds(EpubParserLimits(maxArchiveEntryBytes = 10))
        exceeds(EpubParserLimits(maxInflatedArchiveBytes = 10))
        exceeds(EpubParserLimits(maxXmlBytes = 10))
        exceeds(EpubParserLimits(maxXmlCharacters = 10))
        exceeds(EpubParserLimits(maxXmlDepth = 2))
        exceeds(EpubParserLimits(maxXmlElements = 2))
        exceeds(EpubParserLimits(maxBlocks = 1), book(chapters = mapOf("chapter" to xhtml("<p>One</p><p>Two</p>"))))
        val withImage = book(extraManifest = "<item id='cover' href='cover.png' media-type='image/png' properties='cover-image'/>",
            extras = mapOf("OPS/cover.png" to ByteArray(20) { it.toByte() }))
        exceeds(EpubParserLimits(maxImageBytes = 10), withImage)
        exceeds(EpubParserLimits(maxTotalImageBytes = 10), withImage)
        exceeds(EpubParserLimits(maxCompressionRatio = 2), book(chapters = mapOf("chapter" to xhtml("<p>${"a".repeat(10_000)}</p>"))))
    }

    @Test fun `truncated and non ZIP documents are malformed`() {
        malformed("not EPUB".toByteArray())
        malformed(book().copyOf(100))
    }

    @Test fun `EPUB extension hints are case insensitive and restricted`() {
        assertTrue(parser.supports(" Book.EPUB "))
        assertFalse(parser.supports("book.fb2"))
        assertFalse(parser.supports("book.zip"))
    }

    private fun malformed(archive: ByteArray) {
        assertThrows(BookParseException.MalformedDocument::class.java) { runBlocking { parser.parse(archive.inputStream()) } }
    }
    private fun xhtml(body: String): String = """<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><head><title>Ignored title</title></head><body>$body</body></html>"""
    private fun book(
        chapters: Map<String, String> = mapOf("chapter" to xhtml("<p id='start'>Readable text</p>")),
        title: String = "Quiet book",
        spine: String = chapters.keys.joinToString("") { "<itemref idref='$it'/>" },
        extraMetadata: String = "",
        extraManifest: String = "",
        spineAttributes: String = "",
        extras: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to """<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/package.opf' media-type='application/oebps-package+xml'/></rootfiles></container>""".toByteArray(),
            "OPS/package.opf" to """<package xmlns='http://www.idpf.org/2007/opf' version='3.0' unique-identifier='uid'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>$title</dc:title><dc:creator>Author One</dc:creator><dc:creator>Автор Два</dc:creator><dc:language>ru</dc:language><dc:subject>Fiction</dc:subject><dc:description>An annotation.</dc:description><dc:identifier id='uid'>test-book</dc:identifier>$extraMetadata</metadata><manifest>${chapters.keys.joinToString("") { "<item id='$it' href='$it.xhtml' media-type='application/xhtml+xml'/>" }}$extraManifest</manifest><spine $spineAttributes>$spine</spine></package>""".toByteArray(),
        )
        chapters.forEach { (name, content) -> entries["OPS/$name.xhtml"] = content.toByteArray() }
        entries.putAll(extras)
        return zip(entries)
    }
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip -> entries.forEach { (path, bytes) ->
            zip.putNextEntry(ZipEntry(path).apply { time = 0L })
            zip.write(bytes)
            zip.closeEntry()
        } }
    }.toByteArray()
}
