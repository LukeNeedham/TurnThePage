package com.avstaim.listai.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.unit.dp
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
    onBookmarkSelected: (Bookmark) -> Unit = {},
    onReturnToShelf: () -> Unit = {},
) {
    var insertOpen by rememberSaveable { mutableStateOf(false) }
    var checkpoint by remember(document) { mutableStateOf(initialProgress) }
    var revision by remember { mutableIntStateOf(0) }
    var navigation by remember(document) { mutableStateOf<ReaderNavigationState?>(null) }
    val progressCallback by rememberUpdatedState(onProgressChanged)

    fun navigate(progress: ReadingProgress) {
        checkpoint = progress
        progressCallback?.invoke(progress)
        revision++
        insertOpen = false
    }
    fun goTo(location: BookLocation) {
        val length = document.readingLength().coerceAtLeast(1)
        navigate(ReadingProgress(location, CoverPosition.OPEN,
            if (location.fallbackGlobalOffset >= length - 1) 1f else
                (location.fallbackGlobalOffset.toDouble() / length).toFloat().coerceIn(0f, 1f)))
    }

    BackHandler(enabled = insertOpen) { insertOpen = false }
    BackHandler(enabled = !insertOpen) { onReturnToShelf() }

    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
        key(document.id, revision) {
            PhysicalBookView(
                pages = emptyList(), document = document,
                bookTitle = document.metadata.title,
                bookAuthor = document.metadata.authors.joinToString().ifBlank { null },
                modifier = Modifier.fillMaxSize(),
                initialLocation = checkpoint.location, initialCover = checkpoint.cover,
                initialTocEntryIndex = checkpoint.tocEntryIndex, initialTocCharOffset = checkpoint.tocCharOffset,
                onProgressChanged = { checkpoint = it; progressCallback?.invoke(it) },
                devicePosture = devicePosture, forcedPresentation = presentationMode.physical(),
                outerPadding = 4.dp, coverInset = 3.dp, fontSizeSp = preferences.fontSizeSp,
                darkTheme = darkTheme, includeContents = true, bookmarks = bookmarks,
                onCenterTap = { insertOpen = true }, onReturnToShelf = onReturnToShelf,
                onNavigateToLocation = ::goTo, onNavigationState = { navigation = it },
            )
        }
        if (insertOpen) {
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
                    onBookmarkSelected = { insertOpen = false; onBookmarkSelected(it) },
                    onReturnToShelf = onReturnToShelf, onDismiss = { insertOpen = false },
                    onGoToToc = { navigate(checkpoint.copy(cover = CoverPosition.OPEN,
                        tocEntryIndex = 0, tocCharOffset = 0)) },
                    onReturnFromToc = if (checkpoint.tocEntryIndex != null) {
                        { navigate(checkpoint.copy(cover = CoverPosition.OPEN, tocEntryIndex = null, tocCharOffset = 0)) }
                    } else null,
                    navigation = state, onConfirmNavigation = ::goTo,
                    isBookmarked = currentBookmark != null,
                    canBookmarkCurrent = checkpoint.tocEntryIndex == null && checkpoint.cover == CoverPosition.OPEN,
                    onRemoveCurrentBookmark = { currentBookmark?.let(onDeleteBookmark) },
                    modifier = Modifier.align(Alignment.Center).padding(18.dp),
                )
            }
        }
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

// Legacy test identifier; no production fullscreen control exists.
const val READER_FULLSCREEN_BUTTON_TAG = "reader_fullscreen_button"
