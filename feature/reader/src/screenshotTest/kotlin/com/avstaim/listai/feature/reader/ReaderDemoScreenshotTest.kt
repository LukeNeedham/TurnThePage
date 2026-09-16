package com.avstaim.listai.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.feature.reader.bookview.BookDisplayFeature
import com.avstaim.listai.feature.reader.bookview.BookFoldOrientation
import com.avstaim.listai.feature.reader.bookview.BookPresentation
import com.avstaim.listai.feature.reader.bookview.DemoTurnPreview
import com.avstaim.listai.feature.reader.bookview.PhysicalBookView
import com.avstaim.listai.feature.reader.bookview.PhysicalBookState
import com.avstaim.listai.feature.reader.demo.FakeBookDocumentFactory
import com.avstaim.listai.feature.reader.demo.toDemoPages
import com.avstaim.listai.reader.rendering.turn.CurlOrigin
import com.avstaim.listai.reader.rendering.turn.TurnDirection

@PreviewTest
@Preview(
    name = "Reader demo phone — English",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun ReaderDemoEnglishScreenshot() {
    ListaiTheme(darkTheme = false) {
        ReaderDemoScreen()
    }
}

@PreviewTest
@Preview(
    name = "Reader demo phone — Russian",
    widthDp = 412,
    heightDp = 915,
    locale = "ru",
    showBackground = true,
)
@Composable
fun ReaderDemoRussianScreenshot() {
    ListaiTheme(darkTheme = false) {
        ReaderDemoScreen()
    }
}

@PreviewTest
@Preview(
    name = "Reader fullscreen — portrait",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun ReaderFullscreenPortraitScreenshot() {
    ListaiTheme(darkTheme = false) {
        ReaderDemoScreen(
            isFullscreen = true,
            onFullscreenChange = { _ -> },
        )
    }
}

@PreviewTest
@Preview(
    name = "Reader fullscreen — landscape spread",
    widthDp = 915,
    heightDp = 412,
    locale = "en",
    showBackground = true,
)
@Composable
fun ReaderFullscreenLandscapeScreenshot() {
    ListaiTheme(darkTheme = false) {
        ReaderDemoScreen(
            isFullscreen = true,
            onFullscreenChange = { _ -> },
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical spread — idle",
    widthDp = 1000,
    heightDp = 700,
    locale = "en",
    showBackground = true,
)
@Composable
fun PhysicalSpreadScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SPREAD,
            initialPageIndex = 1,
        )
    }
}

@PreviewTest
@Preview(
    name = "Foldable — centered vertical hinge",
    widthDp = 1000,
    heightDp = 700,
    locale = "en",
    showBackground = true,
)
@Composable
fun CenteredVerticalHingeScreenshot() {
    val hinge = rememberDisplayFeature(
        left = 492.dp,
        top = 0.dp,
        right = 508.dp,
        bottom = 700.dp,
        orientation = BookFoldOrientation.VERTICAL,
    )
    ListaiTheme(darkTheme = false) {
        DemoBook(
            initialPageIndex = 1,
            displayFeature = hinge,
        )
    }
}

@PreviewTest
@Preview(
    name = "Foldable — off-center vertical hinge",
    widthDp = 1000,
    heightDp = 700,
    locale = "en",
    showBackground = true,
)
@Composable
fun OffCenterVerticalHingeScreenshot() {
    val hinge = rememberDisplayFeature(
        left = 392.dp,
        top = 0.dp,
        right = 408.dp,
        bottom = 700.dp,
        orientation = BookFoldOrientation.VERTICAL,
    )
    ListaiTheme(darkTheme = false) {
        DemoBook(
            initialPageIndex = 1,
            displayFeature = hinge,
        )
    }
}

@PreviewTest
@Preview(
    name = "Foldable — horizontal half-opened",
    widthDp = 1000,
    heightDp = 700,
    locale = "en",
    showBackground = true,
)
@Composable
fun HorizontalHalfOpenedScreenshot() {
    val fold = rememberDisplayFeature(
        left = 0.dp,
        top = 338.dp,
        right = 1000.dp,
        bottom = 362.dp,
        orientation = BookFoldOrientation.HORIZONTAL,
        halfOpened = true,
    )
    ListaiTheme(darkTheme = false) {
        DemoBook(
            initialPageIndex = 5,
            displayFeature = fold,
        )
    }
}

@PreviewTest
@Preview(
    name = "Foldable — curl through centered hinge",
    widthDp = 1000,
    heightDp = 700,
    locale = "en",
    showBackground = true,
)
@Composable
fun CenteredHingeCurlScreenshot() {
    val hinge = rememberDisplayFeature(
        left = 492.dp,
        top = 0.dp,
        right = 508.dp,
        bottom = 700.dp,
        orientation = BookFoldOrientation.VERTICAL,
    )
    ListaiTheme(darkTheme = false) {
        DemoBook(
            initialPageIndex = 1,
            displayFeature = hinge,
            turn = DemoTurnPreview(
                direction = TurnDirection.FORWARD,
                fraction = 0.52f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.58f,
                verticalArcFractionY = -0.05f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical page — forward curl",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun ForwardCurlScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SINGLE,
            initialPageIndex = 5,
            turn = DemoTurnPreview(
                direction = TurnDirection.FORWARD,
                fraction = 0.47f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.61f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical page — early curl",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun EarlyCurlScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SINGLE,
            initialPageIndex = 5,
            turn = DemoTurnPreview(
                direction = TurnDirection.FORWARD,
                fraction = 0.04f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.58f,
                verticalArcFractionY = -0.05f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical page — edge anchor",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun EdgeAnchorCurlScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SINGLE,
            initialPageIndex = 5,
            turn = DemoTurnPreview(
                direction = TurnDirection.FORWARD,
                fraction = 0.28f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.52f,
            ),
        )
    }
}

@PreviewTest
@Preview(name = "Physical spread — upper arc stays bound", widthDp = 1000, heightDp = 1100, locale = "ru")
@Composable
fun UpperArcForwardCurlScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(presentation = BookPresentation.SPREAD, initialPageIndex = 5,
            turn = DemoTurnPreview(TurnDirection.FORWARD, .93f, origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = .04f, verticalArcFractionY = .14f))
    }
}

@PreviewTest
@Preview(name = "Physical spread — reverse upper arc stays bound", widthDp = 1000, heightDp = 1100, locale = "ru")
@Composable
fun UpperArcBackwardCurlScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(presentation = BookPresentation.SPREAD, initialPageIndex = 5,
            turn = DemoTurnPreview(TurnDirection.BACKWARD, .93f, origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = .04f, verticalArcFractionY = .14f))
    }
}

@PreviewTest
@Preview(
    name = "Physical page — late curl",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun LateCurlScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SINGLE,
            initialPageIndex = 5,
            turn = DemoTurnPreview(
                direction = TurnDirection.FORWARD,
                fraction = 0.97f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.61f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical page — no content pop",
    widthDp = 1000,
    heightDp = 700,
    locale = "en",
    showBackground = true,
)
@Composable
fun ContentContinuityScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SPREAD,
            initialPageIndex = 1,
            turn = DemoTurnPreview(
                direction = TurnDirection.FORWARD,
                fraction = 0.74f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.61f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical spread — backward curl",
    widthDp = 1000,
    heightDp = 700,
    locale = "en",
    showBackground = true,
)
@Composable
fun BackwardCurlScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SPREAD,
            initialPageIndex = 3,
            turn = DemoTurnPreview(
                direction = TurnDirection.BACKWARD,
                fraction = 0.44f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.42f,
                verticalArcFractionY = 0.10f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical book — front cover",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun FrontCoverScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SINGLE,
            initialBookState = PhysicalBookState.ClosedFront,
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical book — back cover",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun BackCoverScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SINGLE,
            initialBookState = PhysicalBookState.ClosedBack,
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical spread — first-page boundary",
    widthDp = 1000,
    heightDp = 700,
    locale = "en",
    showBackground = true,
)
@Composable
fun FirstPageBoundaryScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SPREAD,
            initialPageIndex = 0,
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical spread — last-page boundary",
    widthDp = 1000,
    heightDp = 700,
    locale = "en",
    showBackground = true,
)
@Composable
fun LastPageBoundaryScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SPREAD,
            initialPageIndex = FakeBookDocumentFactory.DEFAULT_LEAF_COUNT - 1,
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical spread — closing back cover",
    widthDp = 1000,
    heightDp = 700,
    locale = "en",
    showBackground = true,
)
@Composable
fun ClosingBackCoverScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SPREAD,
            initialPageIndex = FakeBookDocumentFactory.DEFAULT_LEAF_COUNT - 1,
            turn = DemoTurnPreview(
                direction = TurnDirection.FORWARD,
                fraction = 0.54f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.57f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical phone — closing front cover",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun ClosingFrontCoverOnPhoneScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SINGLE,
            initialPageIndex = 0,
            turn = DemoTurnPreview(
                direction = TurnDirection.BACKWARD,
                fraction = 0.52f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.53f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical phone — closing front cover early",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun ClosingFrontCoverEarlyOnPhoneScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SINGLE,
            initialPageIndex = 0,
            turn = DemoTurnPreview(
                direction = TurnDirection.BACKWARD,
                fraction = 0.12f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.53f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical phone — closing front cover late",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun ClosingFrontCoverLateOnPhoneScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SINGLE,
            initialPageIndex = 0,
            turn = DemoTurnPreview(
                direction = TurnDirection.BACKWARD,
                fraction = 0.88f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.53f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical phone — opening front cover",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun OpeningFrontCoverOnPhoneScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SINGLE,
            initialBookState = PhysicalBookState.ClosedFront,
            turn = DemoTurnPreview(
                direction = TurnDirection.FORWARD,
                fraction = 0.48f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.53f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical phone — front cover edge-on",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun FrontCoverEdgeOnPhoneScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SINGLE,
            initialBookState = PhysicalBookState.ClosedFront,
            turn = DemoTurnPreview(
                direction = TurnDirection.FORWARD,
                fraction = 0.50f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.53f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical spread — opening front cover",
    widthDp = 1000,
    heightDp = 700,
    locale = "en",
    showBackground = true,
)
@Composable
fun OpeningFrontCoverSpreadScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SPREAD,
            initialBookState = PhysicalBookState.ClosedFront,
            turn = DemoTurnPreview(
                direction = TurnDirection.FORWARD,
                fraction = 0.48f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.53f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical spread — closing front cover",
    widthDp = 1000,
    heightDp = 700,
    locale = "en",
    showBackground = true,
)
@Composable
fun ClosingFrontCoverSpreadScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SPREAD,
            initialPageIndex = 0,
            turn = DemoTurnPreview(
                direction = TurnDirection.BACKWARD,
                fraction = 0.52f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.53f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical phone — opening front cover early",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun OpeningFrontCoverEarlyOnPhoneScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SINGLE,
            initialBookState = PhysicalBookState.ClosedFront,
            turn = DemoTurnPreview(
                direction = TurnDirection.FORWARD,
                fraction = 0.12f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.53f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical phone — opening front cover late",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun OpeningFrontCoverLateOnPhoneScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SINGLE,
            initialBookState = PhysicalBookState.ClosedFront,
            turn = DemoTurnPreview(
                direction = TurnDirection.FORWARD,
                fraction = 0.88f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.53f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical phone — closing back cover",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun ClosingBackCoverOnPhoneScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SINGLE,
            initialPageIndex = FakeBookDocumentFactory.DEFAULT_LEAF_COUNT - 1,
            turn = DemoTurnPreview(
                direction = TurnDirection.FORWARD,
                fraction = 0.52f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.53f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical phone — closing back cover early",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun ClosingBackCoverEarlyOnPhoneScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SINGLE,
            initialPageIndex = FakeBookDocumentFactory.DEFAULT_LEAF_COUNT - 1,
            turn = DemoTurnPreview(
                direction = TurnDirection.FORWARD,
                fraction = 0.12f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.53f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical phone — closing back cover late",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun ClosingBackCoverLateOnPhoneScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SINGLE,
            initialPageIndex = FakeBookDocumentFactory.DEFAULT_LEAF_COUNT - 1,
            turn = DemoTurnPreview(
                direction = TurnDirection.FORWARD,
                fraction = 0.88f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.53f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical phone — opening back cover",
    widthDp = 412,
    heightDp = 915,
    locale = "en",
    showBackground = true,
)
@Composable
fun OpeningBackCoverOnPhoneScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SINGLE,
            initialBookState = PhysicalBookState.ClosedBack,
            turn = DemoTurnPreview(
                direction = TurnDirection.BACKWARD,
                fraction = 0.52f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.53f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical spread — closed back cover",
    widthDp = 1000,
    heightDp = 700,
    locale = "en",
    showBackground = true,
)
@Composable
fun ClosedBackCoverSpreadScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SPREAD,
            initialBookState = PhysicalBookState.ClosedBack,
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical spread — odd final page turn",
    widthDp = 1000,
    heightDp = 700,
    locale = "en",
    showBackground = true,
)
@Composable
fun OddFinalPageTurnScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SPREAD,
            leafCount = 31,
            initialPageIndex = 30,
            turn = DemoTurnPreview(
                direction = TurnDirection.FORWARD,
                fraction = 0.54f,
                origin = CurlOrigin.OUTER_EDGE,
                edgeAnchorFractionY = 0.56f,
            ),
        )
    }
}

@PreviewTest
@Preview(
    name = "Physical spread — odd back boundary",
    widthDp = 1000,
    heightDp = 700,
    locale = "en",
    showBackground = true,
)
@Composable
fun OddBackBoundaryScreenshot() {
    ListaiTheme(darkTheme = false) {
        DemoBook(
            presentation = BookPresentation.SPREAD,
            leafCount = 31,
            initialBookState = PhysicalBookState.BackBoundary,
        )
    }
}

@Composable
private fun DemoBook(
    presentation: BookPresentation? = null,
    leafCount: Int = FakeBookDocumentFactory.DEFAULT_LEAF_COUNT,
    initialPageIndex: Int = 0,
    initialBookState: PhysicalBookState? = null,
    turn: DemoTurnPreview? = null,
    displayFeature: BookDisplayFeature? = null,
) {
    val document = remember(leafCount) { FakeBookDocumentFactory.create(leafCount) }
    PhysicalBookView(
        pages = document.toDemoPages(),
        bookTitle = document.metadata.title,
        bookAuthor = document.metadata.authors.joinToString().ifBlank { null },
        forcedPresentation = presentation,
        initialPageIndex = initialPageIndex,
        initialBookState = initialBookState,
        previewTurn = turn,
        previewDisplayFeature = displayFeature,
    )
}

@Composable
private fun rememberDisplayFeature(
    left: Dp,
    top: Dp,
    right: Dp,
    bottom: Dp,
    orientation: BookFoldOrientation,
    halfOpened: Boolean = false,
): BookDisplayFeature {
    val density = LocalDensity.current
    return remember(density, left, top, right, bottom, orientation, halfOpened) {
        with(density) {
            BookDisplayFeature(
                bounds = androidx.compose.ui.geometry.Rect(
                    left = left.toPx(),
                    top = top.toPx(),
                    right = right.toPx(),
                    bottom = bottom.toPx(),
                ),
                orientation = orientation,
                separating = true,
                occluding = true,
                halfOpened = halfOpened,
            )
        }
    }
}
