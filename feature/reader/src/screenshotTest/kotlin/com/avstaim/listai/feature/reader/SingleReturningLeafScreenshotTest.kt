package com.avstaim.listai.feature.reader

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.designsystem.PLYOS_FONT_FAMILY_ID
import com.avstaim.listai.core.designsystem.rememberPlyosTypefaceFamily
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.bookview.*
import com.avstaim.listai.reader.pagination.*
import com.avstaim.listai.reader.rendering.turn.CurlOrigin
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt

// The same printed leaf is sampled in both directions at complementary progress.
// Compare backward early/middle/late to forward late/middle/early: its spine and ink
// must agree, while the current page remains flat underneath a returning leaf.

@PreviewTest
@Preview(name = "Single leaf — forward early light", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun SingleForwardEarlyLightScreenshot() { ReturningLeafPreview(TurnDirection.FORWARD, .12f, false) }

@PreviewTest
@Preview(name = "Single leaf — forward middle light", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun SingleForwardMiddleLightScreenshot() { ReturningLeafPreview(TurnDirection.FORWARD, .5f, false) }

@PreviewTest
@Preview(name = "Single leaf — forward late light", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun SingleForwardLateLightScreenshot() { ReturningLeafPreview(TurnDirection.FORWARD, .88f, false) }

@PreviewTest
@Preview(name = "Single leaf — backward early light", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun SingleBackwardEarlyLightScreenshot() { ReturningLeafPreview(TurnDirection.BACKWARD, .12f, false) }

@PreviewTest
@Preview(name = "Single leaf — backward middle light", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun SingleBackwardMiddleLightScreenshot() { ReturningLeafPreview(TurnDirection.BACKWARD, .5f, false) }

@PreviewTest
@Preview(name = "Single leaf — backward late light", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun SingleBackwardLateLightScreenshot() { ReturningLeafPreview(TurnDirection.BACKWARD, .88f, false) }

@PreviewTest
@Preview(name = "Single leaf — forward early dark", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun SingleForwardEarlyDarkScreenshot() { ReturningLeafPreview(TurnDirection.FORWARD, .12f, true) }

@PreviewTest
@Preview(name = "Single leaf — forward middle dark", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun SingleForwardMiddleDarkScreenshot() { ReturningLeafPreview(TurnDirection.FORWARD, .5f, true) }

@PreviewTest
@Preview(name = "Single leaf — forward late dark", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun SingleForwardLateDarkScreenshot() { ReturningLeafPreview(TurnDirection.FORWARD, .88f, true) }

@PreviewTest
@Preview(name = "Single leaf — backward early dark", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun SingleBackwardEarlyDarkScreenshot() { ReturningLeafPreview(TurnDirection.BACKWARD, .12f, true) }

@PreviewTest
@Preview(name = "Single leaf — backward middle dark", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun SingleBackwardMiddleDarkScreenshot() { ReturningLeafPreview(TurnDirection.BACKWARD, .5f, true) }

@PreviewTest
@Preview(name = "Single leaf — backward late dark", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun SingleBackwardLateDarkScreenshot() { ReturningLeafPreview(TurnDirection.BACKWARD, .88f, true) }

@Composable
private fun ReturningLeafPreview(direction: TurnDirection, fraction: Float, dark: Boolean) {
    val document = remember {
        BookDocument(BookId("returning-leaf-regression"), BookMetadata("The Returning Leaf", language = "en"),
            List(32) { index -> BookBlock.Paragraph(BlockId("paragraph-$index"), listOf(InlineNode.Text(
                "Passage ${index + 1}. " +
                    "The words stay printed on the same leaf as it rises from the spine and returns to rest. ".repeat(7)))) })
    }
    ListaiTheme(darkTheme = dark) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val layout = with(density) {
                calculateBookCanvasLayout(Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()),
                    BookPresentation.SINGLE, 14.dp.toPx(), 7.dp.toPx())
            }
            val layoutKey = LayoutKey(constraints.maxWidth, constraints.maxHeight, PageMode.SINGLE,
                pageWidthPx = layout.pageWidth.roundToInt(), pageHeightPx = layout.pageHeight.roundToInt(),
                density = density.density, densityKey = density.density.toString(), textScale = density.fontScale,
                fontFamilyId = PLYOS_FONT_FAMILY_ID, darkTheme = dark, localeTag = "en")
            val typefaces = rememberPlyosTypefaceFamily()
            val initial = remember(document, layoutKey, typefaces, direction) { runBlocking {
                val pages = IncrementalPaginationEngine(AndroidBookMeasurer(document, "No image", typefaces = typefaces))
                    .paginate(document, null, layoutKey).last().pages
                pages[if (direction == TurnDirection.FORWARD) 4 else 5].start
            } }
            PhysicalBookView(emptyList(), document.metadata.title, null, document = document,
                forcedPresentation = BookPresentation.SINGLE, initialLocation = initial, darkTheme = dark,
                previewTurn = DemoTurnPreview(direction, fraction, CurlOrigin.OUTER_EDGE, edgeAnchorFractionY = .5f))
        }
    }
}
