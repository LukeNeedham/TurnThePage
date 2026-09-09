package com.avstaim.listai.feature.reader.bookview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.avstaim.listai.core.model.PageId
import com.avstaim.listai.feature.reader.R
import com.avstaim.listai.feature.reader.demo.DemoPageKind
import com.avstaim.listai.feature.reader.demo.DemoPageUiModel
import com.avstaim.listai.reader.rendering.PageSurface
import kotlin.math.max
import kotlin.math.min

internal val WhitePaper = Color(0xFFFFFFFF)
internal val PaperInk = Color(0xFF242424)
internal val PaperEdge = Color(0xFFB8B8B8)

internal data class DemoPageSurface(
    override val pageId: PageId,
    val pageNumber: Int,
    val kind: DemoPageKind,
    val title: TextLayoutResult?,
    val body: TextLayoutResult?,
    val footer: TextLayoutResult,
    val imageAlternativeText: String?,
) : PageSurface

@Composable
internal fun rememberDemoPageSurfaces(
    pages: List<DemoPageUiModel>,
    pageSize: IntSize,
): List<DemoPageSurface> {
    val textMeasurer = rememberTextMeasurer(cacheSize = pages.size * 3)
    val pageNumbers = pages.indices.map { index ->
        androidx.compose.ui.res.stringResource(R.string.reader_page_number, index + 1)
    }
    return remember(pages, pageNumbers, pageSize, textMeasurer) {
        pages.mapIndexed { index, page ->
            prepareDemoPageSurface(
                page = page,
                pageNumber = index + 1,
                pageNumberText = pageNumbers[index],
                pageSize = pageSize,
                textMeasurer = textMeasurer,
            )
        }
    }
}

private fun prepareDemoPageSurface(
    page: DemoPageUiModel,
    pageNumber: Int,
    pageNumberText: String,
    pageSize: IntSize,
    textMeasurer: TextMeasurer,
): DemoPageSurface {
    val width = pageSize.width.coerceAtLeast(1)
    val height = pageSize.height.coerceAtLeast(1)
    val horizontalPadding = (width * 0.115f).toInt().coerceAtLeast(18)
    val verticalPadding = (height * 0.075f).toInt().coerceAtLeast(20)
    val contentWidth = (width - horizontalPadding * 2).coerceAtLeast(1)
    val contentHeight = (height - verticalPadding * 2).coerceAtLeast(1)
    val compact = width < 430

    val title = page.title?.takeIf(String::isNotEmpty)?.let { text ->
        textMeasurer.measure(
            text = AnnotatedString(text),
            style = TextStyle(
                color = PaperInk,
                fontFamily = FontFamily.Serif,
                fontSize = when (page.kind) {
                    DemoPageKind.TITLE -> if (compact) 25.sp else 30.sp
                    else -> if (compact) 21.sp else 25.sp
                },
                fontWeight = FontWeight.SemiBold,
                lineHeight = if (compact) 28.sp else 34.sp,
                textAlign = TextAlign.Center,
            ),
            overflow = TextOverflow.Ellipsis,
            maxLines = 4,
            constraints = Constraints(maxWidth = contentWidth),
        )
    }

    val body = page.body.takeIf(String::isNotEmpty)?.let { text ->
        val isDisplayCopy = page.kind != DemoPageKind.TEXT
        textMeasurer.measure(
            text = AnnotatedString(text),
            style = TextStyle(
                color = if (isDisplayCopy) PaperInk.copy(alpha = 0.72f) else PaperInk,
                fontFamily = FontFamily.Serif,
                fontSize = if (isDisplayCopy) {
                    if (compact) 16.sp else 18.sp
                } else {
                    if (compact) 16.sp else 18.sp
                },
                fontStyle = if (isDisplayCopy) FontStyle.Italic else FontStyle.Normal,
                lineHeight = if (isDisplayCopy) 24.sp else if (compact) 24.sp else 27.sp,
                textAlign = if (isDisplayCopy) TextAlign.Center else TextAlign.Justify,
            ),
            overflow = TextOverflow.Ellipsis,
            maxLines = if (isDisplayCopy) 4 else 18,
            constraints = Constraints(
                maxWidth = contentWidth,
                maxHeight = (contentHeight * 0.78f).toInt().coerceAtLeast(1),
            ),
        )
    }

    val footer = textMeasurer.measure(
        text = AnnotatedString(pageNumberText),
        style = TextStyle(
            color = PaperInk.copy(alpha = 0.52f),
            fontFamily = FontFamily.Serif,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        ),
        maxLines = 1,
    )

    return DemoPageSurface(
        pageId = PageId(pageNumber.toLong()),
        pageNumber = pageNumber,
        kind = page.kind,
        title = title,
        body = body,
        footer = footer,
        imageAlternativeText = page.imageAlternativeText,
    )
}

internal fun DrawScope.drawDemoPageSurface(
    surface: DemoPageSurface,
    bounds: Rect,
    side: PageSide,
    contentAlpha: Float = 1f,
    drawPaper: Boolean = true,
) {
    if (bounds.width <= 0f || bounds.height <= 0f) return

    clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
        if (drawPaper) drawWhitePaper(bounds, side)
        drawPreparedContent(surface, bounds, contentAlpha)
    }
}

/**
 * Draws the paper substrate without allocating or measuring text in the draw pass.
 *
 * The reading surface is deliberately white. Only narrow, neutral edge shadows describe the
 * sheet's position inside the book; there is no warm tint or full-page lighting wash.
 */
internal fun DrawScope.drawWhitePaper(bounds: Rect, side: PageSide) {
    if (bounds.width <= 0f || bounds.height <= 0f) return

    val innerIsLeft = side != PageSide.LEFT
    drawRect(
        color = WhitePaper,
        topLeft = bounds.topLeft,
        size = bounds.size,
    )

    val innerX = if (innerIsLeft) bounds.left else bounds.right
    val innerDirection = if (innerIsLeft) 1f else -1f
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(
                Color.Black.copy(alpha = 0.070f),
                Color.Black.copy(alpha = 0.018f),
                Color.Transparent,
            ),
            startX = innerX,
            endX = innerX + innerDirection * bounds.width * 0.12f,
        ),
        topLeft = bounds.topLeft,
        size = bounds.size,
    )

    val outerX = if (innerIsLeft) bounds.right else bounds.left
    val outerDirection = -innerDirection
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(
                Color.Black.copy(alpha = 0.014f),
                Color.Transparent,
            ),
            startX = outerX,
            endX = outerX + outerDirection * bounds.width * 0.055f,
        ),
        topLeft = bounds.topLeft,
        size = bounds.size,
    )

    drawLine(
        color = PaperEdge.copy(alpha = 0.18f),
        start = Offset(outerX, bounds.top + 3f),
        end = Offset(outerX, bounds.bottom - 3f),
        strokeWidth = 0.8f,
    )
}

private fun DrawScope.drawPreparedContent(
    surface: DemoPageSurface,
    bounds: Rect,
    alpha: Float,
) {
    val horizontalPadding = bounds.width * 0.115f
    val topPadding = bounds.height * 0.075f
    val contentWidth = bounds.width - horizontalPadding * 2f

    when (surface.kind) {
        DemoPageKind.TITLE,
        DemoPageKind.CHAPTER,
        DemoPageKind.END,
        -> {
            val title = surface.title
            val body = surface.body
            val combinedHeight = (title?.size?.height ?: 0) +
                (body?.size?.height ?: 0) +
                if (title != null && body != null) bounds.height * 0.035f else 0f
            var y = bounds.top + (bounds.height - combinedHeight) * 0.42f
            title?.let {
                drawText(
                    textLayoutResult = it,
                    topLeft = Offset(
                        x = bounds.left + (bounds.width - it.size.width) * 0.5f,
                        y = y,
                    ),
                    alpha = alpha,
                )
                y += it.size.height + bounds.height * 0.035f
            }
            body?.let {
                drawText(
                    textLayoutResult = it,
                    topLeft = Offset(
                        x = bounds.left + (bounds.width - it.size.width) * 0.5f,
                        y = y,
                    ),
                    alpha = alpha,
                )
            }
        }

        DemoPageKind.TEXT -> surface.body?.let { body ->
            drawText(
                textLayoutResult = body,
                topLeft = Offset(bounds.left + horizontalPadding, bounds.top + topPadding),
                alpha = alpha,
            )
        }

        DemoPageKind.IMAGE -> drawDemoIllustration(
            bounds = Rect(
                left = bounds.left + horizontalPadding,
                top = bounds.top + bounds.height * 0.22f,
                right = bounds.left + horizontalPadding + contentWidth,
                bottom = bounds.bottom - bounds.height * 0.22f,
            ),
            alpha = alpha,
        )
    }

    val footer = surface.footer
    drawText(
        textLayoutResult = footer,
        topLeft = Offset(
            x = bounds.left + (bounds.width - footer.size.width) * 0.5f,
            y = bounds.bottom - footer.size.height - bounds.height * 0.035f,
        ),
        alpha = alpha,
    )
}

private fun DrawScope.drawDemoIllustration(bounds: Rect, alpha: Float) {
    val radius = min(bounds.width, bounds.height) * 0.025f
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF17233A), Color(0xFF46556A)),
            startY = bounds.top,
            endY = bounds.bottom,
        ),
        topLeft = bounds.topLeft,
        size = bounds.size,
        cornerRadius = CornerRadius(radius),
        alpha = alpha,
    )
    drawCircle(
        color = Color(0xFFFFE6A6).copy(alpha = alpha),
        radius = min(bounds.width, bounds.height) * 0.09f,
        center = Offset(bounds.left + bounds.width * 0.72f, bounds.top + bounds.height * 0.23f),
    )
    val horizonY = bounds.top + bounds.height * 0.69f
    drawLine(
        color = Color(0xFFAAB6C6).copy(alpha = 0.58f * alpha),
        start = Offset(bounds.left + bounds.width * 0.07f, horizonY),
        end = Offset(bounds.right - bounds.width * 0.07f, horizonY),
        strokeWidth = 1.5f,
    )
    val mountain = androidx.compose.ui.graphics.Path().apply {
        moveTo(bounds.left + bounds.width * 0.08f, bounds.bottom)
        lineTo(bounds.left + bounds.width * 0.38f, bounds.top + bounds.height * 0.36f)
        lineTo(bounds.left + bounds.width * 0.52f, bounds.top + bounds.height * 0.43f)
        lineTo(bounds.right - bounds.width * 0.06f, bounds.bottom)
        close()
    }
    drawPath(mountain, Color(0xFFD8C8A8).copy(alpha = alpha))
}
