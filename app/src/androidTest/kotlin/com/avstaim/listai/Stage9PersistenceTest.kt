package com.avstaim.listai

import android.os.Bundle
import android.os.Process
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.database.AppPreferences
import com.avstaim.listai.core.model.*
import com.avstaim.listai.format.fb2.Fb2BookParser
import java.io.File
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run the two methods in separate instrumentation invocations with
 * `-e stage9ProcessRestart true`, force-stopping the target package between them.
 * Ordinary suite runs skip these externally coordinated checks. A changed target PID is required;
 * recreating an Activity or reopening Room in the same process does not satisfy this test.
 */
@RunWith(AndroidJUnit4::class)
class Stage9PersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = AppPreferences(context)
    private val marker = AtomicFile(File(context.filesDir, "stage9-process-restart-fixture.json"))
    private var currentStage = "not started"
    private var activeController: LibraryController? = null

    @Test fun prepareProcessRestart() = runBlocking {
        requireExternalRestartRun()
        scenario("prepare") {
            val controller = controller()
            val document = stage("parse deterministic fixture") { fixtureDocument() }
            // A stopped/failed previous run may leave only this fixture behind. Its marker retains
            // the original user settings, so repeating preparation does not overwrite that snapshot.
            if (markerExists()) {
                report("existing fixture marker found; beginning cleanup")
                cleanup(controller, readMarker(), document.id)
            }
            assertFalse("Reserved fixture already exists without its ownership marker",
                controller.repository.books.first().any { it.id == document.id })

            val blocks = document.readingBlocks()
            val location = BookLocation(blocks.last().id, 23,
                blocks.dropLast(1).sumOf { it.plainText().length + 1L } + 23)
            val checkpoint = ReadingProgress(location, CoverPosition.OPEN,
                (location.fallbackGlobalOffset.toDouble() / document.readingLength()).toFloat())
            val expectedReader = ReaderPreferences(23f, ReaderTheme.DARK)
            val originalReader = preferences.reader.first()
            val state = JSONObject().apply {
                put("version", 1)
                put("ready", false)
                put("pid", Process.myPid())
                put("bookId", document.id.value)
                put("originalBookId", preferences.lastBook.first() ?: JSONObject.NULL)
                put("originalReader", originalReader.toJson())
                put("reader", expectedReader.toJson())
                put("checkpoint", checkpoint.toJson())
            }
            // Record ownership before creating the fixture, including for interruption of prepare.
            stage("persist fixture ownership marker") { writeMarker(state) }
            val id = stage("import fixture") {
                controller.repository.import("stage9-process-restart.fb2") { fixtureBytes().inputStream() }
            }
            assertEquals(document.id, id)
            stage("request fixture selection") { onMain { controller.open(id) } }
            val selected = awaitValue("fixture selection", controller.selection,
                describe = { "bookId=${it?.opened?.book?.id}; session=${it?.session}" }) {
                it?.opened?.book?.id == id
            }!!
            stage("enqueue checkpoint, bookmark and reader settings") {
                onMain {
                    controller.progressChanged(id, selected.session, checkpoint)
                    controller.addBookmark()
                    controller.setReaderPreferences(expectedReader)
                }
            }
            val saved = awaitValue("durable checkpoint: $checkpoint", controller.repository.books,
                describe = { books -> books.singleOrNull { it.id == id }?.progress.toString() }) { books ->
                books.any { it.id == id && it.progress == checkpoint }
            }
                .single { it.id == id }
            val bookmark = awaitValue("durable fixture bookmark", controller.repository.bookmarks(id)) { it.size == 1 }.single()
            assertEquals(location, bookmark.location)
            // Synchronize on the already-running production subscriber. A second cold collector
            // intermittently kept the old value when started during this write in emulator runs;
            // the exact DataStore interleaving was not traced. The controller's StateFlow is fed
            // only by DataStore; a fresh snapshot below and the next process independently check
            // publication and durable storage instead of relying on this observer alone.
            awaitValue("published controller reader settings: $expectedReader", controller.readerPreferences) { it == expectedReader }
            assertEquals("A fresh snapshot after publication must match", expectedReader, preferences.reader.first())
            assertEquals(id.value, preferences.lastBook.first())
            assertTrue(preferences.initialized.first())
            assertTrue(File(context.filesDir, "books/${saved.fileName}").isFile)
            state.put("bookmark", bookmark.toJson())
            state.put("ready", true)
            stage("persist ready marker") { writeMarker(state) }
            report("prepared", id)
        }
    }

    @Test fun verifyAfterProcessRestart() = runBlocking {
        requireExternalRestartRun()
        scenario("verify") {
            assertTrue("Run prepareProcessRestart before force-stopping the target", markerExists())
            val state = readMarker()
            assertTrue("Preparation did not finish", state.getBoolean("ready"))
            assertNotEquals("The target process must restart between these methods",
                state.getInt("pid").toLong(), Process.myPid().toLong())
            val document = stage("parse deterministic fixture") { fixtureDocument() }
            val id = BookId(state.getString("bookId"))
            assertEquals("Only the deterministic Stage 9 fixture may be used", document.id, id)
            val checkpoint = state.getJSONObject("checkpoint").toProgress()
            val expectedReader = state.getJSONObject("reader").toReaderPreferences()
            val expectedBookmark = state.getJSONObject("bookmark").toBookmark(id)
            // No Activity is launched by this test. Read the disk-backed DataStore in this new PID
            // before creating the controller, which opens books and writes the selection at startup.
            // This is the durable-state proof; matching a StateFlow in prepare alone is insufficient.
            stage("cold-process preferences before controller startup") {
                assertEquals(expectedReader, preferences.reader.first())
                assertEquals(id.value, preferences.lastBook.first())
                assertTrue(preferences.initialized.first())
            }
            val controller = controller()
            try {
                // This is the new Application controller's automatic startup selection, before any
                // explicit open in this test. All expected values were persisted by the old process.
                val selected = requireNotNull(controller.selection.value)
                assertEquals(id, selected.opened.book.id)
                assertEquals(checkpoint, selected.initialProgress)
                assertEquals(checkpoint, selected.opened.book.progress)
                assertEquals(expectedReader, preferences.reader.first())
                awaitValue("restored controller reader settings: $expectedReader", controller.readerPreferences) { it == expectedReader }
                assertEquals(id.value, preferences.lastBook.first())
                assertTrue(preferences.initialized.first())
                assertEquals(listOf(expectedBookmark), controller.repository.bookmarks(id).first())

                repeat(3) {
                    val reopened = stage("reconcile and reopen fixture ${it + 1}/3") {
                        controller.repository.initialize()
                        controller.repository.open(id)
                    }
                    assertEquals(checkpoint, reopened.book.progress)
                    assertEquals(document.id, reopened.document.id)
                    assertEquals(listOf(expectedBookmark), controller.repository.bookmarks(id).first())
                    assertEquals(expectedReader, preferences.reader.first())
                }
                report("verified after process restart", id)
            } finally {
                cleanup(controller, state, document.id)
            }
        }
    }

    private fun requireExternalRestartRun() {
        assumeTrue("Requires two separate invocations with a target force-stop between them",
            InstrumentationRegistry.getArguments().getString("stage9ProcessRestart") == "true")
    }

    private suspend fun controller(): LibraryController {
        lateinit var result: LibraryController
        stage("create application controller") {
            onMain { result = (context.applicationContext as ListaiApplication).controller }
            activeController = result
        }
        awaitValue("application initialization", result.busy) { !it }
        assertNull("Application startup reported an error", result.error.value)
        return result
    }

    private suspend fun cleanup(controller: LibraryController, state: JSONObject, fixtureId: BookId) {
        assertEquals("Refusing to remove a book outside the Stage 9 fixture", fixtureId.value, state.getString("bookId"))
        stage("cleanup: detach fixture selection") {
            onMain {
                if (controller.selection.value?.opened?.book?.id == fixtureId) controller.returnToShelf()
            }
        }
        // Await the whole delete: observeBooks hides a tombstone before its bookmarks are removed.
        stage("cleanup: delete fixture files, row and bookmarks") { controller.repository.delete(fixtureId) }
        assertTrue(controller.repository.books.first().none { it.id == fixtureId })
        assertTrue(controller.repository.bookmarks(fixtureId).first().isEmpty())
        val originalReader = state.getJSONObject("originalReader").toReaderPreferences()
        val originalBook = state.optString("originalBookId").takeUnless { it.isEmpty() || it == "null" }
            ?.let(::BookId)?.takeIf { id -> controller.repository.books.first().any { it.id == id } }
        stage("cleanup: request original settings and selection $originalBook") {
            onMain {
                controller.setReaderPreferences(originalReader)
                if (originalBook == null) controller.returnToShelf() else controller.open(originalBook)
            }
        }
        awaitValue("cleanup: original controller reader settings $originalReader", controller.readerPreferences) { it == originalReader }
        awaitValue("cleanup: original controller selection $originalBook", controller.selection,
            describe = { "bookId=${it?.opened?.book?.id}; session=${it?.session}" }) {
            it?.opened?.book?.id == originalBook
        }
        // An opened selection is published only after selectBook has completed. For the shelf,
        // deleting the selected fixture above already durably clears lastBook.
        assertEquals(originalReader, preferences.reader.first())
        assertEquals(originalBook?.value, preferences.lastBook.first())
        stage("cleanup: remove fixture marker") { marker.delete() }
    }

    private suspend fun scenario(name: String, block: suspend () -> Unit) {
        try {
            withTimeout(60_000) { block() }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("$name timed out during '$currentStage'; ${controllerState()}", timeout)
        }
    }

    private suspend fun <T> stage(name: String, block: suspend () -> T): T {
        currentStage = name
        report("START $name")
        return block().also { report("DONE $name") }
    }

    private suspend fun <T> awaitValue(
        name: String,
        values: Flow<T>,
        describe: (T) -> String = { it.toString() },
        matches: (T) -> Boolean,
    ): T = stage("wait for $name") {
        var lastObserved = "no value emitted"
        try {
            withTimeout(20_000) {
                values.first { value ->
                    lastObserved = describe(value)
                    matches(value)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("Waiting for '$name' timed out; lastObserved=$lastObserved; ${controllerState()}", timeout)
        }
    }

    private fun controllerState(): String {
        val controller = activeController ?: return "controller not created"
        val selection = controller.selection.value
        return "busy=${controller.busy.value}; error=${controller.error.value}; " +
            "selection=${selection?.opened?.book?.id}; session=${selection?.session}; " +
            "checkpoint=${selection?.initialProgress}; reader=${controller.readerPreferences.value}"
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun markerExists() = marker.baseFile.isFile || File("${marker.baseFile.path}.bak").isFile

    private fun readMarker(): JSONObject = marker.openRead().bufferedReader().use { JSONObject(it.readText()) }

    private fun writeMarker(state: JSONObject) {
        val output = marker.startWrite()
        try {
            output.write(state.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
            marker.finishWrite(output)
        } catch (failure: Throwable) {
            marker.failWrite(output)
            throw failure
        }
    }

    private fun report(status: String, id: BookId? = null) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stage9Persistence", "$status; targetPid=${Process.myPid()}; bookId=${id?.value}")
    })

    private suspend fun fixtureDocument() = fixtureBytes().inputStream().use {
        Fb2BookParser().parse(it, "stage9-process-restart.fb2")
    }

    private fun fixtureBytes() = """<?xml version="1.0" encoding="UTF-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
        <description><title-info><book-title>Listai Stage 9 process restart fixture 7f7fdb0a</book-title>
        <lang>en</lang></title-info></description><body><section id="stage9-chapter">
        <title><p>A durable reading position</p></title>
        <p id="stage9-source">The persisted source includes Unicode: страница 🌲. ${"A restart preserves this sentence and its bookmark. ".repeat(100)}</p>
        </section></body></FictionBook>""".trimIndent().toByteArray(Charsets.UTF_8)

    private fun ReaderPreferences.toJson() = JSONObject().put("fontSize", fontSizeSp.toDouble()).put("theme", theme.name)
    private fun JSONObject.toReaderPreferences() = ReaderPreferences(getDouble("fontSize").toFloat(), ReaderTheme.valueOf(getString("theme")))
    private fun BookLocation.toJson() = JSONObject().put("blockId", blockId.value)
        .put("charOffset", charOffset).put("globalOffset", fallbackGlobalOffset)
    private fun JSONObject.toLocation() = BookLocation(BlockId(getString("blockId")), getInt("charOffset"), getLong("globalOffset"))
    private fun ReadingProgress.toJson() = JSONObject().put("location", requireNotNull(location).toJson())
        .put("cover", cover.name).put("fraction", fraction.toDouble())
    private fun JSONObject.toProgress() = ReadingProgress(getJSONObject("location").toLocation(),
        CoverPosition.valueOf(getString("cover")), getDouble("fraction").toFloat())
    private fun Bookmark.toJson() = JSONObject().put("id", id).put("location", location.toJson())
        .put("createdAt", createdAt).put("snippet", snippet)
    private fun JSONObject.toBookmark(bookId: BookId) = Bookmark(getString("id"), bookId,
        getJSONObject("location").toLocation(), getLong("createdAt"), getString("snippet"))
}
