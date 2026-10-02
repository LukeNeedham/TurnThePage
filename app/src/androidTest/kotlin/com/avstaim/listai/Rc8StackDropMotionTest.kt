package com.avstaim.listai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Base64
import android.view.Choreographer
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.avstaim.listai.feature.library.LibraryStackDropProgressKey
import com.avstaim.listai.feature.library.LibraryStackShelfProgressKey
import com.avstaim.listai.feature.library.LibraryStackIncomingCornersKey
import com.avstaim.listai.feature.library.LibraryStackDropKindKey
import java.io.ByteArrayOutputStream
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

/** Real finger + real Room; the clock can stop between release and the durable catalog emission. */
@RunWith(AndroidJUnit4::class)
class Rc8StackDropMotionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val databaseName = "rc8-stack-motion-${UUID.randomUUID()}.db"
    private lateinit var database: LibraryDatabase
    private lateinit var storage: PrivateBookStorage
    private lateinit var repository: LibraryRepository
    private lateinit var ids: List<BookId>
    private lateinit var stackId: String
    private val job = SupervisorJob()
    private val operations = CoroutineScope(job + Dispatchers.Main.immediate)
    private val commit = CompletableDeferred<Unit>()
    private val commands = mutableListOf<LibraryCommand>()
    @Volatile private var catalog = LibraryCatalog()
    private var dark by mutableStateOf(false)
    private var interactionEnabled by mutableStateOf(true)
    private var opened: BookId? = null
    private var clock = 100L

    @Before fun setup() = runBlocking {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val context = compose.activity.applicationContext
        database = LibraryDatabase.open(context, databaseName)
        storage = PrivateBookStorage(File(context.cacheDir, "rc8-stack-motion-${UUID.randomUUID()}"))
        repository = LibraryRepository(context, database.library(), storage, AppPreferences(context), clock = { ++clock })
        repository.initialize()
        ids = (1..4).map { index -> repository.import("motion-$index.fb2") {
            val image = coverPng(index)
            """<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
                <description><title-info><book-title>Book $index</book-title><coverpage><image l:href="#cover"/></coverpage></title-info></description>
                <body><section><p>Source of book $index.</p></section></body>
                <binary id="cover" content-type="image/png">$image</binary></FictionBook>""".byteInputStream()
        } }
        stackId = repository.createStack("Motion", ids.take(2))
        val initial = repository.catalog.first()
        compose.runOnUiThread { compose.activity.setContent {
            val current = repository.catalog.collectAsState(initial, context = Dispatchers.Main.immediate).value
            SideEffect { catalog = current }
            ListaiTheme(darkTheme = dark) {
                LibraryScreen(current.books, false, {}, {}, { opened = it }, {}, catalog = current,
                    shelfZoom = ShelfZoom.SMALL, interactionEnabled = interactionEnabled,
                    onLibraryCommand = { command ->
                        commands += command
                        operations.launch {
                            commit.await()
                            when (command) {
                                is LibraryCommand.AddToStack -> repository.addToStack(command.stackId, command.bookIds, command.onTop)
                                is LibraryCommand.CreateStack -> repository.createStack(command.name, command.bookIds, command.requestedId)
                                else -> error("Unexpected drop command: $command")
                            }
                        }
                    })
            }
        } }
        compose.waitForIdle()
        compose.waitUntil(10_000) { catalog.stacks.size == 1 }
    }

    @After fun cleanup() = runBlocking {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { compose.activity.setContent {} }
        job.cancelAndJoin()
        if (::database.isInitialized) database.close()
        compose.activity.deleteDatabase(databaseName)
        if (::storage.isInitialized) storage.directory.deleteRecursively()
        Unit
    }

    @Test fun releasedCoverMovesBeforeDelayedRoomCommitAndStaysUntilBothHaveFinished() {
        val source = ids.last()
        val targetBefore = stackBounds()
        val sourceBefore = bookBounds(source)
        compose.mainClock.autoAdvance = false
        try {
            holdOverStack(source)
            capture("01-held")
            compose.onNodeWithTag("library_screen").performTouchInput { up() }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertEquals(listOf(LibraryCommand.AddToStack(stackId, listOf(source), onTop = true)), commands)
            assertEquals(targetBefore, stackBounds())
            assertEquals(sourceBefore, bookBounds(source))
            compose.onNodeWithTag("library_dragging_book").assertDoesNotExist()
            compose.onNodeWithTag("library_stack_drop_flight").assertIsDisplayed()
            capture("02-released")
            val samples = mutableListOf<Float>()
            for ((index, millis) in listOf(80L, 128L, 128L, 128L).withIndex()) {
                compose.mainClock.advanceTimeBy(millis)
                compose.waitForIdle()
                samples += flightProgress()
                assertEquals("Room latency must not move the destination slot", targetBefore, stackBounds())
                assertNull(catalog.books.single { it.id == source }.stackId)
                capture("0${index + 3}-moving", incomingMarkers = true)
            }
            assertTrue("The same flight must publish distinct intermediate poses: $samples",
                samples.zipWithNext().all { (a, b) -> b > a } && samples.first() > 0f && samples.last() < 1f)
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            assertEquals(1f, flightProgress(), .0001f)
            capture("07-waiting-for-room", incomingMarkers = true)
            assertEquals(2, catalog.stacks.single().bookIds.size)
            commit.complete(Unit)
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                catalog.books.single { it.id == source }.stackId == stackId && stackBounds() != targetBefore
            }
            val newTarget = stackBounds()
            assertTrue("Fixture must remove an earlier source and move the stack to a new slot", newTarget != targetBefore)
            compose.onNodeWithTag("library_stack_drop_flight").assertIsDisplayed()
            capture("08-reflow-start")
            compose.mainClock.advanceTimeBy(128)
            compose.waitForIdle()
            compose.onNodeWithTag("library_stack_drop_flight").assertIsDisplayed()
            val shelfProgress = compose.onNodeWithTag("library_stack_drop_flight").fetchSemanticsNode().config[LibraryStackShelfProgressKey]
            assertTrue("Shelf relocation must be visible between its endpoints", shelfProgress > 0f && shelfProgress < 1f)
            capture("09-reflow-middle")
            compose.mainClock.advanceTimeBy(320)
            compose.waitForIdle()
            compose.onNodeWithTag("library_stack_drop_flight").assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
        assertEquals(listOf(source) + ids.take(2), catalog.stacks.single().bookIds)
        assertEquals(source, catalog.stacks.single().bookIds.first())
        compose.onNodeWithTag("library_book_${source.value}").assertDoesNotExist()
        assertNull(opened)
        capture("10-landed")
    }

    @Test fun immediateRoomCommitCannotRemoveTheMovingCoverMidFlight() {
        compose.runOnIdle { dark = true }
        val source = ids.last()
        val targetBefore = stackBounds()
        commit.complete(Unit)
        compose.mainClock.autoAdvance = false
        try {
            holdOverStack(source)
            compose.onNodeWithTag("library_screen").performTouchInput { up() }
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                catalog.books.single { it.id == source }.stackId == stackId
            }
            assertTrue(flightProgress() < 1f)
            compose.onNodeWithTag("library_stack_drop_flight").assertIsDisplayed()
            assertEquals(targetBefore, stackBounds())
            // The presentation snapshot still owns the book even though Room removed its slot.
            compose.onNodeWithTag("library_book_${source.value}").assertExists()
            capture("dark-committed-in-flight", incomingMarkers = true)
            compose.mainClock.advanceTimeBy(160)
            capture("dark-committed-later", incomingMarkers = true)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("library_stack_drop_flight").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(1, commands.size)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            holdOverStack(ids[2])
            compose.onNodeWithTag("library_screen").performTouchInput { up() }
            compose.mainClock.advanceTimeByFrame()
            assertTrue("A second drop must start at the held pose, not reuse the old progress", flightProgress() < .05f)
            capture("dark-second-release")
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitUntil(10_000) {
            catalog.stacks.single().bookIds.size == 4 &&
                compose.onAllNodesWithTag("library_stack_drop_flight").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(2, commands.size)
        assertNull(opened)
    }

    @Test fun confirmingDraggedBooksAnimatesBothCoversBeforeDelayedCreationCommit() {
        val source = ids.last()
        val target = ids[2]
        compose.mainClock.autoAdvance = false
        try {
            showCreationDialog(source, target)
            capture("create-01-dialog")
            assertTrue(commands.isEmpty())
            compose.onNodeWithTag("library_name_confirm").performClick()
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            compose.onNodeWithTag("library_stack_name").assertDoesNotExist()
            compose.onNodeWithTag("library_stack_drop_flight").assertIsDisplayed()
            assertEquals("creation", compose.onNodeWithTag("library_stack_drop_flight").fetchSemanticsNode().config[LibraryStackDropKindKey])
            val command = commands.single() as LibraryCommand.CreateStack
            assertEquals(listOf(source, target), command.bookIds)
            assertFalse(command.requestedId.isNullOrBlank())
            capture("create-02-release", incomingMarkers = true)
            val samples = mutableListOf<Float>()
            for ((index, millis) in listOf(96L, 144L, 144L, 160L).withIndex()) {
                compose.mainClock.advanceTimeBy(millis)
                samples += flightProgress()
                assertEquals("Creation must retain the pre-commit shelf", 1, catalog.stacks.size)
                capture("create-0${index + 3}-moving", incomingMarkers = true)
            }
            assertTrue(samples.zipWithNext().all { (a, b) -> b > a } && samples.last() < 1f)
            compose.mainClock.advanceTimeBy(400)
            assertEquals(1f, flightProgress(), .0001f)
            capture("create-07-waiting-for-room", incomingMarkers = true)
            assertEquals(1, catalog.stacks.size)
            commit.complete(Unit)
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                catalog.stacks.any { it.id == command.requestedId } &&
                    compose.onAllNodesWithTag("library_stack_drop_flight").fetchSemanticsNodes().isEmpty()
            }
            assertEquals(listOf(source, target), catalog.stacks.single { it.id == command.requestedId }.bookIds)
            compose.onNodeWithTag("library_stack_${command.requestedId}").assertIsDisplayed()
            capture("create-08-landed")
        } finally { compose.mainClock.autoAdvance = true }
        assertNull(opened)
    }

    @Test fun immediateCreationCommitKeepsItsIncomingCoverWholeAndOnTopInDarkTheme() {
        compose.runOnIdle { dark = true }
        commit.complete(Unit)
        compose.mainClock.autoAdvance = false
        try {
            showCreationDialog(ids.last(), ids[2])
            compose.onNodeWithTag("library_name_confirm").performClick()
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                catalog.stacks.size == 2
            }
            assertTrue("Fast Room cannot finish the visual assembly", flightProgress() < 1f)
            capture("create-dark-early-commit", incomingMarkers = true)
            compose.mainClock.advanceTimeBy(320)
            capture("create-dark-middle", incomingMarkers = true)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("library_stack_drop_flight").fetchSemanticsNodes().isEmpty()
        }
        val created = catalog.stacks.single { it.id != stackId }
        assertEquals(listOf(ids.last(), ids[2]), created.bookIds)
        assertEquals(1, commands.size)
        capture("create-dark-landed")
    }

    @Test fun cancellingCreationRestoresBothBooksWithoutAnAnimationOrDurableCommand() {
        val before = catalog
        compose.mainClock.autoAdvance = false
        try {
            showCreationDialog(ids.last(), ids[2])
            compose.onNodeWithText(compose.activity.getString(com.avstaim.listai.feature.library.R.string.library_cancel)).performClick()
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("library_stack_name").assertDoesNotExist()
            compose.onNodeWithTag("library_stack_drop_flight").assertDoesNotExist()
            compose.onNodeWithTag("library_book_${ids.last().value}").assertIsDisplayed()
            compose.onNodeWithTag("library_book_${ids[2].value}").assertIsDisplayed()
            assertTrue(commands.isEmpty())
            assertEquals(before, catalog)
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun showCreationDialog(source: BookId, target: BookId) {
        val screen = compose.onNodeWithTag("library_screen").fetchSemanticsNode().boundsInRoot
        val start = bookBounds(source).center
        val end = bookBounds(target).center
        compose.onNodeWithTag("library_screen").performTouchInput { down(start - screen.topLeft) }
        SystemClock.sleep(650); compose.mainClock.advanceTimeBy(650)
        repeat(12) { index ->
            val point = start + (end - start) * ((index + 1) / 12f)
            compose.onNodeWithTag("library_screen").performTouchInput { moveTo(point - screen.topLeft, delayMillis = 32) }
            compose.mainClock.advanceTimeBy(32)
        }
        SystemClock.sleep(650); compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithTag("library_drop_hint").assertIsDisplayed()
        compose.onNodeWithTag("library_screen").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("library_stack_name").assertIsDisplayed().performTextReplacement("New motion")
        compose.onNodeWithTag("library_stack_drop_flight").assertDoesNotExist()
        compose.onNodeWithTag("library_pending_stack_book").assertIsDisplayed()
    }

    @Test fun firstVisibleStackMovingIntoThePreviousLazyRowIsPositionedBeforeHandoff() {
        runBlocking {
            repeat(30) { index -> repository.import("extra-$index.fb2") {
                """<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info>
                    <book-title>Extra $index</book-title></title-info></description><body><section><p>Extra source $index.</p></section></body></FictionBook>""".byteInputStream()
            } }
        }
        compose.waitUntil(10_000) { catalog.books.size == 34 }
        compose.waitForIdle()
        val visible = catalog.shelf.filterIsInstance<LibraryShelfItem.Book>().mapNotNull { item ->
            compose.onAllNodesWithTag("library_book_${item.bookId.value}").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
        }.filter { it.width > 0f && it.height > 0f }
        val firstTop = visible.minOf { it.top }
        val columns = visible.count { kotlin.math.abs(it.top - firstTop) < 1f }
        assertTrue(columns >= 2)
        val targetIndex = columns * 4
        runBlocking { repository.moveShelfItem("stack:$stackId", catalog.shelf[targetIndex].key) }
        compose.waitUntil(10_000) { catalog.shelf[targetIndex] == LibraryShelfItem.Stack(stackId) }
        val source = (catalog.shelf.first() as LibraryShelfItem.Book).bookId
        val screen = compose.onNodeWithTag("library_screen").fetchSemanticsNode().boundsInRoot
        val sourceRect = bookBounds(source)
        val sourcePoint = sourceRect.center
        commit.complete(Unit)
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("library_screen").performTouchInput { down(sourcePoint - screen.topLeft) }
            SystemClock.sleep(650); compose.mainClock.advanceTimeBy(650)
            capture("lazy-held")
            // Drive the actual held-finger edge auto-scroll. No ScrollToIndex semantics are
            // available while holding (ordinary scrolling is deliberately disabled).
            val viewport = compose.onNodeWithTag("library_shelf").fetchSemanticsNode().boundsInRoot
            val controls = compose.onNodeWithTag("library_floating_controls").fetchSemanticsNode().boundsInRoot
            val edge = Offset(viewport.center.x, controls.top - 8f)
            compose.onNodeWithTag("library_screen").performTouchInput { moveTo(edge - screen.topLeft, delayMillis = 32) }
            val density = compose.activity.resources.displayMetrics.density
            compose.waitUntil(20_000) {
                compose.mainClock.advanceTimeBy(32)
                val target = compose.onAllNodesWithTag("library_stack_$stackId").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
                target != null && target.width > 0f && target.height >= sourceRect.height * .99f &&
                    // Prefetch can expose an unplaced Lazy node at x=0. The real target
                    // belongs to the same measured first column as the source in this fixture.
                    kotlin.math.abs(target.left - sourceRect.left) < 1f &&
                    target.top >= viewport.top && target.top <= viewport.top + 48f * density
            }
            // The first column is now at the top; removing an earlier source moves its new
            // slot into the previous offscreen row. The handoff must scroll that row into view.
            compose.onNodeWithTag("library_screen").performTouchInput { moveTo(viewport.center - screen.topLeft, delayMillis = 32) }
            SystemClock.sleep(64)
            compose.mainClock.advanceTimeBy(64); compose.waitForIdle()
            val before = stackBounds()
            assertTrue("The original destination must be fully visible", before.height >= sourceRect.height * .99f)
            val destination = Offset(before.center.x, before.top + before.height * .7f)
            compose.onNodeWithTag("library_screen").performTouchInput { moveTo(destination - screen.topLeft, delayMillis = 32) }
            compose.mainClock.advanceTimeBy(32)
            val directory = requireNotNull(compose.activity.getExternalFilesDir("rc8-stack-drop")).also { it.mkdirs() }
            File(directory, "lazy-coordinates.txt").writeText("source=$sourceRect\nscreen=$screen\nviewport=$viewport\nedge=$edge\nbefore=$before\ndestination=$destination\n")
            capture("lazy-before-hint")
            try {
                compose.waitUntil(2_000) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.onAllNodesWithTag("library_drop_hint").fetchSemanticsNodes().isNotEmpty()
                }
            } catch (failure: Throwable) { capture("lazy-no-hint"); throw failure }
            compose.onNodeWithTag("library_drop_hint").assertIsDisplayed()
            compose.onNodeWithTag("library_screen").performTouchInput { up() }
            compose.mainClock.advanceTimeBy(600)
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithTag("library_stack_$stackId").fetchSemanticsNodes().singleOrNull()
                    ?.boundsInRoot?.let { it.width > 0f && it != before } == true
            }
            compose.onNodeWithTag("library_stack_drop_flight").assertIsDisplayed()
            capture("lazy-reposition-start")
            compose.mainClock.advanceTimeBy(96)
            compose.onNodeWithTag("library_stack_drop_flight").assertIsDisplayed()
            capture("lazy-reposition-middle")
            compose.mainClock.advanceTimeBy(400)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
        compose.onNodeWithTag("library_stack_drop_flight").assertDoesNotExist()
        val finalBounds = stackBounds()
        val viewport = compose.onNodeWithTag("library_shelf").fetchSemanticsNode().boundsInRoot
        assertTrue(finalBounds.top >= viewport.top && finalBounds.bottom <= viewport.bottom)
        assertEquals(stackId, catalog.books.single { it.id == source }.stackId)
        assertEquals(1, commands.size)
        capture("lazy-reposition-landed")
    }

    @Test fun missingCommitCannotLeaveTheShelfPermanentlyLocked() {
        val source = ids.last()
        compose.mainClock.autoAdvance = false
        try {
            holdOverStack(source)
            compose.onNodeWithTag("library_screen").performTouchInput { up() }
            compose.mainClock.advanceTimeBy(700)
            compose.onNodeWithTag("library_stack_drop_flight").assertIsDisplayed()
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitUntil(6_000) {
            compose.onAllNodesWithTag("library_stack_drop_flight").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("library_book_${source.value}").assertIsDisplayed().performClick()
        assertEquals(source, opened)
        assertNull(catalog.books.single { it.id == source }.stackId)
        assertEquals(1, commands.size)
    }

    @Test fun cancellingBeforeReleaseCommitsNothingAndLeavingDuringFlightReleasesTheSnapshot() {
        val source = ids.last()
        compose.mainClock.autoAdvance = false
        try {
            holdOverStack(source)
            compose.onNodeWithTag("library_screen").performTouchInput { cancel() }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("library_stack_drop_flight").assertDoesNotExist()
            assertTrue(commands.isEmpty())
            holdOverStack(source)
            compose.onNodeWithTag("library_screen").performTouchInput { up() }
            compose.mainClock.advanceTimeBy(80)
            compose.onNodeWithTag("library_stack_drop_flight").assertIsDisplayed()
            compose.runOnUiThread { interactionEnabled = false }
            compose.waitUntil(2_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithTag("library_stack_drop_flight").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("library_stack_drop_flight").assertDoesNotExist()
            compose.runOnUiThread { interactionEnabled = true }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("library_book_${source.value}").assertIsDisplayed()
            commit.complete(Unit)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitUntil(10_000) { catalog.books.single { it.id == source }.stackId == stackId }
        assertEquals(1, commands.size)
        assertNull(opened)
    }

    private fun holdOverStack(source: BookId) {
        val screen = compose.onNodeWithTag("library_screen").fetchSemanticsNode().boundsInRoot
        val start = bookBounds(source).center
        val bounds = stackBounds()
        val end = Offset(bounds.center.x, bounds.bottom - bounds.height * .1f)
        compose.onNodeWithTag("library_screen").performTouchInput { down(start - screen.topLeft) }
        SystemClock.sleep(650)
        compose.mainClock.advanceTimeBy(650)
        repeat(12) { index ->
            val point = start + (end - start) * ((index + 1) / 12f)
            compose.onNodeWithTag("library_screen").performTouchInput { moveTo(point - screen.topLeft, delayMillis = 32) }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            assertEquals(bounds, stackBounds())
        }
        compose.onNodeWithTag("library_drop_hint").assertIsDisplayed()
    }
    private fun bookBounds(id: BookId): Rect = compose.onNodeWithTag("library_book_${id.value}").fetchSemanticsNode().boundsInRoot
    private fun stackBounds(): Rect = compose.onNodeWithTag("library_stack_$stackId").fetchSemanticsNode().boundsInRoot
    private fun flightProgress() = compose.onNodeWithTag("library_stack_drop_flight").fetchSemanticsNode()
        .config[LibraryStackDropProgressKey]

    private fun capture(name: String, incomingMarkers: Boolean = false) {
        compose.waitForIdle()
        val frame = CountDownLatch(1)
        compose.runOnUiThread {
            compose.activity.window.decorView.invalidate()
            Choreographer.getInstance().postFrameCallback { Choreographer.getInstance().postFrameCallback { frame.countDown() } }
        }
        assertTrue(frame.await(5, TimeUnit.SECONDS))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(150, 5_000)
        SystemClock.sleep(200)
        val bitmap = requireNotNull(automation.takeScreenshot())
        val directory = requireNotNull(compose.activity.getExternalFilesDir("rc8-stack-drop")).also { it.mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        File(directory, "$name.txt").writeText(roots.fetchSemanticsNodes().indices.joinToString("\n\n") { roots[it].printToString() })
        try {
            if (incomingMarkers) assertUnbrokenIncomingCover(bitmap, name)
        } finally { bitmap.recycle() }
    }

    /** The old mask cut these upper markers out while all progress-only assertions passed. */
    private fun assertUnbrokenIncomingCover(bitmap: Bitmap, frame: String) {
        val corners = compose.onNodeWithTag("library_stack_drop_flight").fetchSemanticsNode()
            .config[LibraryStackIncomingCornersKey].toFloatArray()
        val projection = Matrix().apply {
            assertTrue(setPolyToPoly(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), 0, corners, 0, 4))
        }
        markers.forEach { (position, expected) ->
            val point = floatArrayOf(position.x, position.y)
            projection.mapPoints(point)
            val x = point[0].toInt(); val y = point[1].toInt()
            assertTrue("$frame incoming marker must be on screen: $x,$y", x in 5 until bitmap.width - 5 && y in 5 until bitmap.height - 5)
            val matches = (-4..4).sumOf { dx -> (-4..4).count { dy ->
                val pixel = bitmap.getPixel(x + dx, y + dy)
                kotlin.math.abs(Color.red(pixel) - Color.red(expected)) < 55 &&
                    kotlin.math.abs(Color.green(pixel) - Color.green(expected)) < 55 &&
                    kotlin.math.abs(Color.blue(pixel) - Color.blue(expected)) < 55
            } }
            assertTrue("$frame must retain the incoming artwork at $position ($x,$y); matching pixels=$matches", matches >= 9)
        }
    }

    private val markers = listOf(
        Offset(.22f, .16f) to Color.rgb(239, 43, 65),
        Offset(.78f, .16f) to Color.rgb(29, 212, 65),
        Offset(.22f, .84f) to Color.rgb(38, 98, 238),
        Offset(.78f, .84f) to Color.rgb(246, 191, 32),
    )

    private fun coverPng(index: Int): String {
        val bitmap = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (index == 4) Color.rgb(235, 240, 232) else Color.rgb(36, 67, 87))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (index == 4) Color.rgb(36, 67, 87) else Color.rgb(235, 240, 232)
            strokeWidth = 5f; style = Paint.Style.STROKE
        }
        canvas.drawRect(10f, 10f, 230f, 350f, paint)
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER; paint.textSize = 34f
        canvas.drawText("BOOK $index", 120f, 100f, paint)
        canvas.drawRect(40f, 175f, 200f, 185f, paint)
        canvas.drawCircle(120f, 230f, 30f, paint)
        if (index == 4) markers.forEach { (position, color) ->
            paint.color = color
            canvas.drawRect(position.x * 240f - 18f, position.y * 360f - 22f,
                position.x * 240f + 18f, position.y * 360f + 22f, paint)
        }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
