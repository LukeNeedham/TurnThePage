package com.avstaim.listai.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.designsystem.PlyosFontFamily
import kotlin.math.roundToInt

internal data class ClosedBookControlPlacement(
    val bounds: Rect,
    val itemHeightPx: Float,
    val spacingPx: Float,
    val compact: Boolean,
    val overlaysCover: Boolean,
)

/** Place tabs beside a spread, or left-aligned on the cover in single-page mode. */
internal fun closedBookControlPlacement(viewportBounds: Rect, bookBounds: Rect,
    density: Float, overlayOnCover: Boolean = false, itemCount: Int = CLOSED_CONTROL_COUNT,
): ClosedBookControlPlacement? {
    if (viewportBounds.isEmpty || bookBounds.isEmpty) return null
    require(itemCount > 0)
    val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    val outerGap = 12f * safeDensity
    val spacing = if (overlayOnCover) 6f * safeDensity else 8f * safeDensity
    val minimumWidth = 48f * safeDensity
    if (overlayOnCover) {
        val availableWidth = bookBounds.width - outerGap * 2f
        if (availableWidth < minimumWidth) return null
        val width = minOf(176f * safeDensity, availableWidth)
        val compact = width < 112f * safeDensity
        var itemHeight = if (compact) 44f * safeDensity else 50f * safeDensity
        val availableHeight = bookBounds.height - outerGap * 2f
        val naturalHeight = itemHeight * itemCount + spacing * (itemCount - 1)
        if (naturalHeight > availableHeight) {
            itemHeight = (availableHeight - spacing * (itemCount - 1)) / itemCount
            if (itemHeight < 40f * safeDensity) return null
        }
        val totalHeight = itemHeight * itemCount + spacing * (itemCount - 1)
        val top = (bookBounds.center.y - totalHeight / 2f)
            .coerceIn(bookBounds.top + outerGap, bookBounds.bottom - outerGap - totalHeight)
        val left = bookBounds.left + outerGap
        return ClosedBookControlPlacement(Rect(left, top, left + width, top + totalHeight),
            itemHeight, spacing, compact, overlaysCover = true)
    }
    val leftSpace = (bookBounds.left - viewportBounds.left).coerceAtLeast(0f)
    val rightSpace = (viewportBounds.right - bookBounds.right).coerceAtLeast(0f)
    val onLeft = leftSpace >= rightSpace
    val regionLeft = if (onLeft) viewportBounds.left else bookBounds.right
    val regionRight = if (onLeft) bookBounds.left else viewportBounds.right
    val availableWidth = regionRight - regionLeft - outerGap * 2f
    if (availableWidth < minimumWidth) return null
    val width = minOf(168f * safeDensity, availableWidth)
    val compact = width < 112f * safeDensity
    var itemHeight = if (compact) 48f * safeDensity else 58f * safeDensity
    val availableHeight = viewportBounds.height - outerGap * 2f
    val naturalHeight = itemHeight * itemCount + spacing * (itemCount - 1)
    if (naturalHeight > availableHeight) {
        itemHeight = (availableHeight - spacing * (itemCount - 1)) / itemCount
        if (itemHeight < 48f * safeDensity) return null
    }
    val totalHeight = itemHeight * itemCount + spacing * (itemCount - 1)
    val minimumTop = viewportBounds.top + outerGap
    val maximumTop = viewportBounds.bottom - outerGap - totalHeight
    if (maximumTop < minimumTop) return null
    val top = (bookBounds.center.y - totalHeight / 2f).coerceIn(minimumTop, maximumTop)
    val left = (regionLeft + (regionRight - regionLeft - width) / 2f)
        .coerceIn(regionLeft + outerGap, regionRight - outerGap - width)
    return ClosedBookControlPlacement(Rect(left, top, left + width, top + totalHeight),
        itemHeight, spacing, compact, overlaysCover = false)
}

/** Loose paper tabs beside a spread or printed over the left edge of a single cover. */
@Composable
internal fun ClosedBookControls(onRead: () -> Unit, onDescription: (() -> Unit)?,
    onShelf: () -> Unit, onSettings: () -> Unit,
    onContents: () -> Unit, onBookmarks: () -> Unit,
    bookBounds: Rect, viewportBounds: Rect, overlayOnCover: Boolean,
    modifier: Modifier = Modifier,
) {
    val items = buildList {
        add(ClosedBookControl(R.string.rc1_closed_read, PaperIcon.Forward,
            "closed_book_read", onRead))
        onDescription?.let {
            add(ClosedBookControl(R.string.rc1_closed_description, PaperIcon.Description,
                "closed_book_description", it))
        }
        add(ClosedBookControl(R.string.rc1_closed_contents, PaperIcon.Contents,
            "closed_book_contents", onContents))
        add(ClosedBookControl(R.string.rc1_closed_bookmarks, PaperIcon.Bookmarks,
            "closed_book_bookmarks", onBookmarks))
        add(ClosedBookControl(R.string.rc1_closed_settings, PaperIcon.Settings,
            "closed_book_settings", onSettings))
        add(ClosedBookControl(R.string.rc1_closed_shelf, PaperIcon.Shelf,
            "closed_book_shelf", onShelf))
    }
    val density = LocalDensity.current
    val placement = closedBookControlPlacement(viewportBounds, bookBounds, density.density,
        overlayOnCover, items.size) ?: return
    Box(modifier) {
        Column(Modifier.absoluteOffset {
            IntOffset(placement.bounds.left.roundToInt(), placement.bounds.top.roundToInt())
        }.size(with(density) { placement.bounds.width.toDp() },
            with(density) { placement.bounds.height.toDp() }),
            verticalArrangement = Arrangement.spacedBy(with(density) { placement.spacingPx.toDp() })) {
            items.forEachIndexed { index, item ->
                val text = stringResource(item.label)
                val shape = RoundedCornerShape(topStart = 2.dp, topEnd = 7.dp, bottomStart = 2.dp, bottomEnd = 2.dp)
                Row(Modifier.fillMaxWidth().height(with(density) { placement.itemHeightPx.toDp() })
                    .rotate(if (index % 2 == 0) -1.1f else .8f)
                    .shadow(3.dp, shape).background(MaterialTheme.colorScheme.surface.copy(
                        alpha = if (placement.overlaysCover) .86f else .9f), shape)
                    .clickable(role = Role.Button, onClick = item.action).testTag(item.tag)
                    .semantics { contentDescription = text }
                    .padding(horizontal = if (placement.compact) 8.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (placement.compact && !placement.overlaysCover)
                        Arrangement.Center else Arrangement.spacedBy(10.dp)) {
                    PaperInkIcon(item.icon, Modifier.size(23.dp))
                    if (!placement.compact) Text(text, color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelSmall, fontFamily = PlyosFontFamily)
                }
            }
        }
    }
}

private data class ClosedBookControl(
    val label: Int,
    val icon: PaperIcon,
    val tag: String,
    val action: () -> Unit,
)

private const val CLOSED_CONTROL_COUNT = 6
