package com.avstaim.listai

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Choreographer
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.database.AppPreferences
import com.avstaim.listai.core.database.LibraryDatabase
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.files.LibraryRepository
import com.avstaim.listai.core.files.PrivateBookStorage
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.library.LibraryCommand
import com.avstaim.listai.feature.library.LibraryScreen
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real pointer paths commit insertion in gaps, at row ends and across rows to Room. */
@RunWith(AndroidJUnit4::class)
class V12StackReorderTrajectoryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val databaseName = "v12-stack-reorder-${UUID.randomUUID()}.db"
    private lateinit var database: LibraryDatabase
    private lateinit var storage: PrivateBookStorage
    private lateinit var repository: LibraryRepository
    private lateinit var ids: List<BookId>
    private val operationJob = SupervisorJob()
    private val operations = CoroutineScope(operationJob + Dispatchers.Main.immediate)
    private val commands = mutableListOf<LibraryCommand>()
    @Volatile private var catalog = LibraryCatalog()
    private var opened: BookId? = null
    private var clock = 100L
    private val captureEvidence get() = InstrumentationRegistry.getArguments().getString("captureShelfReorder") == "true"

    @Before fun setup() = runBlocking {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val context = compose.activity.applicationContext
        database = LibraryDatabase.open(context, databaseName)
        storage = PrivateBookStorage(File(context.cacheDir, "v12-reorder-${UUID.randomUUID()}"))
        repository = LibraryRepository(context, database.library(), storage, AppPreferences(context), clock = { ++clock })
        repository.initialize()
        ids = (1..18).map { index -> repository.import("journey-$index.fb2") {
            """<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info>
                <book-title>Journey $index</book-title></title-info></description>
                <body><section><p>Journey $index source.</p></section></body></FictionBook>""".byteInputStream()
        } }
        val initial = repository.catalog.first()
        compose.runOnUiThread {
            compose.activity.setContent {
                val current = repository.catalog.collectAsState(initial, context = Dispatchers.Main.immediate).value
                SideEffect { catalog = current }
                ListaiTheme {
                    LibraryScreen(current.books, false, {}, {}, { opened = it }, {}, catalog = current,
                        shelfZoom = ShelfZoom.SMALL, onLibraryCommand = { command ->
                            commands += command
                            operations.launch {
                                when (command) {
                                    is LibraryCommand.CreateStack -> repository.createStack(command.name, command.bookIds)
                                    is LibraryCommand.MoveShelfItem -> repository.moveShelfItem(command.key, command.targetKey)
                                    is LibraryCommand.AddToStack -> repository.addToStack(command.stackId, command.bookIds)
                                    is LibraryCommand.ExtractFromStack -> repository.extractFromStack(command.bookIds)
                                    is LibraryCommand.MoveStackBook -> repository.moveStackBook(command.stackId, command.bookId, command.targetBookId)
                                    else -> error("Unexpected trajectory command: $command")
                                }
                            }
                        })
                }
            }
        }
        compose.waitForIdle()
    }

    @After fun cleanup() = runBlocking {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { compose.activity.setContent {} }
        operationJob.cancelAndJoin()
        if (::database.isInitialized) database.close()
        compose.activity.deleteDatabase(databaseName)
        if (::storage.isInitialized) storage.directory.deleteRecursively()
        Unit
    }

    @Test fun slowRootDragIntoTheGapBetweenBooksCommitsTheVisibleInsertion() {
        val rows = visibleRows()
        val source = rows.first().first().first
        val left = rows.first()[1]
        val right = rows.first()[2]
        val gap = Offset((left.second.right + right.second.left) / 2f, left.second.center.y)
        reorder(source, gap, left.first, slow = true)
    }

    @Test fun quickRootDragPastTheLastBookInARowCommitsTheRowEnd() {
        val row = visibleRows().first()
        val source = row.first().first
        val last = row.last()
        val gap = row[1].second.left - row[0].second.right
        reorder(source, Offset(last.second.right + gap / 2f, last.second.center.y), last.first, slow = false)
    }

    @Test fun slowRootDragBetweenRowsInsertsAtTheNearestRowGap() {
        val rows = visibleRows()
        assertTrue("The fixture must expose two rows", rows.size >= 2)
        val source = rows.first().first().first
        val left = rows[1][0]
        val right = rows[1][1]
        val betweenRows = Offset((left.second.right + right.second.left) / 2f,
            left.second.top - left.second.height * .04f)
        reorder(source, betweenRows, left.first, slow = true)
    }

    @Test fun internalStackSupportsSlowGapAndQuickRowEndWithoutMovingTargetsDuringHold() {
        val stackId = runBlocking { repository.createStack("Reorder inside", ids) }
        compose.waitUntil(10_000) { catalog.stacks.any { it.id == stackId } }
        compose.onNodeWithTag("library_stack_$stackId").performClick()
        compose.waitForIdle()
        val row = visibleRows().first()
        val left = row[1]
        val right = row[2]
        reorder(row.first().first, Offset((left.second.right + right.second.left) / 2f, left.second.center.y),
            left.first, slow = true, stackId = stackId)
        val reorderedRow = visibleRows().first()
        val last = reorderedRow.last()
        val gap = reorderedRow[1].second.left - reorderedRow[0].second.right
        reorder(reorderedRow.first().first, Offset(last.second.right + gap / 2f, last.second.center.y),
            last.first, slow = false, stackId = stackId)
    }

    private fun reorder(source: BookId, destination: Offset, target: BookId, slow: Boolean, stackId: String? = null) {
        val before = if (stackId == null) catalog.shelf.map { (it as LibraryShelfItem.Book).bookId }
            else catalog.stacks.single { it.id == stackId }.bookIds
        val expected = before.toMutableList().apply { add(indexOf(target), removeAt(indexOf(source))) }
        val beforeCommands = commands.size
        val screen = compose.onNodeWithTag("library_screen").fetchSemanticsNode().boundsInRoot
        val sourceBounds = bounds(source)
        val restingTargets = visibleRows().flatten().toMap()
        compose.mainClock.autoAdvance = false
        var released = false
        try {
            compose.onNodeWithTag("library_screen").performTouchInput { down(sourceBounds.center - screen.topLeft) }
            pauseWithFingerDown(650)
            val steps = if (slow) 18 else 4
            repeat(steps) { step ->
                val point = sourceBounds.center + (destination - sourceBounds.center) * ((step + 1f) / steps)
                compose.onNodeWithTag("library_screen").performTouchInput { moveTo(point - screen.topLeft, delayMillis = if (slow) 48 else 12) }
                compose.mainClock.advanceTimeBy(if (slow) 48 else 12)
                compose.waitForIdle()
                restingTargets.forEach { (id, resting) ->
                    assertEquals("A target must stay stationary during a real drag", resting, bounds(id))
                }
            }
            // A long pause in a gap must stay an insertion, never become a stack request.
            if (slow) pauseWithFingerDown(650)
            compose.onNodeWithTag("library_insertion_marker").assertIsDisplayed()
            compose.onNodeWithTag("library_drop_hint").assertDoesNotExist()
            assertEquals("Nothing is durable before release", beforeCommands, commands.size)
            capture(if (stackId == null) "root-${if (slow) "slow" else "quick"}" else "stack-${if (slow) "slow" else "quick"}")
            compose.onNodeWithTag("library_screen").performTouchInput { up() }
            released = true
        } finally {
            if (!released) runCatching { compose.onNodeWithTag("library_screen").performTouchInput { cancel() } }
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(10_000) {
            val actual = if (stackId == null) catalog.shelf.map { (it as LibraryShelfItem.Book).bookId }
                else catalog.stacks.single { it.id == stackId }.bookIds
            actual == expected
        }
        val expectedCommand = if (stackId == null) LibraryCommand.MoveShelfItem("book:${source.value}", "book:${target.value}")
            else LibraryCommand.MoveStackBook(stackId, source, target)
        assertEquals(listOf(expectedCommand), commands.drop(beforeCommands))
        assertNull(opened)
        compose.onNodeWithTag("library_stack_name").assertDoesNotExist()
        compose.onNodeWithTag("library_insertion_marker").assertDoesNotExist()
    }

    private fun visibleRows(): List<List<Pair<BookId, Rect>>> = ids.mapNotNull { id ->
        val rect = compose.onAllNodesWithTag("library_book_${id.value}").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
        rect?.takeIf { it.width > 0f && it.height > 0f }?.let { id to it }
    }.groupBy { it.second.top.toInt() }.toSortedMap().values.map { row -> row.sortedBy { it.second.left } }

    private fun capture(name: String) {
        if (!captureEvidence) return
        val frame = CountDownLatch(1)
        compose.runOnUiThread {
            compose.activity.window.decorView.invalidate()
            Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback { frame.countDown() }
            }
        }
        assertTrue(frame.await(5, TimeUnit.SECONDS))
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = requireNotNull(compose.activity.getExternalFilesDir("rc4-shelf-reorder")).also { it.mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun bounds(id: BookId): Rect = compose.onNodeWithTag("library_book_${id.value}").fetchSemanticsNode().boundsInRoot

    private fun pauseWithFingerDown(duration: Long) {
        SystemClock.sleep(duration)
        compose.mainClock.advanceTimeBy(duration)
        compose.waitForIdle()
    }
}
