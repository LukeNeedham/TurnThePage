package com.avstaim.listai.feature.reader.bookview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.DpSize
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.Bookmark
import com.avstaim.listai.feature.reader.R
import com.avstaim.listai.feature.reader.bookmarkColor
import com.avstaim.listai.feature.reader.bookmarkInkColor
import kotlin.math.roundToInt

internal data class QuickBookmarkPlacement(
    val location: BookLocation,
    val bookmark: Bookmark?,
    val bounds: Rect,
)

internal enum class QuickBookmarkTopSlot {
    SPREAD_LEFT,
    SPREAD_RIGHT,
}

internal data class QuickBookmarkTopGeometry(
    val bounds: Rect,
)

/**
 * Gives the bookmark a top-edge touch target without attaching it to a side edge.
 *
 * In a spread the target sits just inside the two spine corners.
 */
internal fun quickBookmarkAtTop(
    pageBounds: Rect,
    targetSize: Float,
    topOverhang: Float,
    slot: QuickBookmarkTopSlot,
): QuickBookmarkTopGeometry {
    require(!pageBounds.isEmpty)
    require(targetSize > 0f && targetSize <= pageBounds.width && targetSize <= pageBounds.height)
    require(topOverhang >= 0f)
    val left = when (slot) {
        QuickBookmarkTopSlot.SPREAD_LEFT -> pageBounds.right - targetSize
        QuickBookmarkTopSlot.SPREAD_RIGHT -> pageBounds.left
    }.coerceIn(pageBounds.left, pageBounds.right - targetSize)
    val top = (pageBounds.top - topOverhang).coerceAtMost(pageBounds.bottom - targetSize)
    return QuickBookmarkTopGeometry(
        bounds = Rect(left, top, left + targetSize, top + targetSize),
    )
}

private data class HorizontalInterval(val start: Float, val end: Float) {
    val width: Float get() = end - start
}

/**
 * Places the single-page bookmark directly after the two left-hand page controls.
 *
 * The camera is not assumed to be centred: both every real control rectangle and every
 * DisplayCutout rectangle are removed from the available top-edge intervals. Returning null is
 * preferable to drawing the bookmark under a cutout on an exceptionally constrained viewport.
 */
internal fun quickBookmarkSingleAtTop(
    pageBounds: Rect,
    targetSize: Float,
    topOverhang: Float,
    gap: Float,
    controlBounds: List<Rect>,
    cutoutBounds: List<Rect>,
    clockBounds: Rect? = null,
    minimumTargetSize: Float = targetSize,
): QuickBookmarkTopGeometry? {
    require(minimumTargetSize > 0f && minimumTargetSize <= targetSize)
    val reserved = controlBounds + listOfNotNull(clockBounds)
    val full = findQuickBookmarkSingleAtTop(pageBounds, targetSize, topOverhang, gap, reserved, cutoutBounds)
    if (full != null || clockBounds == null) return full
    // The ribbon remains the same visual size. Only its generous touch box can shrink
    // to the other top controls' size when the clock and a camera leave a narrow interval.
    val compact = if (minimumTargetSize < targetSize) findQuickBookmarkSingleAtTop(
        pageBounds, minimumTargetSize, topOverhang, gap, reserved, cutoutBounds) else null
    // On an exceptionally narrow leaf the interactive bookmark takes priority. The
    // caller suppresses a clock intersecting this fallback instead of covering its ink.
    return compact ?: findQuickBookmarkSingleAtTop(
        pageBounds, targetSize, topOverhang, gap, controlBounds, cutoutBounds)
}

private fun findQuickBookmarkSingleAtTop(
    pageBounds: Rect,
    targetSize: Float,
    topOverhang: Float,
    gap: Float,
    controlBounds: List<Rect>,
    cutoutBounds: List<Rect>,
): QuickBookmarkTopGeometry? {
    require(!pageBounds.isEmpty)
    require(targetSize > 0f && targetSize <= pageBounds.width && targetSize <= pageBounds.height)
    require(topOverhang >= 0f)
    require(gap >= 0f)

    val top = (pageBounds.top - topOverhang).coerceAtMost(pageBounds.bottom - targetSize)
    val bottom = top + targetSize
    val obstacles = (controlBounds + cutoutBounds).mapNotNull { bounds ->
        if (bounds.bottom <= top || bounds.top >= bottom) return@mapNotNull null
        val start = maxOf(pageBounds.left, bounds.left - gap)
        val end = minOf(pageBounds.right, bounds.right + gap)
        HorizontalInterval(start, end).takeIf { it.width > 0f }
    }.sortedBy { it.start }

    val merged = mutableListOf<HorizontalInterval>()
    obstacles.forEach { obstacle ->
        val previous = merged.lastOrNull()
        if (previous == null || obstacle.start > previous.end) {
            merged += obstacle
        } else {
            merged[merged.lastIndex] = HorizontalInterval(previous.start, maxOf(previous.end, obstacle.end))
        }
    }

    val free = buildList {
        var cursor = pageBounds.left
        merged.forEach { obstacle ->
            if (obstacle.start > cursor) add(HorizontalInterval(cursor, obstacle.start))
            cursor = maxOf(cursor, obstacle.end)
        }
        if (cursor < pageBounds.right) add(HorizontalInterval(cursor, pageBounds.right))
    }.filter { it.width >= targetSize }
    if (free.isEmpty()) return null

    val leftControls = controlBounds.take(2)
    val preferredLeft = ((leftControls.maxOfOrNull { it.right } ?: pageBounds.left) + gap)
        .coerceIn(pageBounds.left, pageBounds.right - targetSize)
    val preferredInterval = free.firstOrNull { interval ->
        maxOf(interval.start, preferredLeft) + targetSize <= interval.end
    }
    val interval = preferredInterval ?: free.minBy { candidate ->
        val candidateLeft = preferredLeft.coerceIn(candidate.start, candidate.end - targetSize)
        kotlin.math.abs(candidateLeft - preferredLeft)
    }
    val left = preferredLeft.coerceIn(interval.start, interval.end - targetSize)
    return QuickBookmarkTopGeometry(Rect(left, top, left + targetSize, bottom))
}

/** A small cloth strip hanging from the page's top edge: empty with a plus, coloured when saved. */
@Composable
internal fun QuickBookmarkTab(
    placement: QuickBookmarkPlacement,
    darkTheme: Boolean,
    onAdd: (BookLocation) -> Unit,
    onRemove: (Bookmark) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val density = LocalDensity.current
    val active = placement.bookmark
    val label = stringResource(if (active == null) R.string.insert_add_bookmark else R.string.insert_remove_current)
    val interaction = remember { MutableInteractionSource() }
    val platformConfiguration = LocalViewConfiguration.current
    val exactTarget = remember(platformConfiguration) {
        object : ViewConfiguration by platformConfiguration {
            override val minimumTouchTargetSize = DpSize.Zero
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides exactTarget) {
        Box(
            modifier.absoluteOffset {
                IntOffset(placement.bounds.left.roundToInt(), placement.bounds.top.roundToInt())
            }.size(
                with(density) { placement.bounds.width.toDp() },
                with(density) { placement.bounds.height.toDp() },
            ).testTag("reader_quick_bookmark")
                .semantics { contentDescription = label }
                .clickable(
                    enabled = enabled,
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = label,
                ) {
                    if (active == null) onAdd(placement.location) else onRemove(active)
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // Keep the 32–48 dp box as a forgiving tap target, while the visible strip
                // retains the restrained proportions of the former passive top indicator.
                val ribbonWidth = minOf(14f * density.density, size.width * .44f)
                val left = (size.width - ribbonWidth) / 2f
                val top = 0f
                val bottom = minOf(30f * density.density, size.height)
                val notch = ribbonWidth * .27f
                fun ribbonPath(dx: Float = 0f, dy: Float = 0f) = Path().apply {
                    moveTo(left + dx, top + dy)
                    lineTo(left + ribbonWidth + dx, top + dy)
                    lineTo(left + ribbonWidth + dx, bottom + dy)
                    lineTo(left + ribbonWidth / 2f + dx, bottom - notch + dy)
                    lineTo(left + dx, bottom + dy)
                    close()
                }

                drawPath(ribbonPath(1.2f * density.density, 2f * density.density),
                    Color.Black.copy(alpha = if (darkTheme) .30f else .20f))
                val fill = active?.let { bookmarkColor(it, darkTheme) }
                    ?: if (darkTheme) Color(0xFF344957) else Color(0xFFDCE7EE)
                val ink = if (active != null) bookmarkInkColor(darkTheme)
                    else if (darkTheme) Color(0xFFC1D3DE) else Color(0xFF426984)
                val path = ribbonPath()
                drawPath(path, fill)
                drawPath(path, ink.copy(alpha = if (active == null) .92f else .45f),
                    style = Stroke(width = 1f * density.density))

                val cx = left + ribbonWidth / 2f
                val cy = top + (bottom - top) * .43f
                val arm = ribbonWidth * .21f
                val stroke = 1.5f * density.density
                if (active == null) {
                    drawLine(ink, Offset(cx - arm, cy), Offset(cx + arm, cy), stroke)
                    drawLine(ink, Offset(cx, cy - arm), Offset(cx, cy + arm), stroke)
                } else {
                    drawLine(ink, Offset(cx - arm, cy), Offset(cx - arm * .15f, cy + arm * .72f), stroke)
                    drawLine(ink, Offset(cx - arm * .15f, cy + arm * .72f),
                        Offset(cx + arm * 1.15f, cy - arm * .78f), stroke)
                }
            }
        }
    }
}
