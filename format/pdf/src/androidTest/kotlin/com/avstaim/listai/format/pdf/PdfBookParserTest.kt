package com.avstaim.listai.format.pdf

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.readingLength
import com.avstaim.listai.format.fb2.BookParseException
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfBookParserTest {
    @Test fun importsEveryPageIncludingBlankWithStableIdentityAndOriginalDimensions() = runBlocking {
        val file = fixture()
        val copy = File(file.parentFile, "${file.name}-renamed.pdf")
        try {
            val document = PdfBookParser(InstrumentationRegistry.getInstrumentation().targetContext).parse(file, "Книга.pdf")
            file.copyTo(copy, overwrite = true)
            val reopened = PdfBookParser(InstrumentationRegistry.getInstrumentation().targetContext).parse(copy, "other.pdf")
            assertEquals("Книга", document.metadata.title)
            assertTrue(document.images.isEmpty())
            assertNull(document.metadata.coverImageId)
            assertEquals(document.id, reopened.id)
            assertEquals(document.blocks.map { it.id }, reopened.blocks.map { it.id })
            val pages = document.blocks.filterIsInstance<BookBlock.FixedPage>()
            assertEquals(2, pages.size)
            assertEquals(2L, document.readingLength())
            assertEquals(listOf(300 to 600, 700 to 200), pages.map { it.widthPoints to it.heightPoints })
            assertEquals(listOf(0, 1), pages.map { it.pageIndex })
            assertTrue(pages.all { it.sourcePath == file.absolutePath })
            assertTrue(file.readBytes().contentEquals(copy.readBytes()))
        } finally { file.delete(); copy.delete() }
    }

    @Test fun invalidAndOversizedDocumentsUseTypedFailures() = runBlocking {
        val file = fixture()
        try {
            try { PdfBookParser(InstrumentationRegistry.getInstrumentation().targetContext, PdfParserLimits(maxPages = 1)).parse(file); fail("Page limit") }
            catch (error: BookParseException.ResourceLimitExceeded) { assertEquals("PDF pages", error.resource) }
            try { PdfBookParser(InstrumentationRegistry.getInstrumentation().targetContext, PdfParserLimits(maxDocumentBytes = 5)).parse(file); fail("Byte limit") }
            catch (error: BookParseException.ResourceLimitExceeded) { assertEquals("PDF bytes", error.resource) }
            file.writeText("%PDF-1.7\nnot a valid cross-reference table")
            try { PdfBookParser(InstrumentationRegistry.getInstrumentation().targetContext).parse(file); fail("Malformed PDF") }
            catch (_: BookParseException.MalformedDocument) { }
            file.writeText("Not a PDF")
            try { PdfBookParser(InstrumentationRegistry.getInstrumentation().targetContext).parse(file); fail("Incorrect signature") }
            catch (_: BookParseException.MalformedDocument) { }
        } finally { file.delete() }
    }

    @Test fun passwordProtectedPdfHasSpecificFailureWithoutPublishingPages() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("listai-encrypted-", ".pdf", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("password-protected.pdf").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            try { PdfBookParser(InstrumentationRegistry.getInstrumentation().targetContext).parse(file); fail("Encrypted PDF must be reported") }
            catch (_: BookParseException.EncryptedDocument) { }
        } finally { file.delete() }
    }

    @Test fun failedPdfDoesNotPoisonLaterDocumentsOnTheSameParser() = runBlocking {
        val parser = PdfBookParser(InstrumentationRegistry.getInstrumentation().targetContext)
        val valid = fixture()
        val invalid = File(valid.parentFile, "${valid.name}.invalid")
        try {
            repeat(3) {
                invalid.writeText("%PDF-1.7\nMalformed native PDF input")
                try { parser.parse(invalid); fail("Malformed PDF") }
                catch (_: BookParseException.MalformedDocument) { }
                assertEquals(2, parser.parse(valid).blocks.size)
                InstrumentationRegistry.getInstrumentation().context.assets.open("password-protected.pdf").use { input ->
                    invalid.outputStream().use { input.copyTo(it) }
                }
                try { parser.parse(invalid); fail("Encrypted PDF") }
                catch (_: BookParseException.EncryptedDocument) { }
                assertEquals(2, parser.parse(valid).blocks.size)
            }
            invalid.writeBytes("unrelated prefix ".toByteArray() + valid.readBytes())
            try { parser.parse(invalid); fail("PDF header must start the document") }
            catch (_: BookParseException.MalformedDocument) { }
        } finally { valid.delete(); invalid.delete() }
    }

    @Test fun cancellationUnbindsEvenWhenLegacyServiceHasNotConnected() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1)
        val requested = CountDownLatch(1)
        val unbound = AtomicInteger()
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
                requested.countDown()
                return true // Simulate a stalled platform bind; no native process is launched.
            }
            override fun unbindService(connection: ServiceConnection) { unbound.incrementAndGet() }
        }
        val file = fixture()
        val parsing = async(Dispatchers.Default) { PdfBookParser(context).parse(file) }
        try {
            assertTrue(requested.await(5, TimeUnit.SECONDS))
            parsing.cancelAndJoin()
            assertTrue(parsing.isCancelled)
            assertEquals(1, unbound.get())
        } finally { parsing.cancelAndJoin(); file.delete() }
    }

    private fun fixture(): File {
        val file = File.createTempFile("listai-pdf-", ".pdf",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        val document = PdfDocument()
        try {
            val first = document.startPage(PdfDocument.PageInfo.Builder(300, 600, 1).create())
            first.canvas.drawColor(Color.BLUE)
            document.finishPage(first)
            val blank = document.startPage(PdfDocument.PageInfo.Builder(700, 200, 2).create())
            document.finishPage(blank)
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        return file
    }
}
