package com.avstaim.listai.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import kotlin.math.min
import kotlin.math.roundToInt

/** The outside face visible when the book is fully closed. */
enum class BookCoverFace {
    FRONT,
    BACK,
}

/**
 * Text layouts prepared for the outside of the book.
 *
 * Keeping these results outside [DrawScope] is important while the cover is being curled: the
 * animated frame only paints already-shaped glyphs and never performs text measurement.
 */
data class BookCoverSurface(
    val title: TextLayoutResult?,
    val author: TextLayoutResult?,
    val preparedSize: IntSize,
    val image: ImageBitmap? = null,
    val darkTheme: Boolean = false,
    val isReady: Boolean = true,
)

/** Restrained, unprinted board stock for the back and thin exposed edges, never a front mat. */
val BookCoverSurface.bindingColor: Color?
    get() = if (image != null) PrintedCoverBoard else null

private val PrintedCoverBoard = Color(0xff29313a)
private val CompoundTitleHyphen = Regex("(?<=\\p{L})[-\\u2010](?=\\p{L})")

/**
 * Prepares the title and author used by [drawBookCover].
 *
 * [author] is already display-ready so callers can choose how multiple authors are joined.
 */
@Composable
fun rememberBookCoverSurface(
    title: String,
    author: String?,
    coverSize: IntSize,
    image: ImageBitmap? = null,
    darkTheme: Boolean = false,
): BookCoverSurface {
    val textMeasurer = rememberTextMeasurer(cacheSize = 4)
    val density = LocalDensity.current
    return remember(title, author, coverSize, textMeasurer, image, darkTheme, density) {
        if (image != null) return@remember BookCoverSurface(null, null, coverSize, image, darkTheme)
        prepareBookCoverSurface(
            title = title,
            author = author,
            coverSize = coverSize,
            textMeasurer = textMeasurer,
            density = density,
        ).copy(image = image, darkTheme = darkTheme)
    }
}

private fun prepareBookCoverSurface(
    title: String,
    author: String?,
    coverSize: IntSize,
    textMeasurer: TextMeasurer,
    density: Density,
): BookCoverSurface {
    val width = coverSize.width.coerceAtLeast(1)
    val height = coverSize.height.coerceAtLeast(1)
    val textWidth = (width * 0.74f).roundToInt().coerceAtLeast(1)
    // Width is physical pixels. Convert exactly once: treating pixels as sp inflated lettering
    // by density * fontScale and clipped titles on real devices while previews looked plausible.
    val authorSize = with(density) { (width * 0.042f).toSp() }

    val titleLayout = title.trim().takeIf(String::isNotEmpty)?.let { displayTitle ->
        // Some Android line breakers keep Cyrillic compounds together and then split off
        // the last letter. Permit a wrap after a normal hyphen on the prepared cover only;
        // metadata, accessibility labels and intentionally non-breaking hyphens stay intact.
        val printableTitle = CompoundTitleHyphen.replace(displayTitle) { "${it.value}\u200B" }
        fun measure(sizePx: Float): TextLayoutResult {
            val titleSize = with(density) { sizePx.toSp() }
            return textMeasurer.measure(
            text = AnnotatedString(printableTitle),
            style = TextStyle(
                color = CoverLettering,
                fontFamily = PlyosFontFamily,
                fontSize = titleSize,
                fontWeight = FontWeight.SemiBold,
                lineHeight = titleSize * 1.18f,
                letterSpacing = titleSize * 0.012f,
                textAlign = TextAlign.Center,
            ),
            overflow = TextOverflow.Ellipsis,
            maxLines = 7,
            constraints = Constraints(
                maxWidth = textWidth,
                maxHeight = (height * 0.48f).roundToInt().coerceAtLeast(1),
            ),
            )
        }
        var sizePx = width * 0.077f
        var result = measure(sizePx)
        while (result.hasVisualOverflow && sizePx > width * .043f) {
            sizePx *= .92f
            result = measure(sizePx)
        }
        result
    }
    val authorLayout = author
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { displayAuthor ->
            textMeasurer.measure(
                text = AnnotatedString(displayAuthor),
                style = TextStyle(
                    color = CoverLettering.copy(alpha = 0.82f),
                    fontFamily = PlyosFontFamily,
                    fontSize = authorSize,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = authorSize * 0.015f,
                    textAlign = TextAlign.Center,
                ),
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                constraints = Constraints(maxWidth = textWidth),
            )
        }

    return BookCoverSurface(
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
fun DrawScope.drawBookCover(
    surface: BookCoverSurface,
    bounds: Rect,
    face: BookCoverFace = BookCoverFace.FRONT,
    spineOnLeft: Boolean = true,
    contentAlpha: Float = 1f,
) {
    if (bounds.width <= 0f || bounds.height <= 0f) return
    if (surface.preparedSize.width <= 0 || surface.preparedSize.height <= 0) return

    // Shape text and fit artwork in the unprojected board's coordinate space. A turning rigid
    // board must compress the entire printed surface, never re-fit its image at every angle.
    val native = Rect(Offset.Zero, Size(surface.preparedSize.width.toFloat(), surface.preparedSize.height.toFloat()))
    withTransform({
        translate(bounds.left, bounds.top)
        scale(bounds.width / native.width, bounds.height / native.height, pivot = Offset.Zero)
    }) {
        clipRect(0f, 0f, native.right, native.bottom) {
            drawBookCoverNative(surface, native, face, spineOnLeft, contentAlpha)
        }
    }
}

private fun DrawScope.drawBookCoverNative(
    surface: BookCoverSurface,
    bounds: Rect,
    face: BookCoverFace,
    spineOnLeft: Boolean,
    contentAlpha: Float,
) {

    val alpha = contentAlpha.coerceIn(0f, 1f)
    val image = surface.image
    if (image != null) {
        // A full-bleed printed jacket, not a picture mounted inside a coloured frame.
        // White only flattens actual transparency in source art; opaque images cover it fully.
        drawRect(if (face == BookCoverFace.FRONT) Color.White else PrintedCoverBoard,
            bounds.topLeft, bounds.size, alpha = alpha)
        if (face == BookCoverFace.FRONT) {
            val artwork = bookCoverArtworkRect(IntSize(image.width, image.height), bounds)
            drawImage(image, dstOffset = IntOffset(artwork.left.roundToInt(), artwork.top.roundToInt()),
                dstSize = IntSize(artwork.width.roundToInt().coerceAtLeast(1),
                    artwork.height.roundToInt().coerceAtLeast(1)), alpha = alpha)
        }
        drawPrintedCoverRelief(bounds, spineOnLeft, alpha)
        return
    }
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
        spineOnLeft = spineOnLeft,
        alpha = alpha,
    )
    if (face == BookCoverFace.FRONT) {
        drawFrontCoverFrame(bounds = bounds, alpha = alpha)
    }
    drawCoverEdge(bounds = bounds, cornerRadius = radius, alpha = alpha)

    if (face == BookCoverFace.FRONT) {
        drawFrontCoverCopy(surface = surface, bounds = bounds, alpha = alpha)
    }
}

/** Neutral light on the print itself: no tinted cloth, inset border or duplicate lettering. */
private fun DrawScope.drawPrintedCoverRelief(bounds: Rect, spineOnLeft: Boolean, alpha: Float) {
    val hingeWidth = bounds.width * .045f
    val hingeStart = if (spineOnLeft) bounds.left else bounds.right
    val hingeEnd = if (spineOnLeft) bounds.left + hingeWidth else bounds.right - hingeWidth
    drawRect(Brush.linearGradient(
        0f to Color.Black.copy(alpha = .12f * alpha),
        .55f to Color.Black.copy(alpha = .035f * alpha),
        1f to Color.Transparent,
        start = Offset(hingeStart, bounds.top), end = Offset(hingeEnd, bounds.top)),
        topLeft = Offset(min(hingeStart, hingeEnd), bounds.top),
        size = Size(hingeWidth, bounds.height))
    val bevel = (min(bounds.width, bounds.height) * .0015f).coerceIn(.5f, 1.5f)
    drawLine(Color.White.copy(alpha = .08f * alpha),
        Offset(bounds.left, bounds.top + bevel / 2), Offset(bounds.right, bounds.top + bevel / 2), bevel)
    drawLine(Color.Black.copy(alpha = .08f * alpha),
        Offset(bounds.left, bounds.bottom - bevel / 2), Offset(bounds.right, bounds.bottom - bevel / 2), bevel)
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
    surface: BookCoverSurface,
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

private val CoverBoardCore = Color(0xFF172532)
private val CoverClothLight = Color(0xFF46637B)
private val CoverCloth = Color(0xFF334F67)
private val CoverClothDark = Color(0xFF253B50)
private val CoverClothThreadLight = Color(0xFFA7BDCF)
private val CoverClothThreadDark = Color(0xFF152230)
private val CoverEdgeShadow = Color(0xFF101C28)
private val CoverEdgeHighlight = Color(0xFF9BB5CA)
private val CoverGilt = Color(0xFFB1CCDF)
private val CoverLettering = Color(0xFFE1EDF6)
private val CoverLetterShadow = Color(0xFF111B28)
