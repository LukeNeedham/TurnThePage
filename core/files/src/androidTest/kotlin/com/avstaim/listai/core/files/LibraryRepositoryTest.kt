package com.avstaim.listai.core.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.database.*
import com.avstaim.listai.core.model.*
import com.avstaim.listai.format.fb2.BookParser
import com.avstaim.listai.format.fb2.Fb2BookParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "library-test-${UUID.randomUUID()}.db"
    private lateinit var database: LibraryDatabase
    private lateinit var preferences: AppPreferences
    private lateinit var storage: PrivateBookStorage
    private lateinit var repository: LibraryRepository
    private var timestamp = 100L

    @Before fun setup() = runBlocking {
        database = LibraryDatabase.open(context, name)
        preferences = AppPreferences(context)
        preferences.selectBook(null)
        storage = PrivateBookStorage(File(context.cacheDir, "books-${UUID.randomUUID()}"))
        repository = repo()
        repository.initialize()
    }

    @After fun cleanup() = runBlocking {
        preferences.selectBook(null)
        database.close()
        context.deleteDatabase(name)
        storage.directory.deleteRecursively()
        Unit
    }

    private fun repo(dao: LibraryDao = database.library(), parser: BookParser = Fb2BookParser()) =
        LibraryRepository(dao, storage, preferences, parser) { ++timestamp }

    @Test fun rawAndZipShareIdentityWithoutResettingDurableCheckpointOrBookmarks() = runBlocking {
        val bytes = fb2("One")
        val id = repository.import("book.fb2") { bytes.inputStream() }
        val document = repository.open(id).document
        val location = BookLocation(document.readingBlocks().last().id, 3, 12)
        val progress = ReadingProgress(location, CoverPosition.BOUNDARY, 1f)
        repository.saveProgress(id, progress)
        repository.addBookmark(id, location, "A saved fragment")
        repository.addBookmark(id, location, "A duplicate tap")

        assertEquals(id, repository.import("renamed.fb2.zip") { zip(bytes).inputStream() })
        assertEquals(1, repository.books.first().size)
        assertEquals(1, repository.bookmarks(id).first().size)
        assertEquals(progress, repository.books.first().single().progress)
        assertEquals(1, storage.directory.listFiles()!!.size)

        database.close()
        database = LibraryDatabase.open(context, name)
        repository = repo()
        repository.initialize()
        val restored = repository.open(id)
        assertEquals(id.value, preferences.lastBook.first())
        assertEquals(progress, restored.book.progress)
        assertEquals(location, repository.bookmarks(id).first().single().location)
        val secondId = repository.import("two.fb2") { fb2("Two").inputStream() }
        repository.open(secondId)
        assertEquals(secondId, repository.books.first().first().id)
        assertEquals(ReadingProgress(), repository.open(secondId).book.progress)
        assertEquals(progress, repository.open(id).book.progress)
    }

    @Test fun deletionCascadesBookmarksAndSelectionButKeepsExternalSource() = runBlocking {
        val original = File(context.cacheDir, "external-${UUID.randomUUID()}.fb2")
        try {
            original.writeBytes(fb2("External"))
            val expected = original.readBytes()
            val id = repository.import(original.name) { original.inputStream() }
            val doc = repository.open(id).document
            val location = BookLocation(doc.readingBlocks().first().id, 0, 0)
            repository.addBookmark(id, location, "Start")
            repository.delete(id)
            // A late callback cannot recreate a deleted row or bookmark.
            repository.saveProgress(id, ReadingProgress(location))
            repository.addBookmark(id, location, "Late")
            assertTrue(repository.books.first().isEmpty())
            assertTrue(repository.bookmarks(id).first().isEmpty())
            assertNull(preferences.lastBook.first())
            assertTrue(storage.directory.listFiles()!!.isEmpty())
            assertArrayEquals(expected, original.readBytes())
        } finally { original.delete() }
    }

    @Test fun malformedUnavailableAndCancelledSourcesLeaveNoRowsOrTemporaryFiles() = runBlocking {
        try { repository.import("broken.fb2") { "<not-fb2/>".byteInputStream() }; fail("Malformed import succeeded") }
        catch (_: com.avstaim.listai.format.fb2.BookParseException) { }
        try { repository.import("missing.fb2") { throw SecurityException("URI revoked") }; fail("Unavailable import succeeded") }
        catch (_: SecurityException) { }
        val parsing = CompletableDeferred<Unit>()
        val parser = object : BookParser {
            override fun supports(fileName: String) = true
            override suspend fun parse(input: InputStream, fileName: String?): BookDocument {
                parsing.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch { repo(parser = parser).import("cancel.fb2") { fb2("Cancelled").inputStream() } }
        parsing.await()
        job.cancelAndJoin()
        assertTrue(repository.books.first().isEmpty())
        assertTrue(storage.directory.listFiles()!!.isEmpty())
    }

    @Test fun failedDatabasePublicationRollsBackPromotedFile() = runBlocking {
        val failingDao = object : LibraryDao by database.library() {
            override suspend fun insert(book: BookEntity): Long = throw IOException("No database space")
        }
        try { repo(dao = failingDao).import("book.fb2") { fb2("Rollback").inputStream() }; fail("Commit succeeded") }
        catch (_: IOException) { }
        assertTrue(repository.books.first().isEmpty())
        assertTrue(storage.directory.listFiles()!!.isEmpty())
    }

    @Test fun startupFinishesTombstonesAndCleansInterruptedImports() = runBlocking {
        val id = repository.import("book.fb2") { fb2("Recover").inputStream() }
        repository.open(id)
        database.library().markDeleting(id.value)
        storage.file("interrupted.part").writeText("partial")
        storage.file("unpublished.book").writeText("unpublished")
        repository.initialize()
        assertTrue(database.library().allBooks().isEmpty())
        assertNull(preferences.lastBook.first())
        assertTrue(storage.directory.listFiles()!!.isEmpty())
    }

    @Test fun concurrentDuplicateImportsPublishExactlyOneCompleteBook() = runBlocking {
        val ids = coroutineScope {
            (1..4).map { async { repository.import("book.fb2") { fb2("Concurrent").inputStream() } } }.awaitAll()
        }
        assertEquals(1, ids.distinct().size)
        assertEquals(1, repository.books.first().size)
        assertEquals(1, storage.directory.listFiles()!!.size)
        assertEquals(ids.first(), repository.open(ids.first()).document.id)
    }

    @Test fun brokenOptionalCoverUsesPlaceholderWithoutRejectingReadableBody() = runBlocking {
        val bytes = fb2("Broken cover").toString(Charsets.UTF_8)
            .replace("<lang>en</lang>", "<lang>en</lang><coverpage><image xmlns:l=\"http://www.w3.org/1999/xlink\" l:href=\"#cover\"/></coverpage>")
            .replace("</FictionBook>", "<binary id=\"cover\" content-type=\"image/png\">bm90IGFuIGltYWdl</binary></FictionBook>")
            .toByteArray()
        val id = repository.import("broken-cover.fb2") { bytes.inputStream() }
        assertNull(repository.books.first().single().coverPath)
        assertEquals("Broken cover", repository.open(id).document.metadata.title)
        assertEquals(1, storage.directory.listFiles()!!.size)
    }

    @Test fun validFb2CoverProducesBoundedOwnedThumbnail() = runBlocking {
        val image = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF244B43.toInt()) }
        val encoded = try {
            ByteArrayOutputStream().also { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                .toByteArray().let { Base64.encodeToString(it, Base64.NO_WRAP) }
        } finally { image.recycle() }
        val bytes = fb2("Cover").toString(Charsets.UTF_8)
            .replace("<lang>en</lang>", "<lang>en</lang><coverpage><image xmlns:l=\"http://www.w3.org/1999/xlink\" l:href=\"#cover\"/></coverpage>")
            .replace("</FictionBook>", "<binary id=\"cover\" content-type=\"image/png\">$encoded</binary></FictionBook>")
            .toByteArray()
        val id = repository.import("cover.fb2") { bytes.inputStream() }
        val row = repository.books.first().single()
        val thumbnail = BitmapFactory.decodeFile(requireNotNull(row.coverPath))
        try {
            assertTrue(thumbnail.width in 1..512 && thumbnail.height in 1..512)
            assertEquals(2f / 3f, thumbnail.width.toFloat() / thumbnail.height, .01f)
        } finally { thumbnail.recycle() }
        assertEquals(2, storage.directory.listFiles()!!.size)
        repository.delete(id)
        assertTrue(storage.directory.listFiles()!!.isEmpty())
    }

    @Test fun savingCurrentCheckpointDoesNotWaitForAnotherBooksSlowImport() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val parser = object : BookParser {
            override fun supports(fileName: String) = true
            override suspend fun parse(input: InputStream, fileName: String?): BookDocument {
                if (fileName == "slow.fb2") { started.complete(Unit); awaitCancellation() }
                return Fb2BookParser().parse(input, fileName)
            }
        }
        val shared = repo(parser = parser)
        val id = shared.import("active.fb2") { fb2("Active").inputStream() }
        val document = shared.open(id).document
        val location = BookLocation(document.readingBlocks().first().id, 1, 1)
        val progress = ReadingProgress(location, CoverPosition.OPEN, .1f)
        val slow = launch { shared.import("slow.fb2") { fb2("Slow").inputStream() } }
        try {
            started.await()
            withTimeout(5_000) { shared.saveProgress(id, progress) }
            assertEquals(progress, shared.books.first().single().progress)
        } finally { slow.cancelAndJoin() }
        assertEquals(1, storage.directory.listFiles()!!.size)
    }

    private fun fb2(title: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
        <description><title-info><book-title>$title</book-title><lang>en</lang></title-info></description>
        <body><section><title><p>$title</p></title><p>Some text in a real imported book.</p></section></body></FictionBook>""".trimIndent().toByteArray()

    private fun zip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { it.putNextEntry(ZipEntry("book.fb2")); it.write(bytes); it.closeEntry() }
    }.toByteArray()
}
