package com.avstaim.listai

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.os.SystemClock
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

/** A real held finger traverses card edges; it never teleports from source to target. */
@RunWith(AndroidJUnit4::class)
class V12StackTrajectoryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val databaseName = "v12-stack-trajectory-${UUID.randomUUID()}.db"
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
    private var darkTheme by mutableStateOf(false)
    private val captureEvidence get() = InstrumentationRegistry.getArguments().getString("captureStackExtraction") == "true"

    @Before fun setup() = runBlocking {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val context = compose.activity.applicationContext
        database = LibraryDatabase.open(context, databaseName)
        storage = PrivateBookStorage(File(context.cacheDir, "v12-trajectory-${UUID.randomUUID()}"))
        repository = LibraryRepository(context, database.library(), storage, AppPreferences(context), clock = { ++clock })
        repository.initialize()
        ids = (1..3).map { index -> repository.import("journey-$index.fb2") {
            """<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info>
                <book-title>Journey $index</book-title></title-info></description>
                <body><section><p>Journey $index source.</p></section></body></FictionBook>""".byteInputStream()
        } }
        val initial = repository.catalog.first()
        compose.runOnUiThread {
            compose.activity.setContent {
                val current = repository.catalog.collectAsState(initial, context = Dispatchers.Main.immediate).value
                SideEffect { catalog = current }
                ListaiTheme(darkTheme = darkTheme) {
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

    @Test fun stationaryHoldThenSlowPathAcrossAnotherBookCreatesTheIntendedStack() {
        val source = ids[2]
        val neighbour = ids[1]
        val target = ids[0]
        val screen = compose.onNodeWithTag("library_screen").fetchSemanticsNode().boundsInRoot
        val sourceBounds = bounds(source)
        val neighbourBounds = bounds(neighbour)
        val targetBounds = bounds(target)
        compose.mainClock.autoAdvance = false
        var released = false
        try {
            compose.onNodeWithTag("library_screen").performTouchInput { down(sourceBounds.center - screen.topLeft) }
            pauseWithFingerDown(650)
            moveAlong(sourceBounds.center, neighbourBounds.center, screen) {
                assertEquals("The neighbour must not escape on edge entry", neighbourBounds, bounds(neighbour))
                assertEquals(targetBounds, bounds(target))
            }
            moveAlong(neighbourBounds.center, targetBounds.center, screen) {
                assertEquals("The intended group target stays in its slot", targetBounds, bounds(target))
            }
            pauseWithFingerDown(650)
            compose.onNodeWithTag("library_drop_hint").assertIsDisplayed()
            compose.onNodeWithTag("library_dragging_book").assertIsDisplayed()
            assertTrue(commands.isEmpty())
            compose.onNodeWithTag("library_screen").performTouchInput { up() }
            released = true
        } finally {
            if (!released) runCatching { compose.onNodeWithTag("library_screen").performTouchInput { cancel() } }
            compose.mainClock.autoAdvance = true
        }
        compose.onNodeWithTag("library_stack_name").assertIsDisplayed().performTextReplacement("Real trajectory")
        compose.onNodeWithTag("library_name_confirm").performClick()
        compose.waitUntil(10_000) { catalog.stacks.size == 1 }
        assertEquals(listOf(target, source), catalog.stacks.single().bookIds)
        assertEquals(listOf(LibraryCommand.CreateStack("Real trajectory", listOf(target, source))), commands)
        assertNull(opened)
        assertEquals(3, catalog.books.size)
        assertNull(catalog.books.single { it.id == neighbour }.stackId)
        assertEquals("Real trajectory", runBlocking { database.library().allStacks().single().name })
    }

    @Test fun secondFingerAfterStationaryArmedDwellCancelsGroupingWithoutMovingBooks() {
        val originalShelf = catalog.shelf
        val source = ids[2]
        val target = ids[1]
        val screen = compose.onNodeWithTag("library_screen").fetchSemanticsNode().boundsInRoot
        val sourceCenter = bounds(source).center
        val targetCenter = bounds(target).center
        compose.mainClock.autoAdvance = false
        var released = false
        try {
            compose.onNodeWithTag("library_screen").performTouchInput { down(sourceCenter - screen.topLeft) }
            pauseWithFingerDown(650)
            moveAlong(sourceCenter, targetCenter, screen) {}
            pauseWithFingerDown(650)
            compose.onNodeWithTag("library_drop_hint").assertIsDisplayed()
            compose.onNodeWithTag("library_screen").performTouchInput {
                down(1, sourceCenter - screen.topLeft)
                up(1)
                up(0)
            }
            released = true
        } finally {
            if (!released) runCatching { compose.onNodeWithTag("library_screen").performTouchInput { cancel() } }
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        compose.onNodeWithTag("library_stack_name").assertDoesNotExist()
        compose.onNodeWithTag("library_dragging_book").assertDoesNotExist()
        assertTrue(commands.isEmpty())
        assertTrue(catalog.stacks.isEmpty())
        assertEquals(originalShelf, catalog.shelf)
        assertNull(opened)
    }

    @Test fun quickReleaseOnTheVisibleStackSpineAddsTheBookWithoutCenterDwell() {
        val stackId = runBlocking { repository.createStack("Existing stack", listOf(ids[0], ids[1])) }
        compose.waitUntil(10_000) { catalog.stacks.any { it.id == stackId } }
        compose.waitForIdle()
        val source = ids[2]
        val screen = compose.onNodeWithTag("library_screen").fetchSemanticsNode().boundsInRoot
        val sourceCenter = bounds(source).center
        val target = compose.onNodeWithTag("library_stack_$stackId").fetchSemanticsNode().boundsInRoot
        // The lower spines are visible and inviting; rc2 excluded this part of the stack.
        val spine = Offset(target.center.x, target.bottom - target.height * .04f)
        compose.mainClock.autoAdvance = false
        var released = false
        try {
            compose.onNodeWithTag("library_screen").performTouchInput { down(sourceCenter - screen.topLeft) }
            pauseWithFingerDown(650)
            moveAlong(sourceCenter, spine, screen) {
                assertEquals(target, compose.onNodeWithTag("library_stack_$stackId").fetchSemanticsNode().boundsInRoot)
            }
            compose.onNodeWithTag("library_drop_hint").assertIsDisplayed()
            assertTrue(commands.isEmpty())
            compose.onNodeWithTag("library_screen").performTouchInput { up() }
            released = true
        } finally {
            if (!released) runCatching { compose.onNodeWithTag("library_screen").performTouchInput { cancel() } }
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(10_000) { catalog.stacks.single().bookIds.contains(source) }
        assertEquals(listOf(LibraryCommand.AddToStack(stackId, listOf(source))), commands)
        assertEquals(stackId, catalog.books.single { it.id == source }.stackId)
        assertNull(opened)
        compose.onNodeWithTag("library_stack_name").assertDoesNotExist()
    }

    @Test fun draggingOntoTheRemoveTargetReturnsTheBookToItsVisibleShelfSlot() {
        extractWithHeldFinger(listOf(ids[0], ids[1], ids[2]))
    }

    @Test fun draggingTheLastBookOutCollapsesTheStackAndLandsOnTheShelf() {
        extractWithHeldFinger(listOf(ids[0]))
    }

    private fun extractWithHeldFinger(members: List<BookId>) {
        compose.runOnIdle { darkTheme = members.size == 1 }
        val capturePrefix = if (members.size == 1) "dark-last-member" else "light-extraction"
        val stackId = runBlocking {
            val initialMembers = if (members.size == 1) members + ids.first { it !in members } else members
            repository.createStack("Extraction", initialMembers).also {
                if (members.size == 1) repository.extractFromStack(initialMembers.drop(1))
            }
        }
        compose.waitUntil(10_000) { catalog.stacks.any { it.id == stackId } }
        compose.onNodeWithTag("library_stack_$stackId").performClick()
        compose.waitForIdle()
        val source = members.first()
        val screen = compose.onNodeWithTag("library_screen").fetchSemanticsNode().boundsInRoot
        val sourceCenter = bounds(source).center
        compose.mainClock.autoAdvance = false
        var released = false
        try {
            compose.onNodeWithTag("library_screen").performTouchInput { down(sourceCenter - screen.topLeft) }
            pauseWithFingerDown(650)
            val removeTarget = compose.onNodeWithTag("library_extract_target").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            capture("$capturePrefix-0-target")
            moveAlong(sourceCenter, removeTarget.center, screen) {}
            capture("$capturePrefix-1-ready")
            assertTrue("Nothing durable before release", commands.isEmpty())
            compose.onNodeWithTag("library_screen").performTouchInput { up() }
            released = true
            if (captureEvidence && ValueAnimator.areAnimatorsEnabled()) {
                compose.waitUntil(10_000) {
                    compose.mainClock.advanceTimeByFrame()
                    catalog.books.single { it.id == source }.stackId == null &&
                        compose.onAllNodesWithTag("library_stack_menu").fetchSemanticsNodes().isEmpty()
                }
                compose.mainClock.advanceTimeBy(64)
                compose.waitForIdle()
                compose.onNodeWithTag("library_extraction_flight").assertIsDisplayed()
                capture("$capturePrefix-2-flight-start")
                compose.mainClock.advanceTimeBy(128)
                compose.waitForIdle()
                capture("$capturePrefix-3-flight-middle")
                compose.mainClock.advanceTimeBy(128)
                compose.waitForIdle()
                capture("$capturePrefix-4-flight-end")
            }
        } finally {
            if (!released) runCatching { compose.onNodeWithTag("library_screen").performTouchInput { cancel() } }
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(10_000) { catalog.books.single { it.id == source }.stackId == null }
        compose.waitForIdle()
        compose.onNodeWithTag("library_book_${source.value}").assertIsDisplayed()
        compose.onNodeWithTag("library_stack_menu").assertDoesNotExist()
        compose.onNodeWithTag("library_extraction_flight").assertDoesNotExist()
        compose.onNodeWithTag("library_extract_target").assertDoesNotExist()
        capture("$capturePrefix-5-landed")
        assertEquals(listOf(LibraryCommand.ExtractFromStack(listOf(source))), commands)
        assertEquals(3, catalog.books.size)
        if (members.size == 1) assertTrue(catalog.stacks.isEmpty())
        else assertEquals(members.drop(1), catalog.stacks.single().bookIds)
        assertNull(opened)
    }

    private fun capture(name: String) {
        if (!captureEvidence) return
        // Compose's clock is deliberately stopped for the intermediate motion frames. Let
        // the rendered frame reach SurfaceFlinger before taking the diagnostic screenshot.
        val frame = CountDownLatch(1)
        compose.runOnUiThread {
            compose.activity.window.decorView.invalidate()
            Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback { frame.countDown() }
            }
        }
        assertTrue(frame.await(5, TimeUnit.SECONDS))
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = requireNotNull(compose.activity.getExternalFilesDir("rc3-stack-extraction")).also { it.mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun bounds(id: BookId): Rect = compose.onNodeWithTag("library_book_${id.value}").fetchSemanticsNode().boundsInRoot

    private fun moveAlong(from: Offset, to: Offset, screen: Rect, check: () -> Unit) {
        repeat(12) { step ->
            val point = from + (to - from) * ((step + 1) / 12f)
            compose.onNodeWithTag("library_screen").performTouchInput { moveTo(point - screen.topLeft, delayMillis = 32) }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            check()
        }
    }

    private fun pauseWithFingerDown(duration: Long) {
        // No injected pointer timestamp or extra move: exercise the stationary timeout path.
        // The real pause also checks that the next (older) queued test event cannot rewind it.
        SystemClock.sleep(duration)
        compose.mainClock.advanceTimeBy(duration)
        compose.waitForIdle()
    }
}
