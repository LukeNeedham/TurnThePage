package com.avstaim.listai

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real shelf input and menu operations; assertions use the committed Room catalog. */
@RunWith(AndroidJUnit4::class)
class V12StackGesturesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val databaseName = "v12-stack-gestures-${UUID.randomUUID()}.db"
    private lateinit var database: LibraryDatabase
    private lateinit var storage: PrivateBookStorage
    private lateinit var repository: LibraryRepository
    private lateinit var preferences: AppPreferences
    private var oldZoom = ShelfZoom.MEDIUM
    private var clock = 100L
    private lateinit var ids: List<BookId>
    @Volatile private var catalog = LibraryCatalog()
    private var opened: BookId? = null
    private val commands = mutableListOf<LibraryCommand>()
    private val operationsJob = SupervisorJob()
    private val operations = CoroutineScope(operationsJob + Dispatchers.Main.immediate)

    @Before fun setup() = runBlocking {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val context = compose.activity.applicationContext
        database = LibraryDatabase.open(context, databaseName)
        storage = PrivateBookStorage(File(context.cacheDir, "v12-stacks-${UUID.randomUUID()}"))
        preferences = AppPreferences(context)
        oldZoom = preferences.shelfZoom.first()
        preferences.setShelfZoom(ShelfZoom.MEDIUM)
        repository = LibraryRepository(context, database.library(), storage, preferences, clock = { ++clock })
        repository.initialize()
        ids = (1..6).map { index -> repository.import("book-$index.fb2") {
            """<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info>
                <author><first-name>Writer</first-name></author><book-title>Journey $index</book-title>
                <sequence name="Journeys"/><genre>sf</genre></title-info></description>
                <body><section><p>Source text for journey $index.</p></section></body></FictionBook>""".byteInputStream()
        } }
    }

    @After fun cleanup() = runBlocking {
        compose.runOnUiThread { compose.activity.setContent {} }
        operationsJob.cancelAndJoin()
        if (::preferences.isInitialized) preferences.setShelfZoom(oldZoom)
        if (::database.isInitialized) database.close()
        compose.activity.deleteDatabase(databaseName)
        if (::storage.isInitialized) storage.directory.deleteRecursively()
        Unit
    }

    @Test fun centerDwellCreatesANamedStackAndDraggingAnotherBookAddsItWithoutOpening() {
        showShelf()
        val source = ids[5]
        val target = ids[4]
        holdAndDrop("library_book_${source.value}", "library_book_${target.value}", dwell = 650)
        compose.onNodeWithTag("library_stack_name").assertIsDisplayed()
        compose.runOnIdle { assertTrue(commands.isEmpty()); assertNull(opened) }
        compose.onNodeWithTag("library_stack_name").performTextReplacement("Journeys together")
        compose.onNodeWithTag("library_name_confirm").performClick()
        compose.waitUntil(10_000) { catalog.stacks.size == 1 }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("library_stack_drop_flight").fetchSemanticsNodes().isEmpty() }
        val stackId = catalog.stacks.single().id
        assertEquals(listOf(source, target), catalog.stacks.single().bookIds)
        assertEquals("Journeys together", runBlocking { database.library().allStacks().single().name })

        val third = ids[3]
        holdAndDrop("library_book_${third.value}", "library_stack_$stackId", dwell = 650)
        compose.waitUntil(10_000) { catalog.stacks.single().bookIds.size == 3 }
        assertEquals(listOf(third, source, target), catalog.stacks.single().bookIds)
        assertEquals(stackId, catalog.books.single { it.id == third }.stackId)
        compose.runOnIdle { assertNull(opened); assertEquals(1, commands.count { it is LibraryCommand.AddToStack }) }

        compose.waitUntil(10_000) { compose.onAllNodesWithTag("library_stack_drop_flight").fetchSemanticsNodes().isEmpty() }
        // Inside a stack, the same center drop only changes internal order; nesting is absent.
        compose.onNodeWithTag("library_stack_$stackId").performClick()
        holdAndDrop("library_book_${third.value}", "library_book_${source.value}", dwell = 100)
        compose.waitUntil(10_000) { catalog.stacks.single().bookIds.first() == source }
        assertEquals(listOf(source, third, target), catalog.stacks.single().bookIds)
        compose.onNodeWithTag("library_back").performClick()
        compose.onNodeWithTag("library_stack_$stackId").assertIsDisplayed()
        compose.onNodeWithTag("library_stack_name").assertDoesNotExist()
    }

    @Test fun accessibleMoveExtractionAndBulkAdditionKeepSingleMembershipAndDropEmptyStacks() {
        val firstStack = runBlocking { repository.createStack("First", listOf(ids[5], ids[4])) }
        val secondStack = runBlocking { repository.createStack("Second", listOf(ids[3], ids[2])) }
        showShelf()
        compose.onNodeWithTag("library_stack_$firstStack").performClick()
        bookActions(ids[5])
        compose.onNodeWithTag("library_move_to_stack").performClick()
        compose.onNodeWithTag("library_destination_$secondStack").performClick()
        compose.waitUntil(10_000) { catalog.books.single { it.id == ids[5] }.stackId == secondStack }
        assertEquals(listOf(ids[4]), catalog.stacks.single { it.id == firstStack }.bookIds)

        bookActions(ids[4])
        compose.onNodeWithTag("library_extract").performClick()
        compose.waitUntil(10_000) { catalog.stacks.none { it.id == firstStack } }
        compose.onNodeWithTag("library_stack_menu").assertDoesNotExist()
        compose.onNodeWithTag("library_book_${ids[4].value}").assertIsDisplayed()
        assertNull(catalog.books.single { it.id == ids[4] }.stackId)
        assertEquals(6, catalog.books.size)

        compose.onNodeWithTag("library_stack_$secondStack").performClick()
        compose.onNodeWithTag("library_stack_menu").performClick()
        compose.onNodeWithTag("library_add_books").performClick()
        compose.onNodeWithTag("library_select_${ids[4].value}").performScrollTo().performClick()
        compose.onNodeWithTag("library_picker_confirm").performClick()
        compose.waitUntil(10_000) { catalog.stacks.single().bookIds.size == 4 }
        assertEquals(listOf(ids[3], ids[2], ids[5], ids[4]), catalog.stacks.single().bookIds)
        assertEquals(4, catalog.books.count { it.stackId == secondStack })
        assertEquals(4, catalog.stacks.single().bookIds.distinct().size)

        bookActions(ids[5])
        compose.onNodeWithTag("library_move_earlier").performClick()
        compose.waitUntil(10_000) { catalog.stacks.single().bookIds[1] == ids[5] }
        assertEquals(listOf(ids[3], ids[5], ids[2], ids[4]), catalog.stacks.single().bookIds)
        assertEquals(6, catalog.books.map { it.id }.distinct().size)
        compose.runOnIdle { assertNull(opened) }
    }

    private fun bookActions(id: BookId) {
        compose.onNodeWithTag("library_book_${id.value}").performSemanticsAction(SemanticsActions.OnLongClick)
    }

    private fun holdAndDrop(sourceTag: String, targetTag: String, dwell: Long) {
        // Stack cards share their fold origin until reveal finishes. Wait for the same
        // interaction contract a real finger uses before sampling the resting coordinates.
        compose.waitUntil(10_000) {
            listOf(sourceTag, targetTag).all { tag ->
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
                    ?.config?.contains(SemanticsActions.OnLongClick) == true
            }
        }
        val screen = compose.onNodeWithTag("library_screen").fetchSemanticsNode().boundsInRoot
        val source = compose.onNodeWithTag(sourceTag).fetchSemanticsNode().boundsInRoot.center - screen.topLeft
        val target = compose.onNodeWithTag(targetTag).fetchSemanticsNode().boundsInRoot.center - screen.topLeft
        assertTrue("Resting source and target must be distinct: $sourceTag=$source, $targetTag=$target",
            (source - target).getDistance() > 1f)
        compose.onNodeWithTag("library_screen").performTouchInput {
            down(source)
            advanceEventTime(650)
            moveTo(source + Offset(1f, 0f))
            advanceEventTime(50)
            moveTo(target)
            advanceEventTime(dwell)
            up()
        }
    }

    private fun showShelf() {
        val initial = runBlocking { repository.catalog.first() }
        assertEquals(6, initial.books.size)
        compose.runOnUiThread {
            compose.activity.setContent {
                // Read in this composition scope, so the telemetry SideEffect is scheduled
                // for every Room/DataStore emission, independently of child restart scopes.
                val current = repository.catalog.collectAsState(initial, context = Dispatchers.Main.immediate).value
                val canUndo = repository.canUndo.collectAsState(context = Dispatchers.Main.immediate).value
                val zoom = preferences.shelfZoom.collectAsState(ShelfZoom.MEDIUM, context = Dispatchers.Main.immediate).value
                SideEffect { catalog = current }
                ListaiTheme {
                    LibraryScreen(current.books, false, {}, {}, { opened = it }, {}, catalog = current,
                        shelfZoom = zoom, onShelfZoomChanged = { operations.launch { preferences.setShelfZoom(it) } },
                        canUndo = canUndo, onUndo = { operations.launch { repository.undoLastLibraryChange() } },
                        onLibraryCommand = { command -> commands += command; operations.launch { apply(command) } })
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(10_000) { catalog.books.size == 6 }
    }

    private suspend fun apply(command: LibraryCommand) { when (command) {
        is LibraryCommand.CreateStack -> repository.createStack(command.name, command.bookIds, command.requestedId)
        is LibraryCommand.RenameStack -> repository.renameStack(command.stackId, command.name)
        is LibraryCommand.AddToStack -> repository.addToStack(command.stackId, command.bookIds, command.onTop)
        is LibraryCommand.ExtractFromStack -> repository.extractFromStack(command.bookIds)
        is LibraryCommand.DissolveStack -> repository.dissolveStack(command.stackId)
        is LibraryCommand.MoveShelfItem -> repository.moveShelfItem(command.key, command.targetKey)
        is LibraryCommand.MoveStackBook -> repository.moveStackBook(command.stackId, command.bookId, command.targetBookId)
    } }
}
