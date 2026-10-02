package com.avstaim.listai

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import com.avstaim.listai.core.adaptive.DevicePosture
import com.avstaim.listai.core.designsystem.drawListaiBackdrop
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.LibraryBook
import com.avstaim.listai.core.model.CoverPosition
import com.avstaim.listai.feature.library.LibraryScreen
import com.avstaim.listai.feature.library.ShelfBookFlight
import com.avstaim.listai.feature.library.shelfBookSourceRect
import com.avstaim.listai.feature.reader.ReaderScreen
import com.avstaim.listai.feature.reader.PaperSettingsSheet
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

enum class ShelfReaderPhase { SHELF, PREPARING, LIFTING, OPENING, READING, CLOSING, LOWERING }
val ShelfTransitionPhase = SemanticsPropertyKey<String>("ShelfTransitionPhase")
val ShelfTransitionProgress = SemanticsPropertyKey<Float>("ShelfTransitionProgress")

private data class ShelfReturnFlight(val source: Rect, val target: Rect, val viewport: Rect)

@Composable
fun ListaiApp(
    modifier: Modifier = Modifier,
    devicePosture: DevicePosture = DevicePosture.Unknown,
    darkTheme: Boolean = false,
    onReaderVisibleChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val controller = remember(context) { (context.applicationContext as ListaiApplication).controller }
    val books by controller.books.collectAsState()
    val catalog by controller.catalog.collectAsState()
    val shelfZoom by controller.shelfZoom.collectAsState()
    val canUndoLibraryChange by controller.canUndoLibraryChange.collectAsState()
    val selection by controller.selection.collectAsState()
    val preferences by controller.readerPreferences.collectAsState()
    val onboardingCompleted by controller.readerOnboardingCompleted.collectAsState()
    var shelfSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var hintsRequest by rememberSaveable { mutableStateOf(0L) }
    val busy by controller.busy.collectAsState()
    val importing by controller.importing.collectAsState()
    val error by controller.error.collectAsState()
    val importProgress by controller.importProgress.collectAsState()
    val importSummary by controller.importSummary.collectAsState()
    val bookmarkFlow = remember(selection?.opened?.book?.id) {
        selection?.opened?.book?.id?.let(controller.repository::bookmarks) ?: flowOf(emptyList())
    }
    val bookmarks by bookmarkFlow.collectAsState(initial = emptyList())
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        controller.importBooks(uris, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(controller::importFolder)
    }
    val density = LocalDensity.current
    var phase by remember { mutableStateOf(ShelfReaderPhase.SHELF) }
    var flightBook by remember { mutableStateOf<LibraryBook?>(null) }
    // The close intent outlives this Activity's animation instances. A recreated Activity may
    // settle directly onto the shelf, but must never reopen the book the user just put away.
    var returnBookId by rememberSaveable { mutableStateOf<String?>(null) }
    val readerCoverBounds = remember { mutableStateMapOf<BookId, Rect>() }
    val readerCoverFaces = remember { mutableStateMapOf<BookId, CoverPosition>() }
    val readerArtwork = remember { mutableStateMapOf<BookId, ImageBitmap>() }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val shelfBounds = remember { mutableStateMapOf<BookId, Rect>() }
    var positionedReturnBook by remember { mutableStateOf<Pair<BookId, Rect?>?>(null) }
    var returnFlight by remember { mutableStateOf<ShelfReturnFlight?>(null) }
    val lift = remember { Animatable(0f) }
    val coverOpen = remember { Animatable(0f) }
    val selectedId = selection?.opened?.book?.id
    val returning = selectedId != null && returnBookId == selectedId.value

    fun returnToShelf() {
        if (returning) return
        if (phase == ShelfReaderPhase.SHELF && selectedId == null) return
        if (selectedId == null) {
            controller.returnToShelf()
            phase = ShelfReaderPhase.SHELF
            flightBook = null
        } else {
            positionedReturnBook = null
            returnFlight = null
            returnBookId = selectedId.value
        }
    }

    LaunchedEffect(selectedId, returning) {
        if (selectedId == null) {
            phase = ShelfReaderPhase.SHELF
            flightBook = null
            returnBookId = null
            positionedReturnBook = null
            returnFlight = null
            readerCoverBounds.clear()
            readerCoverFaces.clear()
            readerArtwork.clear()
            lift.snapTo(0f)
            coverOpen.snapTo(0f)
            return@LaunchedEffect
        }
        val changedBook = flightBook?.id != selectedId
        if (returnBookId != null && returnBookId != selectedId.value) returnBookId = null
        if (changedBook) {
            readerArtwork.keys.filter { it != selectedId }.forEach(readerArtwork::remove)
        }
        if (returning) {
            if (flightBook == null) {
                // Rotation/recreation discarded the old board and its in-flight geometry.
                // Complete the saved intent without replaying an opening or rewriting progress.
                controller.returnToShelf()
                returnBookId = null
                phase = ShelfReaderPhase.SHELF
                return@LaunchedEffect
            }
            // Reversing a half-lift keeps that board at its current location; revealing the
            // full reader here would otherwise jump the book to the far endpoint on Back.
            if (phase != ShelfReaderPhase.PREPARING && phase != ShelfReaderPhase.LIFTING &&
                phase != ShelfReaderPhase.LOWERING) {
                phase = ShelfReaderPhase.CLOSING
                coverOpen.animateTo(0f, tween((360 * coverOpen.value).toInt(), easing = FastOutSlowInEasing))
            }
            // Resizing, an external open, or restored reading can leave the book's row offscreen.
            // The shelf scrolls under the closed reader and acknowledges a freshly measured slot.
            val returnSlot = withTimeoutOrNull(2_000) {
                snapshotFlow { positionedReturnBook?.takeIf { it.first == selectedId && it.second != null } }
                    .filterNotNull().first().second
            }
            if (returnSlot != null) {
                val returnViewport = viewport
                val returnReaderBounds = readerCoverBounds[selectedId]
                returnFlight = ShelfReturnFlight(returnSlot,
                    returnReaderBounds ?: Rect(0f, 0f, viewport.width, viewport.height), returnViewport)
                phase = ShelfReaderPhase.LOWERING
                val interrupted = coroutineScope {
                    val lowering = launch(start = CoroutineStart.LAZY) {
                        lift.animateTo(0f, tween((460 * lift.value).toInt(), easing = FastOutSlowInEasing))
                    }
                    val placementWatch = launch {
                        snapshotFlow {
                            positionedReturnBook != (selectedId to returnSlot) || viewport != returnViewport ||
                                readerCoverBounds[selectedId] != returnReaderBounds
                        }.first { it }
                        // Resize during flight has no continuous safe trajectory. Settle on
                        // the new shelf instead of moving an endpoint underneath the cover.
                        lowering.cancel()
                    }
                    lowering.start()
                    lowering.join()
                    placementWatch.cancel()
                    lowering.isCancelled
                }
                if (interrupted) {
                    // Keep the closed book over the shelf while the new layout finds its
                    // slot. Do not cancel positioning by dropping the reader immediately.
                    phase = ShelfReaderPhase.CLOSING
                    repeat(2) { withFrameNanos { } }
                    withTimeoutOrNull(2_000) {
                        snapshotFlow { positionedReturnBook?.takeIf { it.first == selectedId && it.second != null } }
                            .filterNotNull().first()
                    }
                }
                lift.snapTo(0f)
            } else {
                // A deleted book or a viewport too small for a cover has no safe flight target.
                // Settle directly instead of inventing an endpoint below the visible shelf.
                lift.snapTo(0f)
            }
            // Keep the reader/session alive until the closed board reaches its actual shelf slot.
            controller.returnToShelf()
            flightBook = null
            readerCoverBounds.remove(selectedId)
            phase = ShelfReaderPhase.SHELF
            returnBookId = null
            positionedReturnBook = null
            returnFlight = null
        } else {
            if (phase == ShelfReaderPhase.READING && !changedBook) return@LaunchedEffect
            flightBook = selection?.opened?.book
            phase = ShelfReaderPhase.PREPARING
            lift.snapTo(0f)
            coverOpen.snapTo(0f)
            // Pagination and cover decode both complete while the original book stays on its shelf.
            snapshotFlow { readerCoverBounds[selectedId] }.filterNotNull().first()
            phase = ShelfReaderPhase.LIFTING
            lift.animateTo(1f, tween(460, easing = FastOutSlowInEasing))
            phase = ShelfReaderPhase.OPENING
            coverOpen.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
            phase = ShelfReaderPhase.READING
        }
    }
    LaunchedEffect(busy, error, selectedId) {
        if (!busy && error != null && selectedId == null) {
            phase = ShelfReaderPhase.SHELF
            flightBook = null
        }
    }
    SideEffect { onReaderVisibleChange(selection != null && !busy) }
    BackHandler(enabled = selection != null || phase != ShelfReaderPhase.SHELF) { returnToShelf() }
    BackHandler(enabled = shelfSettingsOpen && phase == ShelfReaderPhase.SHELF) { shelfSettingsOpen = false }

    Box(modifier.fillMaxSize().drawBehind { drawListaiBackdrop(darkTheme) }
        .onGloballyPositioned { viewport = it.boundsInRoot() }
        .testTag("shelf_reader_transition").semantics {
            this[ShelfTransitionPhase] = phase.name
            this[ShelfTransitionProgress] = when (phase) {
                ShelfReaderPhase.OPENING, ShelfReaderPhase.CLOSING -> coverOpen.value
                else -> lift.value
            }
        }) {
        val selected = selection
        val inFlight = phase == ShelfReaderPhase.LIFTING || phase == ShelfReaderPhase.LOWERING
        val readerVisible = phase == ShelfReaderPhase.OPENING || phase == ShelfReaderPhase.READING ||
            phase == ShelfReaderPhase.CLOSING
        LibraryScreen(books, importing, { picker.launch(arrayOf("*/*")) }, controller::cancelImport,
            onOpen = { id ->
                if (phase == ShelfReaderPhase.SHELF && !busy) {
                    flightBook = books.firstOrNull { it.id == id }
                    readerCoverBounds.remove(id)
                    returnBookId = null
                    positionedReturnBook = null
                    phase = ShelfReaderPhase.PREPARING
                    controller.open(id)
                }
            }, onDelete = controller::delete,
            onShare = controller::share,
            onMoveBook = controller::moveBook,
            onImportFolder = { folderPicker.launch(null) },
            modifier = if (phase == ShelfReaderPhase.SHELF) Modifier else Modifier.clearAndSetSemantics {},
            hiddenBookId = flightBook?.id.takeIf { inFlight || readerVisible },
            interactionEnabled = phase == ShelfReaderPhase.SHELF && !busy && !shelfSettingsOpen,
            onBookBoundsChanged = { id, bounds ->
                if (bounds == null) shelfBounds.remove(id) else shelfBounds[id] = bounds
            },
            versionLabel = "v${BuildConfig.VERSION_NAME}",
            onSettings = { shelfSettingsOpen = true },
            darkTheme = darkTheme,
            catalog = catalog,
            shelfZoom = shelfZoom,
            onShelfZoomChanged = controller::setShelfZoom,
            onLibraryCommand = controller::libraryCommand,
            canUndo = canUndoLibraryChange,
            onUndo = controller::undoLibraryChange,
            restoredBookId = selectedId,
            returningBookId = selectedId.takeIf { returning },
            onReturnBookPositioned = { id, bounds -> positionedReturnBook = id to bounds },
            occlusionBounds = when (devicePosture) {
                is DevicePosture.Flat -> devicePosture.foldBounds.takeIf {
                    devicePosture.separating || devicePosture.occluding
                }
                is DevicePosture.HalfOpened -> devicePosture.foldBounds.takeIf {
                    devicePosture.separating || devicePosture.occluding
                }
                DevicePosture.Unknown -> null
            }?.let { listOf(Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())) }
                .orEmpty())
        if (busy && books.isEmpty()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if (selected != null) {
            // The controller's session counter restarts with the process. It must reset a
            // live reader without changing the address of its saved Bundle state.
            key(selected.opened.book.id.value) {
                // A shelf request belongs to this reader instance. Keep its input stable
                // through replay/settings and recreation, but consume the pending request
                // before a later book can accidentally inherit it.
                val readerHintsRequest = rememberSaveable { hintsRequest }
                LaunchedEffect(readerHintsRequest) {
                    if (hintsRequest == readerHintsRequest) hintsRequest = 0L
                }
                ReaderScreen(
                    document = selected.opened.document,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (readerVisible) 1f else 0f }
                        .then(if (readerVisible) Modifier else Modifier.clearAndSetSemantics {}),
                    devicePosture = devicePosture,
                    initialProgress = selected.initialProgress,
                    readerSession = selected.session,
                    onProgressChanged = { controller.progressChanged(selected.opened.book.id, selected.session, it) },
                    preferences = preferences,
                    onPreferencesChange = controller::setReaderPreferences,
                    darkTheme = darkTheme,
                    bookmarks = bookmarks,
                    onAddBookmark = controller::addBookmark,
                    onAddBookmarkAt = controller::addBookmarkAt,
                    onDeleteBookmark = controller::deleteBookmark,
                    onReturnToShelf = ::returnToShelf,
                    transitionCoverProgress = if (phase == ShelfReaderPhase.READING) null else coverOpen.value,
                    closingBook = returning,
                    active = phase == ShelfReaderPhase.READING,
                    onBookReady = { readerCoverBounds[selected.opened.book.id] = it },
                    onBookCoverChanged = { readerCoverFaces[selected.opened.book.id] = it },
                    onBookArtworkReady = { artwork ->
                        if (artwork == null) readerArtwork.remove(selected.opened.book.id)
                        else readerArtwork[selected.opened.book.id] = artwork
                    },
                    onboardingCompleted = onboardingCompleted,
                    onOnboardingComplete = controller::completeReaderOnboarding,
                    onboardingReplayRequest = readerHintsRequest,
                )
            }
        }
        if (inFlight) {
            flightBook?.let { book ->
                val loweringGeometry = returnFlight.takeIf { phase == ShelfReaderPhase.LOWERING }
                val source = loweringGeometry?.let { it.source.translate(-it.viewport.topLeft) } ?:
                    shelfBookSourceRect(shelfBounds[book.id]?.translate(-viewport.topLeft), viewport.size, density.density)
                val target = loweringGeometry?.target ?: readerCoverBounds[book.id] ?: Rect(0f, 0f, viewport.width, viewport.height)
                ShelfBookFlight(book, source, target, lift.value,
                    backCover = readerCoverFaces[book.id] == CoverPosition.BACK,
                    previewImage = readerArtwork[book.id])
            }
        }
        if (phase != ShelfReaderPhase.SHELF && phase != ShelfReaderPhase.READING) {
            // A repeated tap cannot create another parser/session or reach the invisible reader.
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            })
            if (phase == ShelfReaderPhase.PREPARING) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
        if (shelfSettingsOpen && phase == ShelfReaderPhase.SHELF) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .18f))
                .clickable { shelfSettingsOpen = false })
            PaperSettingsSheet(preferences, controller::setReaderPreferences,
                onDismiss = { shelfSettingsOpen = false },
                modifier = Modifier.align(Alignment.Center).displayCutoutPadding().padding(18.dp),
                onShowHints = if (books.isNotEmpty()) {{
                    shelfSettingsOpen = false
                    hintsRequest++
                    controller.open(books.first().id)
                }} else null)
        }
    }
    if (importing) {
        AlertDialog(onDismissRequest = {},
            title = { Text(stringResource(R.string.library_import_title)) },
            text = { Text(stringResource(R.string.library_import_count, importProgress.completed)) },
            confirmButton = { TextButton(onClick = controller::cancelImport) {
                Text(stringResource(R.string.action_cancel_import))
            } })
    }
    importSummary?.let { summary ->
        AlertDialog(onDismissRequest = controller::dismissImportSummary,
            title = { Text(stringResource(if (summary.cancelled) R.string.library_import_cancelled else R.string.library_import_complete)) },
            text = { Text(stringResource(R.string.library_import_summary, summary.added, summary.duplicates,
                summary.failed, summary.skipped)) },
            confirmButton = { TextButton(onClick = controller::dismissImportSummary) { Text(stringResource(R.string.action_ok)) } })
    }
    error?.let { message ->
        AlertDialog(onDismissRequest = controller::dismissError,
            title = { Text(stringResource(R.string.library_error_title)) },
            text = { Text(stringResource(message)) },
            confirmButton = { TextButton(onClick = controller::dismissError) { Text(stringResource(R.string.action_ok)) } })
    }
}
