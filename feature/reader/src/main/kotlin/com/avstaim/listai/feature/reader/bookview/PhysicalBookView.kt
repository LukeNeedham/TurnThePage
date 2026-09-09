package com.avstaim.listai.feature.reader.bookview

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.adaptive.DevicePosture
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.PageMode
import com.avstaim.listai.feature.reader.R
import com.avstaim.listai.feature.reader.ReaderSemanticLocation
import com.avstaim.listai.feature.reader.ReaderPaginationComplete
import com.avstaim.listai.feature.reader.ReaderLayoutIdentity
import com.avstaim.listai.feature.reader.demo.DemoPageUiModel
import com.avstaim.listai.reader.rendering.geometry.PageCurlGeometryEngine
import com.avstaim.listai.reader.rendering.geometry.Point
import com.avstaim.listai.reader.rendering.turn.CurlOrigin
import com.avstaim.listai.reader.rendering.turn.PageTurnPhysicsConfig
import com.avstaim.listai.reader.rendering.turn.SettleTarget
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import com.avstaim.listai.reader.rendering.turn.decideSettleTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
) {
    require(pages.isNotEmpty() || document != null) { "BookView requires pages or a document" }

    var viewportBoundsInWindow by remember { mutableStateOf<Rect?>(null) }
    val readingPosition = document?.let { rememberReaderPosition(it, initialLocation) }
    var dragSession by remember { mutableStateOf<DragSession?>(null) }
    var settleSession by remember { mutableStateOf<SettleSession?>(null) }
    var gestureActive by remember { mutableStateOf(false) }
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
        val pagination = document?.let {
            rememberPaginationSession(it, LayoutKey(
                viewportWidthPx = canvasSize.width.roundToInt().coerceAtLeast(1),
                viewportHeightPx = canvasSize.height.roundToInt().coerceAtLeast(1),
                pageWidthPx = layout.pageWidth.roundToInt().coerceAtLeast(1),
                pageHeightPx = layout.pageHeight.roundToInt().coerceAtLeast(1),
                pageMode = if (presentation == BookPresentation.SPREAD) PageMode.SPREAD else PageMode.SINGLE,
                density = density.density,
                densityKey = density.density.toString(),
                textScale = density.fontScale,
                localeTag = it.metadata.language ?: "en",
            ), requireNotNull(readingPosition))
        }
        // Read during composition even while a displayed snapshot is pinned. Reading only in
        // SideEffect would not subscribe this host to subsequent background publications.
        val latestPagination = pagination?.ready
        var displayedPagination by remember(pagination) { mutableStateOf<PreparedPagination?>(null) }
        // Do not replace navigation/surfaces while a finger or settle animation owns a leaf.
        SideEffect {
            if (!gestureActive && dragSession == null && settleSession == null) displayedPagination = latestPagination
        }
        val ready = displayedPagination ?: latestPagination
        if (pagination != null && ready == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(if (pagination.failed) R.string.reader_pagination_failed else R.string.reader_paginating))
            }
            // Resize can dispose the previous gesture before its settle job clears these fields.
            LaunchedEffect(pagination) { dragSession = null; settleSession = null; gestureActive = false }
            return@BoxWithConstraints
        }
        val preparedPages = ready?.surfaces ?: rememberDemoPageSurfaces(
            pages = pages,
            pageSize = IntSize(
                width = layout.pageWidth.roundToInt().coerceAtLeast(1),
                height = layout.pageHeight.roundToInt().coerceAtLeast(1),
            ),
        )
        val coverSurface = rememberExternalBookCoverSurface(
            title = bookTitle,
            author = bookAuthor,
            coverSize = IntSize(
                width = layout.pageWidth.roundToInt().coerceAtLeast(1),
                height = layout.pageHeight.roundToInt().coerceAtLeast(1),
            ),
        )
        val navigator = remember(preparedPages.size) {
            PhysicalBookNavigator(pageCount = preparedPages.size)
        }
        val resolvedInitialState = remember(
            pagination ?: preparedPages.size,
            initialPageIndex,
            initialBookState,
        ) {
            ready?.let { requireNotNull(readingPosition).initialState(it.snapshot) } ?: initialBookState
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
        val settleFraction = remember { Animatable(0f) }
        val geometryEngine = remember { PageCurlGeometryEngine() }
        val physicsConfig = remember { PageTurnPhysicsConfig() }
        val scope = rememberCoroutineScope()
        var settleJob by remember { mutableStateOf<Job?>(null) }
        LaunchedEffect(layout) {
            settleJob?.cancel()
            dragSession = null
            settleSession = null
            settleFraction.snapTo(0f)
        }

        fun transition(direction: TurnDirection): PhysicalBookTransition? =
            navigator.transition(bookState, direction, presentation)?.takeIf { transition ->
                if (ready == null || ready.snapshot.complete || direction == TurnDirection.BACKWARD) true
                else (transition.to as? PhysicalBookState.Open)?.let { target ->
                    val anchor = navigator.layoutAnchor(target, presentation) ?: 0
                    anchor + (if (presentation == BookPresentation.SPREAD && anchor > 0) 1 else 0) < preparedPages.size
                } == true
            }

        fun canTurn(direction: TurnDirection): Boolean = transition(direction) != null

        fun launchSettle(
            direction: TurnDirection,
            target: SettleTarget,
            startPointer: Point,
            origin: CurlOrigin = CurlOrigin.LOWER_OUTER,
            edgeAnchorY: Double? = null,
            customAnchor: Point? = null,
        ) {
            if (!canTurn(direction)) return
            settleJob?.cancel()
            val completedState = transition(direction)?.to ?: return
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
            settleSession = SettleSession(
                direction = direction,
                target = target,
                startPointer = startPointer,
                endPointer = endPointer,
                completedState = completedState,
                origin = origin,
                edgeAnchorY = edgeAnchorY,
                customAnchor = resolvedCustomAnchor,
            )
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
                if (finished?.target == SettleTarget.COMPLETE) {
                    savedBookPosition = finished.completedState.toSavedPosition(preparedPages.size)
                    ready?.let { readingPosition?.commit(finished.completedState, it.snapshot) }
                }
                settleSession = null
                settleFraction.snapTo(0f)
            }
        }

        fun launchAutomaticTurn(direction: TurnDirection): Boolean {
            if (previewTurn != null || dragSession != null || settleSession != null || !canTurn(direction)) {
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

        val openPageState = (bookState as? PhysicalBookState.Open)?.let { state ->
            stringResource(
                if (ready != null && !ready.snapshot.complete) R.string.reader_page_progress_pending else R.string.reader_page_progress,
                state.semanticPageIndex.coerceIn(0, preparedPages.lastIndex) + 1,
                preparedPages.size,
            )
        }
        val frontCoverState = stringResource(R.string.reader_front_cover_state)
        val backCoverState = stringResource(R.string.reader_back_cover_state)
        val backBoundaryState = stringResource(R.string.reader_back_boundary_state)
        val stateText = when (bookState) {
            PhysicalBookState.ClosedFront -> frontCoverState
            PhysicalBookState.BackBoundary -> backBoundaryState
            PhysicalBookState.ClosedBack -> backCoverState
            is PhysicalBookState.Open -> requireNotNull(openPageState)
        }
        val bookDescription = stringResource(R.string.reader_book_accessibility)
        val forwardAction = stringResource(R.string.reader_turn_forward_action)
        val backwardAction = stringResource(R.string.reader_turn_backward_action)
        val semanticsModifier = Modifier.semantics {
            contentDescription = bookDescription
            stateDescription = stateText
            ready?.let {
                val anchor = readingPosition?.location ?: it.snapshot.pages.first().start
                this[ReaderSemanticLocation] = "${anchor.blockId.value}:${anchor.charOffset}:${anchor.fallbackGlobalOffset}"
                this[ReaderPaginationComplete] = it.snapshot.complete
                this[ReaderLayoutIdentity] = it.snapshot.key.toString()
            }
            // A tap on the outside of the back cover is deliberately reserved for the future
            // "return to shelf" action. The book can still be reopened with a backward swipe or
            // the explicit accessibility action.
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

        val gestureModifier = if (previewTurn == null) {
            Modifier.pointerInput(layout, savedBookPosition, preparedPages.size, presentation) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    gestureActive = true
                    try {
                        if (dragSession != null || settleSession != null) {
                            waitUntilReleased(down.id)
                            return@awaitEachGesture
                        }
                        if (!layout.containsPage(down.position)) {
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
                                abs(displacement.x) >= viewConfiguration.touchSlop &&
                                abs(displacement.x) >= abs(displacement.y) * 0.6f
                            ) {
                                direction = layout.dragDirection(
                                    down = down.position,
                                    horizontalDisplacement = displacement.x,
                                    canForward = canTurn(TurnDirection.FORWARD),
                                    canBackward = canTurn(TurnDirection.BACKWARD),
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
                            if (!movedBeyondTapSlop && bookState != PhysicalBookState.ClosedBack) {
                                layout.tapDirection(
                                    position = down.position,
                                    canForward = canTurn(TurnDirection.FORWARD),
                                    canBackward = canTurn(TurnDirection.BACKWARD),
                                )?.let(::launchAutomaticTurn)
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
            resolveTurn(
                pages = preparedPages,
                coverSurface = coverSurface,
                state = bookState,
                transition = transition(turn.direction),
                direction = turn.direction,
                pointer = turn.pointer,
                origin = turn.origin,
                edgeAnchorY = turn.edgeAnchorY,
                customAnchor = turn.customAnchor,
                presentation = presentation,
                layout = layout,
                geometryEngine = geometryEngine,
                navigator = navigator,
            )
        }
        val idleSurfaces = visibleBookSurfaces(
            pages = preparedPages,
            coverSurface = coverSurface,
            state = bookState,
            presentation = presentation,
            navigator = navigator,
        )
        val logicalProgress = when (bookState) {
            PhysicalBookState.ClosedFront -> 0f
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
                .then(gestureModifier)
                .testTag("book_view_demo"),
        ) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF655440), Color(0xFF3C3025)),
                    center = Offset(size.width * 0.48f, size.height * 0.42f),
                    radius = size.maxDimension * 0.78f,
                ),
            )
            drawPhysicalBook(
                layout = layout,
                visibleSurfaces = resolvedTurn?.baseSurfaces ?: idleSurfaces,
                logicalProgress = logicalProgress,
                pose = bookState.toPhysicalBookPose(),
                curl = resolvedTurn?.frame,
            )
        }
    }
}

private fun resolveTurn(
    pages: List<DemoPageSurface>,
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
): ResolvedTurn? {
    val completedTransition = transition ?: return null
    val current = visibleBookSurfaces(
        pages = pages,
        coverSurface = coverSurface,
        state = state,
        presentation = presentation,
        navigator = navigator,
    )
    val target = visibleBookSurfaces(
        pages = pages,
        coverSurface = coverSurface,
        state = completedTransition.to,
        presentation = presentation,
        navigator = navigator,
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
                (state is PhysicalBookState.Open || state == PhysicalBookState.BackBoundary) &&
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
                        back = BookVisualSurface.Cover(
                            coverSurface,
                            ExternalBookCoverFace.BACK,
                        ),
                        backDestinationBounds = incomingGeometry.finalDestinationBounds.toComposeRect(),
                    ),
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
    pages: List<DemoPageSurface>,
    coverSurface: ExternalBookCoverSurface,
    state: PhysicalBookState,
    presentation: BookPresentation,
    navigator: PhysicalBookNavigator,
): VisibleBookSurfaces {
    val frontCover = BookVisualSurface.Cover(coverSurface, ExternalBookCoverFace.FRONT)
    val blankCover = BookVisualSurface.Cover(coverSurface, ExternalBookCoverFace.BACK)
    return when (state) {
        PhysicalBookState.ClosedFront -> VisibleBookSurfaces(left = null, right = frontCover)
        PhysicalBookState.BackBoundary -> if (presentation == BookPresentation.SPREAD) {
            VisibleBookSurfaces(
                left = BookVisualSurface.PlainPaper,
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
                    left = blankCover,
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
        BookVisualSurface.Cover(coverSurface, ExternalBookCoverFace.BACK)
    }

    transition.from == PhysicalBookState.ClosedBack -> {
        BookVisualSurface.Cover(coverSurface, ExternalBookCoverFace.BACK)
    }

    else -> target.right
}

private fun PhysicalBookState.isClosedCover(): Boolean =
    this == PhysicalBookState.ClosedFront || this == PhysicalBookState.ClosedBack

private fun TurnDirection.opposite(): TurnDirection = when (this) {
    TurnDirection.FORWARD -> TurnDirection.BACKWARD
    TurnDirection.BACKWARD -> TurnDirection.FORWARD
}

private fun PhysicalBookState.normalized(pageCount: Int): PhysicalBookState = when (this) {
    PhysicalBookState.ClosedFront -> this
    PhysicalBookState.BackBoundary -> this
    PhysicalBookState.ClosedBack -> this
    is PhysicalBookState.Open -> copy(semanticPageIndex = semanticPageIndex.coerceIn(0, pageCount - 1))
}

private fun PhysicalBookState.toSavedPosition(pageCount: Int): Int = when (this) {
    PhysicalBookState.ClosedFront -> -1
    PhysicalBookState.BackBoundary -> -2
    PhysicalBookState.ClosedBack -> -3
    is PhysicalBookState.Open -> semanticPageIndex.coerceIn(0, pageCount - 1)
}

private fun Int.toPhysicalBookState(pageCount: Int): PhysicalBookState = when {
    this == -1 -> PhysicalBookState.ClosedFront
    this == -2 -> PhysicalBookState.BackBoundary
    this == -3 -> PhysicalBookState.ClosedBack
    else -> PhysicalBookState.Open(semanticPageIndex = coerceIn(0, pageCount - 1))
}

private fun PhysicalBookState.toPhysicalBookPose(): PhysicalBookPose = when (this) {
    PhysicalBookState.ClosedFront -> PhysicalBookPose.CLOSED_FRONT
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

private fun BookCanvasLayout.containsPage(position: Offset): Boolean =
    rightPage.contains(position) || leftPage?.contains(position) == true

private fun BookCanvasLayout.dragDirection(
    down: Offset,
    horizontalDisplacement: Float,
    canForward: Boolean,
    canBackward: Boolean,
): TurnDirection? {
    if (presentation == BookPresentation.SPREAD) {
        return when {
            rightPage.contains(down) && horizontalDisplacement < 0f && canForward -> {
                TurnDirection.FORWARD
            }
            leftPage?.contains(down) == true && horizontalDisplacement > 0f && canBackward -> {
                TurnDirection.BACKWARD
            }
            else -> null
        }
    }

    return when {
        horizontalDisplacement < 0f && canForward -> TurnDirection.FORWARD
        horizontalDisplacement > 0f && canBackward -> TurnDirection.BACKWARD
        else -> null
    }
}

private fun BookCanvasLayout.tapDirection(
    position: Offset,
    canForward: Boolean,
    canBackward: Boolean,
): TurnDirection? = if (presentation == BookPresentation.SPREAD) {
    when {
        rightPage.contains(position) && canForward -> TurnDirection.FORWARD
        leftPage?.contains(position) == true && canBackward -> TurnDirection.BACKWARD
        else -> null
    }
} else {
    when {
        position.x >= rightPage.center.x && canForward -> TurnDirection.FORWARD
        position.x < rightPage.center.x && canBackward -> TurnDirection.BACKWARD
        else -> null
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitUntilReleased(
    pointerId: PointerId,
) {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId } ?: return
        if (!change.pressed) return
    }
}
