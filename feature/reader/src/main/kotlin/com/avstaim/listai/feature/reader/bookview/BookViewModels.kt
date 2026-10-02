package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.avstaim.listai.reader.rendering.geometry.Point
import com.avstaim.listai.reader.rendering.geometry.Rect as CurlRect
import com.avstaim.listai.reader.rendering.turn.CurlOrigin
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import kotlin.math.min

internal enum class BookPresentation {
    SINGLE,
    SPREAD,
}

internal enum class PageSide {
    LEFT,
    RIGHT,
    SINGLE,
}

internal data class BookCanvasLayout(
    val presentation: BookPresentation,
    val cover: Rect,
    val leftPage: Rect?,
    val rightPage: Rect,
    val spineX: Float,
    val spineBounds: Rect?,
    val pageWidth: Float,
    val pageHeight: Float,
) {
    val bookLeft: Float get() = leftPage?.left ?: rightPage.left
    val bookRight: Float get() = rightPage.right

    fun sourcePage(direction: TurnDirection): Rect = when {
        presentation == BookPresentation.SINGLE -> rightPage
        direction == TurnDirection.FORWARD -> rightPage
        else -> requireNotNull(leftPage)
    }

    fun destinationPage(direction: TurnDirection): Rect? = when {
        presentation == BookPresentation.SINGLE -> null
        direction == TurnDirection.FORWARD -> leftPage
        else -> rightPage
    }

    fun completionPointer(
        direction: TurnDirection,
        anchorY: Double = sourcePage(direction).bottom.toDouble(),
    ): Point {
        val source = sourcePage(direction)
        val constrainedY = anchorY.coerceIn(source.top.toDouble(), source.bottom.toDouble())
        val grabPoint = Point(
            x = when (direction) {
                TurnDirection.FORWARD -> source.right.toDouble()
                TurnDirection.BACKWARD -> source.left.toDouble()
            },
            y = constrainedY,
        )
        return completionPointer(direction, grabPoint)
    }

    fun completionPointer(direction: TurnDirection, grabPoint: Point): Point {
        val source = sourcePage(direction)
        val reflectionAxisX = if (presentation == BookPresentation.SPREAD) {
            spineX.toDouble()
        } else {
            when (direction) {
                TurnDirection.FORWARD -> source.left.toDouble()
                TurnDirection.BACKWARD -> source.right.toDouble()
            }
        }
        return Point(
            x = reflectionAxisX * 2.0 - grabPoint.x,
            y = grabPoint.y.coerceIn(source.top.toDouble(), source.bottom.toDouble()),
        )
    }
}

internal data class DemoTurnPreview(
    val direction: TurnDirection,
    val fraction: Float,
    val origin: CurlOrigin = CurlOrigin.LOWER_OUTER,
    val edgeAnchorFractionY: Float = 0.52f,
    val freeAnchorFractionX: Float = 0.68f,
    val verticalArcFractionY: Float = 0f,
) {
    init {
        require(fraction in 0f..1f) { "fraction must be in 0..1" }
        require(edgeAnchorFractionY in 0f..1f) { "edgeAnchorFractionY must be in 0..1" }
        require(freeAnchorFractionX in 0f..1f) { "freeAnchorFractionX must be in 0..1" }
        require(verticalArcFractionY in -0.5f..0.5f) {
            "verticalArcFractionY must be in -0.5..0.5"
        }
    }
}

internal fun calculateBookCanvasLayout(
    canvasSize: Size,
    presentation: BookPresentation,
    outerPaddingPx: Float,
    coverInsetPx: Float,
    displayFeature: BookDisplayFeature? = null,
): BookCanvasLayout {
    val safeWidth = canvasSize.width.coerceAtLeast(1f)
    val safeHeight = canvasSize.height.coerceAtLeast(1f)
    val padding = outerPaddingPx.coerceAtLeast(0f)
    val coverInset = coverInsetPx.coerceAtLeast(0f)

    if (presentation == BookPresentation.SINGLE) {
        val viewport = singlePageViewport(
            canvasSize = Size(safeWidth, safeHeight),
            displayFeature = displayFeature,
            outerPaddingPx = padding,
        )
        val availableWidth = (viewport.width - padding * 2f).coerceAtLeast(1f)
        val availableHeight = (viewport.height - padding * 2f).coerceAtLeast(1f)
        // Reader paper follows the safe viewport. A fixed print-page aspect ratio leaves most of a
        // tall phone screen unused and is not appropriate for reflowable digital content.
        val pageWidth = availableWidth
        val pageHeight = availableHeight
        val left = viewport.left + (viewport.width - pageWidth) * 0.5f
        val top = viewport.top + (viewport.height - pageHeight) * 0.5f
        val rightPage = Rect(left, top, left + pageWidth, top + pageHeight)
        return BookCanvasLayout(
            presentation = presentation,
            cover = rightPage.inflate(coverInset),
            leftPage = null,
            rightPage = rightPage,
            spineX = rightPage.left,
            spineBounds = null,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
        )
    }

    val availableHeight = (safeHeight - padding * 2f).coerceAtLeast(1f)
    val verticalFeature = displayFeature?.takeIf {
        it.orientation == BookFoldOrientation.VERTICAL
    }
    val spineX = verticalFeature?.bounds?.center?.x
        ?.coerceIn(0f, safeWidth)
        ?: safeWidth * 0.5f
    val leftInnerEdge = if (verticalFeature?.avoidsContent == true) {
        verticalFeature.bounds.left.coerceIn(0f, safeWidth)
    } else {
        spineX
    }
    val rightInnerEdge = if (verticalFeature?.avoidsContent == true) {
        verticalFeature.bounds.right.coerceIn(0f, safeWidth)
    } else {
        spineX
    }
    val leftCapacity = (leftInnerEdge - padding).coerceAtLeast(1f)
    val rightCapacity = (safeWidth - padding - rightInnerEdge).coerceAtLeast(1f)
    // Both sheets keep identical geometry so a curl can land exactly on its destination sheet.
    // The narrower physical pane therefore defines their width, while height fills the viewport.
    val pageWidth = min(leftCapacity, rightCapacity)
    val pageHeight = availableHeight
    val top = (safeHeight - pageHeight) * 0.5f
    val leftPage = Rect(
        left = leftInnerEdge - pageWidth,
        top = top,
        right = leftInnerEdge,
        bottom = top + pageHeight,
    )
    val rightPage = Rect(
        left = rightInnerEdge,
        top = top,
        right = rightInnerEdge + pageWidth,
        bottom = top + pageHeight,
    )
    val bookRect = Rect(leftPage.left, top, rightPage.right, top + pageHeight)
    val spineBounds = verticalFeature?.let { feature ->
        Rect(
            left = feature.bounds.left.coerceIn(leftPage.right, rightPage.left),
            top = top,
            right = feature.bounds.right.coerceIn(leftPage.right, rightPage.left),
            bottom = top + pageHeight,
        )
    }

    return BookCanvasLayout(
        presentation = presentation,
        cover = bookRect.inflate(coverInset),
        leftPage = leftPage,
        rightPage = rightPage,
        spineX = spineX,
        spineBounds = spineBounds,
        pageWidth = pageWidth,
        pageHeight = pageHeight,
    )
}

internal fun singlePageViewport(
    canvasSize: Size,
    displayFeature: BookDisplayFeature?,
    outerPaddingPx: Float,
): Rect {
    val full = Rect(0f, 0f, canvasSize.width, canvasSize.height)
    val feature = displayFeature?.takeIf(BookDisplayFeature::avoidsContent) ?: return full
    val bounds = feature.bounds
    val candidates = when (feature.orientation) {
        BookFoldOrientation.VERTICAL -> listOf(
            Rect(0f, 0f, bounds.left.coerceIn(0f, canvasSize.width), canvasSize.height),
            Rect(bounds.right.coerceIn(0f, canvasSize.width), 0f, canvasSize.width, canvasSize.height),
        )

        BookFoldOrientation.HORIZONTAL -> listOf(
            Rect(0f, 0f, canvasSize.width, bounds.top.coerceIn(0f, canvasSize.height)),
            Rect(0f, bounds.bottom.coerceIn(0f, canvasSize.height), canvasSize.width, canvasSize.height),
        )
    }
    val scores = candidates.map { candidate ->
        val usableWidth = (candidate.width - outerPaddingPx * 2f).coerceAtLeast(0f)
        val usableHeight = (candidate.height - outerPaddingPx * 2f).coerceAtLeast(0f)
        usableWidth * usableHeight
    }
    return when (feature.orientation) {
        // A single physical leaf naturally occupies the right pane when both panes are equal.
        BookFoldOrientation.VERTICAL -> if (scores[1] >= scores[0]) candidates[1] else candidates[0]
        // In tabletop posture the upper pane faces the reader; prefer it when capacities tie.
        BookFoldOrientation.HORIZONTAL -> {
            val nearlyCenteredTolerance = maxOf(1f, canvasSize.height * 0.002f)
            if (candidates[0].height + nearlyCenteredTolerance >= candidates[1].height) {
                candidates[0]
            } else {
                candidates[1]
            }
        }
    }
}

internal fun Rect.toCurlRect(): CurlRect = CurlRect(
    left = left.toDouble(),
    top = top.toDouble(),
    right = right.toDouble(),
    bottom = bottom.toDouble(),
)

internal fun Point.toOffset(): Offset = Offset(x.toFloat(), y.toFloat())

internal fun Offset.toPoint(): Point = Point(x.toDouble(), y.toDouble())

internal fun lerp(start: Point, end: Point, fraction: Float): Point = Point(
    x = start.x + (end.x - start.x) * fraction,
    y = start.y + (end.y - start.y) * fraction,
)
