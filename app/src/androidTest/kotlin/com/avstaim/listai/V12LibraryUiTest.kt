package com.avstaim.listai

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
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
import kotlinx.coroutines.Job
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

/** Production library gestures/menus connected to real Room, private imports and DataStore. */
@RunWith(AndroidJUnit4::class)
class V12LibraryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val databaseName = "v12-ui-${UUID.randomUUID()}.db"
    private lateinit var database: LibraryDatabase
    private lateinit var storage: PrivateBookStorage
    private lateinit var repository: LibraryRepository
    private lateinit var preferences: AppPreferences
    private lateinit var commandScope: CoroutineScope
    private var oldZoom = ShelfZoom.MEDIUM
    private var time = 100L
    private var ids = emptyList<BookId>()
    private val enabled = mutableStateOf(true)
    private var opened: BookId? = null
    @Volatile private var latestCatalog = LibraryCatalog()
    @Volatile private var renderedZoom = ShelfZoom.MEDIUM
    private val commands = mutableListOf<LibraryCommand>()

    @Before fun setup() = runBlocking {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val context = compose.activity.applicationContext
        database = LibraryDatabase.open(context, databaseName)
        storage = PrivateBookStorage(File(context.cacheDir, "v12-ui-${UUID.randomUUID()}"))
        preferences = AppPreferences(context)
        oldZoom = preferences.shelfZoom.first()
        preferences.setShelfZoom(ShelfZoom.MEDIUM)
        repository = LibraryRepository(context, database.library(), storage, preferences, clock = { ++time })
        commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        repository.initialize()
        ids = listOf("Ёлка на орбите", "Тихая планета", "Далёкий берег", "Последняя книга").map { add(it) }
    }

    @After fun cleanup() = runBlocking {
        // Dispose collectors before closing their database, without touching the application library.
        compose.runOnUiThread { compose.activity.setContent {} }
        if (::commandScope.isInitialized) commandScope.coroutineContext[Job]?.cancelAndJoin()
        if (::preferences.isInitialized) preferences.setShelfZoom(oldZoom)
        if (::database.isInitialized) database.close()
        compose.activity.deleteDatabase(databaseName)
        if (::storage.isInitialized) storage.directory.deleteRecursively()
        Unit
    }

    @Test fun searchIsGlobalIncludesMembershipAndReturnsToTheOriginalStackWithQueryIntact() {
        val stack = runBlocking { repository.createStack("Космос", ids.take(2)) }
        showShelf(stack)
        compose.onNodeWithTag("library_book_${ids[0].value}").assertExists()
        compose.onNodeWithTag("library_search").performClick()
        val field = compose.onNodeWithTag("library_search_field")
        field.performTextReplacement("еЛКа   АВТОР")
        compose.onNodeWithTag("library_book_${ids[0].value}").assertIsDisplayed()
        compose.onNodeWithTag("library_book_${ids[1].value}").assertDoesNotExist()
        val membership = compose.activity.getString(com.avstaim.listai.feature.library.R.string.library_in_stack, "Космос")
        compose.onNodeWithText(membership).assertExists()
        compose.onNodeWithTag("library_book_${ids[0].value}").performClick()
        compose.runOnIdle { assertEquals(ids[0], opened); enabled.value = true }
        field.assertTextContains("еЛКа   АВТОР")
        field.performTextReplacement("берег")
        // This book is outside the open stack, but search always covers the entire catalog.
        compose.onNodeWithTag("library_book_${ids[2].value}").assertIsDisplayed()
        field.performTextReplacement("совсем отсутствующая книга")
        compose.onNodeWithTag("library_search_empty").assertExists()
        compose.onNodeWithTag("library_back").performClick()
        compose.onNodeWithTag("library_stack_menu").assertIsDisplayed()
        compose.onNodeWithTag("library_book_${ids[0].value}").assertExists()
        compose.onNodeWithTag("library_book_${ids[2].value}").assertDoesNotExist()
    }

    @Test fun stackMenusWriteRealMembershipRenameDissolveAndUndoWithoutLosingNewImports() {
        showShelf()
        val first = ids[3]
        val second = ids[2]
        compose.onNodeWithTag("library_book_${first.value}").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithTag("library_create_stack").performClick()
        compose.onNodeWithTag("library_select_${second.value}").performScrollTo().performClick()
        compose.onNodeWithTag("library_picker_confirm").performClick()
        compose.onNodeWithTag("library_stack_name").performTextReplacement("  Путешествия  ")
        compose.onNodeWithTag("library_name_confirm").performClick()
        awaitObservedState { latestCatalog.stacks.size == 1 }
        val stack = latestCatalog.stacks.single().id
        assertEquals(listOf(first, second), latestCatalog.stacks.single().bookIds)
        assertEquals("Путешествия", latestCatalog.stacks.single().name)
        compose.onNodeWithTag("library_stack_$stack").performClick()
        compose.onNodeWithTag("library_stack_menu").performClick()
        compose.onNodeWithTag("library_rename_stack").performClick()
        compose.onNodeWithTag("library_stack_name").performTextReplacement("Отпуск")
        compose.onNodeWithTag("library_name_confirm").performClick()
        compose.waitUntil(10_000) { latestCatalog.stacks.singleOrNull()?.name == "Отпуск" }
        compose.onNodeWithTag("library_stack_menu").performClick()
        compose.onNodeWithTag("library_dissolve_stack").performClick()
        compose.onNodeWithTag("library_confirm_dissolve").performClick()
        compose.waitUntil(10_000) { latestCatalog.stacks.isEmpty() }
        val newBook = runBlocking { add("Импорт после разбора") }
        compose.waitUntil(10_000) { latestCatalog.books.size == 5 }
        compose.onNodeWithTag("library_undo").performClick()
        compose.waitUntil(10_000) { latestCatalog.stacks.singleOrNull()?.id == stack }
        assertEquals(listOf(first, second), latestCatalog.stacks.single().bookIds)
        assertEquals("Отпуск", latestCatalog.stacks.single().name)
        assertNull(latestCatalog.books.single { it.id == newBook }.stackId)
        assertEquals(LibraryShelfItem.Book(newBook), latestCatalog.shelf.first())
        compose.onNodeWithTag("library_undo").assertDoesNotExist()
        assertEquals("Отпуск", runBlocking { database.library().allStacks().single().name })
    }

    @Test fun pinchHasThreePersistentLevelsCancelsHeldDragAndOnlyANewDownwardGestureOpensSearch() {
        showShelf()
        pinch(zoomIn = true)
        awaitObservedState(5_000) { renderedZoom == ShelfZoom.LARGE }
        pinch(zoomIn = false)
        compose.waitUntil(5_000) { renderedZoom == ShelfZoom.MEDIUM }
        pinch(zoomIn = false)
        compose.waitUntil(5_000) { renderedZoom == ShelfZoom.SMALL }
        pinch(zoomIn = false)
        compose.runOnIdle { assertEquals(ShelfZoom.SMALL, renderedZoom) }
        assertEquals(ShelfZoom.SMALL, runBlocking { preferences.shelfZoom.first() })
        val shelfBefore = latestCatalog.shelf
        val screen = compose.onNodeWithTag("library_screen").fetchSemanticsNode().boundsInRoot
        val source = compose.onNodeWithTag("library_book_${ids[3].value}").fetchSemanticsNode().boundsInRoot.center - screen.topLeft
        compose.onNodeWithTag("library_screen").performTouchInput {
            down(0, source)
            advanceEventTime(650)
            moveTo(0, source + Offset(1f, 0f))
            down(1, source + Offset(50f, 0f))
            moveTo(0, source + Offset(25f, 20f))
            up(0); up(1)
        }
        compose.runOnIdle { assertEquals(shelfBefore, latestCatalog.shelf); assertTrue(commands.isEmpty()); assertNull(opened) }
        compose.onNodeWithTag("library_drop_hint").assertDoesNotExist()
        // Make real scrolling possible and verify scrolling back does not become pull-search.
        runBlocking { repeat(20) { add("Additional $it") } }
        compose.waitUntil(10_000) { latestCatalog.books.size == 24 }
        compose.onNodeWithTag("library_screen").performTouchInput { swipeUp() }
        compose.onNodeWithTag("library_screen").performTouchInput { swipeDown() }
        compose.onNodeWithTag("library_search_field").assertDoesNotExist()
        // Return to the top through the list's semantics, then start a separate downward gesture.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToIndex(0)
        val density = compose.activity.resources.displayMetrics.density
        compose.onNodeWithTag("library_screen").performTouchInput {
            val start = Offset(width - 6f * density, height * .35f)
            swipe(start, start + Offset(0f, 105f * density), durationMillis = 300)
        }
        compose.onNodeWithTag("library_search_field").assertIsDisplayed()
    }

    @Test fun catRequiresFiveBlankTapsDoesNotOpenBooksAndStopsWhenLibraryLosesActivity() {
        runBlocking { ids.forEach { repository.delete(it) } }
        showShelf(expectedBooks = 0)
        fun taps(count: Int) {
            val density = compose.activity.resources.displayMetrics.density
            compose.onNodeWithTag("library_screen").performTouchInput {
                repeat(count) { click(Offset(width - 8f * density, height * .4f)); advanceEventTime(130) }
            }
        }
        taps(4)
        compose.onNodeWithTag("library_cat").assertDoesNotExist()
        compose.onNodeWithTag("library_search").performClick()
        compose.onNodeWithTag("library_back").performClick()
        taps(1)
        compose.onNodeWithTag("library_cat").assertDoesNotExist()
        taps(4)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("library_cat").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library_cat").performTouchInput { click(center) }
        compose.runOnIdle { assertNull(opened); enabled.value = false }
        compose.onNodeWithTag("library_cat").assertDoesNotExist()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithTag("library_cat").assertDoesNotExist()
        taps(5)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("library_cat").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("library_cat").assertDoesNotExist()
    }

    private fun showShelf(stackId: String? = null, expectedBooks: Int = 4) {
        val initial = runBlocking { repository.catalog.first() }
        assertEquals("Imported fixture count", expectedBooks, initial.books.size)
        compose.runOnUiThread {
            compose.activity.setContent {
                // Match the application's controller scope. The v2 test rule's composition
                // effect dispatcher must not own durable Room/DataStore command continuations.
                val catalog by repository.catalog.collectAsState(initial, Dispatchers.Main.immediate)
                val undo by repository.canUndo.collectAsState(Dispatchers.Main.immediate)
                val zoom by preferences.shelfZoom.collectAsState(ShelfZoom.MEDIUM, Dispatchers.Main.immediate)
                // Subscribe this observer's restart scope; reads only inside SideEffect or the
                // ListaiTheme content below would leave its diagnostics at the first snapshot.
                val currentCatalog = catalog
                val currentZoom = zoom
                SideEffect { latestCatalog = currentCatalog; renderedZoom = currentZoom }
                ListaiTheme {
                    LibraryScreen(catalog.books, false, {}, {}, { opened = it; enabled.value = false },
                        { id -> commandScope.launch { repository.delete(id) } }, catalog = catalog,
                        interactionEnabled = enabled.value, initialStackId = stackId, shelfZoom = zoom,
                        onShelfZoomChanged = { commandScope.launch { preferences.setShelfZoom(it) } },
                        canUndo = undo, onUndo = { commandScope.launch { repository.undoLastLibraryChange() } },
                        onLibraryCommand = { command -> commands += command; commandScope.launch { apply(command) } })
                }
            }
        }
        // setContent schedules work on the Android UI queue, outside the test frame clock.
        // Register/synchronize that new root before waiting on a SideEffect from it.
        compose.waitForIdle()
        compose.waitUntil(10_000) { latestCatalog.books.size == expectedBooks }
    }

    private suspend fun apply(command: LibraryCommand) { when (command) {
        is LibraryCommand.CreateStack -> repository.createStack(command.name, command.bookIds)
        is LibraryCommand.RenameStack -> repository.renameStack(command.stackId, command.name)
        is LibraryCommand.AddToStack -> repository.addToStack(command.stackId, command.bookIds)
        is LibraryCommand.ExtractFromStack -> repository.extractFromStack(command.bookIds)
        is LibraryCommand.DissolveStack -> repository.dissolveStack(command.stackId)
        is LibraryCommand.MoveShelfItem -> repository.moveShelfItem(command.key, command.targetKey)
        is LibraryCommand.MoveStackBook -> repository.moveStackBook(command.stackId, command.bookId, command.targetBookId)
    } }

    private fun awaitObservedState(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis, condition)
        } catch (error: ComposeTimeoutException) {
            val durable = runBlocking { repository.catalog.first() }
            val durableZoom = runBlocking { preferences.shelfZoom.first() }
            val tree = compose.onRoot(useUnmergedTree = true).printToString().take(12_000)
            throw AssertionError("Observed UI state did not update. commands=$commands; " +
                "observedStacks=${latestCatalog.stacks}; durableStacks=${durable.stacks}; " +
                "observedZoom=$renderedZoom; durableZoom=$durableZoom\n$tree", error)
        }
    }

    private fun pinch(zoomIn: Boolean) {
        val density = compose.activity.resources.displayMetrics.density
        compose.onNodeWithTag("library_screen").performTouchInput {
            val center = Offset(width / 2f, height * .4f)
            val start = (if (zoomIn) 36f else 70f) * density
            val end = (if (zoomIn) 70f else 36f) * density
            down(0, center - Offset(start, 0f)); down(1, center + Offset(start, 0f))
            moveTo(0, center - Offset(end, 0f)); moveTo(1, center + Offset(end, 0f))
            up(0); up(1)
        }
    }

    private suspend fun add(title: String): BookId = repository.import("fixture.fb2") {
        """<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info>
            <author><first-name>Автор</first-name></author><book-title>$title</book-title>
            <genre>sf</genre><sequence name="Миры"/></title-info></description>
            <body><section><p>Исходный текст книги $title.</p></section></body></FictionBook>""".byteInputStream()
    }
}
