package com.avstaim.listai.feature.reader.bookview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.roundToInt

/** The outside face visible when the book is fully closed. */
internal enum class ExternalBookCoverFace {
    FRONT,
    BACK,
}

/**
 * Text layouts prepared for the outside of the book.
 *
 * Keeping these results outside [DrawScope] is important while the cover is being curled: the
 * animated frame only paints already-shaped glyphs and never performs text measurement.
 */
internal data class ExternalBookCoverSurface(
    val title: TextLayoutResult?,
    val author: TextLayoutResult?,
    val preparedSize: IntSize,
)

/**
 * Prepares the title and author used by [drawExternalBookCover].
 *
 * [author] is already display-ready so callers can choose how multiple authors are joined.
 */
@Composable
internal fun rememberExternalBookCoverSurface(
    title: String,
    author: String?,
    coverSize: IntSize,
): ExternalBookCoverSurface {
    val textMeasurer = rememberTextMeasurer(cacheSize = 4)
    return remember(title, author, coverSize, textMeasurer) {
        prepareExternalBookCoverSurface(
            title = title,
            author = author,
            coverSize = coverSize,
            textMeasurer = textMeasurer,
        )
    }
}

private fun prepareExternalBookCoverSurface(
    title: String,
    author: String?,
    coverSize: IntSize,
    textMeasurer: TextMeasurer,
): ExternalBookCoverSurface {
    val width = coverSize.width.coerceAtLeast(1)
    val height = coverSize.height.coerceAtLeast(1)
    val textWidth = (width * 0.70f).roundToInt().coerceAtLeast(1)
    val titleSize = (width * 0.068f).coerceIn(24f, 43f).sp
    val authorSize = (width * 0.033f).coerceIn(13f, 20f).sp

    val titleLayout = title.trim().takeIf(String::isNotEmpty)?.let { displayTitle ->
        textMeasurer.measure(
            text = AnnotatedString(displayTitle),
            style = TextStyle(
                color = CoverLettering,
                fontFamily = FontFamily.Serif,
                fontSize = titleSize,
                fontWeight = FontWeight.SemiBold,
                lineHeight = titleSize * 1.18f,
                letterSpacing = 0.35.sp,
                textAlign = TextAlign.Center,
            ),
            overflow = TextOverflow.Ellipsis,
            maxLines = 4,
            constraints = Constraints(
                maxWidth = textWidth,
                maxHeight = (height * 0.34f).roundToInt().coerceAtLeast(1),
            ),
        )
    }
    val authorLayout = author
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { displayAuthor ->
            textMeasurer.measure(
                text = AnnotatedString(displayAuthor),
                style = TextStyle(
                    color = CoverLettering.copy(alpha = 0.82f),
                    fontFamily = FontFamily.Serif,
                    fontSize = authorSize,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.55.sp,
                    textAlign = TextAlign.Center,
                ),
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                constraints = Constraints(maxWidth = textWidth),
            )
        }

    return ExternalBookCoverSurface(
        title = titleLayout,
        author = authorLayout,
        preparedSize = IntSize(width, height),
    )
}

/**
 * Paints one outside face of the closed book. The back intentionally has no copy or ornament.
 *
 * The texture is deterministic and extremely low contrast: it reads as a clean woven binding,
 * not stains or paper grain, and needs no bitmap resource.
 */
internal fun DrawScope.drawExternalBookCover(
    surface: ExternalBookCoverSurface,
    bounds: Rect,
    face: ExternalBookCoverFace,
    side: PageSide,
    contentAlpha: Float = 1f,
) {
    if (bounds.width <= 0f || bounds.height <= 0f) return

    val alpha = contentAlpha.coerceIn(0f, 1f)
    // A case-bound board has much tighter corners than a sheet of paper. The square substrate
    // also prevents the white page underneath from peeking through anti-aliased corner pixels
    // while the cover is edge-on.
    val corner = min(bounds.width, bounds.height) * 0.010f
    val radius = CornerRadius(corner, corner)

    drawRect(
        color = CoverBoardCore,
        topLeft = bounds.topLeft,
        size = bounds.size,
        alpha = alpha,
    )

    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(CoverClothLight, CoverCloth, CoverClothDark),
            start = Offset(bounds.left, bounds.top),
            end = Offset(bounds.right, bounds.bottom),
        ),
        topLeft = bounds.topLeft,
        size = bounds.size,
        cornerRadius = radius,
        alpha = alpha,
    )

    drawCoverClothWeave(bounds = bounds, alpha = alpha)
    drawCoverSpine(
        bounds = bounds,
        spineOnLeft = side != PageSide.LEFT,
        alpha = alpha,
    )
    if (face == ExternalBookCoverFace.FRONT) {
        drawFrontCoverFrame(bounds = bounds, alpha = alpha)
    }
    drawCoverEdge(bounds = bounds, cornerRadius = radius, alpha = alpha)

    if (face == ExternalBookCoverFace.FRONT) {
        drawFrontCoverCopy(surface = surface, bounds = bounds, alpha = alpha)
    }
}

private fun DrawScope.drawCoverClothWeave(bounds: Rect, alpha: Float) {
    val inset = min(bounds.width, bounds.height) * 0.018f
    val left = bounds.left + inset
    val right = bounds.right - inset
    val top = bounds.top + inset
    val bottom = bounds.bottom - inset
    if (left >= right || top >= bottom) return

    // Fine deterministic linen reads as binding cloth without turning into dirty paper grain.
    val verticalStep = (bounds.width / 54f).coerceAtLeast(3f)
    var x = left + verticalStep
    var verticalIndex = 0
    while (x < right) {
        drawLine(
            color = if (verticalIndex % 2 == 0) {
                CoverClothThreadLight.copy(alpha = 0.036f * alpha)
            } else {
                CoverClothThreadDark.copy(alpha = 0.028f * alpha)
            },
            start = Offset(x, top),
            end = Offset(x, bottom),
            strokeWidth = 0.48f,
        )
        x += verticalStep
        verticalIndex += 1
    }

    val horizontalStep = (bounds.height / 76f).coerceAtLeast(3f)
    var y = top + horizontalStep
    var horizontalIndex = 0
    while (y < bottom) {
        drawLine(
            color = if (horizontalIndex % 2 == 0) {
                CoverClothThreadDark.copy(alpha = 0.032f * alpha)
            } else {
                CoverClothThreadLight.copy(alpha = 0.022f * alpha)
            },
            start = Offset(left, y),
            end = Offset(right, y),
            strokeWidth = 0.44f,
        )
        y += horizontalStep
        horizontalIndex += 1
    }
}

private fun DrawScope.drawFrontCoverFrame(bounds: Rect, alpha: Float) {
    val inset = min(bounds.width, bounds.height) * 0.057f
    val inner = bounds.deflate(inset)
    if (inner.width <= 0f || inner.height <= 0f) return

    val frameCorner = CornerRadius(min(inner.width, inner.height) * 0.012f)
    drawRoundRect(
        color = CoverGilt.copy(alpha = 0.46f * alpha),
        topLeft = inner.topLeft,
        size = inner.size,
        cornerRadius = frameCorner,
        style = Stroke(width = 1.05f),
    )
    val secondInset = (min(bounds.width, bounds.height) * 0.010f).coerceAtLeast(2f)
    val second = inner.deflate(secondInset)
    drawRoundRect(
        color = CoverGilt.copy(alpha = 0.18f * alpha),
        topLeft = second.topLeft,
        size = second.size,
        cornerRadius = frameCorner,
        style = Stroke(width = 0.65f),
    )
}

private fun DrawScope.drawCoverSpine(
    bounds: Rect,
    spineOnLeft: Boolean,
    alpha: Float,
) {
    val spineWidth = bounds.width * 0.085f
    val spineLeft = if (spineOnLeft) bounds.left else bounds.right - spineWidth
    val colors = if (spineOnLeft) {
        listOf(
            Color.Black.copy(alpha = 0.22f * alpha),
            Color.Black.copy(alpha = 0.08f * alpha),
            Color.Transparent,
        )
    } else {
        listOf(
            Color.Transparent,
            Color.Black.copy(alpha = 0.08f * alpha),
            Color.Black.copy(alpha = 0.22f * alpha),
        )
    }
    drawRect(
        brush = Brush.horizontalGradient(
            colors = colors,
            startX = spineLeft,
            endX = spineLeft + spineWidth,
        ),
        topLeft = Offset(spineLeft, bounds.top),
        size = Size(spineWidth, bounds.height),
    )
    val creaseX = if (spineOnLeft) bounds.left + spineWidth else bounds.right - spineWidth
    drawLine(
        color = Color.White.copy(alpha = 0.055f * alpha),
        start = Offset(creaseX, bounds.top + bounds.height * 0.025f),
        end = Offset(creaseX, bounds.bottom - bounds.height * 0.025f),
        strokeWidth = 0.75f,
    )
}

private fun DrawScope.drawCoverEdge(
    bounds: Rect,
    cornerRadius: CornerRadius,
    alpha: Float,
) {
    // Two dark passes and a restrained top highlight make the material read as a solid board,
    // not a flexible sheet painted dark.
    drawRoundRect(
        color = CoverEdgeShadow.copy(alpha = 0.90f * alpha),
        topLeft = Offset(bounds.left + 1.2f, bounds.top + 1.2f),
        size = Size(
            width = (bounds.width - 2.4f).coerceAtLeast(0f),
            height = (bounds.height - 2.4f).coerceAtLeast(0f),
        ),
        cornerRadius = cornerRadius,
        style = Stroke(width = 2.15f),
    )
    drawRoundRect(
        color = CoverEdgeHighlight.copy(alpha = 0.22f * alpha),
        topLeft = Offset(bounds.left + 3f, bounds.top + 3f),
        size = Size(
            width = (bounds.width - 6f).coerceAtLeast(0f),
            height = (bounds.height - 6f).coerceAtLeast(0f),
        ),
        cornerRadius = CornerRadius(
            x = (cornerRadius.x - 2f).coerceAtLeast(0f),
            y = (cornerRadius.y - 2f).coerceAtLeast(0f),
        ),
        style = Stroke(width = 0.72f),
    )
    val edgeInset = (cornerRadius.x + 2f).coerceAtMost(bounds.width * 0.12f)
    drawLine(
        color = CoverEdgeShadow.copy(alpha = 0.62f * alpha),
        start = Offset(bounds.left + edgeInset, bounds.bottom - 1.7f),
        end = Offset(bounds.right - edgeInset, bounds.bottom - 1.7f),
        strokeWidth = 2.8f,
    )
    drawLine(
        color = CoverEdgeHighlight.copy(alpha = 0.17f * alpha),
        start = Offset(bounds.left + edgeInset, bounds.top + 1.6f),
        end = Offset(bounds.right - edgeInset, bounds.top + 1.6f),
        strokeWidth = 1.15f,
    )
}

private fun DrawScope.drawFrontCoverCopy(
    surface: ExternalBookCoverSurface,
    bounds: Rect,
    alpha: Float,
) {
    val title = surface.title
    val author = surface.author
    if (title == null && author == null) return

    val gap = if (title != null && author != null) bounds.height * 0.072f else 0f
    val blockHeight = (title?.size?.height ?: 0) + (author?.size?.height ?: 0) + gap
    var y = bounds.top + (bounds.height - blockHeight) * 0.40f

    title?.let { layout ->
        val x = bounds.left + (bounds.width - layout.size.width) * 0.5f
        drawEmbossedText(layout = layout, topLeft = Offset(x, y), alpha = alpha)
        y += layout.size.height + gap
    }
    author?.let { layout ->
        val x = bounds.left + (bounds.width - layout.size.width) * 0.5f
        drawEmbossedText(
            layout = layout,
            topLeft = Offset(x, y),
            alpha = alpha * 0.88f,
            depth = 0.75f,
        )
    }
}

private fun DrawScope.drawEmbossedText(
    layout: TextLayoutResult,
    topLeft: Offset,
    alpha: Float,
    depth: Float = 1f,
) {
    drawText(
        textLayoutResult = layout,
        color = CoverLetterShadow,
        topLeft = topLeft + Offset(0.9f * depth, 1.25f * depth),
        alpha = 0.78f * alpha,
    )
    drawText(
        textLayoutResult = layout,
        topLeft = topLeft,
        alpha = alpha,
    )
    drawText(
        textLayoutResult = layout,
        color = Color.White,
        topLeft = topLeft - Offset(0.32f * depth, 0.45f * depth),
        alpha = 0.10f * alpha,
    )
}

private val CoverBoardCore = Color(0xFF10261F)
private val CoverClothLight = Color(0xFF315C4B)
private val CoverCloth = Color(0xFF24483B)
private val CoverClothDark = Color(0xFF173329)
private val CoverClothThreadLight = Color(0xFF9DB7A7)
private val CoverClothThreadDark = Color(0xFF061A14)
private val CoverEdgeShadow = Color(0xFF07140F)
private val CoverEdgeHighlight = Color(0xFF8CAC9B)
private val CoverGilt = Color(0xFFD8BC76)
private val CoverLettering = Color(0xFFE4D29D)
private val CoverLetterShadow = Color(0xFF09130F)
