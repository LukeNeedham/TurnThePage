package com.avstaim.listai.feature.library

import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.designsystem.BookCoverFace
import com.avstaim.listai.core.designsystem.drawBookCover
import com.avstaim.listai.core.designsystem.drawListaiBackdrop
import com.avstaim.listai.core.designsystem.rememberBookCoverSurface
import com.avstaim.listai.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal data class LibraryTile(val item: LibraryShelfItem, val book: LibraryBook?, val stack: LibraryStack? = null) {
    val key get() = item.key
}

/** The same physical shelf hosts root books, expanded stacks and global search results. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    books: List<LibraryBook>,
    importing: Boolean,
    onImport: () -> Unit,
    onCancelImport: () -> Unit,
    onOpen: (BookId) -> Unit,
    onDelete: (BookId) -> Unit,
    modifier: Modifier = Modifier,
    coverContent: @Composable (LibraryBook) -> Unit = { LibraryCover(it) },
    hiddenBookId: BookId? = null,
    interactionEnabled: Boolean = true,
    onBookBoundsChanged: (BookId, Rect?) -> Unit = { _, _ -> },
    versionLabel: String? = null,
    onSettings: () -> Unit = {},
    darkTheme: Boolean? = null,
    onMoveBook: (BookId, BookId) -> Unit = { _, _ -> },
    onShare: (BookId) -> Unit = {},
    onImportFolder: () -> Unit = {},
    catalog: LibraryCatalog? = null,
    shelfZoom: ShelfZoom = ShelfZoom.MEDIUM,
    onShelfZoomChanged: (ShelfZoom) -> Unit = {},
    onLibraryCommand: (LibraryCommand) -> Unit = {},
    canUndo: Boolean = false,
    onUndo: () -> Unit = {},
    initialStackId: String? = null,
    initialSearchQuery: String? = null,
    restoredBookId: BookId? = null,
    /** Window/root-coordinate exclusions such as a separating or occluding Fold hinge. */
    occlusionBounds: List<Rect> = emptyList(),
) {
    val effectiveCatalog = catalog ?: LibraryCatalog(books = books, shelf = books.map { LibraryShelfItem.Book(it.id) })
    val byId = effectiveCatalog.books.associateBy { it.id }
    val stacks = effectiveCatalog.stacks.associateBy { it.id }
    var stackId by rememberSaveable { mutableStateOf(initialStackId) }
    var searchOpen by rememberSaveable { mutableStateOf(initialSearchQuery != null) }
    var query by rememberSaveable { mutableStateOf(initialSearchQuery.orEmpty()) }
    var restoredOrigin by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(restoredBookId, effectiveCatalog.books) {
        if (!restoredOrigin && restoredBookId != null && restoredBookId in byId) {
            if (!searchOpen && stackId == null) stackId = byId[restoredBookId]?.stackId
            restoredOrigin = true
        }
    }
    // Removing/extracting the final member collapses an empty stack atomically.
    LaunchedEffect(stackId, effectiveCatalog.stacks) {
        if (stackId != null && stackId !in stacks && effectiveCatalog.books.isNotEmpty()) stackId = null
    }
    val stack = stacks[stackId]
    val stackReveal = remember { Animatable(1f) }
    var previousStackId by remember { mutableStateOf(stackId) }
    LaunchedEffect(stackId, interactionEnabled) {
        val changed = previousStackId != stackId
        previousStackId = stackId
        if (changed && stackId != null && interactionEnabled && ValueAnimator.areAnimatorsEnabled()) {
            stackReveal.snapTo(0f)
            stackReveal.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        } else stackReveal.snapTo(1f)
    }
    val incoming = when {
        searchOpen -> searchLibrary(effectiveCatalog, query).map { LibraryTile(LibraryShelfItem.Book(it.id), it) }
        stack != null -> stack.bookIds.mapNotNull { id -> byId[id]?.let { LibraryTile(LibraryShelfItem.Book(id), it) } }
        else -> effectiveCatalog.shelf.mapNotNull { item -> when (item) {
            is LibraryShelfItem.Book -> byId[item.bookId]?.let { LibraryTile(item, it) }
            is LibraryShelfItem.Stack -> stacks[item.stackId]?.let { value -> LibraryTile(item, byId[value.bookIds.firstOrNull()], value) }
        } }
    }
    val tiles = incoming.associateBy { it.key }
    val incomingKeys = incoming.map { it.key }
    val gestures = remember { LibraryShelfGestureState() }
    val surfaceKey = if (searchOpen) "search" else stackId ?: "root"
    LaunchedEffect(incomingKeys, surfaceKey) {
        if (gestures.drag != null && gestures.drag?.originalKeys != incomingKeys) gestures.cancel()
        if (gestures.drag == null) gestures.keys = incomingKeys
    }
    val ordered = gestures.keys.mapNotNull(tiles::get).takeIf { it.size == incoming.size } ?: incoming
    var deleting by remember { mutableStateOf<LibraryBook?>(null) }
    var actionsKey by remember { mutableStateOf<String?>(null) }
    var importMenu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<LibraryBookPickerRequest?>(null) }
    var namingIds by remember { mutableStateOf<List<BookId>?>(null) }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var movingBook by remember { mutableStateOf<LibraryBook?>(null) }
    var dissolvingId by remember { mutableStateOf<String?>(null) }
    val rootList = rememberLazyListState()
    val stackList = rememberLazyListState()
    val searchList = rememberLazyListState()
    val listState = when { searchOpen -> searchList; stack != null -> stackList; else -> rootList }
    var lastStackId by rememberSaveable { mutableStateOf(stackId) }
    LaunchedEffect(stackId) {
        if (stackId != null && stackId != lastStackId) { stackList.scrollToItem(0); lastStackId = stackId }
    }
    val viewports = rememberSaveable(saver = LibraryViewportState.Saver) { LibraryViewportState() }
    var pendingAnchor by remember { mutableStateOf<LibraryViewportAnchor?>(null) }
    val colors = MaterialTheme.colorScheme
    val useDarkBackdrop = darkTheme ?: (colors.background.luminance() < .5f)
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var shelfBounds by remember { mutableStateOf(Rect.Zero) }
    val latestTiles by rememberUpdatedState(tiles)
    val latestStack by rememberUpdatedState(stack)
    val latestSearch by rememberUpdatedState(searchOpen)
    val latestZoom by rememberUpdatedState(shelfZoom)
    val latestList by rememberUpdatedState(listState)
    val latestCommand by rememberUpdatedState(onLibraryCommand)
    val latestMove by rememberUpdatedState(onMoveBook)
    val latestBoundsChanged by rememberUpdatedState(onBookBoundsChanged)
    val latestZoomChanged by rememberUpdatedState(onShelfZoomChanged)
    val newStackLabel = stringResource(R.string.library_new_stack)
    val shareLabel = stringResource(R.string.library_share)
    val deleteLabel = stringResource(R.string.library_delete)
    val earlierLabel = stringResource(R.string.library_move_earlier)
    val laterLabel = stringResource(R.string.library_move_later)
    val bookActionsLabel = stringResource(R.string.library_book_actions)
    val stackActionsLabel = stringResource(R.string.library_stack_actions)
    val createLabel = stringResource(R.string.library_create_stack)
    val zoomInLabel = stringResource(R.string.library_zoom_in)
    val zoomOutLabel = stringResource(R.string.library_zoom_out)
    val importLabel = stringResource(R.string.library_import)
    // The cat consumes these confirmed shelf events, never a competing global gesture detector.
    var blankTapCount by remember { mutableIntStateOf(0) }
    var firstBlankTap by remember { mutableLongStateOf(0L) }
    var firstBlankPoint by remember { mutableStateOf(Offset.Zero) }
    var catSummonPoint by remember { mutableStateOf(Offset.Zero) }
    var catSummon by remember { mutableLongStateOf(0L) }
    var catTap by remember { mutableLongStateOf(0L) }
    var catHide by remember { mutableLongStateOf(0L) }
    var catBounds by remember { mutableStateOf<Rect?>(null) }
    fun hideCat() { blankTapCount = 0; catHide++ }
    fun closeSearch() { searchOpen = false; keyboard?.hide(); focus.clearFocus(); hideCat() }
    fun openSearch() {
        if (searchOpen) { searchFocus.requestFocus(); keyboard?.show() } else searchOpen = true
        hideCat()
    }
    fun openBook(id: BookId) { keyboard?.hide(); focus.clearFocus(); hideCat(); onOpen(id) }
    fun openTile(tile: LibraryTile) {
        if (tile.stack != null) { stackId = tile.stack.id; hideCat() }
        else tile.book?.let { openBook(it.id) }
    }
    fun move(source: String, target: String) {
        if (source == target || latestSearch) return
        val sourceBook = latestTiles[source]?.book
        val targetBook = latestTiles[target]?.book
        val inside = latestStack
        if (inside != null && sourceBook != null && targetBook != null) {
            latestCommand(LibraryCommand.MoveStackBook(inside.id, sourceBook.id, targetBook.id))
        } else if (catalog != null) latestCommand(LibraryCommand.MoveShelfItem(source, target))
        else if (sourceBook != null && targetBook != null) latestMove(sourceBook.id, targetBook.id)
    }
    BackHandler(enabled = interactionEnabled && (searchOpen || stack != null)) {
        when { imeVisible -> { keyboard?.hide(); focus.clearFocus() }; searchOpen -> closeSearch(); else -> { stackId = null; hideCat() } }
    }
    LaunchedEffect(searchOpen, interactionEnabled) {
        if (!interactionEnabled) { keyboard?.hide(); focus.clearFocus() }
        else if (searchOpen && initialSearchQuery == null) { searchFocus.requestFocus(); keyboard?.show() }
    }
    LaunchedEffect(query) { if (searchOpen) searchList.scrollToItem(0) }
    LaunchedEffect(interactionEnabled, searchOpen, stackId) { if (!interactionEnabled || searchOpen) hideCat() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            // The hold already hid the cat. Avoid turning each tiny auto-scroll mutation
            // into a new hide event/recomposition while a drag remains stationary.
            if (scrolling && gestures.drag == null) hideCat()
        }
    }
    val reportBounds: (LibraryTile, Rect?) -> Unit = { tile, bounds ->
        if (bounds == null) gestures.bounds.remove(tile.key) else gestures.bounds[tile.key] = bounds
        if (tile.stack == null) tile.book?.let { latestBoundsChanged(it.id, bounds) }
    }
    BoxWithConstraints(modifier.fillMaxSize().drawBehind { drawListaiBackdrop(useDarkBackdrop) }
        .onGloballyPositioned { shelfBounds = it.boundsInRoot() }.testTag("library_screen")) {
        val direction = LocalLayoutDirection.current
        val cutout = WindowInsets.displayCutout
        val safeWidth = with(density) { maxWidth - cutout.getLeft(this, direction).toDp() - cutout.getRight(this, direction).toDp() }.coerceAtLeast(40.dp)
        val geometry = libraryShelfGeometry(safeWidth.value, shelfZoom)
        val columns = geometry.columns
        val bookWidth = geometry.bookWidthDp.dp
        val bookHeight = geometry.bookHeightDp.dp
        val searchLabels = if (searchOpen) 42.dp else 0.dp
        val topPadding = if (searchOpen || stack != null) 102.dp else 32.dp
        val latestTopPaddingPx by rememberUpdatedState(with(density) { topPadding.toPx() })
        val rows = ordered.chunked(columns)
        val shelfRows = calculateLibraryLayout(maxHeight.value, rows.size, bookHeight.value + searchLabels.value,
            hasEmptyMessage = ordered.isEmpty(), topPaddingDp = topPadding.value,
            topInsetDp = with(density) { cutout.getTop(this).toDp().value }, bottomInsetDp = with(density) { cutout.getBottom(this).toDp().value })
        val latestColumns by rememberUpdatedState(columns)
        fun changeZoom(next: ShelfZoom) {
            if (next == latestZoom) return
            val anchor = gestures.keys.getOrNull(latestList.firstVisibleItemIndex * latestColumns)
            pendingAnchor = anchor?.let { LibraryViewportAnchor(it, latestList.firstVisibleItemScrollOffset) }
            gestures.cancel(); blankTapCount = 0; latestZoomChanged(next)
        }
        LaunchedEffect(columns, shelfZoom, surfaceKey, incomingKeys.isEmpty()) {
            val anchor = pendingAnchor ?: viewports.anchor(surfaceKey)
            val row = anchor?.let { libraryAnchorRow(incomingKeys, columns, it) }
            if (row != null && shelfRows.scrollable) listState.scrollToItem(row, anchor.offset)
            else if (!shelfRows.scrollable) listState.scrollToItem(0)
            pendingAnchor = null
        }
        LaunchedEffect(listState, columns, incomingKeys, surfaceKey) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (row, offset) ->
                viewports.record(surfaceKey, incomingKeys.getOrNull(row * columns), offset)
            }
        }
        val canGroup: (String, String) -> Boolean = { source, target ->
            catalog != null && !latestSearch && latestStack == null && latestTiles[source]?.stack == null && latestTiles[source]?.book != null && source != target
        }
        val edgeSize = with(density) { 64.dp.toPx() }
        val bottomControls = with(density) { 88.dp.toPx() }
        LaunchedEffect(gestures.drag?.key, shelfRows.scrollable) {
            while (isActive && gestures.drag != null && shelfRows.scrollable) {
                val active = gestures.drag
                if (active?.moved == true) {
                    val y = active.pointer.y - shelfBounds.top
                    val lowerEdge = shelfBounds.height - bottomControls
                    val speed = when { y < edgeSize -> -((edgeSize - y) / edgeSize).coerceIn(0f, 1f)
                        y > lowerEdge - edgeSize -> ((y - lowerEdge + edgeSize) / edgeSize).coerceIn(0f, 1f); else -> 0f }
                    val canScroll = (speed < 0f && listState.canScrollBackward) || (speed > 0f && listState.canScrollForward)
                    if (canScroll) {
                        val consumed = listState.scrollBy(speed * with(density) { 12.dp.toPx() })
                        if (kotlin.math.abs(consumed) > .5f) {
                            withFrameNanos { }
                            gestures.drag?.let { gestures.move(it.pointer, with(density) { 8.dp.toPx() }, android.os.SystemClock.uptimeMillis(), canGroup) }
                        }
                    }
                }
                delay(16)
            }
        }
        val shelfModifier = Modifier.fillMaxSize().libraryShelfInput(interactionEnabled, gestures,
            origin = { shelfBounds.topLeft }, zoom = { latestZoom },
            isAtTop = { latestList.firstVisibleItemIndex == 0 && latestList.firstVisibleItemScrollOffset == 0 },
            isSearch = { latestSearch }, canGroup = canGroup, onZoom = ::changeZoom, onSearch = ::openSearch,
            onHold = { hideCat(); haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            onGroupReady = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            onDrop = { drag, reordered ->
                val source = latestTiles[drag.key]
                val target = latestTiles[drag.hoverKey]
                if (drag.armed && source?.book != null && target != null) {
                    gestures.keys = drag.originalKeys
                    if (target.stack != null) latestCommand(LibraryCommand.AddToStack(target.stack.id, listOf(source.book.id)))
                    else target.book?.let { namingIds = listOf(it.id, source.book.id) }
                } else {
                    // Quick drops remain ordinary moves; only a confirmed center dwell groups.
                    val destination = drag.hoverKey ?: drag.originalKeys.getOrNull(reordered.indexOf(drag.key))
                    if (destination != null && destination != drag.key) move(drag.key, destination)
                }
            }, onActions = { actionsKey = it },
            onBlankTap = { point, time ->
                if (catBounds?.contains(point) == true) catTap++
                else if (!searchOpen && point.y >= latestTopPaddingPx && point.y < shelfBounds.height - bottomControls) {
                    if (time - firstBlankTap > 3_000 || (point - firstBlankPoint).getDistance() > with(density) { 72.dp.toPx() }) {
                        firstBlankTap = time; firstBlankPoint = point; blankTapCount = 0
                    }
                    blankTapCount++
                    if (blankTapCount >= 5) { catSummonPoint = point; catSummon++; blankTapCount = 0 }
                }
            }, decorationHit = { catBounds?.contains(it) == true }, onActivity = { blankTapCount = 0 }).displayCutoutPadding().semantics {
                customActions = buildList {
                    if (shelfZoom != ShelfZoom.LARGE) add(CustomAccessibilityAction(zoomInLabel) { changeZoom(shelfZoom.closer()); true })
                    if (shelfZoom != ShelfZoom.SMALL) add(CustomAccessibilityAction(zoomOutLabel) { changeZoom(shelfZoom.farther()); true })
                }
            }
        val tileActions: (LibraryTile) -> List<CustomAccessibilityAction> = { tile -> buildList {
            tile.book?.takeIf { tile.stack == null }?.let { book ->
                add(CustomAccessibilityAction(shareLabel) { onShare(book.id); true })
                add(CustomAccessibilityAction(createLabel) { picker = LibraryBookPickerRequest(listOf(book.id)); true })
                add(CustomAccessibilityAction(deleteLabel) { deleting = book; true })
            }
            if (!searchOpen) {
                val index = ordered.indexOfFirst { it.key == tile.key }
                ordered.getOrNull(index - 1)?.let { before -> add(CustomAccessibilityAction(earlierLabel) { move(tile.key, before.key); true }) }
                ordered.getOrNull(index + 1)?.let { after -> add(CustomAccessibilityAction(laterLabel) { move(tile.key, after.key); true }) }
            }
        } }
        val rowContent: @Composable (Int) -> Unit = { index ->
            LibraryShelfRow(index, rows, bookWidth, bookHeight, searchLabels, ordered.isEmpty(), searchOpen, interactionEnabled,
                hiddenBookId, gestures.drag, ::openTile, reportBounds, coverContent, { actionsKey = it.key },
                bookActionsLabel, stackActionsLabel, tileActions, effectiveCatalog,
                revealProgress = { if (stack != null && !searchOpen) stackReveal.value else 1f })
        }
        if (shelfRows.scrollable) {
            LazyColumn(shelfModifier, state = listState, userScrollEnabled = interactionEnabled && gestures.drag == null,
                contentPadding = PaddingValues(top = topPadding, bottom = 88.dp)) {
                items(shelfRows.rowCount) { rowContent(it) }
            }
        } else {
            Column(shelfModifier.padding(top = topPadding, bottom = 88.dp)) {
                repeat(shelfRows.rowCount) { rowContent(it) }
                repeat(shelfRows.decorativeRowCount) { index ->
                    LibraryShelfRow(shelfRows.rowCount + index, emptyList(), bookWidth, bookHeight, 0.dp, false, false,
                        interactionEnabled, null, null, {}, { _, _ -> }, coverContent, {}, bookActionsLabel, stackActionsLabel, { emptyList() }, effectiveCatalog)
                }
            }
        }
        if (searchOpen || stack != null) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().displayCutoutPadding()
                .background(colors.background.copy(alpha = .97f)).padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (searchOpen) closeSearch() else { stackId = null; hideCat() } }, modifier = Modifier.testTag("library_back")) {
                    Text(stringResource(if (searchOpen) R.string.library_close else R.string.library_to_shelf))
                }
                if (searchOpen) {
                    OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.weight(1f)
                        .focusRequester(searchFocus).testTag("library_search_field"), label = { Text(stringResource(R.string.library_search_hint)) })
                } else stack?.let { current ->
                    Column(Modifier.weight(1f)) {
                        Text(current.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.library_stack_count, current.bookIds.size), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { actionsKey = "stack:${current.id}" }, modifier = Modifier.testTag("library_stack_menu")) { Text("···") }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canUndo) {
                Surface(color = colors.surface.copy(alpha = .97f), shape = RoundedCornerShape(8.dp), shadowElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.library_changed), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { hideCat(); onUndo() }, modifier = Modifier.testTag("library_undo")) { Text(stringResource(R.string.library_undo)) }
                    }
                }
            }
            if (importing) {
                Row(Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(8.dp)).background(colors.surface.copy(alpha = .94f), RoundedCornerShape(8.dp))
                    .padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.library_importing), Modifier.weight(1f), color = colors.onSurface, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { hideCat(); onCancelImport() }, modifier = Modifier.testTag("library_cancel_import")) { Text(stringResource(R.string.library_cancel)) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    versionLabel?.takeIf(String::isNotBlank)?.let { version -> Text(version, Modifier.testTag("library_version"),
                        color = colors.onBackground.copy(alpha = .48f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                val searchLabel = stringResource(R.string.library_search)
                Box(Modifier.size(48.dp).shadow(2.dp, RoundedCornerShape(8.dp)).background(colors.surface.copy(alpha = .82f), RoundedCornerShape(8.dp))
                    .clickable(enabled = interactionEnabled, role = Role.Button, onClick = ::openSearch).testTag("library_search")
                    .semantics { contentDescription = searchLabel }, contentAlignment = Alignment.Center) { PaperSearchIcon() }
                val settingsLabel = stringResource(R.string.library_settings)
                Box(Modifier.size(48.dp).shadow(2.dp, RoundedCornerShape(8.dp)).background(colors.surface.copy(alpha = .82f), RoundedCornerShape(8.dp))
                    .clickable(enabled = interactionEnabled, role = Role.Button, onClick = { hideCat(); onSettings() }).testTag("library_settings")
                    .semantics { contentDescription = settingsLabel }, contentAlignment = Alignment.Center) { PaperSettingsIcon() }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (!importing) SmallFloatingActionButton(onClick = { if (interactionEnabled) { hideCat(); importMenu = true } },
                        modifier = Modifier.fillMaxSize().testTag("library_import").semantics { contentDescription = importLabel },
                        containerColor = colors.surface.copy(alpha = .72f), contentColor = colors.primary) {
                        Text("+", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_import_files)) }, modifier = Modifier.testTag("library_import_files"),
                            onClick = { importMenu = false; onImport() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_import_folder)) }, modifier = Modifier.testTag("library_import_folder"),
                            onClick = { importMenu = false; onImportFolder() })
                    }
                }
            }
        }
        gestures.drag?.takeIf { it.moved }?.let { drag ->
            latestTiles[drag.key]?.let { tile ->
                Box(Modifier.offset { val point = drag.topLeft - shelfBounds.topLeft; IntOffset(point.x.roundToInt(), point.y.roundToInt()) }
                    .size(bookWidth, bookHeight).graphicsLayer { scaleX = 1.04f; scaleY = 1.04f }.shadow(18.dp, RoundedCornerShape(2.dp))
                    .testTag("library_dragging_book")) { if (tile.stack != null) LibraryStackCover(tile, coverContent) else tile.book?.let { coverContent(it) } }
            }
            if (drag.armed) Surface(Modifier.align(Alignment.TopCenter).padding(top = topPadding), shape = RoundedCornerShape(6.dp), shadowElevation = 4.dp) {
                Text(stringResource(if (latestTiles[drag.hoverKey]?.stack != null) R.string.library_stack_drop else R.string.library_group_drop),
                    Modifier.padding(12.dp).testTag("library_drop_hint"), style = MaterialTheme.typography.labelMedium)
            }
        }
        val localViewport = Rect(Offset.Zero, shelfBounds.size)
        val catSupports = gestures.bounds.mapNotNull { (key, bounds) ->
            val tile = tiles[key] ?: return@mapNotNull null
            var local = bounds.translate(-shelfBounds.topLeft)
            // A stack occupies only the lower portion of its navigation slot. Its physical top
            // (rather than the empty space above it) is the cat's resting edge.
            if (tile.stack != null) local = local.copy(top = (local.top + local.height * .35f - with(density) { 50.dp.toPx() }).coerceAtLeast(local.top))
            LibraryCatSupport(key, local, tile.stack != null).takeIf { local.overlaps(localViewport) }
        }.sortedBy { (it.bounds.center - catSummonPoint).getDistance() }
        val topExclusion = if (stack != null) with(density) { topPadding.toPx() } else cutout.getTop(density).toFloat()
        val bottomExclusion = bottomControls + with(density) { ((if (canUndo) 60 else 0) + (if (importing) 64 else 0)).dp.toPx() }
        LibraryCat(active = interactionEnabled && !searchOpen && actionsKey == null && picker == null && namingIds == null &&
            renamingId == null && deleting == null && movingBook == null && dissolvingId == null && !importMenu,
            darkTheme = useDarkBackdrop, zoom = shelfZoom, summonEvent = catSummon, tapEvent = catTap, hideEvent = catHide,
            supports = catSupports, onBoundsChanged = { catBounds = it }, excludedBounds =
                occlusionBounds.map { it.translate(-shelfBounds.topLeft) } + listOf(
                    Rect(0f, 0f, shelfBounds.width, topExclusion),
                    Rect(0f, shelfBounds.height - bottomExclusion, shelfBounds.width, shelfBounds.height)))
    }
    val actionStack = actionsKey?.removePrefix("stack:")?.let(stacks::get)
    val actionBook = actionsKey?.removePrefix("book:")?.let { byId[BookId(it)] }.takeIf { actionStack == null }
    if (actionStack != null || actionBook != null) {
        AlertDialog(onDismissRequest = { actionsKey = null }, title = { Text(actionStack?.name ?: actionBook!!.title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                fun dismiss() { actionsKey = null; hideCat() }
                if (actionStack != null) {
                    LibraryActionButton(R.string.library_add_books, "library_add_books") { picker = LibraryBookPickerRequest(emptyList(), actionStack.id); dismiss() }
                    LibraryActionButton(R.string.library_rename_stack, "library_rename_stack") { renamingId = actionStack.id; dismiss() }
                    LibraryActionButton(R.string.library_dissolve_stack, "library_dissolve_stack") { dissolvingId = actionStack.id; dismiss() }
                } else actionBook?.let { book ->
                    Text(stringResource(R.string.library_reorder_hint), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
                    LibraryActionButton(R.string.library_share, "library_share") { onShare(book.id); dismiss() }
                    LibraryActionButton(R.string.library_create_stack, "library_create_stack") { picker = LibraryBookPickerRequest(listOf(book.id)); dismiss() }
                    if (book.stackId != null) LibraryActionButton(R.string.library_extract, "library_extract") {
                        onLibraryCommand(LibraryCommand.ExtractFromStack(listOf(book.id))); dismiss()
                    }
                    if (effectiveCatalog.stacks.any { it.id != book.stackId }) LibraryActionButton(
                        if (book.stackId == null) R.string.library_add_to_stack else R.string.library_move_to_stack, "library_move_to_stack") { movingBook = book; dismiss() }
                    LibraryActionButton(R.string.library_delete, "library_delete") { deleting = book; dismiss() }
                }
                if (!searchOpen) {
                    val actionKey = actionsKey
                    val index = ordered.indexOfFirst { it.key == actionKey }
                    ordered.getOrNull(index - 1)?.let { target -> LibraryActionButton(R.string.library_move_earlier, "library_move_earlier") { move(actionKey!!, target.key); dismiss() } }
                    ordered.getOrNull(index + 1)?.takeIf { index >= 0 }?.let { target -> LibraryActionButton(R.string.library_move_later, "library_move_later") { move(actionKey!!, target.key); dismiss() } }
                }
            } }, confirmButton = { TextButton(onClick = { actionsKey = null }) { Text(stringResource(R.string.library_close)) } })
    }
    picker?.let { request -> LibraryBookPicker(effectiveCatalog, request, onDismiss = { picker = null }, onConfirm = { ids, name ->
        picker = null
        if (request.destinationStackId != null) onLibraryCommand(LibraryCommand.AddToStack(request.destinationStackId, ids))
        else onLibraryCommand(LibraryCommand.CreateStack(name, ids))
    }) }
    namingIds?.let { ids -> LibraryStackNameDialog(suggestedStackName(ids.mapNotNull(byId::get)) ?: newStackLabel, false,
        onDismiss = { namingIds = null }, onConfirm = { name -> onLibraryCommand(LibraryCommand.CreateStack(name, ids)); namingIds = null }) }
    renamingId?.let { id -> stacks[id]?.let { current -> LibraryStackNameDialog(current.name, true, onDismiss = { renamingId = null },
        onConfirm = { name -> onLibraryCommand(LibraryCommand.RenameStack(id, name)); renamingId = null }) } }
    movingBook?.let { book -> AlertDialog(onDismissRequest = { movingBook = null }, title = { Text(stringResource(R.string.library_move_to_stack)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { effectiveCatalog.stacks.filter { it.id != book.stackId }.forEach { destination ->
            TextButton(onClick = { onLibraryCommand(LibraryCommand.AddToStack(destination.id, listOf(book.id))); movingBook = null },
                modifier = Modifier.fillMaxWidth().testTag("library_destination_${destination.id}")) { Text(destination.name) }
        } } }, confirmButton = { TextButton(onClick = { movingBook = null }) { Text(stringResource(R.string.library_cancel)) } }) }
    dissolvingId?.let { id -> AlertDialog(onDismissRequest = { dissolvingId = null }, title = { Text(stringResource(R.string.library_dissolve_stack)) },
        text = { Text(stringResource(R.string.library_dissolve_message)) }, confirmButton = { TextButton(onClick = {
            onLibraryCommand(LibraryCommand.DissolveStack(id)); dissolvingId = null
        }, modifier = Modifier.testTag("library_confirm_dissolve")) { Text(stringResource(R.string.library_dissolve_stack)) } },
        dismissButton = { TextButton(onClick = { dissolvingId = null }) { Text(stringResource(R.string.library_cancel)) } }) }
    deleting?.let { book -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.library_delete_title)) },
        text = { Text(stringResource(R.string.library_delete_message, book.title)) }, confirmButton = { TextButton(onClick = { onDelete(book.id); deleting = null },
            modifier = Modifier.testTag("library_confirm_delete")) { Text(deleteLabel) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.library_cancel)) } }) }
}

@Composable
private fun LibraryActionButton(label: Int, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) { Text(stringResource(label)) }
}

@Composable
private fun LibraryShelfRow(
    rowIndex: Int, rows: List<List<LibraryTile>>, bookWidth: Dp, bookHeight: Dp, labelHeight: Dp,
    empty: Boolean, search: Boolean, interactionEnabled: Boolean, hiddenBookId: BookId?, drag: ShelfItemDrag?,
    onOpen: (LibraryTile) -> Unit, onBounds: (LibraryTile, Rect?) -> Unit, coverContent: @Composable (LibraryBook) -> Unit,
    onActions: (LibraryTile) -> Unit, bookActionsLabel: String, stackActionsLabel: String,
    accessibilityActions: (LibraryTile) -> List<CustomAccessibilityAction>, catalog: LibraryCatalog,
    revealProgress: () -> Float = { 1f },
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(bookHeight + labelHeight + 22.dp).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
            val rowTiles = rows.getOrNull(rowIndex).orEmpty()
            rowTiles.forEachIndexed { columnIndex, tile -> key(tile.key) {
                DisposableEffect(tile.key) { onDispose { onBounds(tile, null) } }
                Column(Modifier.width(bookWidth)) {
                    Box(Modifier.width(bookWidth).height(bookHeight)
                        .testTag(if (tile.stack != null) "library_stack_${tile.stack.id}" else "library_book_${tile.book?.id?.value}")
                        .onGloballyPositioned { onBounds(tile, it.boundsInRoot()) }
                        .graphicsLayer {
                            val reveal = revealProgress()
                            alpha = when { tile.stack == null && tile.book?.id == hiddenBookId -> 0f; tile.key == drag?.key -> .25f; else -> .82f + .18f * reveal }
                            translationX = -(columnIndex - (rowTiles.size - 1) / 2f) * 12.dp.toPx() * (1f - reveal)
                            translationY = 6.dp.toPx() * (1f - reveal)
                        }
                        .then(if (tile.stack == null) Modifier.shadow(7.dp, RoundedCornerShape(2.dp), ambientColor = Color(0xFF142A3D), spotColor = Color(0xFF142A3D)) else Modifier)
                        .clickable(enabled = interactionEnabled, role = Role.Button, onClick = { onOpen(tile) })
                        .semantics { if (interactionEnabled) {
                            onLongClick(if (tile.stack != null) stackActionsLabel else bookActionsLabel) { onActions(tile); true }
                            customActions = accessibilityActions(tile)
                        } }) {
                        if (tile.stack != null) LibraryStackCover(tile, coverContent) else tile.book?.let { coverContent(it) }
                        if (drag?.armed == true && drag.hoverKey == tile.key) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = .23f)))
                    }
                    if (search) {
                        val membership = catalog.stacks.firstOrNull { tile.book?.id in it.bookIds }
                        Text(membership?.let { stringResource(R.string.library_in_stack, it.name) } ?: tile.book?.authors.orEmpty(),
                            Modifier.height(labelHeight).fillMaxWidth().padding(top = 5.dp), style = MaterialTheme.typography.labelSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .7f))
                    }
                }
            } }
        }
        ShelfEdge()
        if (empty && rowIndex == 0) Text(stringResource(if (search) R.string.library_search_empty else R.string.library_empty),
            Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp).testTag(if (search) "library_search_empty" else "library_empty"),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = .75f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
    }
}

/** A few visible paper/cover layers suggest any stack size; the label always gives the true count. */
@Composable
internal fun LibraryStackCover(tile: LibraryTile, coverContent: @Composable (LibraryBook) -> Unit) {
    val stack = tile.stack ?: return
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize().semantics { contentDescription = "${stack.name}, ${stack.bookIds.size}" }) {
        val layerHeight = maxHeight * .085f
        val stackBase = maxHeight * .65f
        repeat(minOf(stack.bookIds.size, 4).coerceAtLeast(1)) { index ->
            Box(Modifier.fillMaxWidth(.94f).height(layerHeight).align(Alignment.BottomCenter)
                .offset(y = -(32.dp + layerHeight * index)).graphicsLayer { rotationZ = if (index % 2 == 0) -2f else 2f }
                .shadow(3.dp, RoundedCornerShape(2.dp)).background(colors.primary.copy(alpha = .54f + .1f * (index % 3)), RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth().height(layerHeight * .54f).align(Alignment.Center).padding(horizontal = 3.dp).background(Color(0xFFE8EBEB)))
            }
        }
        tile.book?.let { book ->
            Box(Modifier.width(maxWidth * .89f).height(stackBase).align(Alignment.BottomCenter).offset(y = -50.dp)
                .graphicsLayer { rotationZ = -5f; rotationX = 24f; cameraDistance = 14f * density }
                .shadow(8.dp, RoundedCornerShape(2.dp))) { coverContent(book) }
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(min = 32.dp), color = colors.surface,
            shape = RoundedCornerShape(2.dp), shadowElevation = 1.dp) {
            Column(Modifier.padding(horizontal = 5.dp, vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stack.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(stringResource(R.string.library_stack_count, stack.bookIds.size), style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ShelfEdge() {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        drawRect(colors.onBackground.copy(alpha = .09f), size = Size(size.width, 1.dp.toPx()))
        drawRect(colors.surfaceVariant, topLeft = Offset(0f, 1.dp.toPx()), size = Size(size.width, 5.dp.toPx()))
        drawRect(colors.onBackground.copy(alpha = .16f), topLeft = Offset(0f, 6.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
        drawRect(Color(0xFF15324B).copy(alpha = .07f), topLeft = Offset(0f, 7.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
    }
}

@Composable
private fun PaperSearchIcon() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(19.dp)) {
        val width = 1.5.dp.toPx()
        drawCircle(color, radius = size.width * .3f, center = Offset(size.width * .4f, size.height * .4f), style = androidx.compose.ui.graphics.drawscope.Stroke(width))
        drawLine(color, Offset(size.width * .63f, size.height * .63f), Offset(size.width * .94f, size.height * .94f), width)
    }
}
@Composable
private fun PaperSettingsIcon() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(18.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.35.dp.toPx())
        val width = size.width
        val height = size.height
        drawRoundRect(color, topLeft = Offset(2.dp.toPx(), 1.dp.toPx()),
            size = Size(width - 4.dp.toPx(), height - 2.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()), style = stroke)
        drawLine(color, Offset(width - 6.dp.toPx(), 1.dp.toPx()), Offset(width - 6.dp.toPx(), 5.dp.toPx()), strokeWidth = stroke.width)
        drawLine(color, Offset(width - 6.dp.toPx(), 5.dp.toPx()), Offset(width - 2.dp.toPx(), 5.dp.toPx()), strokeWidth = stroke.width)
        drawLine(color, Offset(5.dp.toPx(), 9.dp.toPx()), Offset(width - 5.dp.toPx(), 9.dp.toPx()), strokeWidth = stroke.width)
        drawLine(color, Offset(5.dp.toPx(), 13.dp.toPx()), Offset(width - 7.dp.toPx(), 13.dp.toPx()), strokeWidth = stroke.width)
    }
}

@Composable
fun LibraryCover(book: LibraryBook, previewImage: ImageBitmap? = null, face: BookCoverFace = BookCoverFace.FRONT) {
    val cached = remember(book.coverPath) { book.coverPath?.let(ShelfCoverCache::get) }
    val decoded by produceState(cached, book.id, book.coverPath) {
        value = withContext(Dispatchers.IO) {
            runCatching { book.coverPath?.let(ShelfCoverCache::decode) }.getOrNull()
        }
    }
    val image = previewImage ?: decoded
    BoxWithConstraints(Modifier.fillMaxSize().semantics { contentDescription = book.title }) {
        val surface = rememberBookCoverSurface(book.title, book.authors.ifBlank { null },
            IntSize(constraints.maxWidth.coerceAtLeast(1), constraints.maxHeight.coerceAtLeast(1)), image)
        Canvas(Modifier.fillMaxSize()) {
            drawBookCover(surface, Rect(Offset.Zero, size), face)
        }
    }
}

/** Shares the already visible original artwork with the lifted book, avoiding a fallback flash. */
private object ShelfCoverCache {
    private val cache = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    fun get(path: String): ImageBitmap? = cache.get(path)
    fun decode(path: String): ImageBitmap? {
        get(path)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 4_194_304L) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.also { it.prepareToDraw() }?.asImageBitmap()
            ?.also { cache.put(path, it) }
    }
}
