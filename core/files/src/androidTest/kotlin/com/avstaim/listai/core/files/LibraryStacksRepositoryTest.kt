package com.avstaim.listai.core.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.database.*
import com.avstaim.listai.core.model.*
import com.avstaim.listai.format.fb2.BookParser
import com.avstaim.listai.format.fb2.Fb2BookParser
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryStacksRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "stacks-${UUID.randomUUID()}.db"
    private lateinit var database: LibraryDatabase
    private lateinit var storage: PrivateBookStorage
    private lateinit var repository: LibraryRepository
    private lateinit var preferences: AppPreferences
    private var time = 1_000L

    @Before fun setup() = runBlocking {
        database = LibraryDatabase.open(context, name)
        storage = PrivateBookStorage(File(context.cacheDir, "stacks-${UUID.randomUUID()}"))
        preferences = AppPreferences(context)
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

    private fun repo(parser: BookParser = Fb2BookParser()) =
        LibraryRepository(context, database.library(), storage, preferences, parser) { ++time }

    @Test fun mixedShelfAndStackOrderSurviveReopenAndUndoKeepsNewProgressImportsAndDeletes() = runBlocking {
        val a = add("A"); val b = add("B"); val c = add("C"); val d = add("D")
        val stack = repository.createStack("  Серия  ", listOf(b, a))
        assertEquals(listOf(d, c, b, a), repository.catalog.first().books.map { it.id })
        assertEquals(listOf(b, a), repository.catalog.first().stacks.single().bookIds)
        repository.moveShelfItem("stack:$stack", "book:${d.value}")
        repository.moveStackBook(stack, a, b)
        val before = repository.catalog.first()
        assertEquals(listOf(a, b, d, c), before.books.map { it.id })
        repository.dissolveStack(stack)
        val doc = repository.open(a).document
        val location = BookLocation(doc.readingBlocks().last().id, 3, 13)
        val fresh = ReadingProgress(location, fraction = .6f,
            titleLocation = BookLocation(BlockId("title"), 2, 2))
        repository.saveProgress(a, fresh)
        repository.addBookmark(a, location, "New bookmark after dissolve")
        val new = add("New import")
        repository.delete(b)
        assertTrue(repository.undoLastLibraryChange())
        assertFalse(repository.canUndo.value)
        val restored = repository.catalog.first()
        assertEquals(listOf(new, a, d, c), restored.books.map { it.id })
        assertEquals(LibraryStack(stack, "Серия", listOf(a)), restored.stacks.single())
        assertEquals(fresh, restored.books.first { it.id == a }.progress)
        assertEquals(1, repository.bookmarks(a).first().size)
        assertTrue(repository.bookmarks(b).first().isEmpty())
        assertFalse(repository.undoLastLibraryChange())
        database.close()
        database = LibraryDatabase.open(context, name)
        repository = repo()
        repository.initialize()
        assertEquals(restored, repository.catalog.first())
        repository.extractFromStack(listOf(a))
        assertTrue(repository.catalog.first().stacks.isEmpty())
        assertEquals(listOf(new, a, d, c), repository.books.first().map { it.id })
        repository.undoLastLibraryChange()
        repository.delete(a)
        assertTrue(repository.catalog.first().stacks.isEmpty())
        assertTrue(database.library().allStacks().isEmpty())
    }

    @Test fun topTransferIsDurableAndUndoRestoresBothStacksWithoutRevertingReading() = runBlocking {
        val a = add("A"); val b = add("B"); val c = add("C"); val d = add("D"); val e = add("E")
        val source = repository.createStack("Source", listOf(a, b))
        val target = repository.createStack("Target", listOf(c, d))
        val before = repository.catalog.first()
        repository.addToStack(target, listOf(b, c, a, b), onTop = true)
        val transferred = repository.catalog.first()
        assertEquals(listOf(b, c, a, d), transferred.stacks.single().bookIds)
        assertEquals(target, transferred.stacks.single().id)
        assertEquals(listOf("book:${e.value}", "stack:$target"), transferred.shelf.map { it.key })
        assertTrue(database.library().allBooks().filter { it.id != e.value }.all { it.stackId == target })
        val document = repository.open(b).document
        val location = BookLocation(document.readingBlocks().first().id, 3, 3)
        val progress = ReadingProgress(location, fraction = .3f)
        repository.saveProgress(b, progress)
        repository.addBookmark(b, location, "After transfer")
        assertTrue(repository.undoLastLibraryChange())
        val undone = repository.catalog.first()
        assertEquals(before.stacks, undone.stacks)
        assertEquals(before.shelf, undone.shelf)
        assertEquals(progress, undone.books.first { it.id == b }.progress)
        assertEquals(1, repository.bookmarks(b).first().size)
        assertEquals(source, database.library().book(b.value)!!.stackId)

        repository.addToStack(target, listOf(b, c, a, b), onTop = true)
        // The menu/picker default still appends instead of replacing the top book.
        repository.addToStack(target, listOf(e, c, e))
        assertEquals(listOf(b, c, a, d, e), repository.catalog.first().stacks.single().bookIds)
        // Selecting every existing member must keep the same stack and shelf slot.
        repository.addToStack(target, listOf(e, d, a, c, b, e), onTop = true)
        val final = repository.catalog.first()
        assertEquals(listOf(e, d, a, c, b), final.stacks.single().bookIds)
        assertEquals(listOf("stack:$target"), final.shelf.map { it.key })
        database.close()
        database = LibraryDatabase.open(context, name)
        repository = repo()
        repository.initialize()
        assertEquals(final, repository.catalog.first())
        assertEquals(progress, repository.catalog.first().books.first { it.id == b }.progress)
        assertEquals(1, repository.bookmarks(b).first().size)
    }

    @Test fun requestedStackIdSurvivesReopenAndCollisionLeavesCatalogAndUndoUntouched() = runBlocking {
        val a = add("A"); val b = add("B"); val c = add("C"); val d = add("D")
        val original = repository.catalog.first()
        val requestedId = UUID.randomUUID().toString()
        assertEquals(requestedId, repository.createStack("Named", listOf(b, a), requestedId))
        val created = repository.catalog.first()
        assertEquals(LibraryStack(requestedId, "Named", listOf(b, a)), created.stacks.single())
        try {
            repository.createStack("Must not replace", listOf(c, d, b), requestedId)
            fail("An existing stack ID must reject the entire operation")
        } catch (_: IllegalArgumentException) {
            assertEquals(created, repository.catalog.first())
            assertEquals("Named", database.library().allStacks().single().name)
            assertNull(database.library().book(c.value)!!.stackId)
            assertTrue(repository.canUndo.value)
        }
        // A rejected operation must not overwrite the previous successful operation's undo.
        assertTrue(repository.undoLastLibraryChange())
        assertEquals(original, repository.catalog.first())
        assertFalse(repository.undoLastLibraryChange())
        repository.createStack("Named", listOf(b, a), requestedId)
        val generatedId = repository.createStack("Generated", listOf(c, d))
        assertNotEquals(requestedId, generatedId)
        assertEquals(generatedId, UUID.fromString(generatedId).toString())
        val final = repository.catalog.first()
        database.close()
        database = LibraryDatabase.open(context, name)
        repository = repo()
        repository.initialize()
        assertEquals(final, repository.catalog.first())
        assertEquals(listOf(b, a), repository.catalog.first().stacks.first { it.id == requestedId }.bookIds)
    }

    @Test fun metadataIsStoredAsSeparateAuthorsAndBackfillIsBoundedResumableAndFailureIsolated() = runBlocking {
        val a = add("A"); val b = add("B"); val c = add("C")
        val imported = repository.books.first().first()
        assertEquals(listOf("First Author", "Second Writer"), imported.authorNames)
        assertEquals("Voyages", imported.series)
        assertEquals(listOf("adventure"), imported.genres)
        val stored = database.library().book(a.value)!!
        val fresh = ReadingProgress(BookLocation(BlockId("saved"), 2, 3), fraction = .3f)
        repository.saveProgress(a, fresh)
        database.openHelper.writableDatabase.execSQL("UPDATE books SET metadataState = 0, authorNamesJson = '[]', series = NULL, genresJson = '[]'")
        assertEquals(1, repository.backfillMetadata(batchSize = 1))
        assertEquals(1, database.library().allBooks().count { it.metadataState == 1 })
        assertEquals(fresh, database.library().book(a.value)!!.progress.toModel())
        assertEquals(stored.fileName, database.library().book(a.value)!!.fileName)
        val started = CompletableDeferred<Unit>()
        val suspendedParser = object : BookParser {
            override fun supports(fileName: String) = true
            override suspend fun parse(input: InputStream, fileName: String?): BookDocument {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch { repo(suspendedParser).backfillMetadata(1) }
        started.await()
        job.cancelAndJoin()
        assertEquals(2, database.library().pendingMetadata(32).size)
        // One broken private source is marked once; it cannot starve other pending rows.
        storage.file(database.library().book(b.value)!!.fileName).writeText("broken")
        database.close()
        database = LibraryDatabase.open(context, name)
        repository = repo()
        assertEquals(2, repository.backfillMetadata())
        assertEquals(2, database.library().book(b.value)!!.metadataState)
        assertEquals(1, database.library().book(c.value)!!.metadataState)
        assertEquals(0, repository.backfillMetadata())
        assertEquals(fresh, database.library().book(a.value)!!.progress.toModel())
    }

    @Test fun absentMetadataIsCompleteAndTransferHasOneMembershipEvenWhenRequestsRace() = runBlocking {
        val ids = (1..4).map { add("Book $it") }
        val first = repository.createStack("First", ids.take(2))
        val second = repository.createStack("Second", ids.drop(2))
        coroutineScope {
            listOf(async { repository.addToStack(first, ids) }, async { repository.addToStack(second, ids) }).awaitAll()
        }
        val state = repository.catalog.first()
        assertEquals(ids.size, state.books.size)
        assertEquals(ids.toSet(), state.stacks.flatMap { it.bookIds }.toSet())
        assertEquals(ids.size, state.stacks.sumOf { it.bookIds.size })
        repository.dismissLibraryUndo()
        assertFalse(repository.undoLastLibraryChange())
        val plain = repository.import("plain.fb2") { fb2("Plain").replace(Regex("<author>.*?</author>|<genre>.*?</genre>|<sequence[^>]*/>"), "").byteInputStream() }
        val row = database.library().book(plain.value)!!
        assertEquals(1, row.metadataState)
        assertNull(row.stackId)
        assertEquals(0, repository.backfillMetadata())
    }

    private suspend fun add(title: String): BookId = repository.import("book.fb2") { fb2(title).byteInputStream() }
    private fun fb2(title: String) = """<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
        <description><title-info><genre>adventure</genre><author><first-name>First</first-name><last-name>Author</last-name></author><author><first-name>Second</first-name><last-name>Writer</last-name></author><book-title>$title</book-title><sequence name="Voyages" number="2"/></title-info></description>
        <body><section><p>Source text $title.</p></section></body></FictionBook>""".trimIndent()
}
