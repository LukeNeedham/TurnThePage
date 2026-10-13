package com.avstaim.listai.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class ReaderCutoutInsets(val left: Float = 0f, val top: Float = 0f,
    val right: Float = 0f, val bottom: Float = 0f, val cutouts: List<Rect> = emptyList())

internal data class ReaderTopControlLayout(
    val contentTop: Float,
    val buttons: List<Rect>,
    val onCoverMaterial: Boolean = false,
    val onWhitePaper: Boolean = false,
    val clockBounds: Rect? = null,
    val touchBounds: List<Rect> = buttons,
)

/** Two/three ink marks at the paper edges with the camera and fold kept clear. */
internal fun readerTopControlLayout(width: Float, insets: ReaderCutoutInsets, density: Float,
    paperBounds: List<Rect> = listOf(Rect(0f, 0f, width, 10000f)),
): ReaderTopControlLayout {
    if (paperBounds.isEmpty()) return ReaderTopControlLayout(0f, emptyList())
    val gap = 4f * density
    val narrowestPaper = paperBounds.minOf { it.width }
    val touch = minOf(32f * density, ((narrowestPaper - gap * 6) / 5f)).coerceAtLeast(22f * density)
    val initialTop = paperBounds.maxOf { it.top } + gap
    fun candidates(top: Float): List<Rect> {
        var intervals = paperBounds.sortedBy { it.left }.mapNotNull { paper ->
            val sideMargin = minOf(12f * density, ((paper.width - touch * 5 - gap * 4) / 2)
                .coerceAtLeast(gap))
            val left = maxOf(paper.left + sideMargin, insets.left + gap)
            val right = minOf(paper.right - sideMargin, width - insets.right - gap)
            (left to right).takeIf { right - left >= touch && top + touch <= paper.bottom }
        }
        insets.cutouts.filter { it.bottom > top && it.top < top + touch }.forEach { cutout ->
            intervals = intervals.flatMap { (a, b) ->
                val start = cutout.left - gap
                val end = cutout.right + gap
                if (end <= a || start >= b) listOf(a to b)
                else buildList { if (start > a) add(a to minOf(start, b)); if (end < b) add(maxOf(end, a) to b) }
            }
        }
        return intervals.flatMap { (a, b) ->
            val count = ((b - a + gap) / (touch + gap)).toInt().coerceAtLeast(0)
            if (count == 0) emptyList() else {
                // Anchor at the actual paper edges; the free middle is deliberately left empty.
                val spacing = if (count == 1) 0f else (b - a - touch) / (count - 1)
                List(count) { index -> Rect(a + index * spacing, top, a + index * spacing + touch, top + touch) }
            }
        }
    }
    var top = initialTop
    var slots = candidates(top)
    if (slots.size < 5) {
        // An exceptionally wide notch may leave too little paper beside it.
        top = maxOf(top, insets.top, insets.cutouts.filter { it.bottom > top && it.top < top + touch }
            .maxOfOrNull { it.bottom + gap } ?: top)
        slots = candidates(top)
    }
    val buttons = if (slots.size >= 5) slots.take(2) + slots.takeLast(3) else emptyList()
    val contentTop = maxOf(top + touch, insets.top) + 14f * density
    val clockWidth = 82f * density
    val minimumClockWidth = 64f * density
    val clockHeight = 12f * density
    // Keep the clock in the printed controls' row, close to the paper edge. Anchoring it
    // to contentTop put its lower edge directly against the book text. The clock never
    // changes the text inset, including when a notch leaves no room for it in this row.
    val clockTop = top + (touch - clockHeight) / 2
    val clock = paperBounds.sortedByDescending { it.width }.firstNotNullOfOrNull { paper ->
        if (clockTop < paper.top || clockTop + clockHeight > paper.bottom) return@firstNotNullOfOrNull null
        var intervals = listOf(maxOf(paper.left + gap, insets.left + gap) to
            minOf(paper.right - gap, width - insets.right - gap))
        (buttons + insets.cutouts).filter { it.bottom > clockTop && it.top < clockTop + clockHeight }
            .forEach { occupied ->
                intervals = intervals.flatMap { (a, b) ->
                    val start = occupied.left - gap
                    val end = occupied.right + gap
                    if (end <= a || start >= b) listOf(a to b)
                    else buildList {
                        if (start > a) add(a to minOf(start, b))
                        if (end < b) add(maxOf(end, a) to b)
                    }
                }
            }
        intervals.filter { (a, b) -> b - a >= minimumClockWidth }.map { (a, b) ->
            val availableWidth = minOf(clockWidth, b - a)
            val center = paper.center.x.coerceIn(a + availableWidth / 2, b - availableWidth / 2)
            Rect(center - availableWidth / 2, clockTop, center + availableWidth / 2, clockTop + clockHeight)
        }.minByOrNull { abs(it.center.x - paper.center.x) }
    }
    // A small invisible allowance makes the ink easier to tap without moving it,
    // increasing the text inset, or reaching into a cutout or neighbouring action.
    val touchBounds = buttons.map { button ->
        val paper = paperBounds.first { it.contains(button.center) }
        val allowance = 2f * density
        var target = Rect(maxOf(paper.left, button.left - allowance),
            maxOf(paper.top, button.top - allowance),
            minOf(paper.right, button.right + allowance),
            minOf(paper.bottom, button.bottom + allowance))
        val obstacles = buttons.filter { it != button } + insets.cutouts + listOfNotNull(clock)
        obstacles.forEach { obstacle ->
            if (target.overlaps(obstacle)) {
                target = when {
                    obstacle.right <= button.left -> target.copy(left = maxOf(target.left, obstacle.right))
                    obstacle.left >= button.right -> target.copy(right = minOf(target.right, obstacle.left))
                    obstacle.bottom <= button.top -> target.copy(top = maxOf(target.top, obstacle.bottom))
                    obstacle.top >= button.bottom -> target.copy(bottom = minOf(target.bottom, obstacle.top))
                    else -> button
                }
            }
        }
        target
    }
    return ReaderTopControlLayout(contentTop, buttons, clockBounds = clock, touchBounds = touchBounds)
}

@Composable
internal fun ReaderTopControls(
    layout: ReaderTopControlLayout,
    darkTheme: Boolean,
    onBookmarks: () -> Unit,
    onToggleTheme: () -> Unit,
    onContents: () -> Unit,
    onCloseBook: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onMenu: () -> Unit = {},
    showClock: Boolean = false,
    active: Boolean = true,
) {
    val density = LocalDensity.current
    val labels = listOf(stringResource(R.string.reader_top_close), stringResource(R.string.reader_top_contents),
        stringResource(R.string.rc1_reader_menu),
        stringResource(if (darkTheme) R.string.reader_top_light else R.string.reader_top_dark),
        stringResource(R.string.reader_top_bookmarks))
    val icons = listOf(PaperIcon.Shelf, PaperIcon.Contents, PaperIcon.Settings, PaperIcon.Appearance, PaperIcon.Bookmarks)
    val tags = listOf("reader_top_close", "reader_top_contents", "reader_top_settings", "reader_top_theme", "reader_top_bookmarks")
    val actions = listOf(onCloseBook, onContents, onMenu, onToggleTheme, onBookmarks)
    val platformConfiguration = LocalViewConfiguration.current
    val exactTargets = remember(platformConfiguration) {
        object : ViewConfiguration by platformConfiguration {
            // Expanding a compact target through a camera notch would violate the layout's
            // cutout exclusions. These controls use their exact allocated rectangles.
            override val minimumTouchTargetSize = DpSize.Zero
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides exactTargets) {
      Box(modifier) {
        if (showClock) layout.clockBounds?.let { bounds ->
            ReaderClock(active, if ((darkTheme && !layout.onWhitePaper) || layout.onCoverMaterial)
                androidx.compose.ui.graphics.Color(0xFF98A5B0) else androidx.compose.ui.graphics.Color(0xFF71808B),
                Modifier.absoluteOffset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                    .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() }))
        }
        layout.buttons.forEachIndexed { index, bounds ->
            val target = layout.touchBounds[index]
            Box(Modifier.absoluteOffset { IntOffset(target.left.roundToInt(), target.top.roundToInt()) }
                .size(with(density) { target.width.toDp() }, with(density) { target.height.toDp() })
                .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() },
                    indication = null, role = Role.Button, onClickLabel = labels[index], onClick = actions[index])
                .semantics { contentDescription = labels[index] }
                .testTag(tags[index])) {
                Box(Modifier.absoluteOffset {
                    IntOffset(bounds.left.roundToInt() - target.left.roundToInt(),
                        bounds.top.roundToInt() - target.top.roundToInt())
                }.size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() }),
                    contentAlignment = Alignment.Center) {
                    PaperInkIcon(icons[index], Modifier.size(with(density) {
                        minOf(19.dp.toPx(), bounds.height * .72f, bounds.width * .72f).toDp()
                    }), color = if ((darkTheme && !layout.onWhitePaper) || layout.onCoverMaterial) androidx.compose.ui.graphics.Color(0xFFA6B3BF)
                        else androidx.compose.ui.graphics.Color(0xFF63717B))
                }
            }
        }
      }
    }
}
