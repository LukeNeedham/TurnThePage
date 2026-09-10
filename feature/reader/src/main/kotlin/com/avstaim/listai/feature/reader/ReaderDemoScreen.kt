package com.avstaim.listai.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.adaptive.DevicePosture
import com.avstaim.listai.core.model.BookDocument
import androidx.compose.ui.semantics.SemanticsPropertyKey
import com.avstaim.listai.feature.reader.bookview.BookPresentation
import com.avstaim.listai.feature.reader.bookview.PhysicalBookView
import com.avstaim.listai.feature.reader.demo.FakeBookDocumentFactory
import com.avstaim.listai.feature.reader.demo.toDemoPages

enum class ReaderPresentationMode {
    AUTOMATIC,
    SINGLE,
    SPREAD,
}

/** Adaptive Stage 2 host for the deterministic physical-book demo. */
@Composable
fun ReaderDemoScreen(
    modifier: Modifier = Modifier,
    devicePosture: DevicePosture = DevicePosture.Unknown,
    presentationMode: ReaderPresentationMode = ReaderPresentationMode.AUTOMATIC,
    initialPageIndex: Int = 0,
    isFullscreen: Boolean = false,
    onFullscreenChange: ((Boolean) -> Unit)? = null,
) = ReaderBookScreen(null, modifier, devicePosture, presentationMode, initialPageIndex, isFullscreen, onFullscreenChange)

/** Production document reader. Demo fixtures remain isolated to graphical regression tests. */
@Composable
fun ReaderScreen(
    document: BookDocument,
    modifier: Modifier = Modifier,
    devicePosture: DevicePosture = DevicePosture.Unknown,
    presentationMode: ReaderPresentationMode = ReaderPresentationMode.AUTOMATIC,
    isFullscreen: Boolean = false,
    onFullscreenChange: ((Boolean) -> Unit)? = null,
) = ReaderBookScreen(document, modifier, devicePosture, presentationMode, 0, isFullscreen, onFullscreenChange)

val ReaderSemanticLocation = SemanticsPropertyKey<String>("ReaderSemanticLocation")
val ReaderPaginationComplete = SemanticsPropertyKey<Boolean>("ReaderPaginationComplete")
val ReaderLayoutIdentity = SemanticsPropertyKey<String>("ReaderLayoutIdentity")
val ReaderCacheBytes = SemanticsPropertyKey<Long>("ReaderCacheBytes")
val ReaderCachedPages = SemanticsPropertyKey<Int>("ReaderCachedPages")
val ReaderSurfaceRenders = SemanticsPropertyKey<Long>("ReaderSurfaceRenders")

@Composable
private fun ReaderBookScreen(
    parsedDocument: BookDocument?,
    modifier: Modifier,
    devicePosture: DevicePosture,
    presentationMode: ReaderPresentationMode,
    initialPageIndex: Int,
    isFullscreen: Boolean,
    onFullscreenChange: ((Boolean) -> Unit)?,
) {
    val document = parsedDocument ?: remember { FakeBookDocumentFactory.create() }
    if (onFullscreenChange != null) {
        BackHandler(enabled = isFullscreen) {
            onFullscreenChange(false)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        PhysicalBookView(
            pages = if (parsedDocument == null) document.toDemoPages() else emptyList(),
            document = parsedDocument,
            bookTitle = document.metadata.title,
            bookAuthor = document.metadata.authors.joinToString().ifBlank { null },
            initialPageIndex = initialPageIndex,
            modifier = Modifier.fillMaxSize(),
            devicePosture = devicePosture,
            forcedPresentation = when (presentationMode) {
                ReaderPresentationMode.AUTOMATIC -> null
                ReaderPresentationMode.SINGLE -> BookPresentation.SINGLE
                ReaderPresentationMode.SPREAD -> BookPresentation.SPREAD
            },
            outerPadding = if (isFullscreen) FULLSCREEN_BOOK_PADDING else DEFAULT_BOOK_PADDING,
            coverInset = if (isFullscreen) FULLSCREEN_COVER_INSET else DEFAULT_COVER_INSET,
        )

        onFullscreenChange?.let { onChange ->
            FullscreenButton(
                isFullscreen = isFullscreen,
                onClick = { onChange(!isFullscreen) },
                modifier = Modifier
                    // Keep the control out of the text block. Demo pages reserve their lower
                    // corner for chrome while the page number remains centred.
                    .align(Alignment.BottomEnd)
                    .then(
                        if (isFullscreen) {
                            Modifier.windowInsetsPadding(
                                WindowInsets.displayCutout.only(
                                    WindowInsetsSides.Bottom + WindowInsetsSides.End,
                                ),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .padding(if (isFullscreen) 6.dp else 12.dp),
            )
        }
    }
}

@Composable
private fun FullscreenButton(
    isFullscreen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(
        if (isFullscreen) R.string.reader_exit_fullscreen else R.string.reader_enter_fullscreen,
    )
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(44.dp)
            .background(Color.Black.copy(alpha = 0.58f), CircleShape)
            .testTag(READER_FULLSCREEN_BUTTON_TAG)
            .semantics { contentDescription = label },
    ) {
        Canvas(
            modifier = Modifier
                .size(22.dp)
                .clearAndSetSemantics { },
        ) {
            val strokeWidth = 2.dp.toPx()
            val inset = 2.dp.toPx()
            val arm = 6.dp.toPx()
            val start = if (isFullscreen) inset + arm else inset
            val end = if (isFullscreen) size.width - inset - arm else size.width - inset
            val direction = if (isFullscreen) -1f else 1f
            val paint = Stroke(width = strokeWidth, cap = StrokeCap.Round)

            listOf(
                androidx.compose.ui.geometry.Offset(start, start) to
                    androidx.compose.ui.geometry.Offset(direction, direction),
                androidx.compose.ui.geometry.Offset(end, start) to
                    androidx.compose.ui.geometry.Offset(-direction, direction),
                androidx.compose.ui.geometry.Offset(start, end) to
                    androidx.compose.ui.geometry.Offset(direction, -direction),
                androidx.compose.ui.geometry.Offset(end, end) to
                    androidx.compose.ui.geometry.Offset(-direction, -direction),
            ).forEach { (corner, signs) ->
                drawLine(
                    color = Color.White,
                    start = corner,
                    end = corner.copy(x = corner.x + signs.x * arm),
                    strokeWidth = paint.width,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color.White,
                    start = corner,
                    end = corner.copy(y = corner.y + signs.y * arm),
                    strokeWidth = paint.width,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private val DEFAULT_BOOK_PADDING: Dp = 14.dp
private val DEFAULT_COVER_INSET: Dp = 7.dp
private val FULLSCREEN_BOOK_PADDING: Dp = 4.dp
private val FULLSCREEN_COVER_INSET: Dp = 3.dp

const val READER_FULLSCREEN_BUTTON_TAG = "reader_fullscreen_button"
