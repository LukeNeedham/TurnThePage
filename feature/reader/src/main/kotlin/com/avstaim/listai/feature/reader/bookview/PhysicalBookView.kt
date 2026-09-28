package com.avstaim.listai.feature.reader.bookview

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import com.avstaim.listai.core.designsystem.PLYOS_FONT_FAMILY_ID
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.adaptive.DevicePosture
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.CoverPosition
import com.avstaim.listai.core.model.ReadingProgress
import com.avstaim.listai.core.model.Bookmark
import com.avstaim.listai.core.model.NoteId
import com.avstaim.listai.feature.reader.ReaderNavigationState
import com.avstaim.listai.feature.reader.ReaderNavigationSource
import com.avstaim.listai.core.model.readingLength
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.PageMode
import com.avstaim.listai.reader.pagination.pageIndexContaining
import com.avstaim.listai.reader.pagination.readingFlow
import com.avstaim.listai.reader.pagination.resolveLocation
import com.avstaim.listai.feature.reader.R
import com.avstaim.listai.feature.reader.bookmarkColor
import com.avstaim.listai.feature.reader.ReaderSemanticLocation
import com.avstaim.listai.feature.reader.ReaderPaginationComplete
import com.avstaim.listai.feature.reader.ReaderLayoutIdentity
import com.avstaim.listai.feature.reader.ReaderCacheBytes
import com.avstaim.listai.feature.reader.ReaderCachedPages
import com.avstaim.listai.feature.reader.ReaderSurfaceRenders
import com.avstaim.listai.feature.reader.ReaderContentsLocation
import com.avstaim.listai.feature.reader.ReaderVisibleFooters
import com.avstaim.listai.feature.reader.ReaderPaperBounds
import com.avstaim.listai.feature.reader.ReaderContentsRows
import com.avstaim.listai.feature.reader.ReaderContentsTargets
import com.avstaim.listai.feature.reader.ReaderPageNumberBounds
import com.avstaim.listai.feature.reader.ReaderJumpInProgress
import com.avstaim.listai.feature.reader.ReaderJumpDirection
import com.avstaim.listai.feature.reader.ReaderTurnDirection
import com.avstaim.listai.feature.reader.ReaderTurnProgress
import com.avstaim.listai.feature.reader.ReaderTurnSubject
import com.avstaim.listai.feature.reader.ReaderTurnSourceText
import com.avstaim.listai.feature.reader.ReaderCutoutInsets
import com.avstaim.listai.feature.reader.ReaderTopControlLayout
import com.avstaim.listai.feature.reader.ReaderNoteLinks
import com.avstaim.listai.feature.reader.ReaderNoteLinkTargets
import com.avstaim.listai.feature.reader.ReaderNoteReturns
import com.avstaim.listai.feature.reader.ReaderNoteReturnTargets
import com.avstaim.listai.feature.reader.ReaderNotesLocation
import com.avstaim.listai.feature.reader.ReaderNoteVisit
import com.avstaim.listai.feature.reader.readerTopControlLayout
import com.avstaim.listai.feature.reader.demo.DemoPageUiModel
import com.avstaim.listai.reader.rendering.geometry.PageCurlGeometryEngine
import com.avstaim.listai.reader.rendering.geometry.calculateReturningPage
import com.avstaim.listai.reader.rendering.geometry.Point
import com.avstaim.listai.reader.rendering.turn.CurlOrigin
import com.avstaim.listai.reader.rendering.turn.PageTurnPhysicsConfig
import com.avstaim.listai.reader.rendering.turn.SettleTarget
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import com.avstaim.listai.reader.rendering.turn.decideSettleTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlin.math.abs
import kotlin.math.roundToInt

private data class DragSession(
    val direction: TurnDirection,
    val origin: CurlOrigin,
    val rawStart: Offset,
    val rawCurrent: Offset,
)

private data class SettleSession(
    val direction: TurnDirection,
    val target: SettleTarget,
    val startPointer: Point,
    val endPointer: Point,
    val completedState: PhysicalBookState,
    val origin: CurlOrigin,
    val edgeAnchorY: Double?,
    val customAnchor: Point?,
    val jumpDestination: PreparedPagination? = null,
)

private data class ActiveTurn(
    val direction: TurnDirection,
    val pointer: Point,
    val origin: CurlOrigin,
    val edgeAnchorY: Double? = null,
    val customAnchor: Point? = null,
)

private data class ResolvedTurn(
    val baseSurfaces: VisibleBookSurfaces,
    val frame: CurlFrame,
    val progress: Float = frame.geometry.progress.toFloat(),
)

@Composable
internal fun PhysicalBookView(
    pages: List<DemoPageUiModel>,
    bookTitle: String,
    bookAuthor: String?,
    modifier: Modifier = Modifier,
    forcedPresentation: BookPresentation? = null,
    initialPageIndex: Int = 0,
    initialBookState: PhysicalBookState? = null,
    previewTurn: DemoTurnPreview? = null,
    devicePosture: DevicePosture = DevicePosture.Unknown,
    previewDisplayFeature: BookDisplayFeature? = null,
    outerPadding: Dp = 14.dp,
    coverInset: Dp = 7.dp,
    document: BookDocument? = null,
    initialLocation: BookLocation? = null,
    initialCover: CoverPosition = CoverPosition.OPEN,
    onProgressChanged: ((ReadingProgress) -> Unit)? = null,
    initialTocEntryIndex: Int? = null,
    initialTocCharOffset: Int = 0,
    initialNoteId: NoteId? = null,
    readerSession: Long = 0L,
    fontSizeSp: Float = 18f,
    darkTheme: Boolean = false,
    includeContents: Boolean = false,
    bookmarks: List<Bookmark> = emptyList(),
    onAddBookmark: ((BookLocation) -> Unit)? = null,
    onDeleteBookmark: ((Bookmark) -> Unit)? = null,
    onBookmarkableLocationChanged: ((BookLocation?) -> Unit)? = null,
    onCenterTap: (() -> Unit)? = null,
    onPageNumberTap: (() -> Unit)? = null,
    onReturnToShelf: (() -> Unit)? = null,
    onNavigateToLocation: ((BookLocation) -> Unit)? = null,
    onNavigationState: ((ReaderNavigationState) -> Unit)? = null,
    navigationRequest: ReaderJumpRequest? = null,
    onNavigationRequestConsumed: ((Long) -> Unit)? = null,
    noteBackEnabled: Boolean = true,
    transitionCoverProgress: Float? = null,
    closingBook: Boolean = false,
    onBookReady: ((Rect) -> Unit)? = null,
    onBookCoverChanged: ((CoverPosition) -> Unit)? = null,
    onBookArtworkReady: ((ImageBitmap?) -> Unit)? = null,
    drawBackdrop: Boolean = true,
    pageControlInsets: ReaderCutoutInsets = ReaderCutoutInsets(),
    pageControls: (@Composable (ReaderTopControlLayout) -> Unit)? = null,
    closedBookControls: (@Composable (
        bookBounds: Rect,
        viewportBounds: Rect,
        presentation: BookPresentation,
        frontCover: Boolean,
        openFrontCover: () -> Unit,
    ) -> Unit)? = null,
    initialAnnotationLocation: BookLocation? = null,
    initialTitleLocation: BookLocation? = null,
    insideCoverControls: (@Composable (bookBounds: Rect, viewportBounds: Rect, onDescription: () -> Unit) -> Unit)? = null,
    onTurnActiveChange: ((Boolean) -> Unit)? = null,
) {
    require(pages.isNotEmpty() || document != null) { "BookView requires pages or a document" }

    var viewportBoundsInWindow by remember { mutableStateOf<Rect?>(null) }
    val readingPosition = document?.let { rememberReaderPosition(it, initialLocation, initialCover,
        initialTocEntryIndex, initialTocCharOffset, initialNoteId, readerSession, initialAnnotationLocation, initialTitleLocation) }
    var contentsVisit by rememberSaveable(document?.id?.value, readerSession) {
        mutableStateOf(initialTocEntryIndex != null)
    }
    LaunchedEffect(navigationRequest?.sequence) {
        if (navigationRequest?.progress?.tocEntryIndex != null) contentsVisit = true
    }
    LaunchedEffect(closingBook) {
        // An opening transition also runs after Activity restoration. Only the explicit
        // shelving intent may discard a restored note visit, even before surfaces are ready.
        if (closingBook) readingPosition?.clearNotes()
    }
    val totalCharacters = remember(document) { document?.readingLength() ?: 1L }
    val sourceFlow = remember(document) { document?.readingFlow() }
    val navigationSource = remember(document) { document?.let(::ReaderNavigationSource) }
    val progressCallback by rememberUpdatedState(onProgressChanged)
    val centerCallback by rememberUpdatedState(onCenterTap)
    val pageNumberCallback by rememberUpdatedState(onPageNumberTap)
    val shelfCallback by rememberUpdatedState(onReturnToShelf)
    val navigateCallback by rememberUpdatedState(onNavigateToLocation)
    val navigationCallback by rememberUpdatedState(onNavigationState)
    val consumedNavigationCallback by rememberUpdatedState(onNavigationRequestConsumed)
    val readyCallback by rememberUpdatedState(onBookReady)
    val coverCallback by rememberUpdatedState(onBookCoverChanged)
    val artworkCallback by rememberUpdatedState(onBookArtworkReady)
    val bookmarkableLocationCallback by rememberUpdatedState(onBookmarkableLocationChanged)
    val externalTransitionActive by rememberUpdatedState(transitionCoverProgress != null)
    LaunchedEffect(readingPosition) {
        readingPosition?.let { progressCallback?.invoke(it.progress(totalCharacters)) }
    }
    var dragSession by remember { mutableStateOf<DragSession?>(null) }
    var settleSession by remember { mutableStateOf<SettleSession?>(null) }
    var gestureActive by remember { mutableStateOf(false) }
    val turnActiveCallback by rememberUpdatedState(onTurnActiveChange)
    val physicalTurnActive = gestureActive || dragSession != null || settleSession != null || transitionCoverProgress != null
    LaunchedEffect(physicalTurnActive) {
        turnActiveCallback?.invoke(physicalTurnActive)
    }
    BoxWithConstraints(
        modifier = modifier.onGloballyPositioned { coordinates ->
            viewportBoundsInWindow = coordinates.boundsInWindow()
        },
    ) {
        val density = LocalDensity.current
        val canvasSize = androidx.compose.ui.geometry.Size(
            width = constraints.maxWidth.coerceAtLeast(1).toFloat(),
            height = constraints.maxHeight.coerceAtLeast(1).toFloat(),
        )
        val outerPaddingPx = with(density) { outerPadding.toPx() }
        val coverInsetPx = with(density) { coverInset.toPx() }
        val displayFeatureInWindow = remember(devicePosture) {
            devicePosture.toBookDisplayFeatureInWindow()
        }
        val displayFeature = previewDisplayFeature ?: remember(
            displayFeatureInWindow,
            viewportBoundsInWindow,
        ) {
            val viewport = viewportBoundsInWindow
            if (displayFeatureInWindow != null && viewport != null) {
                displayFeatureInWindow.toLocalViewport(viewport)
            } else {
                null
            }
        }
        val presentation = remember(
            canvasSize,
            density.density,
            outerPaddingPx,
            displayFeature,
            forcedPresentation,
        ) {
            resolveAdaptiveBookPresentation(
                canvasSize = canvasSize,
                density = density.density,
                outerPaddingPx = outerPaddingPx,
                displayFeature = displayFeature,
                forcedPresentation = forcedPresentation,
            )
        }
        val layout = remember(canvasSize, presentation, displayFeature, outerPaddingPx, coverInsetPx) {
            calculateBookCanvasLayout(
                canvasSize = canvasSize,
                presentation = presentation,
                outerPaddingPx = outerPaddingPx,
                coverInsetPx = coverInsetPx,
                displayFeature = displayFeature,
            )
        }
        val allPaperBounds = listOfNotNull(layout.leftPage, layout.rightPage)
        val paperContentTop = if (pageControls == null) 0f else remember(layout, pageControlInsets, density.density) {
            // Reserve the worst case too: the first/last leaf can be the only visible paper.
            (listOf(allPaperBounds) + allPaperBounds.map { listOf(it) }).maxOf { bounds ->
                readerTopControlLayout(canvasSize.width, pageControlInsets, density.density, bounds).contentTop
            } - layout.rightPage.top
        }
        val pagination = document?.let {
            rememberPaginationSession(it, LayoutKey(
                viewportWidthPx = canvasSize.width.roundToInt().coerceAtLeast(1),
                viewportHeightPx = canvasSize.height.roundToInt().coerceAtLeast(1),
                pageWidthPx = layout.pageWidth.roundToInt().coerceAtLeast(1),
                pageHeightPx = layout.pageHeight.roundToInt().coerceAtLeast(1),
                pageMode = if (presentation == BookPresentation.SPREAD) PageMode.SPREAD else PageMode.SINGLE,
                fontFamilyId = PLYOS_FONT_FAMILY_ID,
                density = density.density,
                densityKey = density.density.toString(),
                textScale = density.fontScale,
                fontSizeSp = fontSizeSp,
                darkTheme = darkTheme,
                localeTag = it.metadata.language ?: "en",
                topContentInsetDp = paperContentTop.coerceAtLeast(0f) / density.density,
            ), requireNotNull(readingPosition), includeContents &&
                (contentsVisit || readingPosition.tocEntryIndex != null || navigationRequest?.progress?.tocEntryIndex != null))
        }
        // Read during composition even while a displayed snapshot is pinned. Reading only in
        // SideEffect would not subscribe this host to subsequent background publications.
        val latestPagination = pagination?.ready
        // Cache activity continues while a curl pins its immutable scene. Observe the store
        // separately so diagnostics track live ownership instead of the source scene's metrics.
        val liveCache = if (pagination != null && !LocalInspectionMode.current)
            pagination.store.state.collectAsState().value else null
        var displayedPagination by remember(pagination) { mutableStateOf<PreparedPagination?>(null) }
        var jumpPreparing by remember(pagination) { mutableStateOf<ReaderJumpRequest?>(null) }
        // Do not replace navigation/surfaces while a finger or settle animation owns a leaf.
        SideEffect {
            if (!gestureActive && dragSession == null && settleSession == null && jumpPreparing == null)
                displayedPagination = latestPagination
        }
        val ready = displayedPagination ?: latestPagination
        val latestCoverSurface = rememberExternalBookCoverSurface(
            title = bookTitle, author = bookAuthor,
            coverSize = IntSize(layout.pageWidth.roundToInt().coerceAtLeast(1),
                layout.pageHeight.roundToInt().coerceAtLeast(1)),
            document = document, darkTheme = darkTheme,
        )
        if (pagination != null && ready == null) {
            val loadingModifier = if (drawBackdrop) {
                Modifier.fillMaxSize().drawBehind { drawReaderBackdrop(darkTheme) }
            } else {
                Modifier.fillMaxSize()
            }
            Box(loadingModifier,
                contentAlignment = Alignment.Center) {
                Text(stringResource(if (pagination.failed) R.string.reader_pagination_failed else R.string.reader_paginating),
                    color = MaterialTheme.colorScheme.onBackground)
            }
            // Resize can dispose the previous gesture before its settle job clears these fields.
            LaunchedEffect(pagination) {
                dragSession = null
                settleSession = null
                gestureActive = false
                bookmarkableLocationCallback?.invoke(null)
            }
            LaunchedEffect(pagination.failed, latestCoverSurface.isReady) {
                if (pagination.failed && latestCoverSurface.isReady) {
                    artworkCallback?.invoke(latestCoverSurface.image)
                    readyCallback?.invoke(layout.rightPage)
                }
            }
            return@BoxWithConstraints
        }
        val preparedPages = ready?.surfaces ?: rememberDemoPageSurfaces(
            pages = pages,
            pageSize = IntSize(
                width = layout.pageWidth.roundToInt().coerceAtLeast(1),
                height = layout.pageHeight.roundToInt().coerceAtLeast(1),
            ),
        )
        var displayedCoverSurface by remember(pagination, layout) { mutableStateOf(latestCoverSurface) }
        SideEffect {
            if (!gestureActive && dragSession == null && settleSession == null) displayedCoverSurface = latestCoverSurface
        }
        val coverSurface = displayedCoverSurface
        val insideFront = ready?.insideFront
        // A narrow/single-leaf book opens onto a separate white front-matter leaf. Its FB2
        // annotation is paper content, never ink attached to the moving hard cover.
        val hasFrontEndpaper = presentation == BookPresentation.SINGLE &&
            (document == null || document.blocks.any { it is com.avstaim.listai.core.model.BookBlock.FixedPage })
        val navigator = remember(preparedPages.size, hasFrontEndpaper, presentation) {
            PhysicalBookNavigator(pageCount = preparedPages.size, hasFrontEndpaper = hasFrontEndpaper)
        }
        val resolvedInitialState = remember(
            pagination ?: preparedPages.size,
            initialPageIndex,
            initialBookState,
        ) {
            ready?.let { requireNotNull(readingPosition).initialState(it.snapshot, it.contents, it.tocSnapshot,
                it.notes, it.notesSnapshot, it.annotationSnapshot) } ?: initialBookState
                ?.normalized(preparedPages.size)
                ?: PhysicalBookState.Open(initialPageIndex.coerceIn(0, preparedPages.lastIndex))
        }
        val savedPositionState = if (pagination != null) remember(pagination) {
            // Persist the source anchor, never restore an obsolete numeric page after recreation.
            mutableIntStateOf(resolvedInitialState.toSavedPosition(preparedPages.size))
        } else rememberSaveable(
            preparedPages.size,
            resolvedInitialState,
        ) {
            mutableIntStateOf(resolvedInitialState.toSavedPosition(preparedPages.size))
        }
        var savedBookPosition by savedPositionState
        val bookState = savedBookPosition.toPhysicalBookState(preparedPages.size)
        val atInsideFront = bookState == PhysicalBookState.FrontBoundary ||
            presentation == BookPresentation.SPREAD && navigator.layoutAnchor(bookState, presentation) == 0
        val readyRawBounds = layout.closedBookBounds(bookState) ?: layout.rightPage
        // Publish geometry in the same composition frame. Closed-book controls and the shelf
        // transition must be positioned from the native leaf before any asynchronous effect
        // gets a chance to expose an unadorned or stale-size cover.
        SideEffect {
            if ((document == null || ready != null) && coverSurface.isReady) {
                readyCallback?.invoke(readyRawBounds)
            }
        }
        LaunchedEffect(layout, ready != null, coverSurface.isReady, bookState) {
            if ((document == null || ready != null) && coverSurface.isReady) {
                artworkCallback?.invoke(coverSurface.image)
                coverCallback?.invoke(if (bookState == PhysicalBookState.ClosedBack) CoverPosition.BACK else CoverPosition.FRONT)
            }
        }
        val navigationState = remember(document, ready?.snapshot, readingPosition?.location) {
            if (navigationSource != null && navigationSource.flow.isNotEmpty() && ready != null && ready.snapshot.pages.isNotEmpty())
                ReaderNavigationState(navigationSource, ready.snapshot, readingPosition?.location) else null
        }
        LaunchedEffect(navigationState) { navigationState?.let { navigationCallback?.invoke(it) } }
        SideEffect {
            if (!gestureActive && dragSession == null && settleSession == null && jumpPreparing == null)
                pagination?.request(bookState, presentation)
        }
        val settleFraction = remember { Animatable(0f) }
        val geometryEngine = remember { PageCurlGeometryEngine() }
        val physicsConfig = remember { PageTurnPhysicsConfig() }
        val scope = rememberCoroutineScope()
        var settleJob by remember { mutableStateOf<Job?>(null) }
        fun cancelUncommittedTurn() {
            settleJob?.cancel()
            settleJob = null
            dragSession = null
            settleSession = null
            gestureActive = false
            jumpPreparing = null
            contentsVisit = readingPosition?.tocEntryIndex != null
            pagination?.cancelJump(bookState, presentation)
        }
        // A layout session owns both its rasters and its transactions. Disposing it cannot commit
        // the old target into a newly paginated book, nor replay a consumed navigation command.
        val view = LocalView.current
        val cancelLatestTurn by rememberUpdatedState({ cancelUncommittedTurn() })
        DisposableEffect(pagination, layout, view) {
            val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) cancelLatestTurn()
            }
            lifecycle?.addObserver(observer)
            onDispose {
                lifecycle?.removeObserver(observer)
                settleJob?.cancel()
                settleJob = null
                dragSession = null
                settleSession = null
                gestureActive = false
                jumpPreparing = null
                pagination?.cancelJump()
            }
        }
        LaunchedEffect(transitionCoverProgress != null) {
            if (transitionCoverProgress != null) {
                // The outer cover transition owns the scene, including an opening after
                // recreation. Keep its confirmed source or notes position intact.
                cancelUncommittedTurn()
                settleFraction.snapTo(0f)
            }
        }

        fun transition(direction: TurnDirection): PhysicalBookTransition? =
            navigator.transition(bookState, direction, presentation)?.takeIf { transition ->
                val indexed = if (ready == null || ready.snapshot.complete || direction == TurnDirection.BACKWARD) true
                else (transition.to as? PhysicalBookState.Open)?.let { target ->
                    val anchor = navigator.layoutAnchor(target, presentation) ?: 0
                    anchor + (if (presentation == BookPresentation.SPREAD && anchor > 0) 1 else 0) < preparedPages.size
                } == true
                indexed && visiblePageIndices(transition.to, presentation, preparedPages.size).all { preparedPages[it] != null }
            }

        fun canTurn(direction: TurnDirection): Boolean = transition(direction) != null

        fun launchSettle(
            direction: TurnDirection,
            target: SettleTarget,
            startPointer: Point,
            origin: CurlOrigin = CurlOrigin.LOWER_OUTER,
            edgeAnchorY: Double? = null,
            customAnchor: Point? = null,
            jumpDestination: PreparedPagination? = null,
        ) {
            if (jumpDestination == null && !canTurn(direction)) return
            settleJob?.cancel()
            val completedState = jumpDestination?.jump?.target ?: transition(direction)?.to ?: return
            val sourceBounds = layout.sourcePage(direction)
            val anchorY = edgeAnchorY
                ?.coerceIn(sourceBounds.top.toDouble(), sourceBounds.bottom.toDouble())
                ?: sourceBounds.bottom.toDouble()
            val resolvedCustomAnchor = customAnchor?.takeIf { origin == CurlOrigin.FREE_POINT }
            val endPointer = when {
                target == SettleTarget.COMPLETE && resolvedCustomAnchor != null ->
                    layout.completionPointer(direction, resolvedCustomAnchor)

                target == SettleTarget.COMPLETE -> layout.completionPointer(direction, anchorY)
                resolvedCustomAnchor != null -> resolvedCustomAnchor
                else -> Point(
                    x = if (direction == TurnDirection.FORWARD) {
                        sourceBounds.right.toDouble()
                    } else {
                        sourceBounds.left.toDouble()
                    },
                    y = anchorY,
                )
            }
            dragSession = null
            val session = SettleSession(
                direction = direction,
                target = target,
                startPointer = startPointer,
                endPointer = endPointer,
                completedState = completedState,
                origin = origin,
                edgeAnchorY = edgeAnchorY,
                customAnchor = resolvedCustomAnchor,
                jumpDestination = jumpDestination,
            )
            settleSession = session
            settleJob = scope.launch {
                settleFraction.snapTo(0f)
                val duration = if (target == SettleTarget.COMPLETE) {
                    physicsConfig.maxSettleDurationMs
                } else {
                    (physicsConfig.maxSettleDurationMs * 0.72f).roundToInt()
                }
                settleFraction.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = duration.coerceAtLeast(physicsConfig.minSettleDurationMs),
                        easing = if (target == SettleTarget.COMPLETE) {
                            CubicBezierEasing(0.18f, 0.72f, 0.18f, 1f)
                        } else {
                            CubicBezierEasing(0.22f, 0.78f, 0.34f, 1f)
                        },
                    ),
                )
                val finished = settleSession
                if (finished === session && finished.target == SettleTarget.COMPLETE && !externalTransitionActive) {
                    val destination = finished.jumpDestination ?: ready
                    savedBookPosition = finished.completedState.toSavedPosition(destination?.surfaces?.size ?: preparedPages.size)
                    destination?.let {
                        if (finished.jumpDestination != null) {
                            readingPosition?.commitJump(requireNotNull(it.jump).request, it.notes)
                            displayedPagination = it
                            jumpPreparing = null
                            pagination?.cancelJump(finished.completedState, presentation)
                        } else readingPosition?.commit(finished.completedState, it.snapshot, it.contents,
                            it.tocSnapshot, it.notes, it.notesSnapshot, it.annotationSnapshot, it.titleBlockIds)
                        readingPosition?.let { position ->
                            progressCallback?.invoke(position.progress(totalCharacters))
                            contentsVisit = position.tocEntryIndex != null
                        }
                    }
                    pagination?.request(finished.completedState, presentation, finished.direction)
                }
                settleSession = null
                settleFraction.snapTo(0f)
            }
        }

        fun launchAutomaticTurn(direction: TurnDirection): Boolean {
            if (previewTurn != null || externalTransitionActive || jumpPreparing != null || dragSession != null || settleSession != null || !canTurn(direction)) {
                return false
            }
            val source = layout.sourcePage(direction)
            val grab = Point(
                x = if (direction == TurnDirection.FORWARD) source.right.toDouble() else source.left.toDouble(),
                y = source.bottom.toDouble(),
            )
            val inwardNudge = if (direction == TurnDirection.FORWARD) -1.5 else 1.5
            launchSettle(
                direction = direction,
                target = SettleTarget.COMPLETE,
                startPointer = Point(grab.x + inwardNudge, grab.y - 1.5),
            )
            return true
        }

        fun beginJump(request: ReaderJumpRequest) {
            if (pagination == null || ready == null || externalTransitionActive) return
            cancelUncommittedTurn()
            val source = ready.visibleOnly(bookState, presentation)
            displayedPagination = source
            val preparedRequest = readingPosition?.prepareJump(request) ?: request
            contentsVisit = preparedRequest.progress.tocEntryIndex != null || readingPosition?.tocEntryIndex != null
            jumpPreparing = preparedRequest
            pagination.requestJump(preparedRequest, source, bookState, presentation)
        }
        var localJumpSequence by remember(document) { androidx.compose.runtime.mutableLongStateOf(-1L) }
        fun openNote(noteId: NoteId, origin: BookLocation? = null) {
            val position = readingPosition ?: return
            val target = ready?.notes?.targets?.get(noteId) ?: return
            beginJump(ReaderJumpRequest(--localJumpSequence, position.progress(totalCharacters), noteId, origin, target))
        }
        fun returnFromNote(noteId: NoteId) {
            val position = readingPosition ?: return
            val origin = ready?.notes?.returnOrigin(noteId, position.visit) ?: return
            val progress = position.progress(totalCharacters).copy(location = origin, cover = CoverPosition.OPEN,
                tocEntryIndex = null, tocCharOffset = 0)
            beginJump(ReaderJumpRequest(--localJumpSequence, progress, returningFromNote = true))
        }
        val noteBackTarget = readingPosition?.noteId?.takeIf {
            ready?.notes?.returnOrigin(it, readingPosition.visit) != null
        }
        BackHandler(enabled = noteBackEnabled && noteBackTarget != null && !externalTransitionActive) {
            noteBackTarget?.let(::returnFromNote)
        }
        LaunchedEffect(navigationRequest, pagination, layout) {
            val request = navigationRequest ?: return@LaunchedEffect
            if (pagination == null || ready == null) return@LaunchedEffect
            beginJump(request)
            consumedNavigationCallback?.invoke(request.sequence)
        }
        LaunchedEffect(latestPagination?.jump?.request?.sequence, jumpPreparing?.sequence) {
            val destination = latestPagination ?: return@LaunchedEffect
            val jump = destination.jump ?: return@LaunchedEffect
            if (jumpPreparing?.sequence != jump.request.sequence || externalTransitionActive) return@LaunchedEffect
            val direction = when {
                jump.request.noteId != null -> TurnDirection.FORWARD
                jump.request.returningFromNote -> TurnDirection.BACKWARD
                else -> readerJumpDirection(bookState, jump.target,
                    requireNotNull(readingPosition).progress(totalCharacters), jump.request.progress)
            }
            val source = layout.sourcePage(direction)
            launchSettle(direction, SettleTarget.COMPLETE,
                Point(if (direction == TurnDirection.FORWARD) source.right - 1.5 else source.left + 1.5,
                    source.bottom - 1.5), jumpDestination = destination)
        }
        LaunchedEffect(pagination?.failedJumpSequence, jumpPreparing?.sequence) {
            if (jumpPreparing != null && pagination?.failedJumpSequence == jumpPreparing?.sequence) {
                cancelUncommittedTurn()
                settleFraction.snapTo(0f)
            }
        }

        val annotationState = stringResource(R.string.reader_annotation_state)
        val titlePageState = stringResource(R.string.reader_title_page)
        val openPageState = (bookState as? PhysicalBookState.Open)?.let { state ->
            if (state.semanticPageIndex < (ready?.annotationPageCount ?: 0)) {
                if (ready?.annotationSnapshot?.pages?.getOrNull(state.semanticPageIndex)?.start?.blockId in ready?.titleBlockIds.orEmpty())
                    titlePageState else annotationState
            } else
                preparedPages.getOrNull(state.semanticPageIndex)?.paginated?.footerText ?: stringResource(
                if (ready != null && !ready.snapshot.complete) R.string.reader_page_progress_pending else R.string.reader_page_progress,
                state.semanticPageIndex.coerceIn(0, preparedPages.lastIndex) + 1,
                preparedPages.size,
            )
        }
        val frontCoverState = stringResource(R.string.reader_front_cover_state)
        val frontEndpaperState = stringResource(R.string.reader_front_endpaper_state)
        val backCoverState = stringResource(R.string.reader_back_cover_state)
        val backBoundaryState = stringResource(R.string.reader_back_boundary_state)
        val stateText = when (bookState) {
            PhysicalBookState.ClosedFront -> frontCoverState
            PhysicalBookState.FrontBoundary -> if (insideFront != null) annotationState else frontEndpaperState
            PhysicalBookState.BackBoundary -> backBoundaryState
            PhysicalBookState.ClosedBack -> backCoverState
            is PhysicalBookState.Open -> requireNotNull(openPageState)
        }
        val bookDescription = stringResource(R.string.reader_book_accessibility)
        val forwardAction = stringResource(R.string.reader_turn_forward_action)
        val backwardAction = stringResource(R.string.reader_turn_backward_action)
        val idleSurfaces = visibleBookSurfaces(
            pages = preparedPages, coverSurface = coverSurface, state = bookState,
            presentation = presentation, navigator = navigator,
            insideFront = insideFront,
        )
        val openPaperBounds = if (bookState.isClosedCover()) emptyList() else buildList {
            if (idleSurfaces.left.isReadablePaperSurface())
                layout.leftPage?.let(::add)
            if (idleSurfaces.right.isReadablePaperSurface())
                add(layout.rightPage)
        }
        val pageControlsLayout = readerTopControlLayout(canvasSize.width, pageControlInsets,
            density.density, openPaperBounds).copy(onCoverMaterial =
                (idleSurfaces.left as? BookVisualSurface.Cover)?.insideContent != null ||
                    (idleSurfaces.right as? BookVisualSurface.Cover)?.insideContent != null,
                onWhitePaper = idleSurfaces.left.isWhitePaperSurface() ||
                    idleSurfaces.right.isWhitePaperSurface())
        val pageNumberBounds = if (ready != null) buildList {
            if ((idleSurfaces.left as? BookVisualSurface.Page)?.surface?.paginated?.footerText != null)
                layout.leftPage?.let { add(pageNumberTapBounds(it, ready.snapshot.key)) }
            if ((idleSurfaces.right as? BookVisualSurface.Page)?.surface?.paginated?.footerText != null)
                add(pageNumberTapBounds(layout.rightPage, ready.snapshot.key))
        } else emptyList()
        val bookmarkableLocation = if (
            !sourceFlow.isNullOrEmpty() && ready != null && bookState is PhysicalBookState.Open &&
            readingPosition?.cover == "open" && readingPosition.tocEntryIndex == null &&
            readingPosition.annotationLocation == null && readingPosition.titleLocation == null && readingPosition.noteId == null
        ) {
            val resolved = sourceFlow.resolveLocation(readingPosition.location)
            val sourcePage = ready.snapshot.pages.pageIndexContaining(resolved)
            val physicalPage = ready.sourcePageOffset + sourcePage
            resolved.takeIf {
                physicalPage in visiblePageIndices(bookState, presentation, preparedPages.size)
            }
        } else null
        LaunchedEffect(bookmarkableLocation) {
            bookmarkableLocationCallback?.invoke(bookmarkableLocation)
        }
        val quickBookmarkPlacement = bookmarkableLocation?.let { location ->
            val sourcePage = requireNotNull(ready).snapshot.pages.pageIndexContaining(location)
            val physicalPage = ready.sourcePageOffset + sourcePage
            val visible = visiblePageIndices(bookState, presentation, preparedPages.size)
            val side = visible.indexOf(physicalPage)
            if (side < 0) null else {
                // A bookmark belongs to the open spread, not to whichever leaf happens to contain
                // the semantic anchor. Its physical strip always hangs from the upper-right edge
                // of the left leaf, immediately before the spine. This is also where the original
                // passive indicator lived.
                val spread = presentation == BookPresentation.SPREAD && visible.size > 1
                val pageBounds = if (spread) layout.leftPage ?: layout.rightPage else layout.rightPage
                val target48 = with(density) { 48.dp.toPx() }
                val target32 = with(density) { 32.dp.toPx() }
                val targetSize = minOf(target48, pageBounds.width * .18f, pageBounds.height * .18f)
                    .coerceAtLeast(minOf(target32, pageBounds.width, pageBounds.height))
                val top = if (spread) {
                    quickBookmarkAtTop(
                        pageBounds = pageBounds,
                        targetSize = targetSize,
                        topOverhang = with(density) { 3.dp.toPx() },
                        slot = QuickBookmarkTopSlot.SPREAD_LEFT,
                    )
                } else {
                    quickBookmarkSingleAtTop(
                        pageBounds = pageBounds,
                        targetSize = targetSize,
                        topOverhang = with(density) { 3.dp.toPx() },
                        gap = with(density) { 4.dp.toPx() },
                        controlBounds = pageControlsLayout.buttons,
                        cutoutBounds = pageControlInsets.cutouts,
                    )
                }
                top?.let { geometry ->
                    val current = bookmarks.firstOrNull {
                        it.location.blockId == location.blockId && it.location.charOffset == location.charOffset
                    }
                    QuickBookmarkPlacement(
                        location = location,
                        bookmark = current,
                        bounds = geometry.bounds,
                    ).takeIf { current?.let { onDeleteBookmark != null } ?: (onAddBookmark != null) }
                }
            }
        }
        val noteLinks = remember(ready, bookState, layout, presentation, density.density) {
            buildList {
                if (bookState !is PhysicalBookState.Open || ready == null) return@buildList
                val visible = visiblePageIndices(bookState, presentation, preparedPages.size)
                visible.forEachIndexed { side, index ->
                    val leaf = if (visible.size > 1 && side == 0) layout.leftPage ?: layout.rightPage else layout.rightPage
                    preparedPages[index]?.paginated?.noteLinks?.forEach { link ->
                        add(noteTapBounds(link.bounds, leaf, ready.snapshot.key, density.density) to link)
                    }
                }
            }
        }
        val noteReturns = remember(ready, bookState, layout, presentation, density.density) {
            buildList {
                if (bookState !is PhysicalBookState.Open || ready?.notesSnapshot == null) return@buildList
                val visible = visiblePageIndices(bookState, presentation, preparedPages.size)
                visible.forEachIndexed { side, index ->
                    val leaf = if (visible.size > 1 && side == 0) layout.leftPage ?: layout.rightPage else layout.rightPage
                    preparedPages[index]?.paginated?.noteReturns?.forEach { link ->
                        add(noteTapBounds(link.bounds, leaf, ready.notesSnapshot.key, density.density, expand = false) to link)
                    }
                }
            }
        }
        val semanticsModifier = Modifier.semantics {
            contentDescription = bookDescription
            stateDescription = stateText
            this[ReaderPaperBounds] = openPaperBounds
            this[ReaderPageNumberBounds] = pageNumberBounds
            this[ReaderJumpInProgress] = jumpPreparing != null
            this[ReaderJumpDirection] = settleSession?.takeIf { it.jumpDestination != null }
                ?.direction?.name?.lowercase().orEmpty()
            this[ReaderNoteLinks] = noteLinks.map { it.first }
            this[ReaderNoteLinkTargets] = noteLinks.map { (_, link) ->
                "${link.noteId.value}|${link.origin.blockId.value}:${link.origin.charOffset}:${link.origin.fallbackGlobalOffset}|${link.kind.name.lowercase()}"
            }
            this[ReaderNoteReturns] = noteReturns.map { it.first }
            this[ReaderNoteReturnTargets] = noteReturns.map { it.second.noteId.value }
            this[ReaderNotesLocation] = readingPosition?.noteLocation?.let {
                "${readingPosition.noteId?.value}|${it.blockId.value}:${it.charOffset}:${it.fallbackGlobalOffset}"
            }.orEmpty()
            this[ReaderNoteVisit] = readingPosition?.visit?.let {
                "${it.noteId.value}|${it.origin.blockId.value}:${it.origin.charOffset}:${it.origin.fallbackGlobalOffset}"
            }.orEmpty()
            ready?.let {
                val anchor = readingPosition?.location ?: it.snapshot.pages.firstOrNull()?.start
                    ?: it.annotationSnapshot?.pages?.firstOrNull()?.start
                this[ReaderSemanticLocation] = anchor?.let { "${it.blockId.value}:${it.charOffset}:${it.fallbackGlobalOffset}" }.orEmpty()
                this[ReaderPaginationComplete] = it.snapshot.complete
                this[ReaderLayoutIdentity] = it.snapshot.key.toString()
                this[ReaderCacheBytes] = liveCache?.bytes ?: it.cacheBytes
                this[ReaderCachedPages] = liveCache?.values?.size ?: it.cachedPages
                this[ReaderSurfaceRenders] = liveCache?.rendered ?: it.renderedPages
                this[ReaderContentsLocation] = readingPosition?.tocEntryIndex?.let { entry ->
                    "$entry:${readingPosition.tocCharOffset}"
                }.orEmpty()
                this[ReaderVisibleFooters] = visiblePageIndices(bookState, presentation, preparedPages.size)
                    .mapNotNull { preparedPages[it]?.paginated?.footerText }.joinToString(" | ")
                val contentsRows = buildList {
                    val visible = visiblePageIndices(bookState, presentation, preparedPages.size)
                    visible.forEachIndexed { side, index ->
                        val bounds = if (visible.size > 1 && side == 0) layout.leftPage ?: layout.rightPage else layout.rightPage
                        it.tocSnapshot?.pages?.getOrNull(index - it.annotationPageCount)?.blocks?.forEach { row ->
                            it.contents?.targets?.get(row.blockId)?.let { target ->
                                add(Rect(row.x, row.y, row.x + row.width, row.y + row.height)
                                    .translate(bounds.topLeft) to target)
                            }
                        }
                    }
                }
                this[ReaderContentsRows] = contentsRows.map { row -> row.first }
                this[ReaderContentsTargets] = contentsRows.map { row ->
                    "${row.second.blockId.value}:${row.second.charOffset}:${row.second.fallbackGlobalOffset}"
                }
                val insideText = insideFront?.paginated?.accessibleText?.takeIf {
                    atInsideFront
                }
                text = AnnotatedString((listOfNotNull(insideText) + visiblePageIndices(bookState, presentation, preparedPages.size).mapNotNull { index ->
                    preparedPages[index]?.paginated?.accessibleText
                }).joinToString("\n\n"))
            }
            // A backward cover swipe reopens it; its tap returns the book to the shelf.
            if (bookState != PhysicalBookState.ClosedBack && canTurn(TurnDirection.FORWARD)) {
                onClick(label = forwardAction) { launchAutomaticTurn(TurnDirection.FORWARD) }
            }
            customActions = buildList {
                if (canTurn(TurnDirection.FORWARD)) {
                    add(CustomAccessibilityAction(forwardAction) {
                        launchAutomaticTurn(TurnDirection.FORWARD)
                    })
                }
                if (canTurn(TurnDirection.BACKWARD)) {
                    add(CustomAccessibilityAction(backwardAction) {
                        launchAutomaticTurn(TurnDirection.BACKWARD)
                    })
                }
            }
        }

        val gestureModifier = if (previewTurn == null && transitionCoverProgress == null) {
            Modifier.pointerInput(layout, savedBookPosition, preparedPages.size, presentation, ready?.interactionToken,
                pageControlsLayout, pageControls != null, quickBookmarkPlacement?.bounds) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (pageControls != null && pageControlsLayout.buttons.any { it.contains(down.position) }) {
                        waitUntilReleased(down.id)
                        return@awaitEachGesture
                    }
                    if (quickBookmarkPlacement?.bounds?.contains(down.position) == true) {
                        waitUntilReleased(down.id)
                        return@awaitEachGesture
                    }
                    gestureActive = true
                    try {
                        if (jumpPreparing != null || dragSession != null || settleSession != null) {
                            waitUntilReleased(down.id)
                            return@awaitEachGesture
                        }
                        val closedBackground = layout.isClosedBookBackground(bookState, down.position)
                        val central = readerMenuTapBounds(canvasSize).contains(down.position)
                        if (!layout.containsPage(down.position) && !central && !closedBackground) {
                            waitUntilReleased(down.id)
                            return@awaitEachGesture
                        }

                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var current: PointerInputChange = down
                        var dragging = false
                        var movedBeyondTapSlop = false
                        var direction: TurnDirection? = null

                        while (current.pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val displacement = change.position - down.position
                            if (displacement.getDistance() >= viewConfiguration.touchSlop) {
                                movedBeyondTapSlop = true
                            }
                            if (
                                !dragging &&
                                !closedBackground &&
                                abs(displacement.x) >= viewConfiguration.touchSlop &&
                                abs(displacement.x) >= abs(displacement.y) * 0.6f
                            ) {
                                direction = layout.dragDirection(
                                    down = down.position,
                                    horizontalDisplacement = displacement.x,
                                    canForward = canTurn(TurnDirection.FORWARD),
                                    canBackward = canTurn(TurnDirection.BACKWARD),
                                    viewport = canvasSize,
                                )
                                dragging = direction != null
                            }
                            if (dragging) {
                                dragSession = DragSession(
                                    direction = requireNotNull(direction),
                                    origin = CurlOrigin.OUTER_EDGE,
                                    rawStart = down.position,
                                    rawCurrent = change.position,
                                )
                                change.consume()
                            }
                            current = change
                        }

                        if (!dragging) {
                            if (!movedBeyondTapSlop && down.uptimeMillis + viewConfiguration.longPressTimeoutMillis >= current.uptimeMillis) {
                                val indices = visiblePageIndices(bookState, presentation, preparedPages.size)
                                val pageBounds = when {
                                    layout.leftPage?.contains(down.position) == true -> layout.leftPage
                                    layout.rightPage.contains(down.position) -> layout.rightPage
                                    else -> null
                                }
                                val anchor = navigator.layoutAnchor(bookState, presentation)
                                val pageIndex = when {
                                    pageBounds == null -> null
                                    presentation == BookPresentation.SINGLE -> indices.firstOrNull()
                                    anchor == 0 && pageBounds == layout.rightPage -> indices.firstOrNull()
                                    anchor == 0 -> null
                                    pageBounds == layout.leftPage -> indices.firstOrNull()
                                    else -> indices.getOrNull(1)
                                }
                                val tocLink = if (pageIndex != null && pageBounds != null && ready?.contents != null)
                                    ready.tocSnapshot?.contentsLinkAt(down.position.x - pageBounds.left,
                                        down.position.y - pageBounds.top, pageIndex - ready.annotationPageCount, ready.contents) else null
                                val noteReturn = noteReturns.firstOrNull { it.first.contains(down.position) }?.second
                                val noteLink = noteLinks.filter { it.first.contains(down.position) }
                                    .minByOrNull { (it.first.center - down.position).getDistance() }?.second
                                when {
                                    closedBackground -> shelfCallback?.invoke()
                                    bookState == PhysicalBookState.ClosedBack -> shelfCallback?.invoke()
                                    noteReturn != null -> returnFromNote(noteReturn.noteId)
                                    noteLink != null -> openNote(noteLink.noteId, noteLink.origin)
                                    tocLink != null && ready?.notes?.noteAt(tocLink) != null ->
                                        openNote(requireNotNull(ready.notes.noteAt(tocLink)))
                                    pageNumberBounds.any { it.contains(down.position) } && pageNumberCallback != null -> pageNumberCallback?.invoke()
                                    tocLink != null -> navigateCallback?.invoke(tocLink)
                                    central && centerCallback != null &&
                                        (bookState is PhysicalBookState.Open || bookState == PhysicalBookState.FrontBoundary) -> centerCallback?.invoke()
                                    else -> layout.tapDirection(
                                    position = down.position,
                                    canForward = canTurn(TurnDirection.FORWARD),
                                    canBackward = canTurn(TurnDirection.BACKWARD),
                                )?.let(::launchAutomaticTurn)
                                }
                            }
                        } else {
                            val session = dragSession ?: return@awaitEachGesture
                            val velocity = tracker.calculateVelocity()
                            val target = decideSettleTarget(
                                direction = session.direction,
                                pointerDisplacement = Point(
                                    x = (session.rawCurrent.x - session.rawStart.x).toDouble(),
                                    y = (session.rawCurrent.y - session.rawStart.y).toDouble(),
                                ),
                                velocityPxPerSecond = Point(velocity.x.toDouble(), velocity.y.toDouble()),
                                pageWidthPx = layout.pageWidth.toDouble(),
                                config = physicsConfig,
                            )
                            launchSettle(
                                direction = session.direction,
                                target = target,
                                startPointer = session.pointer(layout),
                                origin = session.origin,
                                edgeAnchorY = session.edgeAnchorY,
                                customAnchor = session.customAnchor,
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        dragSession = null
                        throw cancelled
                    } finally {
                        gestureActive = false
                    }
                }
            }
        } else {
            Modifier
        }

        val activeTurn = when {
            previewTurn != null && canTurn(previewTurn.direction) -> {
                val source = layout.sourcePage(previewTurn.direction)
                val anchorY = if (
                    previewTurn.origin == CurlOrigin.OUTER_EDGE ||
                    previewTurn.origin == CurlOrigin.FREE_POINT
                ) {
                    source.top + source.height * previewTurn.edgeAnchorFractionY
                } else {
                    source.bottom
                }
                val grab = Point(
                    x = if (previewTurn.origin == CurlOrigin.FREE_POINT) {
                        (source.left + source.width * previewTurn.freeAnchorFractionX).toDouble()
                    } else {
                        if (previewTurn.direction == TurnDirection.FORWARD) {
                            source.right.toDouble()
                        } else {
                            source.left.toDouble()
                        }
                    },
                    y = anchorY.toDouble(),
                )
                val endpoint = if (previewTurn.origin == CurlOrigin.FREE_POINT) {
                    layout.completionPointer(previewTurn.direction, grab)
                } else {
                    layout.completionPointer(previewTurn.direction, anchorY.toDouble())
                }
                val base = lerp(grab, endpoint, previewTurn.fraction)
                val requestedVerticalArc = source.height * previewTurn.verticalArcFractionY *
                    kotlin.math.sin(Math.PI * previewTurn.fraction).toFloat()
                // A pure 2D reflection can fling the long edge of a viewport-height sheet far
                // outside the screen. Keep only the small hand-induced tilt that reads as curl;
                // the strip deformation still supplies the visible depth.
                val verticalArc = requestedVerticalArc.coerceIn(
                    minimumValue = -source.width * 0.06f,
                    maximumValue = source.width * 0.06f,
                )
                ActiveTurn(
                    direction = previewTurn.direction,
                    pointer = base.copy(
                        y = base.y + verticalArc,
                    ),
                    origin = previewTurn.origin,
                    edgeAnchorY = anchorY.toDouble().takeIf {
                        previewTurn.origin == CurlOrigin.OUTER_EDGE
                    },
                    customAnchor = grab.takeIf { previewTurn.origin == CurlOrigin.FREE_POINT },
                )
            }

            dragSession != null -> dragSession?.let { session ->
                ActiveTurn(
                    direction = session.direction,
                    pointer = session.pointer(layout),
                    origin = session.origin,
                    edgeAnchorY = session.edgeAnchorY,
                    customAnchor = session.customAnchor,
                )
            }

            settleSession != null -> settleSession?.let { session ->
                ActiveTurn(
                    direction = session.direction,
                    pointer = lerp(session.startPointer, session.endPointer, settleFraction.value),
                    origin = session.origin,
                    edgeAnchorY = session.edgeAnchorY,
                    customAnchor = session.customAnchor,
                )
            }

            else -> null
        }

        val resolvedTurn = activeTurn?.let { turn ->
            val jump = settleSession?.jumpDestination
            resolveTurn(
                pages = preparedPages,
                targetPages = jump?.surfaces ?: preparedPages,
                coverSurface = coverSurface,
                state = bookState,
                transition = jump?.jump?.let {
                    PhysicalBookTransition(bookState, it.target, turn.direction, PhysicalTurnSubject.PAGE)
                } ?: transition(turn.direction),
                direction = turn.direction,
                pointer = turn.pointer,
                origin = turn.origin,
                edgeAnchorY = turn.edgeAnchorY,
                customAnchor = turn.customAnchor,
                presentation = presentation,
                layout = layout,
                geometryEngine = geometryEngine,
                navigator = navigator,
                insideFront = insideFront,
            )
        }
        val logicalProgress = when (bookState) {
            PhysicalBookState.ClosedFront, PhysicalBookState.FrontBoundary -> 0f
            PhysicalBookState.BackBoundary -> 1f
            PhysicalBookState.ClosedBack -> 1f
            is PhysicalBookState.Open -> {
                bookState.semanticPageIndex.toFloat() / preparedPages.lastIndex.coerceAtLeast(1)
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(semanticsModifier)
                .semantics {
                    val frame = resolvedTurn?.frame
                    this[ReaderTurnDirection] = activeTurn?.direction?.name?.lowercase().orEmpty()
                    this[ReaderTurnProgress] = resolvedTurn?.progress ?: 0f
                    this[ReaderTurnSubject] = when (val source = frame?.source) {
                        is BookVisualSurface.Page -> "page"
                        is BookVisualSurface.Cover -> if (source.face == ExternalBookCoverFace.FRONT || source.isInside) "front_cover" else "back_cover"
                        else -> ""
                    }
                    this[ReaderTurnSourceText] = when (val source = frame?.source) {
                        is BookVisualSurface.Page -> source.surface.paginated?.accessibleText.orEmpty()
                        is BookVisualSurface.Cover -> source.insideContent?.paginated?.accessibleText.orEmpty()
                        else -> ""
                    }
                }
                .then(gestureModifier)
                .testTag("book_view_demo"),
        ) {
                if (drawBackdrop) drawReaderBackdrop(darkTheme)
                val transitionProgress = transitionCoverProgress?.takeUnless { bookState.isClosedCover() }?.coerceIn(0f, 1f)
                if (transitionProgress == 0f) {
                    drawPhysicalBook(layout, VisibleBookSurfaces(null,
                        BookVisualSurface.Cover(coverSurface, ExternalBookCoverFace.FRONT)),
                        logicalProgress, PhysicalBookPose.CLOSED_FRONT, coverSurface = coverSurface)
                } else {
                    val entryFrame = transitionProgress?.takeIf { it < 1f }?.let { progress ->
                        val source = layout.rightPage
                        val grab = Point(source.right.toDouble(), source.bottom.toDouble())
                        val geometry = geometryEngine.calculate(
                            pageBounds = source.toCurlRect(),
                            pointer = lerp(grab, layout.completionPointer(TurnDirection.FORWARD), progress),
                            direction = TurnDirection.FORWARD,
                            destinationBounds = layout.leftPage?.toCurlRect(),
                        )
                        CurlFrame(TurnDirection.FORWARD, geometry,
                            BookVisualSurface.Cover(coverSurface, ExternalBookCoverFace.FRONT),
                            source, PageSide.RIGHT,
                            idleSurfaces.left ?: if (
                                presentation == BookPresentation.SPREAD && atInsideFront
                            ) {
                                BookVisualSurface.Cover(coverSurface, ExternalBookCoverFace.BACK,
                                    insideFront, isInside = true)
                            } else {
                                BookVisualSurface.Cover(coverSurface, ExternalBookCoverFace.BACK)
                            },
                            layout.leftPage ?: geometry.finalDestinationBounds.toComposeRect())
                    }
                    drawPhysicalBook(
                        layout = layout,
                        visibleSurfaces = resolvedTurn?.baseSurfaces ?: idleSurfaces,
                        logicalProgress = logicalProgress,
                        pose = if (entryFrame != null) PhysicalBookPose.OPEN else bookState.toPhysicalBookPose(),
                        curl = entryFrame ?: resolvedTurn?.frame,
                        coverSurface = coverSurface,
                    )
                }
                if (activeTurn == null && bookState is PhysicalBookState.Open && ready != null) {
                    val visible = visiblePageIndices(bookState, presentation, preparedPages.size)
                    visible.forEachIndexed { sideIndex, physicalIndex ->
                        val sourceIndex = physicalIndex - ready.sourcePageOffset
                        val page = ready.snapshot.pages.getOrNull(sourceIndex)
                        val pageBookmarks = if (page == null) emptyList() else bookmarks.filter { mark ->
                            mark.id != quickBookmarkPlacement?.bookmark?.id &&
                                (ready.snapshot.complete || mark.location.fallbackGlobalOffset < page.endExclusive.fallbackGlobalOffset) &&
                                ready.snapshot.pages.pageIndexContaining(mark.location) == sourceIndex
                        }.take(4)
                        if (pageBookmarks.isNotEmpty()) {
                            val bounds = if (presentation == BookPresentation.SPREAD && visible.size > 1 && sideIndex == 0)
                                layout.leftPage ?: layout.rightPage else layout.rightPage
                            val width = minOf(9.dp.toPx(), ready.snapshot.key.marginX * .34f)
                            val gap = 2.dp.toPx()
                            val onLeft = presentation == BookPresentation.SPREAD && visible.size > 1 && sideIndex == 0
                            val top = maxOf(bounds.top + 2.dp.toPx(), pageControlsLayout.contentTop - 14.dp.toPx())
                            val bottom = minOf(top + 23.dp.toPx(), bounds.bottom - 2.dp.toPx())
                            pageBookmarks.forEachIndexed { markerIndex, mark ->
                                val left = if (onLeft) bounds.left + gap + markerIndex * (width + gap)
                                    else bounds.right - gap - width - markerIndex * (width + gap)
                                val right = left + width
                                val ribbon = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(left, top); lineTo(right, top); lineTo(right, bottom)
                                    lineTo(left + width / 2, bottom - width / 2); lineTo(left, bottom); close()
                                }
                                drawPath(ribbon, bookmarkColor(mark, darkTheme))
                            }
                        }
                    }
                }
        }
        // No backing surface, container, divider or extra viewport inset: just ink on paper.
        if (openPaperBounds.isNotEmpty() && transitionCoverProgress == null &&
            resolvedTurn?.frame?.source !is BookVisualSurface.Cover) {
            pageControls?.invoke(pageControlsLayout)
        }
        if (quickBookmarkPlacement != null && transitionCoverProgress == null &&
            resolvedTurn?.frame?.source !is BookVisualSurface.Cover) {
            QuickBookmarkTab(
                placement = quickBookmarkPlacement,
                darkTheme = darkTheme,
                // Keep the strip steady like the other printed controls. The committed source
                // anchor stays visible until settling; do not let a tap bookmark it mid-turn.
                enabled = activeTurn == null && !physicalTurnActive && jumpPreparing == null && !closingBook,
                onAdd = { onAddBookmark?.invoke(it) },
                onRemove = { onDeleteBookmark?.invoke(it) },
            )
        }
        if (bookState.isClosedCover() && transitionCoverProgress == null && !closingBook &&
            resolvedTurn?.frame == null && !physicalTurnActive) {
            closedBookControls?.invoke(readyRawBounds,
                Rect(0f, 0f, canvasSize.width, canvasSize.height),
                presentation,
                bookState == PhysicalBookState.ClosedFront,
                { launchAutomaticTurn(TurnDirection.FORWARD) },
            )
        }
        if (presentation == BookPresentation.SPREAD && atInsideFront &&
            bookState is PhysicalBookState.Open && transitionCoverProgress == null &&
            !closingBook && resolvedTurn?.frame == null && !physicalTurnActive) {
            layout.leftPage?.let { bounds -> insideCoverControls?.invoke(bounds,
                Rect(0f, 0f, canvasSize.width, canvasSize.height), {
                    val description = ready?.annotationSnapshot?.pages?.firstOrNull()?.start
                    if (description != null && readingPosition != null) beginJump(ReaderJumpRequest(
                        --localJumpSequence, readingPosition.progress(totalCharacters).copy(
                            cover = CoverPosition.OPEN, annotationLocation = description,
                            titleLocation = null, tocEntryIndex = null, tocCharOffset = 0)))
                }) }
        }
    }
}

private fun resolveTurn(
    pages: List<DemoPageSurface?>,
    targetPages: List<DemoPageSurface?> = pages,
    coverSurface: ExternalBookCoverSurface,
    state: PhysicalBookState,
    transition: PhysicalBookTransition?,
    direction: TurnDirection,
    pointer: Point,
    origin: CurlOrigin,
    edgeAnchorY: Double?,
    customAnchor: Point?,
    presentation: BookPresentation,
    layout: BookCanvasLayout,
    geometryEngine: PageCurlGeometryEngine,
    navigator: PhysicalBookNavigator,
    insideFront: DemoPageSurface? = null,
): ResolvedTurn? {
    val completedTransition = transition ?: return null
    val current = visibleBookSurfaces(
        pages = pages,
        coverSurface = coverSurface,
        state = state,
        presentation = presentation,
        navigator = navigator,
        insideFront = insideFront,
    )
    val target = visibleBookSurfaces(
        pages = targetPages,
        coverSurface = coverSurface,
        state = completedTransition.to,
        presentation = presentation,
        navigator = if (targetPages.size == pages.size) navigator else PhysicalBookNavigator(targetPages.size),
        insideFront = insideFront,
    )
    val sourceBounds = layout.sourcePage(direction)

    return when (presentation) {
        BookPresentation.SINGLE -> {
            val source = current.right ?: return null
            val outgoingGeometry = geometryEngine.calculate(
                pageBounds = sourceBounds.toCurlRect(),
                pointer = pointer,
                direction = direction,
                origin = origin,
                edgeAnchorY = edgeAnchorY,
                customAnchor = customAnchor,
                destinationBounds = layout.destinationPage(direction)?.toCurlRect(),
            )
            if (
                (state is PhysicalBookState.Open || state == PhysicalBookState.BackBoundary || state == PhysicalBookState.FrontBoundary) &&
                completedTransition.to.isClosedCover()
            ) {
                val incomingCover = target.right ?: return null
                val incomingDirection = direction.opposite()
                val incomingTemplate = geometryEngine.calculate(
                    pageBounds = sourceBounds.toCurlRect(),
                    pointer = Point(sourceBounds.center.x.toDouble(), sourceBounds.center.y.toDouble()),
                    direction = incomingDirection,
                    origin = origin,
                    edgeAnchorY = edgeAnchorY,
                    customAnchor = customAnchor,
                )
                val incomingPointer = lerp(
                    start = incomingTemplate.completionPointer,
                    end = incomingTemplate.grabPoint,
                    fraction = outgoingGeometry.progress.toFloat(),
                )
                val incomingGeometry = geometryEngine.calculate(
                    pageBounds = sourceBounds.toCurlRect(),
                    pointer = incomingPointer,
                    direction = incomingDirection,
                    origin = origin,
                    edgeAnchorY = edgeAnchorY,
                    customAnchor = customAnchor,
                )
                return ResolvedTurn(
                    // The page stays put while the actual hard cover swings in over it. This is
                    // the exact reverse of opening that cover from a closed state.
                    baseSurfaces = current,
                    frame = CurlFrame(
                        direction = incomingDirection,
                        geometry = incomingGeometry,
                        source = incomingCover,
                        sourceBounds = sourceBounds,
                        sourceSide = if (completedTransition.to == PhysicalBookState.ClosedBack) {
                            PageSide.LEFT
                        } else {
                            PageSide.RIGHT
                        },
                        back = BookVisualSurface.Cover(coverSurface,
                            if (completedTransition.to == PhysicalBookState.FrontBoundary) ExternalBookCoverFace.FRONT
                            else ExternalBookCoverFace.BACK),
                        backDestinationBounds = incomingGeometry.finalDestinationBounds.toComposeRect(),
                    ),
                )
            }
            if (direction == TurnDirection.BACKWARD && completedTransition.subject == PhysicalTurnSubject.PAGE) {
                val returningPage = target.right ?: return null
                val incomingGeometry = geometryEngine.calculateReturningPage(
                    pageBounds = sourceBounds.toCurlRect(),
                    pointer = pointer,
                    origin = origin,
                    edgeAnchorY = edgeAnchorY,
                    customAnchor = customAnchor,
                )
                return ResolvedTurn(
                    // Rewind the previous forward turn. The current page stays underneath while
                    // the previous leaf returns from the left binding, instead of peeling the
                    // current page off the right edge. Both faces were prepared before the drag.
                    baseSurfaces = current,
                    frame = CurlFrame(
                        direction = TurnDirection.FORWARD,
                        geometry = incomingGeometry,
                        source = returningPage,
                        sourceBounds = sourceBounds,
                        sourceSide = PageSide.SINGLE,
                        back = current.right,
                        backDestinationBounds = incomingGeometry.finalDestinationBounds.toComposeRect(),
                    ),
                    progress = (1.0 - incomingGeometry.progress).toFloat(),
                )
            }
            ResolvedTurn(
                baseSurfaces = target,
                frame = CurlFrame(
                    direction = direction,
                    geometry = outgoingGeometry,
                    source = source,
                    sourceBounds = sourceBounds,
                    sourceSide = if (state == PhysicalBookState.ClosedBack) {
                        PageSide.LEFT
                    } else {
                        PageSide.SINGLE
                    },
                    // The destination is already visible underneath. Painting it on the moving
                    // back as well keeps text/cover material attached to the sheet from the first
                    // frame, instead of popping in after the settle animation.
                    back = singleBackSurface(
                        transition = completedTransition,
                        target = target,
                        coverSurface = coverSurface,
                    ),
                    backDestinationBounds = outgoingGeometry.finalDestinationBounds.toComposeRect(),
                ),
            )
        }

        BookPresentation.SPREAD -> when (direction) {
            TurnDirection.FORWARD -> {
                val source = current.right ?: return null
                ResolvedTurn(
                    baseSurfaces = VisibleBookSurfaces(left = current.left, right = target.right),
                    frame = CurlFrame(
                        direction = direction,
                        geometry = geometryEngine.calculate(
                            pageBounds = sourceBounds.toCurlRect(),
                            pointer = pointer,
                            direction = direction,
                            origin = origin,
                            edgeAnchorY = edgeAnchorY,
                            customAnchor = customAnchor,
                            destinationBounds = layout.destinationPage(direction)?.toCurlRect(),
                        ),
                        source = source,
                        sourceBounds = sourceBounds,
                        sourceSide = PageSide.RIGHT,
                        back = target.left,
                        backDestinationBounds = layout.leftPage,
                    ),
                )
            }

            TurnDirection.BACKWARD -> {
                val source = current.left ?: return null
                ResolvedTurn(
                    baseSurfaces = VisibleBookSurfaces(left = target.left, right = current.right),
                    frame = CurlFrame(
                        direction = direction,
                        geometry = geometryEngine.calculate(
                            pageBounds = sourceBounds.toCurlRect(),
                            pointer = pointer,
                            direction = direction,
                            origin = origin,
                            edgeAnchorY = edgeAnchorY,
                            customAnchor = customAnchor,
                            destinationBounds = layout.destinationPage(direction)?.toCurlRect(),
                        ),
                        source = source,
                        sourceBounds = sourceBounds,
                        sourceSide = PageSide.LEFT,
                        back = target.right,
                        backDestinationBounds = layout.rightPage,
                    ),
                )
            }
        }
    }
}

private fun visibleBookSurfaces(
    pages: List<DemoPageSurface?>,
    coverSurface: ExternalBookCoverSurface,
    state: PhysicalBookState,
    presentation: BookPresentation,
    navigator: PhysicalBookNavigator,
    insideFront: DemoPageSurface? = null,
): VisibleBookSurfaces {
    val frontCover = BookVisualSurface.Cover(coverSurface, ExternalBookCoverFace.FRONT)
    val blankCover = BookVisualSurface.Cover(coverSurface, ExternalBookCoverFace.BACK)
    val insideFrontCover = BookVisualSurface.Cover(coverSurface, ExternalBookCoverFace.BACK, insideFront, isInside = true)
    return when (state) {
        PhysicalBookState.ClosedFront -> VisibleBookSurfaces(left = null, right = frontCover)
        PhysicalBookState.FrontBoundary -> VisibleBookSurfaces(left = null,
            right = if (presentation == BookPresentation.SINGLE) {
                insideFront?.let(BookVisualSurface::Page) ?: BookVisualSurface.PlainPaper
            } else insideFrontCover)
        PhysicalBookState.BackBoundary -> if (presentation == BookPresentation.SPREAD) {
            VisibleBookSurfaces(
                left = if (coverSurface.darkTheme) BookVisualSurface.DarkPlainPaper else BookVisualSurface.PlainPaper,
                right = blankCover,
            )
        } else {
            VisibleBookSurfaces(
                left = null,
                right = pages.lastOrNull()?.let(BookVisualSurface::Page),
            )
        }

        PhysicalBookState.ClosedBack -> if (presentation == BookPresentation.SPREAD) {
            VisibleBookSurfaces(left = blankCover, right = null)
        } else {
            VisibleBookSurfaces(left = null, right = blankCover)
        }

        is PhysicalBookState.Open -> {
            val anchor = requireNotNull(navigator.layoutAnchor(state, presentation))
            if (presentation == BookPresentation.SINGLE) {
                VisibleBookSurfaces(
                    left = null,
                    right = pages.getOrNull(anchor)?.let(BookVisualSurface::Page),
                )
            } else if (anchor == 0) {
                // The first printed page sits opposite the inside of the front cover. It is a
                // physical boundary, not an invented blank sheet.
                VisibleBookSurfaces(
                    left = insideFrontCover,
                    right = pages.firstOrNull()?.let(BookVisualSurface::Page),
                )
            } else {
                VisibleBookSurfaces(
                    left = pages.getOrNull(anchor)?.let(BookVisualSurface::Page),
                    // Likewise, an odd final leaf ends against the inside of the back cover.
                    right = pages.getOrNull(anchor + 1)?.let(BookVisualSurface::Page) ?: blankCover,
                )
            }
        }
    }
}

private fun singleBackSurface(
    transition: PhysicalBookTransition,
    target: VisibleBookSurfaces,
    coverSurface: ExternalBookCoverSurface,
): BookVisualSurface? = when {
    transition.from == PhysicalBookState.ClosedFront -> {
        // The rigid board keeps its own unprinted inner face while the separate annotation
        // leaf is already lying underneath it as the transition destination.
        BookVisualSurface.Cover(coverSurface, ExternalBookCoverFace.BACK)
    }

    transition.from == PhysicalBookState.ClosedBack -> {
        BookVisualSurface.Cover(coverSurface, ExternalBookCoverFace.BACK)
    }

    else -> target.right
}

private fun PhysicalBookState.isClosedCover(): Boolean =
    this == PhysicalBookState.ClosedFront || this == PhysicalBookState.ClosedBack

private fun BookVisualSurface?.isReadablePaperSurface(): Boolean =
    this is BookVisualSurface.Page || this == BookVisualSurface.PlainPaper ||
        this == BookVisualSurface.DarkPlainPaper || (this as? BookVisualSurface.Cover)?.insideContent != null

private fun BookVisualSurface?.isWhitePaperSurface(): Boolean =
    this == BookVisualSurface.PlainPaper ||
        this is BookVisualSurface.Page && this.surface.paginated?.darkTheme != true

private fun TurnDirection.opposite(): TurnDirection = when (this) {
    TurnDirection.FORWARD -> TurnDirection.BACKWARD
    TurnDirection.BACKWARD -> TurnDirection.FORWARD
}

private fun PhysicalBookState.normalized(pageCount: Int): PhysicalBookState = when (this) {
    PhysicalBookState.ClosedFront -> this
    PhysicalBookState.FrontBoundary -> this
    PhysicalBookState.BackBoundary -> this
    PhysicalBookState.ClosedBack -> this
    is PhysicalBookState.Open -> copy(semanticPageIndex = semanticPageIndex.coerceIn(0, pageCount - 1))
}

private fun PhysicalBookState.toSavedPosition(pageCount: Int): Int = when (this) {
    PhysicalBookState.ClosedFront -> -1
    PhysicalBookState.FrontBoundary -> -4
    PhysicalBookState.BackBoundary -> -2
    PhysicalBookState.ClosedBack -> -3
    is PhysicalBookState.Open -> semanticPageIndex.coerceIn(0, pageCount - 1)
}

private fun Int.toPhysicalBookState(pageCount: Int): PhysicalBookState = when {
    this == -1 -> PhysicalBookState.ClosedFront
    this == -2 -> PhysicalBookState.BackBoundary
    this == -3 -> PhysicalBookState.ClosedBack
    this == -4 -> PhysicalBookState.FrontBoundary
    else -> PhysicalBookState.Open(semanticPageIndex = coerceIn(0, pageCount - 1))
}

private fun PhysicalBookState.toPhysicalBookPose(): PhysicalBookPose = when (this) {
    PhysicalBookState.ClosedFront -> PhysicalBookPose.CLOSED_FRONT
    PhysicalBookState.FrontBoundary -> PhysicalBookPose.OPEN
    PhysicalBookState.BackBoundary -> PhysicalBookPose.OPEN
    PhysicalBookState.ClosedBack -> PhysicalBookPose.CLOSED_BACK
    is PhysicalBookState.Open -> PhysicalBookPose.OPEN
}

private fun com.avstaim.listai.reader.rendering.geometry.Rect.toComposeRect(): Rect = Rect(
    left = left.toFloat(),
    top = top.toFloat(),
    right = right.toFloat(),
    bottom = bottom.toFloat(),
)

private val DragSession.edgeAnchorY: Double?
    get() = rawStart.y.toDouble().takeIf { origin == CurlOrigin.OUTER_EDGE }

private val DragSession.customAnchor: Point?
    get() = rawStart.toPoint().takeIf { origin == CurlOrigin.FREE_POINT }

private fun DragSession.pointer(layout: BookCanvasLayout): Point {
    val source = layout.sourcePage(direction)
    if (origin == CurlOrigin.FREE_POINT) {
        val anchor = rawStart.toPoint()
        val completion = layout.completionPointer(direction, anchor)
        return Point(
            x = rawCurrent.x.toDouble().coerceIn(
                minimumValue = minOf(anchor.x, completion.x),
                maximumValue = maxOf(anchor.x, completion.x),
            ),
            y = rawCurrent.y.toDouble(),
        )
    }
    val grabX = if (direction == TurnDirection.FORWARD) source.right else source.left
    return Point(
        x = (grabX + rawCurrent.x - rawStart.x).toDouble(),
        y = if (origin == CurlOrigin.OUTER_EDGE) {
            val maxVerticalDelta = source.width * 0.14f
            (rawStart.y + (rawCurrent.y - rawStart.y).coerceIn(
                minimumValue = -maxVerticalDelta,
                maximumValue = maxVerticalDelta,
            )).toDouble()
        } else {
            (source.bottom + (rawCurrent.y - rawStart.y) * 0.72f).toDouble()
        },
    )
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitUntilReleased(
    pointerId: PointerId,
) {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId } ?: return
        if (!change.pressed) return
    }
}
