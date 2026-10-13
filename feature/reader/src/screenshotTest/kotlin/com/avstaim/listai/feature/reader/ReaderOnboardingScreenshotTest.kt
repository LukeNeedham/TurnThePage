package com.avstaim.listai.feature.reader

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.library.LibraryScreen
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.feature.reader.bookview.BookPresentation
import com.avstaim.listai.feature.reader.bookview.PhysicalBookView
import com.avstaim.listai.feature.reader.demo.FakeBookDocumentFactory
import com.avstaim.listai.feature.reader.demo.toDemoPages

@PreviewTest
@Preview(name = "Reading guide — compact Russian", widthDp = 320, heightDp = 568, locale = "ru")
@Preview(name = "Reading guide — dark English", widthDp = 412, heightDp = 892, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Reading guide — English spread", widthDp = 892, heightDp = 412, locale = "en")
@Preview(name = "Reading guide — dark Russian spread", widthDp = 740, heightDp = 360, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ReaderOnboardingScreenshots() {
    val spread = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    OnboardingPreview(if (spread) ReaderOnboardingStep.DRAG_FORWARD else ReaderOnboardingStep.MENU)
}

@PreviewTest
@Preview(name = "Guide bookmarks — compact Russian large type", widthDp = 320, heightDp = 568, locale = "ru", fontScale = 1.3f)
@Preview(name = "Guide bookmarks — dark English", widthDp = 412, heightDp = 892, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun GuideBookmarksScreenshots() { OnboardingPreview(ReaderOnboardingStep.BOOKMARKS) }

@PreviewTest
@Preview(name = "Guide illustrations — compact Russian", widthDp = 320, heightDp = 568, locale = "ru")
@Preview(name = "Guide illustrations — dark English spread large type", widthDp = 740, heightDp = 360, locale = "en", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun GuideIllustrationsScreenshots() { OnboardingPreview(ReaderOnboardingStep.ILLUSTRATIONS) }

@PreviewTest
@Preview(name = "Guide stacks — compact Russian large type", widthDp = 320, heightDp = 568, locale = "ru", fontScale = 1.3f)
@Preview(name = "Guide stacks — dark English spread", widthDp = 892, heightDp = 412, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun GuideStacksScreenshots() { OnboardingPreview(ReaderOnboardingStep.STACKS) }

@PreviewTest
@Preview(name = "Guide search — dark Russian spread", widthDp = 740, heightDp = 360, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Guide search — compact English", widthDp = 320, heightDp = 568, locale = "en")
@Composable
fun GuideSearchScreenshots() { OnboardingPreview(ReaderOnboardingStep.SEARCH) }

@PreviewTest
@Preview(name = "Guide shelf zoom — compact Russian", widthDp = 320, heightDp = 568, locale = "ru")
@Preview(name = "Guide shelf zoom — dark English spread large type", widthDp = 740, heightDp = 360, locale = "en", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun GuideShelfZoomScreenshots() { OnboardingPreview(ReaderOnboardingStep.SHELF_ZOOM) }

@PreviewTest
@Preview(name = "Guide text zoom — compact Russian large type", widthDp = 320, heightDp = 568, locale = "ru", fontScale = 1.3f)
@Preview(name = "Guide text zoom — dark English spread", widthDp = 740, heightDp = 360, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun GuideTextZoomScreenshots() { OnboardingPreview(ReaderOnboardingStep.TEXT_ZOOM) }

@Composable
private fun OnboardingPreview(step: ReaderOnboardingStep) {
    if (step in setOf(ReaderOnboardingStep.STACKS, ReaderOnboardingStep.SEARCH, ReaderOnboardingStep.SHELF_ZOOM)) {
        ShelfOnboardingPreview(step)
        return
    }
    val dark = isSystemInDarkTheme()
    val spread = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val document = remember(step) {
        val source = FakeBookDocumentFactory.create()
        if (step != ReaderOnboardingStep.ILLUSTRATIONS) source else {
            val bytes = ByteArrayOutputStream().apply {
                observatoryIllustration().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, this)
            }.toByteArray()
            source.copy(images = listOf(BookImage(ImageId("demo-moonlit-library"), "image/png", BookBinaryContent { bytes })))
        }
    }
    val state = remember(step) {
        ReaderOnboardingState(ReaderOnboardingSession(step.ordinal)).apply { readerReady = true }
    }
    val imageLesson = step == ReaderOnboardingStep.ILLUSTRATIONS
    val sourceLesson = imageLesson || step == ReaderOnboardingStep.BOOKMARKS
    val anchorIndex = if (imageLesson) 9 else 4
    val sourceAnchor = document.blocks.getOrNull(anchorIndex)?.let { block ->
        BookLocation(block.id, 0, document.blocks.take(anchorIndex).sumOf { it.plainText().length + 1L })
    }
    ListaiTheme(darkTheme = dark) {
        Box(Modifier.fillMaxSize()) {
            PhysicalBookView(if (sourceLesson) emptyList() else document.toDemoPages(), document.metadata.title, null, Modifier.fillMaxSize(),
                document = document.takeIf { sourceLesson }, initialLocation = sourceAnchor.takeIf { sourceLesson },
                forcedPresentation = if (spread) BookPresentation.SPREAD else BookPresentation.SINGLE,
                initialPageIndex = 4, darkTheme = dark, outerPadding = 4.dp, coverInset = 3.dp,
                onQuickBookmarkBoundsChanged = { bounds ->
                    if (step == ReaderOnboardingStep.BOOKMARKS) state.targetBounds = bounds
                },
                onVisibleImageBounds = { bounds ->
                    if (imageLesson) state.targetBounds = bounds.firstOrNull()
                })
            ReaderOnboardingOverlay(state, dark, illustrationProgress = .4f)
        }
    }
}

/** Use the real shelf and its measured hit targets for every localized shelf lesson. */
@Composable
private fun ShelfOnboardingPreview(step: ReaderOnboardingStep) {
    val dark = isSystemInDarkTheme()
    val books = remember {
        listOf("Седьмой огонь", "The Quiet Coast", "Winter Letters", "Морской дневник").mapIndexed { index, title ->
            LibraryBook(BookId("guide-$index"), title, "A. Reader", "guide-$index.fb2", null,
                index.toLong(), null, 1000, ReadingProgress())
        }
    }
    val state = remember { ReaderOnboardingState(ReaderOnboardingSession(step.ordinal)).apply { readerReady = true } }
    val bookBounds = remember { mutableStateMapOf<BookId, Rect>() }
    var searchBounds by remember { mutableStateOf<Rect?>(null) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val targets = bookBounds.values.filter { it.overlaps(viewport) }.sortedWith(compareBy<Rect> { it.top }.thenBy { it.left })
    val measuredSearch = searchBounds
    val measuredViewport = viewport
    SideEffect {
        state.targetBounds = when (step) {
            ReaderOnboardingStep.STACKS -> targets.firstOrNull()
            ReaderOnboardingStep.SEARCH -> measuredSearch
            else -> measuredViewport.copy(bottom = measuredViewport.top + measuredViewport.height * .8f)
        }
        state.secondaryTargetBounds = if (step == ReaderOnboardingStep.STACKS) targets.getOrNull(1) else null
    }
    ListaiTheme(darkTheme = dark) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { viewport = it.boundsInRoot() }) {
            LibraryScreen(books = books, importing = false, onImport = {}, onCancelImport = {},
                onOpen = {}, onDelete = {}, modifier = Modifier.fillMaxSize(), darkTheme = dark,
                onBookBoundsChanged = { id, bounds -> if (bounds == null) bookBounds.remove(id) else bookBounds[id] = bounds },
                onSearchBoundsChanged = { searchBounds = it })
            ReaderOnboardingOverlay(state, dark, illustrationProgress = .4f)
        }
    }
}
