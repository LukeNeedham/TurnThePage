package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import com.avstaim.listai.reader.rendering.geometry.AffineTransform2D
import com.avstaim.listai.reader.rendering.geometry.PageCurlBandGeometry
import com.avstaim.listai.reader.rendering.geometry.PageCurlBandGeometryEngine
import com.avstaim.listai.reader.rendering.geometry.PageCurlGeometry
import com.avstaim.listai.reader.rendering.geometry.Point
import com.avstaim.listai.reader.rendering.geometry.Polygon
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val CoverDark = Color(0xFF10271F)
private val CoverLight = Color(0xFF315846)
private val PageEdgeLine = Color(0xFFB8B8B8)
private val CurlBandEngine = PageCurlBandGeometryEngine()

internal sealed interface BookVisualSurface {
    data class Page(val surface: DemoPageSurface) : BookVisualSurface

    /** The unprinted reverse of a real final sheet, used only before the inner back cover. */
    data object PlainPaper : BookVisualSurface

    data class Cover(
        val surface: ExternalBookCoverSurface,
        val face: ExternalBookCoverFace,
    ) : BookVisualSurface
}

internal data class VisibleBookSurfaces(
    val left: BookVisualSurface?,
    val right: BookVisualSurface?,
)

internal enum class PhysicalBookPose {
    OPEN,
    CLOSED_FRONT,
    CLOSED_BACK,
}

internal data class CurlFrame(
    val direction: TurnDirection,
    val geometry: PageCurlGeometry,
    val source: BookVisualSurface,
    val sourceBounds: Rect,
    val sourceSide: PageSide,
    val back: BookVisualSurface?,
    val backDestinationBounds: Rect?,
)

/** A rigid cover is one affine board on either side of its hinge, never a segmented curl. */
internal data class RigidCoverProjection(
    val nativeBounds: Rect,
    val projectedBounds: Rect,
    val nativeHingeX: Float,
    val projectedHingeX: Float,
    val horizontalScale: Float,
    val showsSourceFace: Boolean,
) {
    val isFinite: Boolean
        get() =
            nativeBounds.isFinite &&
                projectedBounds.isFinite &&
                nativeHingeX.isFinite() &&
                projectedHingeX.isFinite() &&
                horizontalScale.isFinite()
}

internal fun calculateRigidCoverProjection(
    sourceBounds: Rect,
    destinationBounds: Rect,
    direction: TurnDirection,
    progress: Float,
): RigidCoverProjection {
    val p = progress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val sourceHinge = when (direction) {
        TurnDirection.FORWARD -> sourceBounds.left
        TurnDirection.BACKWARD -> sourceBounds.right
    }
    val destinationHinge = when (direction) {
        TurnDirection.FORWARD -> destinationBounds.right
        TurnDirection.BACKWARD -> destinationBounds.left
    }
    val centreHinge = (sourceHinge + destinationHinge) * 0.5f
    val showsSource = p <= 0.5f
    val native = if (showsSource) sourceBounds else destinationBounds
    val nativeHinge = if (showsSource) sourceHinge else destinationHinge
    val phase = if (showsSource) p * 2f else (p - 0.5f) * 2f
    val easedPhase = phase * phase * (3f - 2f * phase)
    val projectedHinge = if (showsSource) {
        lerpFloat(sourceHinge, centreHinge, easedPhase)
    } else {
        lerpFloat(centreHinge, destinationHinge, easedPhase)
    }
    val cosineScale = abs(cos(PI.toFloat() * p)).coerceIn(0f, 1f)
    // Snap the almost edge-on board to zero. Keeping a sub-pixel textured sliver here creates
    // exactly the kind of bright/white raster wedge a rigid cover is meant to avoid.
    val scale = if (cosineScale < 0.001f) 0f else cosineScale
    val projectedLeft = projectedHinge + (native.left - nativeHinge) * scale
    val projectedRight = projectedHinge + (native.right - nativeHinge) * scale
    return RigidCoverProjection(
        nativeBounds = native,
        projectedBounds = Rect(
            left = min(projectedLeft, projectedRight),
            top = native.top,
            right = max(projectedLeft, projectedRight),
            bottom = native.bottom,
        ),
        nativeHingeX = nativeHinge,
        projectedHingeX = projectedHinge,
        horizontalScale = scale,
        showsSourceFace = showsSource,
    )
}

internal fun DrawScope.drawPhysicalBook(
    layout: BookCanvasLayout,
    visibleSurfaces: VisibleBookSurfaces,
    logicalProgress: Float,
    pose: PhysicalBookPose,
    curl: CurlFrame? = null,
) {
    when (pose) {
        PhysicalBookPose.OPEN -> drawOpenBook(
            layout = layout,
            visibleSurfaces = visibleSurfaces,
            logicalProgress = logicalProgress,
        )

        PhysicalBookPose.CLOSED_FRONT -> {
            drawClosedBook(
                surface = visibleSurfaces.right,
                bounds = layout.rightPage,
                side = closedPageSide(layout),
            )
        }

        PhysicalBookPose.CLOSED_BACK -> {
            val backBounds = if (layout.presentation == BookPresentation.SINGLE) {
                layout.rightPage
            } else {
                layout.leftPage
            }
            val backSurface = if (layout.presentation == BookPresentation.SINGLE) {
                visibleSurfaces.right
            } else {
                visibleSurfaces.left
            }
            if (backBounds != null) {
                drawClosedBook(
                    surface = backSurface,
                    bounds = backBounds,
                    side = PageSide.LEFT,
                )
            }
        }
    }

    val flexibleCurl = curl?.takeUnless { it.source is BookVisualSurface.Cover }
    flexibleCurl?.let { frame ->
        val flatPath = frame.geometry.flatFront.toPath()
        if (!flatPath.isEmpty) {
            clipPath(flatPath, clipOp = ClipOp.Intersect) {
                drawBookVisualSurface(
                    surface = frame.source,
                    bounds = frame.sourceBounds,
                    side = frame.sourceSide,
                )
            }
        }
    }

    if (pose == PhysicalBookPose.OPEN && layout.presentation == BookPresentation.SPREAD) {
        drawSpine(layout)
    }
    curl?.let { frame ->
        if (frame.source is BookVisualSurface.Cover) {
            drawRigidCoverTurn(frame)
        } else {
            drawCurl(frame)
        }
    }
}

private fun DrawScope.drawRigidCoverTurn(frame: CurlFrame) {
    val destinationBounds = frame.backDestinationBounds ?: frame.geometry.finalDestinationBounds.let {
        Rect(
            left = it.left.toFloat(),
            top = it.top.toFloat(),
            right = it.right.toFloat(),
            bottom = it.bottom.toFloat(),
        )
    }
    val projection = calculateRigidCoverProjection(
        sourceBounds = frame.sourceBounds,
        destinationBounds = destinationBounds,
        direction = frame.direction,
        progress = frame.geometry.progress.toFloat(),
    )
    if (!projection.isFinite) return

    val width = projection.projectedBounds.width
    val edgeWidth = (density * 2.2f).coerceAtLeast(1.5f)
    val face = if (projection.showsSourceFace) frame.source else frame.back ?: frame.source
    val faceSide = if (projection.showsSourceFace) {
        frame.sourceSide
    } else if (frame.direction == TurnDirection.FORWARD) {
        PageSide.LEFT
    } else {
        PageSide.RIGHT
    }

    if (width > 0.25f) {
        val travel = sin(PI.toFloat() * frame.geometry.progress.toFloat().coerceIn(0f, 1f))
        drawRect(
            color = Color.Black.copy(alpha = 0.18f + 0.11f * travel),
            topLeft = Offset(
                projection.projectedBounds.left + edgeWidth * 0.7f,
                projection.projectedBounds.top + density * (3.5f + 2f * travel),
            ),
            size = Size(
                width = projection.projectedBounds.width,
                height = projection.projectedBounds.height,
            ),
        )

        val transform = AffineTransform2D(
            m00 = projection.horizontalScale.toDouble(),
            m01 = 0.0,
            m02 = (
                projection.projectedHingeX -
                    projection.horizontalScale * projection.nativeHingeX
                ).toDouble(),
            m10 = 0.0,
            m11 = 1.0,
            m12 = 0.0,
        )
        withTransform({ transform(transform.toComposeMatrix()) }) {
            drawBookVisualSurface(
                surface = face,
                bounds = projection.nativeBounds,
                side = faceSide,
            )
        }
    }

    // Board thickness remains visible at the edge-on midpoint. It is deliberately dark and has
    // no white specular ridge, so the page underneath cannot appear as a bright corner artifact.
    drawLine(
        color = Color(0xFF07140F),
        start = Offset(projection.projectedHingeX, projection.projectedBounds.top + 1f),
        end = Offset(projection.projectedHingeX, projection.projectedBounds.bottom - 1f),
        strokeWidth = edgeWidth,
        cap = StrokeCap.Square,
    )
    if (width > edgeWidth) {
        val outerX = when {
            projection.showsSourceFace && frame.direction == TurnDirection.FORWARD ->
                projection.projectedBounds.right

            projection.showsSourceFace -> projection.projectedBounds.left
            frame.direction == TurnDirection.FORWARD -> projection.projectedBounds.left
            else -> projection.projectedBounds.right
        }
        drawLine(
            color = Color(0xFF0A1B15).copy(alpha = 0.82f),
            start = Offset(outerX, projection.projectedBounds.top + 1f),
            end = Offset(outerX, projection.projectedBounds.bottom - 1f),
            strokeWidth = (edgeWidth * 0.72f).coerceAtLeast(1f),
            cap = StrokeCap.Square,
        )
    }
}

private fun lerpFloat(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

private fun DrawScope.drawOpenBook(
    layout: BookCanvasLayout,
    visibleSurfaces: VisibleBookSurfaces,
    logicalProgress: Float,
) {
    drawBookShadowAndCover(
        layout = layout,
        hasLeftSurface = visibleSurfaces.left != null,
        hasRightSurface = visibleSurfaces.right != null,
    )
    drawPageStacks(
        layout = layout,
        logicalProgress = logicalProgress,
        hasLeftSurface = visibleSurfaces.left != null,
        hasRightSurface = visibleSurfaces.right != null,
    )

    layout.leftPage?.let { leftBounds ->
        visibleSurfaces.left?.let { surface ->
            drawBookVisualSurface(
                surface = surface,
                bounds = leftBounds,
                side = PageSide.LEFT,
            )
        }
    }
    visibleSurfaces.right?.let { surface ->
        drawBookVisualSurface(
            surface = surface,
            bounds = layout.rightPage,
            side = if (layout.presentation == BookPresentation.SINGLE) {
                PageSide.SINGLE
            } else {
                PageSide.RIGHT
            },
        )
    }
}

private fun DrawScope.drawClosedBook(
    surface: BookVisualSurface?,
    bounds: Rect,
    side: PageSide,
) {
    if (surface == null) return
    drawClosedBookShadow(bounds)
    drawBookVisualSurface(surface = surface, bounds = bounds, side = side)
}

private fun DrawScope.drawClosedBookShadow(bounds: Rect) {
    val corner = min(bounds.width, bounds.height) * 0.025f
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.31f),
        topLeft = Offset(bounds.left - 2f, bounds.top + 10f),
        size = bounds.size,
        cornerRadius = CornerRadius(corner),
    )
}

private fun closedPageSide(layout: BookCanvasLayout): PageSide =
    if (layout.presentation == BookPresentation.SINGLE) PageSide.SINGLE else PageSide.RIGHT

private fun DrawScope.drawBookVisualSurface(
    surface: BookVisualSurface,
    bounds: Rect,
    side: PageSide,
    contentAlpha: Float = 1f,
    drawSubstrate: Boolean = true,
) {
    when (surface) {
        BookVisualSurface.PlainPaper -> if (drawSubstrate) {
            drawWhitePaper(bounds = bounds, side = side)
        }

        is BookVisualSurface.Page -> drawDemoPageSurface(
            surface = surface.surface,
            bounds = bounds,
            side = side,
            contentAlpha = contentAlpha,
            drawPaper = drawSubstrate,
        )

        is BookVisualSurface.Cover -> drawExternalBookCover(
            surface = surface.surface,
            bounds = bounds,
            face = surface.face,
            side = side,
            contentAlpha = contentAlpha,
        )
    }
}

private fun DrawScope.drawBookShadowAndCover(
    layout: BookCanvasLayout,
    hasLeftSurface: Boolean,
    hasRightSurface: Boolean,
) {
    val cover = when {
        layout.presentation == BookPresentation.SINGLE || hasLeftSurface && hasRightSurface -> {
            layout.cover
        }

        hasLeftSurface -> {
            val leftPage = layout.leftPage ?: return
            Rect(layout.cover.left, layout.cover.top, leftPage.right, layout.cover.bottom)
        }

        hasRightSurface -> {
            Rect(layout.rightPage.left, layout.cover.top, layout.cover.right, layout.cover.bottom)
        }

        else -> return
    }
    val corner = min(cover.width, cover.height) * 0.018f
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.28f),
        topLeft = Offset(cover.left - 2f, cover.top + 10f),
        size = cover.size,
        cornerRadius = CornerRadius(corner),
    )
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(CoverLight, CoverDark, Color(0xFF392C21)),
            start = cover.topLeft,
            end = cover.bottomRight,
        ),
        topLeft = cover.topLeft,
        size = cover.size,
        cornerRadius = CornerRadius(corner),
    )
    drawRoundRect(
        color = Color(0xFFB79B76).copy(alpha = 0.16f),
        topLeft = Offset(cover.left + 1f, cover.top + 1f),
        size = androidx.compose.ui.geometry.Size(cover.width - 2f, cover.height - 2f),
        cornerRadius = CornerRadius(corner),
        style = Stroke(width = 1f),
    )
}

private fun DrawScope.drawPageStacks(
    layout: BookCanvasLayout,
    logicalProgress: Float,
    hasLeftSurface: Boolean,
    hasRightSurface: Boolean,
) {
    if (!hasLeftSurface && !hasRightSurface) return

    val progress = logicalProgress.coerceIn(0f, 1f)
    val leftThickness = 1f + progress * 7f
    val rightThickness = 1f + (1f - progress) * 7f
    val top = layout.rightPage.top + 4f
    val bottom = layout.rightPage.bottom - 4f
    val leftEdge = layout.leftPage?.left ?: layout.rightPage.left
    val rightEdge = layout.rightPage.right
    // A single page owns both of its physical outer edges. In a spread, an absent base surface
    // must not leave its former stack floating on the desk during a hard-cover transition.
    val drawsLeftEdge = hasLeftSurface ||
        (layout.presentation == BookPresentation.SINGLE && hasRightSurface)
    val drawsRightEdge = hasRightSurface

    repeat(4) { index ->
        val fraction = (index + 1) / 4f
        val leftOffset = leftThickness * fraction
        val rightOffset = rightThickness * fraction
        val alpha = 0.34f - index * 0.045f
        if (drawsLeftEdge) {
            drawLine(
                color = PageEdgeLine.copy(alpha = alpha),
                start = Offset(leftEdge - leftOffset, top + index * 0.6f),
                end = Offset(leftEdge - leftOffset, bottom - index * 0.5f),
                strokeWidth = 1f,
            )
        }
        if (drawsRightEdge) {
            drawLine(
                color = PageEdgeLine.copy(alpha = alpha),
                start = Offset(rightEdge + rightOffset, top + index * 0.6f),
                end = Offset(rightEdge + rightOffset, bottom - index * 0.5f),
                strokeWidth = 1f,
            )
        }

        val bottomSegment = when {
            layout.presentation == BookPresentation.SINGLE && hasRightSurface ->
                Triple(layout.rightPage.left + 3f, layout.rightPage.right - 3f, rightOffset)

            hasLeftSurface && hasRightSurface ->
                Triple(leftEdge + 3f, rightEdge - 3f, rightOffset)

            hasLeftSurface -> layout.leftPage?.let { leftPage ->
                Triple(leftPage.left + 3f, leftPage.right - 3f, leftOffset)
            }

            hasRightSurface ->
                Triple(layout.rightPage.left + 3f, layout.rightPage.right - 3f, rightOffset)

            else -> null
        }
        bottomSegment?.let { (startX, endX, thickness) ->
            drawLine(
                color = PageEdgeLine.copy(alpha = alpha * 0.8f),
                start = Offset(startX, layout.rightPage.bottom + thickness * 0.55f),
                end = Offset(endX, layout.rightPage.bottom + thickness * 0.55f),
                strokeWidth = 0.8f,
            )
        }
    }
}

private fun DrawScope.drawSpine(layout: BookCanvasLayout) {
    val halfWidth = min(density * 10f, layout.pageWidth * 0.06f)
    val top = layout.rightPage.top
    val bottom = layout.rightPage.bottom
    val physicalSpine = layout.spineBounds
    val spineLeft = min(layout.spineX - halfWidth, physicalSpine?.left ?: layout.spineX)
    val spineRight = max(layout.spineX + halfWidth, physicalSpine?.right ?: layout.spineX)
    drawRect(
        brush = Brush.horizontalGradient(
            0f to Color.Transparent,
            0.24f to Color.Black.copy(alpha = 0.018f),
            0.43f to Color.Black.copy(alpha = 0.065f),
            0.50f to Color.Black.copy(alpha = 0.14f),
            0.57f to Color.Black.copy(alpha = 0.060f),
            0.76f to Color.Black.copy(alpha = 0.014f),
            1f to Color.Transparent,
            startX = spineLeft,
            endX = spineRight,
        ),
        topLeft = Offset(spineLeft, top),
        size = androidx.compose.ui.geometry.Size(spineRight - spineLeft, bottom - top),
    )
    drawLine(
        color = Color.White.copy(alpha = 0.085f),
        start = Offset(layout.rightPage.left + 0.6f, top + 3f),
        end = Offset(layout.rightPage.left + 0.6f, bottom - 3f),
        strokeWidth = 0.55f,
    )
    if (physicalSpine != null && physicalSpine.width > 0f) {
        drawLine(
            color = Color.Black.copy(alpha = 0.10f),
            start = Offset(layout.leftPage?.right ?: layout.spineX, top + 2f),
            end = Offset(layout.leftPage?.right ?: layout.spineX, bottom - 2f),
            strokeWidth = 0.7f,
        )
        drawLine(
            color = Color.Black.copy(alpha = 0.10f),
            start = Offset(layout.rightPage.left, top + 2f),
            end = Offset(layout.rightPage.left, bottom - 2f),
            strokeWidth = 0.7f,
        )
    }
}

private fun DrawScope.drawCurl(frame: CurlFrame) {
    val geometry = frame.geometry
    if (geometry.reflectedBack.points.size < 3) return

    val backPath = geometry.reflectedBack.toPath()
    val foldStart = geometry.fold.start.toOffset()
    val foldEnd = geometry.fold.end.toOffset()
    val normal = geometry.fold.normal
    val lift = geometry.lift.toFloat()
    val pageWidth = frame.sourceBounds.width
    val midpoint = foldMidpoint(foldStart, foldEnd)
    val normalOffset = normal.toOffset()

    // The cast shadow falls onto the flat sheet. It is broad, neutral and independent from the
    // contact shadow drawn at the actual paper-to-paper boundary below.
    val castWidth = max(10f, pageWidth * (0.035f + 0.055f * lift))
    clipRect(
        left = frame.sourceBounds.left,
        top = frame.sourceBounds.top,
        right = frame.sourceBounds.right,
        bottom = frame.sourceBounds.bottom,
    ) {
        drawFoldBand(
            foldStart = foldStart,
            foldEnd = foldEnd,
            normal = normal,
            negativeWidth = 0.75f,
            positiveWidth = castWidth,
            brush = Brush.linearGradient(
                0f to Color.Black.copy(alpha = 0.11f + 0.055f * lift),
                0.14f to Color.Black.copy(alpha = 0.075f + 0.030f * lift),
                0.52f to Color.Black.copy(alpha = 0.026f + 0.020f * lift),
                1f to Color.Transparent,
                start = midpoint,
                end = midpoint + normalOffset * castWidth,
            ),
        )
    }

    val curlBand = CurlBandEngine.calculate(
        geometry = geometry,
        stripCount = 12,
        minimumWidth = max(density * 10f, pageWidth * 0.12f).toDouble(),
    )
    if (curlBand.isActive) {
        drawCurvedSheet(frame, curlBand)
    } else {
        drawSheetPiece(
            frame = frame,
            clip = geometry.reflectedBack,
            sourceToDevice = geometry.currentReflection,
            destinationToDevice = geometry.destinationToReflectedBack,
            frontFacing = false,
        )
    }

    // A single continuous lighting field sits above all affine pieces. It hides tiny raster seams
    // while preserving the readable content that is already attached to the moving paper.
    val shadedDepth = max(28f, pageWidth * 0.78f)
    drawPath(
        path = backPath,
        brush = Brush.linearGradient(
            0f to Color.Black.copy(alpha = 0.085f + 0.035f * lift),
            0.07f to Color.White.copy(alpha = 0.070f + 0.035f * lift),
            0.24f to Color.Transparent,
            0.78f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.018f),
            start = midpoint,
            end = midpoint - normalOffset * shadedDepth,
        ),
    )

    drawPath(
        path = backPath,
        color = Color.Black.copy(alpha = 0.16f),
        style = Stroke(width = 0.58f),
    )

    // A narrow neutral contact shadow joins the two surfaces; white highlights come from the
    // lighting field above and never tint the paper.
    val contactNegativeWidth = 1.25f + 2.25f * lift
    val contactPositiveWidth = 0.9f + 1.6f * lift
    clipRect(
        left = frame.sourceBounds.left,
        top = frame.sourceBounds.top,
        right = frame.sourceBounds.right,
        bottom = frame.sourceBounds.bottom,
    ) {
        drawFoldBand(
            foldStart = foldStart,
            foldEnd = foldEnd,
            normal = normal,
            negativeWidth = contactNegativeWidth,
            positiveWidth = contactPositiveWidth,
            brush = Brush.linearGradient(
                0f to Color.Transparent,
                0.34f to Color.Black.copy(alpha = 0.050f + 0.045f * lift),
                0.62f to Color.Black.copy(alpha = 0.13f + 0.080f * lift),
                0.82f to Color.Black.copy(alpha = 0.055f + 0.035f * lift),
                1f to Color.Transparent,
                start = midpoint - normalOffset * contactNegativeWidth,
                end = midpoint + normalOffset * contactPositiveWidth,
            ),
        )
    }

    val ridge = Path().apply {
        val middle = midpoint - normalOffset * (3f * lift)
        moveTo(foldStart.x, foldStart.y)
        quadraticTo(middle.x, middle.y, foldEnd.x, foldEnd.y)
    }
    drawPath(
        ridge,
        Color.White.copy(alpha = 0.16f * lift),
        style = Stroke(width = 0.7f + 0.45f * lift, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawCurvedSheet(
    frame: CurlFrame,
    curlBand: PageCurlBandGeometry,
) {
    curlBand.rigidRemainder?.takeIf { it.deformed.points.size >= 3 }?.let { remainder ->
        drawSheetPiece(
            frame = frame,
            clip = remainder.deformed,
            sourceToDevice = remainder.sourceToDeformed,
            destinationToDevice = remainder.destinationToDeformed,
            frontFacing = false,
            drawContent = true,
        )
    }

    // Draw lower pseudo-depth first. Where the 2D projection folds over itself, the crest then
    // naturally covers the sheet below instead of exposing the former hard-reflection ghost.
    curlBand.strips
        .asSequence()
        .filter { it.deformed.points.size >= 3 }
        .sortedBy { strip ->
            val u = ((strip.normalStart + strip.normalEnd) * 0.5 / curlBand.width)
                .coerceIn(0.0, 1.0)
            sin(PI * u) * curlBand.lift
        }
        .forEach { strip ->
            drawSheetPiece(
                frame = frame,
                clip = strip.deformed,
                sourceToDevice = strip.sourceToDeformed,
                destinationToDevice = strip.destinationToDeformed,
                frontFacing = false,
                drawContent = true,
            )

            val u = ((strip.normalStart + strip.normalEnd) * 0.5 / curlBand.width)
                .toFloat()
                .coerceIn(0f, 1f)
            val crest = sin(PI.toFloat() * u) * curlBand.lift.toFloat()
            drawPath(
                path = strip.deformed.toPath(),
                color = if (u < 0.18f) {
                    Color.Black.copy(alpha = (0.18f - u) * 0.16f * curlBand.lift.toFloat())
                } else {
                    Color.White.copy(alpha = 0.030f * crest)
                },
            )
        }
}

private fun DrawScope.drawSheetPiece(
    frame: CurlFrame,
    clip: Polygon,
    sourceToDevice: AffineTransform2D,
    destinationToDevice: AffineTransform2D,
    frontFacing: Boolean,
    drawContent: Boolean = true,
) {
    if (clip.points.size < 3) return
    val piecePath = clip.toPath()
    val backSurface = frame.back
    val destination = frame.backDestinationBounds
    clipPath(piecePath) {
        if (!drawContent) return@clipPath
        when {
            frontFacing -> withTransform({ transform(sourceToDevice.toComposeMatrix()) }) {
                drawBookVisualSurface(
                    surface = frame.source,
                    bounds = frame.sourceBounds,
                    side = frame.sourceSide,
                )
            }

            backSurface != null && destination != null -> {
                withTransform({ transform(destinationToDevice.toComposeMatrix()) }) {
                    drawBookVisualSurface(
                        surface = backSurface,
                        bounds = destination,
                        side = if (frame.direction == TurnDirection.FORWARD) {
                            PageSide.LEFT
                        } else {
                            PageSide.RIGHT
                        },
                    )
                }
            }

            else -> withTransform({ transform(sourceToDevice.toComposeMatrix()) }) {
                drawBookVisualSurface(
                    surface = frame.source,
                    bounds = frame.sourceBounds,
                    side = frame.sourceSide,
                    contentAlpha = if (frame.source is BookVisualSurface.Page) 0.065f else 1f,
                )
            }
        }
    }
}

private fun DrawScope.drawFoldBand(
    foldStart: Offset,
    foldEnd: Offset,
    normal: Point,
    negativeWidth: Float,
    positiveWidth: Float,
    brush: Brush,
) {
    if (foldStart == foldEnd) return
    val n = normal.toOffset()
    val path = Path().apply {
        moveTo(
            foldStart.x - n.x * negativeWidth,
            foldStart.y - n.y * negativeWidth,
        )
        lineTo(
            foldEnd.x - n.x * negativeWidth,
            foldEnd.y - n.y * negativeWidth,
        )
        lineTo(
            foldEnd.x + n.x * positiveWidth,
            foldEnd.y + n.y * positiveWidth,
        )
        lineTo(
            foldStart.x + n.x * positiveWidth,
            foldStart.y + n.y * positiveWidth,
        )
        close()
    }
    drawPath(path, brush)
}

private fun Polygon.toPath(): Path = Path().apply {
    points.firstOrNull()?.let { first ->
        moveTo(first.x.toFloat(), first.y.toFloat())
        points.drop(1).forEach { point -> lineTo(point.x.toFloat(), point.y.toFloat()) }
        close()
    }
}

private fun Polygon.composeBounds(): Rect {
    if (points.isEmpty()) return Rect.Zero
    return Rect(
        left = points.minOf(Point::x).toFloat(),
        top = points.minOf(Point::y).toFloat(),
        right = points.maxOf(Point::x).toFloat(),
        bottom = points.maxOf(Point::y).toFloat(),
    )
}

private fun foldMidpoint(start: Offset, end: Offset): Offset = Offset(
    x = (start.x + end.x) * 0.5f,
    y = (start.y + end.y) * 0.5f,
)

private operator fun Offset.times(scale: Float): Offset = Offset(x * scale, y * scale)

/**
 * Converts the rendering module's row-equation affine transform to Compose's column-addressed
 * [Matrix] convention. Compose maps `x' = m[0,0]x + m[1,0]y + m[3,0]` and the equivalent y row.
 */
internal fun AffineTransform2D.toComposeMatrix(): Matrix = Matrix().also { matrix ->
    matrix[0, 0] = m00.toFloat()
    matrix[1, 0] = m01.toFloat()
    matrix[3, 0] = m02.toFloat()
    matrix[0, 1] = m10.toFloat()
    matrix[1, 1] = m11.toFloat()
    matrix[3, 1] = m12.toFloat()
}
