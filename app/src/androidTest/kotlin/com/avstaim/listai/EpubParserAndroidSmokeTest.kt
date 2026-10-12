package com.avstaim.listai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.database.AppPreferences
import com.avstaim.listai.core.database.LibraryDatabase
import com.avstaim.listai.core.files.LibraryRepository
import com.avstaim.listai.core.files.PrivateBookStorage
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.InlineNode
import com.avstaim.listai.core.model.LinkTarget
import com.avstaim.listai.core.model.CoverPosition
import com.avstaim.listai.core.model.ReadingProgress
import com.avstaim.listai.core.model.plainText
import com.avstaim.listai.format.epub.EpubBookParser
import com.avstaim.listai.format.fb2.BookParseException
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the platform SAX implementation, whose entity features differ from the JVM. */
@RunWith(AndroidJUnit4::class)
class EpubParserAndroidSmokeTest {
    @Test
    fun legacyEpubImportDeduplicatesAndRestoresProgressFromPrivateCopy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val databaseName = "epub-smoke-$token.db"
        var database = LibraryDatabase.open(context, databaseName)
        val preferences = AppPreferences(context)
        val previousSelection = preferences.lastBook.first()
        val storage = PrivateBookStorage(File(context.cacheDir, "epub-smoke-$token"))
        val original = File(context.cacheDir, "external-epub-$token.fb2")
        val shares = File(context.cacheDir, "epub-shares-$token")
        val bytes = epub(XmlEncoding("UTF-8", Charsets.UTF_8))
        try {
            original.writeBytes(bytes)
            var repository = LibraryRepository(context, database.library(), storage, preferences)
            repository.initialize()
            // SAF filenames can be misleading. The copied bytes determine the parser.
            val id = repository.import(original.name) { original.inputStream() }
            val opened = repository.open(id)
            assertLegacyDocument(opened.document)
            val anchor = opened.document.locationsBySourceId.getValue("OPS/chapter.xhtml@mark")
            val progress = ReadingProgress(anchor, CoverPosition.OPEN, .4f)
            repository.saveProgress(id, progress)
            repository.addBookmark(id, anchor, "A saved fragment")
            assertEquals(id, repository.import("renamed.epub") { bytes.inputStream() })
            assertEquals(progress, repository.books.first().single().progress)

            database.close()
            database = LibraryDatabase.open(context, databaseName)
            repository = LibraryRepository(context, database.library(), storage, preferences)
            repository.initialize()
            val restored = repository.open(id)
            assertEquals(opened.document.id, restored.document.id)
            assertEquals(opened.document.locationsBySourceId, restored.document.locationsBySourceId)
            assertEquals(progress, restored.book.progress)
            assertEquals(anchor, repository.bookmarks(id).first().single().location)
            val shared = repository.prepareShare(id, shares)
            assertEquals("application/epub+zip", shared.mimeType)
            assertArrayEquals(bytes, shared.file.readBytes())

            val hostile = "<!DOCTYPE html [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><html><body><p>&xxe;</p></body></html>"
            val privateFiles = storage.directory.listFiles()!!.map { it.name }.toSet()
            try {
                repository.import("hostile.epub") { epub(XmlEncoding("UTF-8", Charsets.UTF_8), hostile).inputStream() }
                fail("Hostile EPUB was published")
            } catch (_: BookParseException.MalformedDocument) { }
            assertEquals(listOf(id), repository.books.first().map { it.id })
            assertEquals(privateFiles, storage.directory.listFiles()!!.map { it.name }.toSet())
            repository.delete(id)
            assertTrue(storage.directory.listFiles()!!.isEmpty())
            assertArrayEquals(bytes, original.readBytes())
        } finally {
            preferences.selectBook(previousSelection)
            database.close()
            context.deleteDatabase(databaseName)
            storage.directory.deleteRecursively()
            shares.deleteRecursively()
            original.delete()
        }
    }

    @Test
    fun androidSaxReadsLegacyXhtmlAndNcxDoctypesWithoutFetchingDtds() = runBlocking {
        val bytes = epub(XmlEncoding("UTF-8", Charsets.UTF_8))
        val parser = EpubBookParser()
        val document = parser.parse(bytes.inputStream(), "legacy.epub")
        assertLegacyDocument(document)

        // Opening the private copy has no original extension and must retain source identity.
        val reopened = parser.parse(bytes.inputStream(), "private-copy.book")
        assertEquals(document.id, reopened.id)
        assertEquals(document.blocks, reopened.blocks)
        assertEquals(document.locationsBySourceId, reopened.locationsBySourceId)
    }

    @Test
    fun androidSaxDecodesUtf16DoctypesEntitiesAndAnchorsInBothByteOrders() = runBlocking {
        for (encoding in utf16Encodings) {
            val document = EpubBookParser().parse(epub(encoding).inputStream(), "utf16.epub")
            assertLegacyDocument(document)
        }
    }

    @Test
    fun androidSaxDecodesDeclaredWindows1251InPackageXhtmlAndNcx() = runBlocking {
        val encoding = XmlEncoding("windows-1251", Charset.forName("windows-1251"))
        val document = EpubBookParser().parse(epub(encoding).inputStream(), "legacy.epub")
        assertLegacyDocument(document)
    }

    @Test
    fun androidSaxRejectsInternalEntityDeclarationsInUtf8AndUtf16() = runBlocking {
        val hostile = """
            <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN"
                "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd" [
              <!ENTITY first "expanded">
              <!ENTITY repeated "&first;&first;&first;">
            ]>
            <html xmlns="http://www.w3.org/1999/xhtml"><body><p>&repeated;</p></body></html>
        """.trimIndent()
        for (encoding in listOf(XmlEncoding("UTF-8", Charsets.UTF_8)) + utf16Encodings) {
            try {
                EpubBookParser().parse(epub(encoding, hostile).inputStream(), "hostile.epub")
                fail("Internal entity declarations were accepted for ${encoding.charset.name()}")
            } catch (_: BookParseException.MalformedDocument) {
                // The compatibility path must not admit custom entity expansion.
            }
        }
    }

    private fun assertLegacyDocument(document: BookDocument) {
        assertEquals("Тихая книга", document.metadata.title)
        assertEquals(listOf("Автор"), document.metadata.authors)
        assertEquals("legacy-android-smoke", document.metadata.documentId)
        assertEquals(listOf("Глава", "A😀\u00a0ёж—“текст” назад", "Конец"), document.blocks.map { it.plainText() })

        val heading = document.locationsBySourceId.getValue("OPS/chapter.xhtml@opening")
        val inline = document.locationsBySourceId.getValue("OPS/chapter.xhtml@mark")
        assertEquals(document.blocks.first().id, heading.blockId)
        assertEquals(4, inline.charOffset) // A + two UTF-16 units for the emoji + NBSP.
        assertEquals(10L, inline.fallbackGlobalOffset)
        val paragraph = document.blocks[1] as BookBlock.Paragraph
        assertEquals("ёж", paragraph.plainText().substring(inline.charOffset, inline.charOffset + 2))
        assertEquals(LinkTarget.Internal(inline), paragraph.content.filterIsInstance<InlineNode.Link>().single().target)

        val toc = document.tableOfContents.single()
        assertEquals("Открытие", toc.title)
        assertEquals(heading, toc.location)
        assertEquals("Середина", toc.children.single().title)
        assertEquals(inline, toc.children.single().location)
    }

    private data class XmlEncoding(val declaration: String, val charset: Charset, val bom: ByteArray = byteArrayOf()) {
        fun encode(xml: String): ByteArray = bom +
            ("<?xml version='1.0' encoding='$declaration'?>\n" + xml).toByteArray(charset)
    }

    private val utf16Encodings = listOf(
        XmlEncoding("UTF-16", Charsets.UTF_16BE, byteArrayOf(0xfe.toByte(), 0xff.toByte())),
        XmlEncoding("UTF-16", Charsets.UTF_16LE, byteArrayOf(0xff.toByte(), 0xfe.toByte())),
    )

    private fun epub(encoding: XmlEncoding, chapter: String = legacyChapter): ByteArray {
        val resources = linkedMapOf(
            "mimetype" to "application/epub+zip".toByteArray(Charsets.US_ASCII),
            "META-INF/container.xml" to encoding.encode("""
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                  <rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent()),
            "OPS/package.opf" to encoding.encode("""
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="book-id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Тихая книга</dc:title><dc:creator>Автор</dc:creator><dc:language>ru</dc:language>
                    <dc:identifier id="book-id">legacy-android-smoke</dc:identifier>
                  </metadata>
                  <manifest>
                    <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                  </manifest>
                  <spine toc="ncx"><itemref idref="chapter"/></spine>
                </package>
            """.trimIndent()),
            "OPS/chapter.xhtml" to encoding.encode(chapter),
            "OPS/toc.ncx" to encoding.encode("""
                <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN"
                    "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <head><meta name="dtb:uid" content="legacy-android-smoke"/></head>
                  <docTitle><text>Тихая книга</text></docTitle>
                  <navMap><navPoint id="opening" playOrder="1">
                    <navLabel><text>Открытие</text></navLabel><content src="chapter.xhtml#opening"/>
                    <navPoint id="middle" playOrder="2">
                      <navLabel><text>Середина</text></navLabel><content src="chapter.xhtml#mark"/>
                    </navPoint>
                  </navPoint></navMap>
                </ncx>
            """.trimIndent()),
        )
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for ((path, bytes) in resources) {
                    val entry = ZipEntry(path)
                    if (path == "mimetype") {
                        entry.method = ZipEntry.STORED
                        entry.size = bytes.size.toLong()
                        entry.crc = CRC32().apply { update(bytes) }.value
                    }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private val legacyChapter = """
        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN"
            "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Тихая книга</title></head><body>
          <h1 id="opening">Глава</h1>
          <p>A&#x1F600;&nbsp;<span id="mark">ёж</span>&mdash;&ldquo;текст&rdquo; <a href="#mark">назад</a></p>
          <p>Конец</p>
        </body></html>
    """.trimIndent()
}
