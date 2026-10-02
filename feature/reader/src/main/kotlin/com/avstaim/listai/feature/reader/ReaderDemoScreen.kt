package com.avstaim.listai.feature.reader

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.core.view.ViewCompat
import com.avstaim.listai.core.adaptive.DevicePosture
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.bookview.*
import com.avstaim.listai.feature.reader.demo.FakeBookDocumentFactory
import com.avstaim.listai.feature.reader.demo.toDemoPages

enum class ReaderPresentationMode { AUTOMATIC, SINGLE, SPREAD }

private data class SelectedBookImage(
    val image: BookImage,
    val alternativeText: String?,
    val sourceBounds: Rect?,
    val preview: BookImagePreview,
    val transient: Boolean,
    val visible: Boolean = true,
)

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
    onAddBookmarkAt: (BookLocation) -> Unit = { onAddBookmark() },
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
    active: Boolean = true,
) {
    var insertOpen by rememberSaveable(readerSession) { mutableStateOf(false) }
    var insertSection by rememberSaveable(readerSession) { mutableStateOf(PaperInsertSection.MENU) }
    var checkpoint by remember(document, readerSession) { mutableStateOf(initialProgress) }
    var contentsOrigin by rememberSaveable(document.id.value) { mutableStateOf<Bundle?>(null) }
    var navigationSequence by remember(document, readerSession) { mutableLongStateOf(0L) }
    var navigationRequest by remember(document, readerSession) { mutableStateOf<ReaderJumpRequest?>(null) }
    var navigation by remember(document, readerSession) { mutableStateOf<ReaderNavigationState?>(null) }
    var bookmarkableLocation by remember(document, readerSession) { mutableStateOf<BookLocation?>(null) }
    var selectedImage by remember(document, readerSession) { mutableStateOf<SelectedBookImage?>(null) }
    val firstChapterLocation = remember(document) { document.firstChapterLocation() }
    val hasDescription = remember(document) { PhysicalAnnotation.create(document)?.hasDescription == true }
    val progressCallback by rememberUpdatedState(onProgressChanged)
    var localReplay by rememberSaveable { mutableLongStateOf(0L) }
    var handledExternalReplay by rememberSaveable(document.id.value) { mutableLongStateOf(0L) }
    var dispatchedExternalReplay by remember(document, readerSession) { mutableLongStateOf(0L) }
    var hintsOpening by remember(document, readerSession) { mutableStateOf(false) }
    val atReadingPage = checkpoint.cover == CoverPosition.OPEN &&
        checkpoint.annotationLocation == null && checkpoint.titleLocation == null && checkpoint.tocEntryIndex == null
    val externalOpeningPending = onboardingReplayRequest > 0L &&
        handledExternalReplay != onboardingReplayRequest && !atReadingPage
    val onboarding = rememberReaderOnboardingState(onboardingCompleted,
        readerReady = transitionCoverProgress == null && !closingBook && !insertOpen && selectedImage == null &&
            !externalOpeningPending && !hintsOpening && checkpoint.cover == CoverPosition.OPEN &&
            navigation != null,
        replayRequest = onboardingReplayRequest + localReplay, onComplete = onOnboardingComplete)
    // Let the central tap visibly reveal the real card before continuing the gesture guide.
    LaunchedEffect(insertOpen, onboarding.step) {
        if (insertOpen && onboarding.step == ReaderOnboardingStep.DRAG_FORWARD) {
            delay(1100)
            insertOpen = false
        }
    }

    fun navigate(progress: ReadingProgress, returningFromContents: Boolean = false) {
        navigationRequest = ReaderJumpRequest(++navigationSequence, progress,
            returningFromContents = returningFromContents)
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
                cover = CoverPosition.OPEN, annotationLocation = null, titleLocation = null,
                tocEntryIndex = null, tocCharOffset = 0))
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
    fun returnFromContents() {
        val origin = contentsOrigin?.toReaderProgress() ?: checkpoint.copy(cover = CoverPosition.OPEN,
            tocEntryIndex = null, tocCharOffset = 0, annotationLocation = null, titleLocation = null)
        // Keep the origin until the return commits; a cancelled curl remains in this visit.
        navigate(origin, returningFromContents = true)
    }
    BackHandler(enabled = active && !insertOpen && !onboarding.visible && selectedImage == null) {
        if (checkpoint.tocEntryIndex != null) returnFromContents() else onReturnToShelf()
    }

    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val cutout = WindowInsets.displayCutout
    var windowOrigin by remember { mutableStateOf(Offset.Zero) }
    var imageViewportSize by remember { mutableStateOf(Size.Zero) }
    fun openMenu() { insertSection = PaperInsertSection.MENU; insertOpen = true }
    fun openContents() {
        if (checkpoint.tocEntryIndex == null) contentsOrigin = checkpoint.toReaderBundle()
        navigate(checkpoint.copy(cover = CoverPosition.OPEN, tocEntryIndex = 0,
            tocCharOffset = 0, annotationLocation = null, titleLocation = null))
    }
    fun replayHints() {
        insertOpen = false
        openReadingPageForHints()
        localReplay++
    }
    LaunchedEffect(active, closingBook) {
        if (!active || closingBook) selectedImage = selectedImage?.copy(visible = false)
    }
    LaunchedEffect(imageViewportSize, devicePosture) {
        // A new paper layout can move the source; fade out instead of returning to stale bounds.
        selectedImage = selectedImage?.copy(visible = false, sourceBounds = null)
    }
    Box(modifier.fillMaxSize().drawBehind { drawReaderBackdrop(darkTheme) }
        .readerOnboardingGestures(onboarding)
        .onGloballyPositioned {
            val bounds = it.boundsInWindow()
            windowOrigin = bounds.topLeft
            imageViewportSize = bounds.size
        }) {
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
        val paperInsets = ReaderCutoutInsets(top = top,
            cutouts = nativeCutouts.map { it.translate(-bookOrigin) })
        Box(Modifier.fillMaxSize().then(if (selectedImage != null) Modifier.clearAndSetSemantics {} else Modifier)) {
            key(document.id) {
                PhysicalBookView(
                    pages = emptyList(), document = document,
                    bookTitle = document.metadata.title,
                    bookAuthor = document.metadata.authors.joinToString().ifBlank { null },
                    modifier = Modifier.fillMaxSize().padding(
                        start = with(density) { left.toDp() },
                        end = with(density) { right.toDp() }, bottom = with(density) { bottom.toDp() }),
                    initialLocation = checkpoint.location, initialCover = checkpoint.cover,
                    readerSession = readerSession,
                    initialTocEntryIndex = checkpoint.tocEntryIndex, initialTocCharOffset = checkpoint.tocCharOffset,
                    initialAnnotationLocation = checkpoint.annotationLocation,
                    initialTitleLocation = checkpoint.titleLocation,
                    onProgressChanged = {
                        // Once ordinary turning or a chapter link leaves the TOC, a later
                        // natural visit must not return to an unrelated old menu origin.
                        if (checkpoint.tocEntryIndex != null && it.tocEntryIndex == null) contentsOrigin = null
                        checkpoint = it
                        if (it.cover == CoverPosition.OPEN && it.annotationLocation == null &&
                            it.titleLocation == null && it.tocEntryIndex == null) {
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
                    onAddBookmark = onAddBookmarkAt,
                    onDeleteBookmark = onDeleteBookmark,
                    onBookmarkableLocationChanged = { bookmarkableLocation = it },
                    onCenterTap = ::openMenu, onReturnToShelf = onReturnToShelf,
                    onPageNumberTap = { insertSection = PaperInsertSection.PROGRESS; insertOpen = true },
                    onImageTap = { hit ->
                        document.imagesById[hit.image.imageId]?.let {
                            selectedImage = SelectedBookImage(it, hit.image.alternativeText,
                                hit.bounds.translate(bookOrigin), hit.preview, transient = false)
                        }
                    },
                    onImageHoldStart = { hit ->
                        document.imagesById[hit.image.imageId]?.let {
                            selectedImage = SelectedBookImage(it, hit.image.alternativeText,
                                hit.bounds.translate(bookOrigin), hit.preview, transient = true)
                        }
                    },
                    onImageHoldEnd = {
                        selectedImage?.takeIf { it.transient }?.let {
                            selectedImage = it.copy(visible = false)
                        }
                    },
                    onNavigateToLocation = ::goTo, onNavigationState = { navigation = it },
                    navigationRequest = navigationRequest,
                    noteBackEnabled = !insertOpen && selectedImage == null,
                    onNavigationRequestConsumed = { sequence ->
                        if (navigationRequest?.sequence == sequence) navigationRequest = null
                    },
                    transitionCoverProgress = transitionCoverProgress,
                    closingBook = closingBook,
                    onBookReady = {
                        onBookReady(it.translate(bookOrigin))
                    },
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
                            showClock = preferences.showClock,
                            active = active && selectedImage == null && !closingBook && transitionCoverProgress == null,
                            onCloseBook = onReturnToShelf, modifier = Modifier.fillMaxSize(), onMenu = ::openMenu)
                    },
                    closedBookControls = { bookBounds, viewportBounds, physicalPresentation,
                        frontCover, openFrontCover ->
                        if (!insertOpen) ClosedBookControls(
                            onRead = { (checkpoint.location ?: firstChapterLocation)?.let(::goTo) },
                            onDescription = openFrontCover.takeIf { frontCover && hasDescription },
                            onShelf = onReturnToShelf,
                            onSettings = { insertSection = PaperInsertSection.SETTINGS; insertOpen = true },
                            onContents = ::openContents,
                            onBookmarks = { insertSection = PaperInsertSection.BOOKMARKS; insertOpen = true },
                            bookBounds = bookBounds,
                            viewportBounds = viewportBounds,
                            overlayOnCover = physicalPresentation == BookPresentation.SINGLE,
                            modifier = Modifier.fillMaxSize())
                    },
                    insideCoverControls = { bookBounds, viewportBounds, openDescription ->
                        if (!insertOpen) ClosedBookControls(
                            onRead = { (checkpoint.location ?: firstChapterLocation)?.let(::goTo) },
                            onDescription = openDescription.takeIf { hasDescription },
                            onShelf = onReturnToShelf,
                            onSettings = { insertSection = PaperInsertSection.SETTINGS; insertOpen = true },
                            onContents = ::openContents,
                            onBookmarks = { insertSection = PaperInsertSection.BOOKMARKS; insertOpen = true },
                            bookBounds = bookBounds, viewportBounds = viewportBounds,
                            overlayOnCover = true, modifier = Modifier.fillMaxSize())
                    },
                )
            }
            AnimatedVisibility(insertOpen, modifier = Modifier.fillMaxSize(),
                enter = fadeIn(tween(140)) + slideInVertically(tween(230)) { it / 12 } + scaleIn(tween(230), initialScale = .96f),
                exit = fadeOut(tween(130))) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .18f))
                        .clickable { insertOpen = false })
                    val currentBookmark = bookmarks.firstOrNull {
                        it.location.blockId == bookmarkableLocation?.blockId &&
                            it.location.charOffset == bookmarkableLocation?.charOffset
                    }
                    PaperReaderInsert(
                        preferences = preferences, onPreferencesChange = onPreferencesChange,
                        bookmarks = bookmarks, onAddBookmark = {
                            bookmarkableLocation?.let(onAddBookmarkAt) ?: onAddBookmark()
                        },
                        onDeleteBookmark = onDeleteBookmark,
                        onBookmarkSelected = { goTo(it.location) },
                        onReturnToShelf = { insertOpen = false; onReturnToShelf() }, onDismiss = { insertOpen = false },
                        onGoToToc = ::openContents,
                        onReturnFromToc = if (checkpoint.tocEntryIndex != null) {
                            ::returnFromContents
                        } else null,
                        navigation = navigation, onConfirmNavigation = ::goTo,
                        isBookmarked = currentBookmark != null,
                        canBookmarkCurrent = bookmarkableLocation != null,
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
            ReaderOnboardingOverlay(onboarding, darkTheme)
        }
        selectedImage?.let { selected ->
            // Lift only the illustration, leaving a single moving copy over the paper.
            selected.sourceBounds?.let { source ->
                // Cover the filtered edge of the pixel-aligned page raster as well.
                val mask = source.inflate(1f)
                Box(Modifier.fillMaxSize().drawBehind {
                    drawRect(if (darkTheme) DarkPaper else WhitePaper,
                        topLeft = mask.topLeft, size = mask.size)
                })
            }
            BookImageViewer(selected.image, selected.alternativeText,
                onDismissCompleted = { selectedImage = null }, devicePosture = devicePosture,
                sourceBounds = selected.sourceBounds, preview = selected.preview,
                visible = selected.visible, transientPreview = selected.transient,
                onDismissRequested = { selectedImage = selectedImage?.copy(visible = false) })
        }
    }
}

private fun BookDocument.firstChapterLocation(): BookLocation? {
    chapters.firstOrNull()?.startBlockId?.let { id ->
        var offset = 0L
        readingBlocks().forEach { block ->
            if (block.id == id) return BookLocation(id, 0, offset)
            offset += block.plainText().length + 1L
        }
    }
    val firstEntry = tableOfContents.firstOrNull()
    // FB2 has no semantic "chapter" kind. One level handles the common Part -> Chapter
    // hierarchy without accidentally skipping a real first chapter for its first subchapter.
    return firstEntry?.children?.firstOrNull()?.location ?: firstEntry?.location
        ?: readingBlocks().firstOrNull()?.let { BookLocation(it.id, 0, 0) }
}

/** Only platform-saveable values cross recreation; source and supplemental anchors stay separate. */
private fun ReadingProgress.toReaderBundle(): Bundle = Bundle().apply {
    fun putLocation(prefix: String, value: BookLocation?) {
        value ?: return
        putString("${prefix}Id", value.blockId.value)
        putInt("${prefix}Char", value.charOffset)
        putLong("${prefix}Global", value.fallbackGlobalOffset)
    }
    putLocation("source", location)
    putLocation("annotation", annotationLocation)
    putLocation("title", titleLocation)
    putString("cover", cover.name)
    putFloat("fraction", fraction)
    tocEntryIndex?.let { putInt("tocEntry", it) }
    putInt("tocChar", tocCharOffset)
}

private fun Bundle.toReaderProgress(): ReadingProgress {
    fun location(prefix: String) = getString("${prefix}Id")?.let {
        BookLocation(BlockId(it), getInt("${prefix}Char"), getLong("${prefix}Global"))
    }
    return ReadingProgress(location("source"),
        CoverPosition.entries.firstOrNull { it.name == getString("cover") } ?: CoverPosition.OPEN,
        getFloat("fraction"), if (containsKey("tocEntry")) getInt("tocEntry") else null,
        getInt("tocChar"), location("annotation"), location("title"))
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
val ReaderPaperKinds = SemanticsPropertyKey<List<String>>("ReaderPaperKinds")
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
val ReaderTurnDestinationText = SemanticsPropertyKey<List<String>>("ReaderTurnDestinationText")
val ReaderNoteLinks = SemanticsPropertyKey<List<Rect>>("ReaderNoteLinks")
val ReaderNoteLinkTargets = SemanticsPropertyKey<List<String>>("ReaderNoteLinkTargets")
val ReaderNoteReturns = SemanticsPropertyKey<List<Rect>>("ReaderNoteReturns")
val ReaderNoteReturnTargets = SemanticsPropertyKey<List<String>>("ReaderNoteReturnTargets")
val ReaderNotesLocation = SemanticsPropertyKey<String>("ReaderNotesLocation")
val ReaderNoteVisit = SemanticsPropertyKey<String>("ReaderNoteVisit")
val ReaderImageBounds = SemanticsPropertyKey<List<Rect>>("ReaderImageBounds")
val ReaderImageIds = SemanticsPropertyKey<List<String>>("ReaderImageIds")

// Legacy test identifier; no production fullscreen control exists.
const val READER_FULLSCREEN_BUTTON_TAG = "reader_fullscreen_button"
