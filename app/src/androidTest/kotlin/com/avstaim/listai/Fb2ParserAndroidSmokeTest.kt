package com.avstaim.listai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.model.plainText
import com.avstaim.listai.format.fb2.Fb2BookParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Fb2ParserAndroidSmokeTest {
    @Test
    fun androidSaxParsesBundledDocumentAndCachesLazyImage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val document = context.resources.openRawResource(R.raw.stage3_preview).use { input ->
            Fb2BookParser().parse(input, fileName = "stage3_preview.fb2")
        }

        assertEquals("The Atlas of Quiet Pages", document.metadata.title)
        assertEquals(listOf("Open Book Workshop"), document.metadata.authors)
        assertEquals(1, document.tableOfContents.size)
        assertEquals(1, document.notes.size)
        assertEquals(1, document.images.size)

        val firstLoad = document.images.single().content.load()
        assertNotNull(firstLoad)
        assertSame(firstLoad, document.images.single().content.load())
    }

    @Test
    fun androidSaxHonorsDeclaredLegacyEncoding() = runBlocking {
        val xml = """<?xml version="1.0" encoding="windows-1251"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description><title-info><book-title>Тихая книга</book-title></title-info></description>
              <body><section><p>Русский текст</p></section></body>
            </FictionBook>
        """.trimIndent().toByteArray(Charset.forName("windows-1251"))

        val document = Fb2BookParser().parse(ByteArrayInputStream(xml), fileName = "legacy.fb2")

        assertEquals("Тихая книга", document.metadata.title)
        assertEquals("Русский текст", document.blocks.single().plainText())
    }

    @Test
    fun androidSaxHonorsUtf16ByteOrderMarks() = runBlocking {
        val xml = """<?xml version="1.0" encoding="UTF-16"?>
            <FictionBook>
              <description><title-info><book-title>Тихая книга</book-title></title-info></description>
              <body><section><p>Русский текст ёж</p></section></body>
            </FictionBook>
        """.trimIndent()
        val encodings = listOf(
            Charsets.UTF_16BE to byteArrayOf(0xfe.toByte(), 0xff.toByte()),
            Charsets.UTF_16LE to byteArrayOf(0xff.toByte(), 0xfe.toByte()),
        )
        for ((charset, bom) in encodings) {
            val document = Fb2BookParser().parse(ByteArrayInputStream(bom + xml.toByteArray(charset)))
            assertEquals(charset.name(), "Тихая книга", document.metadata.title)
            assertEquals(charset.name(), "Русский текст ёж", document.blocks.single().plainText())
        }
    }

    @Test
    fun androidSaxHonorsLegacyEncodingInsideZip() = runBlocking {
        val xml = """<?xml version='1.0' encoding = 'windows-1251'?>
            <FictionBook>
              <description><title-info><book-title>Тихая книга</book-title></title-info></description>
              <body><section><p>Русский текст ёж</p></section></body>
            </FictionBook>
        """.trimIndent().toByteArray(Charset.forName("windows-1251"))
        val archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("book.fb2"))
                zip.write(xml)
                zip.closeEntry()
            }
        }.toByteArray()
        val document = Fb2BookParser().parse(ByteArrayInputStream(archive), "legacy.fb2.zip")

        assertEquals("Тихая книга", document.metadata.title)
        assertEquals("Русский текст ёж", document.blocks.single().plainText())
    }

    @Test
    fun androidRuntimeParsesZipContainer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rawFb2 = context.resources.openRawResource(R.raw.stage3_preview).use { it.readBytes() }
        val archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("BOOK.FB2"))
                zip.write(rawFb2)
                zip.closeEntry()
            }
        }.toByteArray()

        val document = Fb2BookParser().parse(
            input = ByteArrayInputStream(archive),
            fileName = "preview.fb2.zip",
        )

        assertEquals("The Atlas of Quiet Pages", document.metadata.title)
    }
}
