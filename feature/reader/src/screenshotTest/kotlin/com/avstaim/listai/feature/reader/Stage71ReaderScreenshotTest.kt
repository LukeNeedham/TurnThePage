package com.avstaim.listai.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.CoverPosition
import com.avstaim.listai.feature.reader.bookview.DemoTurnPreview
import com.avstaim.listai.feature.reader.bookview.BookPresentation
import com.avstaim.listai.feature.reader.bookview.PhysicalBookView
import com.avstaim.listai.feature.reader.bookview.drawReaderBackdrop
import com.avstaim.listai.reader.rendering.turn.TurnDirection

@PreviewTest
@Preview(name = "Reader 7.1 — progress and camera controls", widthDp = 412, heightDp = 850, locale = "en")
@Composable
fun ReaderCameraProgressScreenshot() { Stage71Preview(camera = true) }

@PreviewTest
@Preview(name = "Reader 7.1 — source progress on curled leaf", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun ReaderMovingProgressScreenshot() { Stage71Preview(camera = true, curl = true) }

@PreviewTest
@Preview(name = "Reader 7.1 — landscape side cutout", widthDp = 900, heightDp = 440, locale = "ru")
@Composable
fun ReaderLandscapeControlsScreenshot() { Stage71Preview(landscapeCutout = true, dark = true) }

@PreviewTest
@Preview(name = "Reader 7.1 — no cutout", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun ReaderNoCutoutControlsScreenshot() { Stage71Preview() }

@PreviewTest
@Preview(name = "Reader — quick paper bookmark", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun ReaderQuickBookmarkScreenshot() { Stage71Preview(quickBookmark = true) }

@PreviewTest
@Preview(name = "Reader — quick bookmark avoids camera", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun ReaderQuickBookmarkCameraScreenshot() { Stage71Preview(camera = true, quickBookmark = true) }

@PreviewTest
@Preview(name = "Reader — quick bookmark at spread spine", widthDp = 1000, heightDp = 650, locale = "ru")
@Composable
fun ReaderQuickBookmarkSpreadScreenshot() {
    Stage71Preview(quickBookmark = true, forceSpread = true)
}

@PreviewTest
@Preview(name = "Reader — quick bookmark dark single top", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun ReaderQuickBookmarkDarkSingleScreenshot() {
    Stage71Preview(quickBookmark = true, dark = true)
}

@PreviewTest
@Preview(name = "Reader — quick bookmark dark left of spine", widthDp = 1000, heightDp = 650, locale = "ru")
@Composable
fun ReaderQuickBookmarkDarkSpreadScreenshot() {
    Stage71Preview(quickBookmark = true, forceSpread = true, dark = true)
}

@PreviewTest
@Preview(name = "Reader 7.1 — closed back and blank return area", widthDp = 1000, heightDp = 650)
@Composable
fun ReaderClosedBackBackgroundScreenshot() { Stage71Preview(back = true) }

@PreviewTest
@Preview(name = "Reader 7.1 — rigid cover reveals saved place", widthDp = 1000, heightDp = 650)
@Composable
fun ReaderSavedPlaceOpeningScreenshot() { Stage71Preview(coverProgress = .38f) }

@Composable
private fun Stage71Preview(camera: Boolean = false, landscapeCutout: Boolean = false,
    dark: Boolean = false, curl: Boolean = false, back: Boolean = false, coverProgress: Float? = null,
    quickBookmark: Boolean = false, forceSpread: Boolean = false) {
    val document = remember { stage7PreviewDocument() }
    val density = LocalDensity.current
    ListaiTheme(darkTheme = dark) {
        BoxWithConstraints(Modifier.fillMaxSize().drawBehind { drawReaderBackdrop(dark) }) {
            val cutouts = with(density) { when {
                camera -> ReaderCutoutInsets(top = 32.dp.toPx(), cutouts = listOf(
                    Rect(constraints.maxWidth / 2f - 14.dp.toPx(), 0f,
                        constraints.maxWidth / 2f + 14.dp.toPx(), 32.dp.toPx())))
                landscapeCutout -> ReaderCutoutInsets(left = 32.dp.toPx(), cutouts = listOf(
                    Rect(0f, constraints.maxHeight / 2f - 14.dp.toPx(),
                        32.dp.toPx(), constraints.maxHeight / 2f + 14.dp.toPx())))
                else -> ReaderCutoutInsets()
            } }
            PhysicalBookView(emptyList(), document.metadata.title, document.metadata.authors.joinToString(),
                document = document, darkTheme = dark, includeContents = true,
                initialCover = if (back) CoverPosition.BACK else CoverPosition.OPEN,
                initialLocation = document.tableOfContents[8].location,
                forcedPresentation = BookPresentation.SPREAD.takeIf { forceSpread },
                onAddBookmark = if (quickBookmark) ({ _ -> }) else null,
                onDeleteBookmark = if (quickBookmark) ({ _ -> }) else null,
                outerPadding = 4.dp, coverInset = 3.dp,
                previewTurn = if (curl) DemoTurnPreview(TurnDirection.FORWARD, .38f) else null,
                transitionCoverProgress = coverProgress,
                drawBackdrop = false,
                pageControlInsets = cutouts.copy(left = 0f,
                    cutouts = cutouts.cutouts.map { it.translate(androidx.compose.ui.geometry.Offset(-cutouts.left, 0f)) }),
                pageControls = { ReaderTopControls(it, dark, {}, {}, {}, {}, Modifier.fillMaxSize()) },
                modifier = Modifier.fillMaxSize().padding(start = with(density) { cutouts.left.toDp() }))
            Canvas(Modifier.fillMaxSize()) {
                cutouts.cutouts.forEach { rect -> drawRoundRect(Color.Black, rect.topLeft, rect.size,
                    CornerRadius(12.dp.toPx())) }
            }
        }
    }
}
