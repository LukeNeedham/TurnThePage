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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.designsystem.BookCoverFace
import com.avstaim.listai.core.designsystem.drawBookCover
import com.avstaim.listai.core.designsystem.rememberBookCoverSurface
import com.avstaim.listai.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.UUID
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
    onSearchBoundsChanged: (Rect) -> Unit = {},
    dismissSearchRequest: Long = 0L,
    restoredBookId: BookId? = null,
    returningBookId: BookId? = null,
    onReturnBookPositioned: (BookId, Rect?) -> Unit = { _, _ -> },
    /** Window/root-coordinate exclusions such as a separating or occluding Fold hinge. */
    occlusionBounds: List<Rect> = emptyList(),
) {
    val actualCatalog = catalog ?: LibraryCatalog(books = books, shelf = books.map { LibraryShelfItem.Book(it.id) })
    var stackDrop by remember { mutableStateOf<LibraryStackDrop?>(null) }
    var pendingStackCreation by remember { mutableStateOf<PendingLibraryStackCreation?>(null) }
    val stackDropProgress = remember(stackDrop?.book?.id) { Animatable(0f) }
    val stackDropShelfProgress = remember(stackDrop?.book?.id) { Animatable(0f) }
    // Room can remove the source immediately. Hold the old slots and cover independently
    // until it has visibly joined the stack; the durable catalog still updates underneath.
    val effectiveCatalog = stackDrop?.takeUnless { it.releasingShelf }?.shelf ?: pendingStackCreation?.shelf ?: actualCatalog
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
    val searchReveal = remember { Animatable(1f) }
    var requestSearchKeyboard by remember { mutableStateOf(false) }
    val motionScope = rememberCoroutineScope()
    val inspection = LocalInspectionMode.current
    var stackTransition by remember { mutableStateOf(false) }
    var stackOrigin by remember { mutableStateOf<Rect?>(null) }
    var extraction by remember { mutableStateOf<LibraryBookExtraction?>(null) }
    val extractionProgress = remember { Animatable(0f) }
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
    LaunchedEffect(returningBookId, incomingKeys) {
        val id = returningBookId ?: return@LaunchedEffect
        if ("book:${id.value}" !in incomingKeys) {
            // A restored/external selection may not belong to the currently visible surface.
            byId[id]?.let { book -> searchOpen = false; stackId = book.stackId }
        }
    }
    val gestures = remember { LibraryShelfGestureState() }
    val extractionRootBounds = remember { mutableStateMapOf<String, Rect>() }
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
    var shelfBounds by remember { mutableStateOf(Rect.Zero) }
    var inputBounds by remember { mutableStateOf(Rect.Zero) }
    var headerHeight by remember { mutableIntStateOf(0) }
    var footerHeight by remember { mutableIntStateOf(0) }
    val shelfEnabled = interactionEnabled && !stackTransition && stackDrop == null && pendingStackCreation == null
    val latestCatalog by rememberUpdatedState(actualCatalog)
    val latestTiles by rememberUpdatedState(tiles)
    val latestStack by rememberUpdatedState(stack)
    val latestSearch by rememberUpdatedState(searchOpen)
    val latestQuery by rememberUpdatedState(query)
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
    LaunchedEffect(dismissSearchRequest) {
        if (dismissSearchRequest > 0L) closeSearch()
    }
    fun openSearch() {
        requestSearchKeyboard = true
        if (searchOpen) { searchFocus.requestFocus(); keyboard?.show() } else searchOpen = true
        hideCat()
    }
    fun openBook(id: BookId) { keyboard?.hide(); focus.clearFocus(); hideCat(); onOpen(id) }
    fun changeStack(id: String?) {
        if (stackTransition || stackDrop != null || id == stackId) return
        hideCat(); gestures.cancel()
        val animate = !inspection && ValueAnimator.areAnimatorsEnabled()
        if (!animate) { stackId = id; return }
        if (id != null) stackOrigin = gestures.bounds["stack:$id"]
        stackTransition = true
        motionScope.launch {
            try {
                if (id != null) {
                    stackReveal.snapTo(0f)
                    stackId = id
                    withFrameNanos { }
                    stackReveal.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
                } else {
                    stackReveal.animateTo(0f, tween(340, easing = FastOutSlowInEasing))
                    stackId = null
                    stackReveal.snapTo(1f)
                }
            } finally { stackTransition = false }
        }
    }
    fun openTile(tile: LibraryTile) {
        if (tile.stack != null) changeStack(tile.stack.id) else tile.book?.let { openBook(it.id) }
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
    LaunchedEffect(extraction?.book?.id) {
        val departing = extraction ?: return@LaunchedEffect
        try {
            extractionProgress.snapTo(0f)
            val committed = withTimeoutOrNull(3_000) {
                snapshotFlow { latestCatalog.books.firstOrNull { it.id == departing.book.id }?.let { it.stackId == null } == true }
                    .first { it }
            } == true
            if (!committed) return@LaunchedEffect
            // The Room update may also remove the final, now empty stack. Both paths land
            // on the root shelf before measuring the destination; stale stack bounds never fly.
            stackId = null
            stackReveal.snapTo(1f)
            val destination = withTimeoutOrNull(2_000) {
                snapshotFlow { extraction?.target }.filterNotNull().first()
            }
            if (destination != null && !inspection && ValueAnimator.areAnimatorsEnabled()) {
                extractionProgress.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
            }
        } finally {
            extraction = null
            stackTransition = false
        }
    }
    LaunchedEffect(shelfBounds.size, interactionEnabled) {
        // A new layout has new physical slots. Release the transient presentation on resize
        // or leaving the library; never carry old window coordinates into the next surface.
        if (!interactionEnabled || stackDrop?.let { it.viewport.size != shelfBounds.size } == true) stackDrop = null
        if (!interactionEnabled || pendingStackCreation?.let { it.viewport.size != shelfBounds.size } == true) pendingStackCreation = null
        if (!interactionEnabled) namingIds = null
    }
    LaunchedEffect(stackDrop?.book?.id) {
        val arriving = stackDrop ?: return@LaunchedEffect
        try {
            stackDropProgress.snapTo(0f)
            stackDropShelfProgress.snapTo(0f)
            if (!inspection && ValueAnimator.areAnimatorsEnabled()) {
                stackDropProgress.animateTo(1f, tween(arriving.durationMillis, easing = FastOutSlowInEasing))
            } else stackDropProgress.snapTo(1f)
            // A slow/failed operation must never freeze the library indefinitely. A successful
            // emission releases the retained shelf only after the rigid cover has landed.
            val committed = withTimeoutOrNull(3_000) {
                snapshotFlow { arriving.isCommittedIn(latestCatalog) }.first { it }
            } == true
            if (committed) {
                // Removing an earlier source can move the destination into the previous row.
                // Keep the whole landed stack above the new layout until it reaches that slot.
                gestures.bounds.remove(arriving.target.key)
                stackDrop = arriving.copy(releasingShelf = true)
                val target = withTimeoutOrNull(1_000) {
                    snapshotFlow { stackDrop?.reflowTarget }.filterNotNull().first()
                }
                if (target != null && target != arriving.destination && !inspection && ValueAnimator.areAnimatorsEnabled()) {
                    stackDropShelfProgress.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
                }
            }
        } finally { stackDrop = null }
    }
    BackHandler(enabled = interactionEnabled && (searchOpen || stack != null || stackTransition || stackDrop != null)) {
        // The IME consumes its own first Back. Once Back reaches this handler, close
        // the search even if a pre-30 IME inset still describes the previous frame.
        when { stackTransition || stackDrop != null -> Unit; searchOpen -> closeSearch(); else -> changeStack(null) }
    }
    LaunchedEffect(searchOpen, interactionEnabled) {
        if (!interactionEnabled) { keyboard?.hide(); focus.clearFocus() }
        else if (searchOpen && (requestSearchKeyboard || initialSearchQuery == null)) { searchFocus.requestFocus(); keyboard?.show() }
    }
    LaunchedEffect(searchOpen) {
        if (searchOpen && requestSearchKeyboard && !inspection && ValueAnimator.areAnimatorsEnabled()) {
            searchReveal.snapTo(0f)
            searchReveal.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
        } else searchReveal.snapTo(1f)
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
        if (stack == null && !searchOpen) {
            if (bounds == null) extractionRootBounds.remove(tile.key) else extractionRootBounds[tile.key] = bounds
        }
        if (tile.stack == null) tile.book?.let { latestBoundsChanged(it.id, bounds) }
    }
    BoxWithConstraints(modifier.fillMaxSize().then(if (searchOpen) Modifier.imePadding() else Modifier)
        .drawBehind { drawLibraryCabinet(useDarkBackdrop) }
        .onGloballyPositioned { shelfBounds = it.boundsInRoot() }.testTag("library_screen")) {
        val direction = LocalLayoutDirection.current
        val cutout = WindowInsets.displayCutout
        val safeWidth = with(density) { maxWidth - cutout.getLeft(this, direction).toDp() - cutout.getRight(this, direction).toDp() }.coerceAtLeast(40.dp)
        val geometry = libraryShelfGeometry(safeWidth.value, shelfZoom)
        val columns = geometry.columns
        val bookWidth = geometry.bookWidthDp.dp
        val bookHeight = geometry.bookHeightDp.dp
        val rows = ordered.chunked(columns)
        val rowBookHeight = if (ordered.isEmpty() && searchOpen) 0.dp else bookHeight
        val textMeasurer = rememberTextMeasurer()
        val captionStyle = MaterialTheme.typography.labelSmall
        val stackCaptions = ordered.mapNotNull { tile -> tile.stack?.let { value ->
            tile.key to ShelfCaption(value.name,
                pluralStringResource(R.plurals.library_sheet_book_count, value.bookIds.size, value.bookIds.size))
        } }.toMap()
        val captions = remember(stackCaptions, bookWidth, density.density, density.fontScale, captionStyle) {
            val width = with(density) { (bookWidth * .9f - 16.dp).roundToPx() }.coerceAtLeast(1)
            stackCaptions.mapValues { (_, caption) ->
                val singleLine = textMeasurer.measure(AnnotatedString(caption.label), style = captionStyle,
                    maxLines = 1, softWrap = false).size.width
                caption.copy(separateCount = singleLine > width)
            }
        }
        val bookDescriptions = ordered.associate { tile ->
            val membership = effectiveCatalog.stacks.firstOrNull { tile.book?.id in it.bookIds }
            tile.key to listOf(tile.book?.title.orEmpty(), tile.book?.authors.orEmpty(),
                membership?.let { stringResource(R.string.library_in_stack, it.name) }.orEmpty())
                .filter(String::isNotBlank).joinToString(", ")
        }
        val captionHeights = remember(captions, bookWidth, density.density, density.fontScale, captionStyle) {
            captions.mapValues { (_, caption) ->
                val width = with(density) { (bookWidth * .9f - 16.dp).roundToPx() }.coerceAtLeast(1)
                fun height(text: String, lines: Int) = textMeasurer.measure(AnnotatedString(text), style = captionStyle,
                    maxLines = lines, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = width)).size.height
                val height = if (caption.separateCount) height(caption.title, 2) + height(caption.author, 1)
                    else height(caption.label, 1)
                with(density) { height.toDp() } + 8.dp + if (caption.separateCount) 2.dp else 0.dp
            }
        }
        val rowCaptionHeights = rows.map { row -> row.maxOfOrNull { captionHeights[it.key]?.value ?: 0f } ?: 0f }
        val headerVisible = searchOpen || stack != null
        val topPadding = if (headerVisible) with(density) { headerHeight.toDp() } else with(density) { cutout.getTop(this).toDp() } + 16.dp
        val bottomPadding = with(density) { footerHeight.toDp() }.coerceAtLeast(88.dp)
        val latestTopPaddingPx by rememberUpdatedState(with(density) { topPadding.toPx() })
        val shelfRows = calculateLibraryLayout(maxHeight.value, rows.size, rowBookHeight.value,
            hasEmptyMessage = ordered.isEmpty(), topPaddingDp = topPadding.value + 4f, bottomPaddingDp = bottomPadding.value + 12f,
            shelfHeightDp = 8f, rowCaptionHeightsDp = rowCaptionHeights)
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
        val bottomControls = with(density) { bottomPadding.toPx() }
        LaunchedEffect(gestures.drag?.key, shelfRows.scrollable) {
            while (isActive && gestures.drag != null && shelfRows.scrollable) {
                val active = gestures.drag
                if (active?.moved == true && !active.extractReady) {
                    val y = active.pointer.y - inputBounds.top
                    val lowerEdge = (inputBounds.height - bottomControls).coerceAtLeast(edgeSize)
                    val speed = when { y < edgeSize -> -((edgeSize - y) / edgeSize).coerceIn(0f, 1f)
                        y > lowerEdge - edgeSize -> ((y - lowerEdge + edgeSize) / edgeSize).coerceIn(0f, 1f); else -> 0f }
                    val canScroll = (speed < 0f && listState.canScrollBackward) || (speed > 0f && listState.canScrollForward)
                    if (canScroll) {
                        val consumed = listState.scrollBy(speed * with(density) { 12.dp.toPx() })
                        if (kotlin.math.abs(consumed) > .5f) {
                            withFrameNanos { }
                            gestures.refreshAfterScroll(with(density) { 8.dp.toPx() }, canGroup)
                        }
                    }
                }
                delay(16)
            }
        }
        val shelfModifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .onGloballyPositioned { inputBounds = it.boundsInRoot(); gestures.viewportBounds = inputBounds }
            .testTag("library_shelf").libraryShelfInput(shelfEnabled, gestures,
            origin = { inputBounds.topLeft }, zoom = { latestZoom },
            isAtTop = { latestList.firstVisibleItemIndex == 0 && latestList.firstVisibleItemScrollOffset == 0 },
            isSearch = { latestSearch }, canGroup = canGroup, onZoom = ::changeZoom, onSearch = ::openSearch,
            canCloseSearch = { latestSearch && latestQuery.isBlank() }, onCloseSearch = ::closeSearch,
            onHold = { hideCat(); haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            onGroupReady = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            onDrop = { drag, reordered ->
                val source = latestTiles[drag.key]
                val target = latestTiles[drag.hoverKey]
                if (drag.extractReady && latestStack != null && source?.book != null) {
                    gestures.keys = drag.originalKeys
                    hideCat()
                    extractionRootBounds.clear()
                    extraction = LibraryBookExtraction(source.book, Rect(drag.topLeft, drag.bounds.size))
                    stackTransition = true
                    latestCommand(LibraryCommand.ExtractFromStack(listOf(source.book.id)))
                } else if (drag.armed && source?.book != null && target != null) {
                    gestures.keys = drag.originalKeys
                    if (target.stack != null) {
                        val targetBounds = gestures.bounds[target.key]
                        if (targetBounds != null) {
                            hideCat()
                            stackDrop = LibraryStackDrop(source.book, target,
                                Rect(drag.topLeft, drag.bounds.size), targetBounds, latestCatalog, shelfBounds)
                        }
                        try { latestCommand(LibraryCommand.AddToStack(target.stack.id, listOf(source.book.id), onTop = true)) }
                        catch (failure: Throwable) { stackDrop = null; throw failure }
                    }
                    else target.book?.let { targetBook ->
                        hideCat()
                        namingIds = listOf(source.book.id, targetBook.id)
                        pendingStackCreation = gestures.bounds[target.key]?.let { targetBounds ->
                            PendingLibraryStackCreation(source.book, targetBook,
                                Rect(drag.topLeft, drag.bounds.size), targetBounds, latestCatalog, shelfBounds)
                        }
                    }
                } else {
                    // A new group needs a center dwell; existing stacks accept release directly.
                    val destination = drag.dropKey ?: drag.originalKeys.getOrNull(reordered.indexOf(drag.key))
                    if (destination != null && destination != drag.key) move(drag.key, destination)
                }
            }, onActions = { actionsKey = it },
            onBlankTap = { inputPoint, time ->
                val point = inputPoint + inputBounds.topLeft - shelfBounds.topLeft
                if (catBounds?.contains(point) == true) catTap++
                else if (!searchOpen && point.y >= latestTopPaddingPx && point.y < shelfBounds.height - bottomControls) {
                    if (time - firstBlankTap > 3_000 || (point - firstBlankPoint).getDistance() > with(density) { 72.dp.toPx() }) {
                        firstBlankTap = time; firstBlankPoint = point; blankTapCount = 0
                    }
                    blankTapCount++
                    if (blankTapCount >= 5) { catSummonPoint = point; catSummon++; blankTapCount = 0 }
                }
            }, decorationHit = { catBounds?.contains(it + inputBounds.topLeft - shelfBounds.topLeft) == true }, onActivity = { blankTapCount = 0 }).semantics {
                customActions = buildList {
                    if (shelfEnabled) {
                        if (shelfZoom != ShelfZoom.LARGE) add(CustomAccessibilityAction(zoomInLabel) { changeZoom(shelfZoom.closer()); true })
                        if (shelfZoom != ShelfZoom.SMALL) add(CustomAccessibilityAction(zoomOutLabel) { changeZoom(shelfZoom.farther()); true })
                    }
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
            LibraryShelfRow(index, rows, bookWidth, rowBookHeight, (rowCaptionHeights.getOrNull(index) ?: 0f).dp,
                ordered.isEmpty(), searchOpen, shelfEnabled, hiddenBookId ?: extraction?.book?.id ?: stackDrop?.book?.id ?: pendingStackCreation?.book?.id, gestures.drag, ::openTile, reportBounds,
                coverContent, { actionsKey = it.key }, bookActionsLabel, stackActionsLabel, tileActions, captions,
                bookDescriptions = bookDescriptions, hiddenStackId = stackDrop?.stackId,
                hiddenTargetBookId = stackDrop?.takeIf { it.creating }?.target?.book?.id,
                hiddenCaptionStackId = stackDrop?.takeIf { it.releasingShelf }?.stackId,
                stackOrigin = stackOrigin,
                revealProgress = { if (stack != null && !searchOpen) stackReveal.value else 1f })
        }
        Column(Modifier.fillMaxSize()) {
            if (headerVisible) {
                Box(Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }.displayCutoutPadding().padding(horizontal = if (searchOpen) 16.dp else 6.dp, vertical = if (searchOpen) 12.dp else 0.dp), contentAlignment = Alignment.TopCenter) {
                    if (searchOpen) LibrarySearchStrip(query, { query = it }, ::closeSearch,
                        { keyboard?.hide(); focus.clearFocus() }, searchFocus, shelfEnabled, Modifier.widthIn(max = 640.dp).fillMaxWidth().graphicsLayer {
                            translationY = -20.dp.toPx() * (1f - searchReveal.value); alpha = searchReveal.value
                        }, onFieldBoundsChanged = onSearchBoundsChanged)
                    else stack?.let { current ->
                        val holdingBook = gestures.drag != null
                        Box(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                            // Keep the measured heading (including large type) in the layout.
                            // A shorter replacement can switch LazyColumn/Column mid-gesture
                            // and detach the pointer host while the finger is still down.
                            LibraryStackHeader(current, { changeStack(null) }, { actionsKey = "stack:${current.id}" },
                                shelfEnabled && !holdingBook, Modifier.fillMaxWidth().graphicsLayer { alpha = if (holdingBook) 0f else 1f }
                                    .then(if (holdingBook) Modifier.clearAndSetSemantics {} else Modifier))
                            if (holdingBook) LibraryStackExtractionTarget(gestures.drag?.extractReady == true,
                                { gestures.extractionBounds = it }, Modifier.matchParentSize())
                        }
                    }
                }
            } else {
                Spacer(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top)).height(16.dp))
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // The shelf extends behind the floating actions. Its scroll padding lets the
                // last book and caption rise above them instead of reserving an opaque strip.
                val fittedRows = calculateLibraryLayout(maxHeight.value, rows.size, rowBookHeight.value,
                    hasEmptyMessage = ordered.isEmpty(), topPaddingDp = 4f, bottomPaddingDp = bottomPadding.value + 12f,
                    shelfHeightDp = 8f, rowCaptionHeightsDp = rowCaptionHeights)
                PositionReturningLibraryBook(returningBookId, ordered.map { it.key }, columns, listState,
                    fittedRows.scrollable, gestures.bounds,
                    inputBounds.copy(bottom = (inputBounds.bottom - bottomControls).coerceAtLeast(inputBounds.top)),
                    onReturnBookPositioned)
                PositionReturningLibraryItem(stackDrop?.takeIf { it.releasingShelf }?.target?.key,
                    ordered.map { it.key }, columns, listState, fittedRows.scrollable, gestures.bounds,
                    inputBounds.copy(bottom = (inputBounds.bottom - bottomControls).coerceAtLeast(inputBounds.top))) { key, target ->
                    val landing = stackDrop
                    if (landing?.releasingShelf == true && landing.target.key == key && landing.reflowTarget != target) {
                        stackDrop = landing.copy(reflowTarget = target)
                    }
                }
                PositionReturningLibraryBook(extraction?.book?.id?.takeIf { stack == null && !searchOpen },
                    ordered.map { it.key }, columns, listState, fittedRows.scrollable, extractionRootBounds,
                    inputBounds.copy(bottom = (inputBounds.bottom - bottomControls).coerceAtLeast(inputBounds.top))) { id, target ->
                    if (extraction?.book?.id == id && target != null && extraction?.target == null) {
                        extraction = extraction?.copy(target = target)
                    }
                }
                if (fittedRows.scrollable) {
                    LazyColumn(shelfModifier, state = listState, userScrollEnabled = shelfEnabled && gestures.drag == null,
                        contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding + 12.dp)) {
                        items(fittedRows.rowCount) { rowContent(it) }
                    }
                } else {
                    val backgroundRows = calculateLibraryLayout(maxHeight.value, rows.size, rowBookHeight.value,
                        hasEmptyMessage = ordered.isEmpty(), topPaddingDp = 4f, bottomPaddingDp = 0f,
                        shelfHeightDp = 8f, rowCaptionHeightsDp = rowCaptionHeights)
                    Column(shelfModifier.padding(top = 4.dp)) {
                        repeat(fittedRows.rowCount) { rowContent(it) }
                        repeat(if (searchOpen && ordered.isEmpty()) 0 else backgroundRows.decorativeRowCount) { index ->
                            LibraryShelfRow(fittedRows.rowCount + index, emptyList(), bookWidth, bookHeight, 0.dp, false, false,
                                shelfEnabled, null, null, {}, { _, _ -> }, coverContent, {}, bookActionsLabel, stackActionsLabel, { emptyList() }, emptyMap())
                        }
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { footerHeight = it.height }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 24.dp, vertical = 20.dp).testTag("library_floating_controls"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (importing) {
                Row(Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(8.dp)).background(colors.surface.copy(alpha = .94f), RoundedCornerShape(8.dp))
                    .padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.library_importing), Modifier.weight(1f), color = colors.onSurface, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { hideCat(); onCancelImport() }, modifier = Modifier.testTag("library_cancel_import")) { Text(stringResource(R.string.library_cancel)) }
                }
            }
            if (stack != null && !searchOpen) {
                Surface(onClick = { hideCat(); picker = LibraryBookPickerRequest(emptyList(), stack.id) },
                    enabled = shelfEnabled, color = Color(0xFFF3F3F0), contentColor = Color(0xFF31556E),
                    shape = RoundedCornerShape(6.dp), shadowElevation = 5.dp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 520.dp).fillMaxWidth()
                        .heightIn(min = 48.dp).testTag("library_stack_add_books")) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.library_add_books), style = MaterialTheme.typography.labelLarge)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
                    val searchLabel = stringResource(R.string.library_search)
                    LibraryShelfFab(searchLabel, shelfEnabled, { if (searchOpen) closeSearch() else openSearch() },
                        Modifier.testTag("library_search").onGloballyPositioned { if (!searchOpen) onSearchBoundsChanged(it.boundsInRoot()) }) { PaperSearchIcon() }
                    val settingsLabel = stringResource(R.string.library_settings)
                    LibraryShelfFab(settingsLabel, shelfEnabled, { hideCat(); onSettings() },
                        Modifier.testTag("library_settings")) { PaperSettingsIcon() }
                    Box(contentAlignment = Alignment.Center) {
                        LibraryShelfFab(importLabel, shelfEnabled && !importing, { hideCat(); importMenu = true },
                            Modifier.testTag("library_import")) {
                            Text("+", style = MaterialTheme.typography.headlineSmall)
                        }
                        DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.library_import_files)) }, modifier = Modifier.testTag("library_import_files"),
                                onClick = { importMenu = false; onImport() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.library_import_folder)) }, modifier = Modifier.testTag("library_import_folder"),
                                onClick = { importMenu = false; onImportFolder() })
                            if (canUndo) {
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(stringResource(R.string.library_undo)) }, modifier = Modifier.testTag("library_undo"),
                                    onClick = { importMenu = false; hideCat(); onUndo() })
                            }
                        }
                    }
                }
            }
        }
        if (stack == null && !searchOpen) versionLabel?.takeIf(String::isNotBlank)?.let { version ->
            Text(version, Modifier.align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(start = 8.dp, bottom = 3.dp).testTag("library_version"),
                color = Color(0xFFD7E4EC).copy(alpha = .5f), style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        gestures.drag?.takeIf { it.moved }?.let { drag ->
            latestTiles[drag.key]?.let { tile ->
                Box(Modifier.offset { val point = drag.topLeft - shelfBounds.topLeft; IntOffset(point.x.roundToInt(), point.y.roundToInt()) }
                    .size(bookWidth, bookHeight).graphicsLayer { scaleX = LIBRARY_HELD_BOOK_SCALE; scaleY = LIBRARY_HELD_BOOK_SCALE }.shadow(18.dp, RoundedCornerShape(2.dp))
                    .testTag("library_dragging_book")) { if (tile.stack != null) LibraryStackCover(tile, coverContent) else tile.book?.let { coverContent(it) } }
            }
            if (drag.armed) Surface(Modifier.align(Alignment.TopCenter).padding(top = topPadding), shape = RoundedCornerShape(6.dp), shadowElevation = 4.dp) {
                Text(stringResource(if (latestTiles[drag.hoverKey]?.stack != null) R.string.library_stack_drop else R.string.library_group_drop),
                    Modifier.padding(12.dp).testTag("library_drop_hint"), style = MaterialTheme.typography.labelMedium)
            }
            if (!drag.armed && !drag.extractReady) drag.insertionMarker?.let { marker ->
                val markerTop = marker.top.coerceAtLeast(inputBounds.top)
                val markerBottom = marker.bottom.coerceAtMost(inputBounds.bottom)
                if (markerBottom > markerTop) Canvas(Modifier
                    .offset { IntOffset((marker.left - shelfBounds.left - 6.dp.toPx()).roundToInt(), (markerTop - shelfBounds.top).roundToInt()) }
                    .size(12.dp, with(density) { (markerBottom - markerTop).toDp() })
                    .testTag("library_insertion_marker")) {
                    val x = size.width / 2f
                    val radius = 3.dp.toPx()
                    // The dragged cover can cross this gap. A paper-coloured outline keeps
                    // the insertion visible on both a blue cover and the shelf backdrop.
                    drawLine(colors.surface, Offset(x, radius), Offset(x, size.height - radius),
                        strokeWidth = 7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawCircle(colors.surface, radius + 2.dp.toPx(), Offset(x, radius))
                    drawCircle(colors.surface, radius + 2.dp.toPx(), Offset(x, size.height - radius))
                    drawLine(colors.primary, Offset(x, radius), Offset(x, size.height - radius),
                        strokeWidth = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawCircle(colors.primary, radius, Offset(x, radius))
                    drawCircle(colors.primary, radius, Offset(x, size.height - radius))
                }
            }
        }
        pendingStackCreation?.let { pending ->
            // Preserve the exact released cover under the modal scrim. Confirm continues
            // from this visible pose; dismiss simply gives its original shelf slot back.
            Box(Modifier.offset { val point = pending.from.topLeft - shelfBounds.topLeft
                IntOffset(point.x.roundToInt(), point.y.roundToInt()) }
                .size(with(density) { pending.from.width.toDp() }, with(density) { pending.from.height.toDp() })
                .graphicsLayer { scaleX = LIBRARY_HELD_BOOK_SCALE; scaleY = LIBRARY_HELD_BOOK_SCALE }
                .shadow(18.dp, RoundedCornerShape(2.dp)).testTag("library_pending_stack_book")) {
                coverContent(pending.book)
            }
        }
        extraction?.let { LibraryExtractedBookFlight(it, extractionProgress.value, shelfBounds.topLeft, coverContent) }
        stackDrop?.let { arriving ->
            val destination = stackDropDestination(arriving.destination, arriving.reflowTarget ?: arriving.destination,
                stackDropShelfProgress.value)
            LibraryStackDropFlight(arriving, stackDropProgress.value, shelfBounds.topLeft, coverContent,
                destination = destination, shelfProgress = stackDropShelfProgress.value)
            if (arriving.releasingShelf) captions[arriving.target.key]?.let { caption ->
                Box(Modifier.offset { IntOffset((destination.left - shelfBounds.left).roundToInt(),
                    (destination.bottom - shelfBounds.top + 5.dp.toPx()).roundToInt()) }
                    .width(with(density) { destination.width.toDp() }), contentAlignment = Alignment.TopCenter) {
                    LibraryShelfCaption(caption, Modifier.widthIn(max = bookWidth * .9f))
                }
            }
        }
        val localViewport = Rect(Offset.Zero, shelfBounds.size)
        val catSupports = gestures.bounds.mapNotNull { (key, bounds) ->
            val tile = tiles[key] ?: return@mapNotNull null
            var local = bounds.translate(-shelfBounds.topLeft)
            // A stack occupies only the lower portion of its navigation slot. Its physical top
            // (rather than the empty space above it) is the cat's resting edge.
            if (tile.stack != null) local = local.copy(top = (local.top + local.height * .42f).coerceAtLeast(local.top))
            LibraryCatSupport(key, local, tile.stack != null).takeIf { local.overlaps(localViewport) }
        }.sortedBy { (it.bounds.center - catSummonPoint).getDistance() }
        val topExclusion = if (stack != null) with(density) { topPadding.toPx() } else cutout.getTop(density).toFloat()
        val bottomExclusion = bottomControls
        LibraryCat(active = shelfEnabled && !searchOpen && actionsKey == null && picker == null && namingIds == null &&
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
        fun dismiss() { actionsKey = null; hideCat() }
        val menuActions = buildList {
            if (actionStack != null) {
                if (stackId != actionStack.id) add(LibraryMenuAction(R.string.library_open_item, "library_open_item", LibraryMenuIcon.OPEN,
                    { dismiss(); changeStack(actionStack.id) }))
                add(LibraryMenuAction(R.string.library_add_books, "library_add_books", LibraryMenuIcon.ADD,
                    { picker = LibraryBookPickerRequest(emptyList(), actionStack.id); dismiss() }))
                add(LibraryMenuAction(R.string.library_rename_stack, "library_rename_stack", LibraryMenuIcon.RENAME,
                    { renamingId = actionStack.id; dismiss() }))
                add(LibraryMenuAction(R.string.library_dissolve_stack, "library_dissolve_stack", LibraryMenuIcon.DISSOLVE,
                    { dissolvingId = actionStack.id; dismiss() }, destructive = true))
            } else actionBook?.let { book ->
                add(LibraryMenuAction(R.string.library_open_item, "library_open_item", LibraryMenuIcon.OPEN, { dismiss(); openBook(book.id) }))
                add(LibraryMenuAction(R.string.library_share, "library_share", LibraryMenuIcon.SHARE, { onShare(book.id); dismiss() }))
                add(LibraryMenuAction(R.string.library_create_stack, "library_create_stack", LibraryMenuIcon.STACK,
                    { picker = LibraryBookPickerRequest(listOf(book.id)); dismiss() }))
                if (book.stackId != null) add(LibraryMenuAction(R.string.library_extract, "library_extract", LibraryMenuIcon.EXTRACT,
                    { onLibraryCommand(LibraryCommand.ExtractFromStack(listOf(book.id))); dismiss() }))
                if (effectiveCatalog.stacks.any { it.id != book.stackId }) add(LibraryMenuAction(
                    if (book.stackId == null) R.string.library_add_to_stack else R.string.library_move_to_stack,
                    "library_move_to_stack", LibraryMenuIcon.ADD, { movingBook = book; dismiss() }))
                add(LibraryMenuAction(R.string.library_delete, "library_delete", LibraryMenuIcon.DELETE,
                    { deleting = book; dismiss() }, destructive = true))
            }
            if (!searchOpen) {
                val actionKey = actionsKey
                val index = ordered.indexOfFirst { it.key == actionKey }
                ordered.getOrNull(index - 1)?.let { target -> add(LibraryMenuAction(R.string.library_move_earlier, "library_move_earlier",
                    LibraryMenuIcon.EARLIER, { move(actionKey!!, target.key); dismiss() })) }
                ordered.getOrNull(index + 1)?.takeIf { index >= 0 }?.let { target -> add(LibraryMenuAction(R.string.library_move_later, "library_move_later",
                    LibraryMenuIcon.LATER, { move(actionKey!!, target.key); dismiss() })) }
            }
        }
        LibraryItemMenu(actionBook, actionStack, effectiveCatalog.books, menuActions, onDismiss = { actionsKey = null })
    }
    picker?.let { request -> LibraryBookPicker(effectiveCatalog, request, onDismiss = { picker = null }, onConfirm = { ids, name ->
        picker = null
        if (request.destinationStackId != null) onLibraryCommand(LibraryCommand.AddToStack(request.destinationStackId, ids))
        else onLibraryCommand(LibraryCommand.CreateStack(name, ids))
    }) }
    namingIds?.let { ids -> LibraryStackNameDialog(suggestedStackName(ids.mapNotNull(byId::get)) ?: newStackLabel, false,
        books = ids.mapNotNull(byId::get), onDismiss = { namingIds = null; pendingStackCreation = null }, onConfirm = { name ->
            val requestedId = UUID.randomUUID().toString()
            val pending = pendingStackCreation
            if (pending != null && pending.viewport.size == shelfBounds.size &&
                ids.all { id -> latestCatalog.books.any { it.id == id && it.stackId == null } }) {
                stackDrop = pending.confirmed(name, requestedId)
            }
            namingIds = null
            pendingStackCreation = null
            try { onLibraryCommand(LibraryCommand.CreateStack(name, ids, requestedId)) }
            catch (failure: Throwable) { stackDrop = null; throw failure }
        }) }
    renamingId?.let { id -> stacks[id]?.let { current -> LibraryStackNameDialog(current.name, true, onDismiss = { renamingId = null },
        onConfirm = { name -> onLibraryCommand(LibraryCommand.RenameStack(id, name)); renamingId = null }) } }
    movingBook?.let { book -> LibraryPaperSheet(onDismiss = { movingBook = null }) {
        Text(stringResource(R.string.library_move_to_stack), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            effectiveCatalog.stacks.filter { it.id != book.stackId }.forEach { destination ->
                Row(Modifier.fillMaxWidth().clickable {
                    onLibraryCommand(LibraryCommand.AddToStack(destination.id, listOf(book.id))); movingBook = null
                }.testTag("library_destination_${destination.id}").padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LibrarySheetIcon(LibraryMenuIcon.STACK, Modifier.size(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(destination.name, style = MaterialTheme.typography.bodyLarge)
                        Text(pluralStringResource(R.plurals.library_sheet_book_count, destination.bookIds.size, destination.bookIds.size),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        TextButton({ movingBook = null }, Modifier.align(Alignment.End).padding(horizontal = 16.dp)) { Text(stringResource(R.string.library_cancel)) }
    } }
    dissolvingId?.let { id -> LibraryShelfConfirmation(stringResource(R.string.library_dissolve_stack),
        stringResource(R.string.library_dissolve_message), stringResource(R.string.library_dissolve_stack),
        "library_confirm_dissolve", onDismiss = { dissolvingId = null }, onConfirm = {
            onLibraryCommand(LibraryCommand.DissolveStack(id)); dissolvingId = null
        }) }
    deleting?.let { book -> LibraryShelfConfirmation(stringResource(R.string.library_delete_title),
        stringResource(R.string.library_delete_message, book.title), deleteLabel, "library_confirm_delete",
        onDismiss = { deleting = null }, onConfirm = { onDelete(book.id); deleting = null }) }

}

@Composable
private fun LibraryShelfRow(
    rowIndex: Int, rows: List<List<LibraryTile>>, bookWidth: Dp, bookHeight: Dp, labelHeight: Dp,
    empty: Boolean, search: Boolean, interactionEnabled: Boolean, hiddenBookId: BookId?, drag: ShelfItemDrag?,
    onOpen: (LibraryTile) -> Unit, onBounds: (LibraryTile, Rect?) -> Unit, coverContent: @Composable (LibraryBook) -> Unit,
    onActions: (LibraryTile) -> Unit, bookActionsLabel: String, stackActionsLabel: String,
    accessibilityActions: (LibraryTile) -> List<CustomAccessibilityAction>, captions: Map<String, ShelfCaption>,
    bookDescriptions: Map<String, String> = emptyMap(), hiddenStackId: String? = null,
    hiddenCaptionStackId: String? = null, hiddenTargetBookId: BookId? = null,
    stackOrigin: Rect? = null, revealProgress: () -> Float = { 1f },
) {
    val rowTiles = rows.getOrNull(rowIndex).orEmpty()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(bookHeight + 22.dp).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
            rowTiles.forEachIndexed { columnIndex, tile -> key(tile.key) {
                var restingBounds by remember { mutableStateOf<Rect?>(null) }
                DisposableEffect(tile.key) { onDispose { onBounds(tile, null) } }
                Box(Modifier.width(bookWidth).height(bookHeight)
                    .testTag(if (tile.stack != null) "library_stack_${tile.stack.id}" else "library_book_${tile.book?.id?.value}")
                    .onGloballyPositioned { coordinates ->
                        // boundsInRoot clips a partially visible lazy row. The flight needs the
                        // whole cover so it can distinguish an offscreen slot from a smaller book.
                        val bounds = Rect(coordinates.localToRoot(Offset.Zero),
                            Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                        restingBounds = bounds
                        onBounds(tile, bounds)
                    }
                    .graphicsLayer {
                        val p = revealProgress()
                        alpha = when { tile.stack != null && tile.stack.id == hiddenStackId -> 0f; tile.stack == null && (tile.book?.id == hiddenBookId || tile.book?.id == hiddenTargetBookId) -> 0f; tile.key == drag?.key -> .25f; else -> 1f }
                        val target = restingBounds
                        val origin = stackOrigin
                        if (p < 1f && target != null && origin != null) {
                            val fan = columnIndex - (rowTiles.size - 1) / 2f
                            translationX = (origin.center.x - target.center.x) * (1f - p) + fan * 18.dp.toPx() * kotlin.math.sin(p * Math.PI).toFloat()
                            translationY = (origin.bottom - target.bottom) * (1f - p) - 36.dp.toPx() * kotlin.math.sin(p * Math.PI).toFloat()
                            rotationZ = fan * 9f * (1f - p)
                            rotationX = 58f * (1f - p)
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, 1f)
                            cameraDistance = 24f * density
                            scaleX = .82f + .18f * p; scaleY = .82f + .18f * p
                        }
                    }
                    .then(if (tile.stack == null) Modifier.shadow(7.dp, RoundedCornerShape(2.dp), ambientColor = Color(0xFF142A3D), spotColor = Color(0xFF142A3D)) else Modifier)
                    .clickable(enabled = interactionEnabled, role = Role.Button, onClick = { onOpen(tile) })
                    .semantics(mergeDescendants = true) {
                        if (tile.stack == null) tile.book?.let { book ->
                            contentDescription = bookDescriptions[tile.key] ?: book.title
                        }
                        if (interactionEnabled) {
                        onLongClick(if (tile.stack != null) stackActionsLabel else bookActionsLabel) { onActions(tile); true }
                        customActions = accessibilityActions(tile)
                    } }) {
                    if (tile.stack != null) LibraryStackCover(tile, coverContent) else tile.book?.let { coverContent(it) }
                    if (drag?.armed == true && drag.hoverKey == tile.key) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = .18f)))
                }
            } }
        }
        LibraryShelfBoard()
        if (labelHeight > 0.dp) Row(Modifier.fillMaxWidth().height(labelHeight).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)) {
            rowTiles.forEach { tile ->
                Box(Modifier.width(bookWidth), contentAlignment = Alignment.TopCenter) {
                    captions[tile.key]?.let { LibraryShelfCaption(it, Modifier.widthIn(max = bookWidth * .9f).offset(y = (-3).dp).graphicsLayer { alpha = if (tile.stack?.id == hiddenCaptionStackId) 0f else revealProgress() }) }
                }
            }
        }
        if (empty && rowIndex == 0) Text(stringResource(if (search) R.string.library_search_empty else R.string.library_empty),
            Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp).testTag(if (search) "library_search_empty" else "library_empty"),
            color = Color(0xFFE6EEF3).copy(alpha = .85f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PaperSearchIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(19.dp)) {
        val width = 1.5.dp.toPx()
        drawCircle(color, radius = size.width * .3f, center = Offset(size.width * .4f, size.height * .4f), style = androidx.compose.ui.graphics.drawscope.Stroke(width))
        drawLine(color, Offset(size.width * .63f, size.height * .63f), Offset(size.width * .94f, size.height * .94f), width)
    }
}
@Composable
private fun PaperSettingsIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(18.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.35.dp.toPx())
        val width = size.width
        val height = size.height
        val center = Offset(width / 2f, height / 2f)
        drawCircle(color, width * .32f, center, style = stroke)
        drawCircle(color, width * .12f, center, style = stroke)
        repeat(8) { tooth ->
            val angle = tooth * Math.PI / 4
            val direction = Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
            drawLine(color, center + direction * width * .33f, center + direction * width * .48f,
                strokeWidth = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
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
