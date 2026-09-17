package com.avstaim.listai.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import androidx.core.view.ViewCompat
import com.avstaim.listai.core.adaptive.DevicePosture
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.bookview.*
import com.avstaim.listai.feature.reader.demo.FakeBookDocumentFactory
import com.avstaim.listai.feature.reader.demo.toDemoPages

enum class ReaderPresentationMode { AUTOMATIC, SINGLE, SPREAD }

/** Deterministic renderer fixture, retained for geometry regressions. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ReaderDemoScreen(
    modifier: Modifier = Modifier,
    devicePosture: DevicePosture = DevicePosture.Unknown,
    presentationMode: ReaderPresentationMode = ReaderPresentationMode.AUTOMATIC,
    initialPageIndex: Int = 0,
    isFullscreen: Boolean = true,
    onFullscreenChange: ((Boolean) -> Unit)? = null,
) {
    val document = remember { FakeBookDocumentFactory.create() }
    PhysicalBookView(document.toDemoPages(), document.metadata.title,
        document.metadata.authors.joinToString(), modifier.fillMaxSize(),
        forcedPresentation = presentationMode.physical(), initialPageIndex = initialPageIndex,
        devicePosture = devicePosture, outerPadding = 4.dp, coverInset = 3.dp)
}

/** Application controls are a loose sheet inserted into the same physical book. */
@Composable
fun ReaderScreen(
    document: BookDocument,
    modifier: Modifier = Modifier,
    devicePosture: DevicePosture = DevicePosture.Unknown,
    presentationMode: ReaderPresentationMode = ReaderPresentationMode.AUTOMATIC,
    initialProgress: ReadingProgress = ReadingProgress(),
    onProgressChanged: ((ReadingProgress) -> Unit)? = null,
    preferences: ReaderPreferences = ReaderPreferences(),
    darkTheme: Boolean = false,
    onPreferencesChange: (ReaderPreferences) -> Unit = {},
    bookmarks: List<Bookmark> = emptyList(),
    onAddBookmark: () -> Unit = {},
    onDeleteBookmark: (Bookmark) -> Unit = {},
    onReturnToShelf: () -> Unit = {},
    transitionCoverProgress: Float? = null,
    closingBook: Boolean = false,
    onBookReady: (Rect) -> Unit = {},
    onBookCoverChanged: (CoverPosition) -> Unit = {},
    onBookArtworkReady: (ImageBitmap?) -> Unit = {},
    readerSession: Long = 0L,
    onboardingCompleted: Boolean? = true,
    onOnboardingComplete: () -> Unit = {},
    onboardingReplayRequest: Long = 0L,
) {
    var insertOpen by rememberSaveable(readerSession) { mutableStateOf(false) }
    var insertSection by rememberSaveable(readerSession) { mutableStateOf(PaperInsertSection.MENU) }
    var checkpoint by remember(document, readerSession) { mutableStateOf(initialProgress) }
    var navigationSequence by remember(document, readerSession) { mutableLongStateOf(0L) }
    var navigationRequest by remember(document, readerSession) { mutableStateOf<ReaderJumpRequest?>(null) }
    var navigation by remember(document, readerSession) { mutableStateOf<ReaderNavigationState?>(null) }
    val progressCallback by rememberUpdatedState(onProgressChanged)
    var localReplay by rememberSaveable { mutableLongStateOf(0L) }
    var handledExternalReplay by rememberSaveable(document.id.value) { mutableLongStateOf(0L) }
    var dispatchedExternalReplay by remember(document, readerSession) { mutableLongStateOf(0L) }
    var hintsOpening by remember(document, readerSession) { mutableStateOf(false) }
    var turnActive by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var rawBookBounds by remember { mutableStateOf<Rect?>(null) }
    val closed = checkpoint.cover == CoverPosition.FRONT || checkpoint.cover == CoverPosition.BACK
    var bookAtReadingScale by remember { mutableStateOf(!closed) }
    val atReadingPage = checkpoint.cover == CoverPosition.OPEN &&
        checkpoint.annotationLocation == null && checkpoint.tocEntryIndex == null
    val externalOpeningPending = onboardingReplayRequest > 0L &&
        handledExternalReplay != onboardingReplayRequest && !atReadingPage
    val onboarding = rememberReaderOnboardingState(onboardingCompleted,
        readerReady = transitionCoverProgress == null && !closingBook && !insertOpen &&
            !externalOpeningPending && !hintsOpening && checkpoint.cover == CoverPosition.OPEN &&
            bookAtReadingScale && navigation != null,
        replayRequest = onboardingReplayRequest + localReplay, onComplete = onOnboardingComplete)
    // Let the central tap visibly reveal the real card before continuing the gesture guide.
    LaunchedEffect(insertOpen, onboarding.step) {
        if (insertOpen && onboarding.step == ReaderOnboardingStep.DRAG_FORWARD) {
            delay(1100)
            insertOpen = false
        }
    }

    fun navigate(progress: ReadingProgress) {
        navigationRequest = ReaderJumpRequest(++navigationSequence, progress)
        insertOpen = false
    }
    fun goTo(location: BookLocation) {
        val length = document.readingLength().coerceAtLeast(1)
        navigate(ReadingProgress(location, CoverPosition.OPEN,
            if (location.fallbackGlobalOffset >= length - 1) 1f else
                (location.fallbackGlobalOffset.toDouble() / length).toFloat().coerceIn(0f, 1f)))
    }
    fun openReadingPageForHints() {
        val current = navigation?.current ?: return
        if (!atReadingPage) {
            hintsOpening = true
            navigate(checkpoint.copy(location = checkpoint.location ?: current.location,
                cover = CoverPosition.OPEN, annotationLocation = null, tocEntryIndex = null, tocCharOffset = 0))
        }
    }
    // A replay requested on the shelf may restore either cover or the inside-cover annotation.
    // Wait for the shelf flight, then use the normal one-shot jump to the retained source anchor.
    // Save the receipt only after the source commit, so recreation retries an interrupted
    // opening but never reopens the book a second time after the reader starts interacting.
    LaunchedEffect(onboardingReplayRequest, transitionCoverProgress, closingBook, navigation, onboarding.step) {
        if (onboardingReplayRequest > 0L && handledExternalReplay != onboardingReplayRequest &&
            dispatchedExternalReplay != onboardingReplayRequest &&
            transitionCoverProgress == null && !closingBook && navigation != null && onboarding.step != null) {
            dispatchedExternalReplay = onboardingReplayRequest
            openReadingPageForHints()
            if (atReadingPage) handledExternalReplay = onboardingReplayRequest
        }
    }

    BackHandler(enabled = insertOpen) { insertOpen = false }
    BackHandler(enabled = !insertOpen && !onboarding.visible) { onReturnToShelf() }

    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val cutout = WindowInsets.displayCutout
    var windowOrigin by remember { mutableStateOf(Offset.Zero) }
    // Only a resting closed book moves back on the desk. The renderer's page geometry stays
    // unchanged, and opening gestures finish at their original scale before expanding.
    var restingClosed by remember { mutableStateOf(closed) }
    LaunchedEffect(closed, turnActive, transitionCoverProgress) {
        if (!turnActive && transitionCoverProgress == null) restingClosed = closed
    }
    val closedScale = (1f - 92f * density.density / (canvasSize.height.coerceAtLeast(1) * .65f)).coerceIn(.60f, .88f)
    val bookScale by animateFloatAsState(if (restingClosed) closedScale else 1f,
        tween(240), label = "closed book rests on desk")
    SideEffect { bookAtReadingScale = kotlin.math.abs(bookScale - 1f) < .002f }
    val showClosedControls = closed && !turnActive && transitionCoverProgress == null && !closingBook &&
        kotlin.math.abs(bookScale - closedScale) < .002f
    fun openMenu() { insertSection = PaperInsertSection.MENU; insertOpen = true }
    fun openContents() { navigate(checkpoint.copy(cover = CoverPosition.OPEN, tocEntryIndex = 0,
        tocCharOffset = 0, annotationLocation = null)) }
    fun replayHints() {
        insertOpen = false
        openReadingPageForHints()
        localReplay++
    }
    Box(modifier.fillMaxSize().drawBehind { drawReaderBackdrop(darkTheme) }
        .onSizeChanged { canvasSize = it }.readerOnboardingGestures(onboarding)
        .onGloballyPositioned { windowOrigin = it.boundsInWindow().topLeft }) {
        val left = cutout.getLeft(density, direction).toFloat()
        val top = cutout.getTop(density).toFloat()
        val right = cutout.getRight(density, direction).toFloat()
        val bottom = cutout.getBottom(density).toFloat()
        val nativeCutouts = remember(view, configuration, left, top, right, bottom, windowOrigin) {
            ViewCompat.getRootWindowInsets(view)?.displayCutout?.boundingRects.orEmpty().map { rect ->
                Rect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
                    .translate(-windowOrigin)
            }
        }
        val bookOrigin = Offset(left, 0f)
        val pivot = Offset(canvasSize.width * .5f, canvasSize.height * .35f)
        val visualBookBounds = rawBookBounds?.let { raw ->
            Rect(pivot + (raw.topLeft - pivot) * bookScale, pivot + (raw.bottomRight - pivot) * bookScale)
        }
        LaunchedEffect(visualBookBounds) { visualBookBounds?.let(onBookReady) }
        if (showClosedControls) Box(Modifier.fillMaxSize().clickable(onClick = onReturnToShelf))
        val paperInsets = ReaderCutoutInsets(top = top,
            cutouts = nativeCutouts.map { it.translate(-bookOrigin) })
        key(document.id) {
            PhysicalBookView(
                pages = emptyList(), document = document,
                bookTitle = document.metadata.title,
                bookAuthor = document.metadata.authors.joinToString().ifBlank { null },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = bookScale; scaleY = bookScale; transformOrigin = TransformOrigin(.5f, .35f)
                }.padding(
                    start = with(density) { left.toDp() },
                    end = with(density) { right.toDp() }, bottom = with(density) { bottom.toDp() }),
                initialLocation = checkpoint.location, initialCover = checkpoint.cover,
                readerSession = readerSession,
                initialTocEntryIndex = checkpoint.tocEntryIndex, initialTocCharOffset = checkpoint.tocCharOffset,
                initialAnnotationLocation = checkpoint.annotationLocation,
                onProgressChanged = {
                    checkpoint = it
                    if (it.cover == CoverPosition.OPEN && it.annotationLocation == null && it.tocEntryIndex == null) {
                        if (hintsOpening && dispatchedExternalReplay == onboardingReplayRequest) {
                            handledExternalReplay = onboardingReplayRequest
                        }
                        hintsOpening = false
                    }
                    progressCallback?.invoke(it)
                },
                devicePosture = devicePosture, forcedPresentation = presentationMode.physical(),
                outerPadding = 4.dp, coverInset = 3.dp, fontSizeSp = preferences.fontSizeSp,
                darkTheme = darkTheme, includeContents = true, bookmarks = bookmarks,
                onCenterTap = ::openMenu, onReturnToShelf = onReturnToShelf,
                onPageNumberTap = { insertSection = PaperInsertSection.PROGRESS; insertOpen = true },
                onNavigateToLocation = ::goTo, onNavigationState = { navigation = it },
                navigationRequest = navigationRequest,
                noteBackEnabled = !insertOpen,
                onNavigationRequestConsumed = { sequence ->
                    if (navigationRequest?.sequence == sequence) navigationRequest = null
                },
                transitionCoverProgress = transitionCoverProgress,
                closingBook = closingBook,
                onBookReady = { rawBookBounds = it.translate(bookOrigin) },
                onTurnActiveChange = { turnActive = it },
                onBookCoverChanged = onBookCoverChanged,
                onBookArtworkReady = onBookArtworkReady,
                drawBackdrop = false,
                pageControlInsets = paperInsets,
                pageControls = { layout ->
                    ReaderTopControls(layout, darkTheme,
                        onBookmarks = { insertSection = PaperInsertSection.BOOKMARKS; insertOpen = true },
                        onToggleTheme = { onPreferencesChange(preferences.copy(theme =
                            if (darkTheme) ReaderTheme.LIGHT else ReaderTheme.DARK)) },
                        onContents = ::openContents,
                        onCloseBook = onReturnToShelf, modifier = Modifier.fillMaxSize(), onMenu = ::openMenu)
                },
            )
        }
        if (showClosedControls && !insertOpen) {
            ClosedBookControls(onReturnToShelf,
                onSettings = { insertSection = PaperInsertSection.SETTINGS; insertOpen = true },
                onContents = ::openContents,
                onBookmarks = { insertSection = PaperInsertSection.BOOKMARKS; insertOpen = true },
                modifier = Modifier.align(Alignment.BottomCenter).displayCutoutPadding().padding(bottom = 12.dp))
        }
        AnimatedVisibility(insertOpen && navigation != null, modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(140)) + slideInVertically(tween(230)) { it / 12 } + scaleIn(tween(230), initialScale = .96f),
            exit = fadeOut(tween(130))) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .18f))
                    .clickable { insertOpen = false })
                navigation?.let { state ->
                val currentBookmark = bookmarks.firstOrNull {
                    it.location.blockId == checkpoint.location?.blockId &&
                        it.location.charOffset == checkpoint.location?.charOffset
                }
                PaperReaderInsert(
                    preferences = preferences, onPreferencesChange = onPreferencesChange,
                    bookmarks = bookmarks, onAddBookmark = onAddBookmark,
                    onDeleteBookmark = onDeleteBookmark,
                    onBookmarkSelected = { goTo(it.location) },
                    onReturnToShelf = { insertOpen = false; onReturnToShelf() }, onDismiss = { insertOpen = false },
                    onGoToToc = ::openContents,
                    onReturnFromToc = if (checkpoint.tocEntryIndex != null) {
                        { navigate(checkpoint.copy(cover = CoverPosition.OPEN, tocEntryIndex = null, tocCharOffset = 0, annotationLocation = null)) }
                    } else null,
                    navigation = state, onConfirmNavigation = ::goTo,
                    isBookmarked = currentBookmark != null,
                    canBookmarkCurrent = checkpoint.tocEntryIndex == null && checkpoint.annotationLocation == null && checkpoint.cover == CoverPosition.OPEN,
                    onRemoveCurrentBookmark = { currentBookmark?.let(onDeleteBookmark) },
                    initialSection = insertSection,
                    onShowHints = ::replayHints,
                    modifier = Modifier.align(Alignment.Center).padding(
                        start = with(density) { left.toDp() } + 18.dp,
                        top = with(density) { top.toDp() } + 12.dp,
                        end = with(density) { right.toDp() } + 18.dp,
                        bottom = with(density) { bottom.toDp() } + 12.dp),
                )
                }
            }
        }
        ReaderOnboardingOverlay(onboarding, darkTheme)
    }
}

private fun ReaderPresentationMode.physical(): BookPresentation? = when (this) {
    ReaderPresentationMode.AUTOMATIC -> null
    ReaderPresentationMode.SINGLE -> BookPresentation.SINGLE
    ReaderPresentationMode.SPREAD -> BookPresentation.SPREAD
}

val ReaderSemanticLocation = SemanticsPropertyKey<String>("ReaderSemanticLocation")
val ReaderPaginationComplete = SemanticsPropertyKey<Boolean>("ReaderPaginationComplete")
val ReaderLayoutIdentity = SemanticsPropertyKey<String>("ReaderLayoutIdentity")
val ReaderCacheBytes = SemanticsPropertyKey<Long>("ReaderCacheBytes")
val ReaderCachedPages = SemanticsPropertyKey<Int>("ReaderCachedPages")
val ReaderSurfaceRenders = SemanticsPropertyKey<Long>("ReaderSurfaceRenders")
val ReaderContentsLocation = SemanticsPropertyKey<String>("ReaderContentsLocation")
val ReaderVisibleFooters = SemanticsPropertyKey<String>("ReaderVisibleFooters")
val ReaderPaperBounds = SemanticsPropertyKey<List<Rect>>("ReaderPaperBounds")
val ReaderContentsRows = SemanticsPropertyKey<List<Rect>>("ReaderContentsRows")
val ReaderContentsTargets = SemanticsPropertyKey<List<String>>("ReaderContentsTargets")
val ReaderPageNumberBounds = SemanticsPropertyKey<List<Rect>>("ReaderPageNumberBounds")
val ReaderJumpInProgress = SemanticsPropertyKey<Boolean>("ReaderJumpInProgress")
val ReaderJumpDirection = SemanticsPropertyKey<String>("ReaderJumpDirection")
val ReaderTurnDirection = SemanticsPropertyKey<String>("ReaderTurnDirection")
val ReaderTurnProgress = SemanticsPropertyKey<Float>("ReaderTurnProgress")
val ReaderTurnSubject = SemanticsPropertyKey<String>("ReaderTurnSubject")
val ReaderTurnSourceText = SemanticsPropertyKey<String>("ReaderTurnSourceText")
val ReaderNoteLinks = SemanticsPropertyKey<List<Rect>>("ReaderNoteLinks")
val ReaderNoteLinkTargets = SemanticsPropertyKey<List<String>>("ReaderNoteLinkTargets")
val ReaderNoteReturns = SemanticsPropertyKey<List<Rect>>("ReaderNoteReturns")
val ReaderNoteReturnTargets = SemanticsPropertyKey<List<String>>("ReaderNoteReturnTargets")
val ReaderNotesLocation = SemanticsPropertyKey<String>("ReaderNotesLocation")
val ReaderNoteVisit = SemanticsPropertyKey<String>("ReaderNoteVisit")

// Legacy test identifier; no production fullscreen control exists.
const val READER_FULLSCREEN_BUTTON_TAG = "reader_fullscreen_button"
