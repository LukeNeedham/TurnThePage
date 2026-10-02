package com.avstaim.listai.feature.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import com.avstaim.listai.core.model.BookId

internal fun visibleReturnBookBounds(bounds: Rect?, viewport: Rect): Rect? = bounds?.takeIf {
    it.width > 0f && it.height > 0f && viewport.width > 0f && viewport.height > 0f &&
        it.left >= viewport.left - 1f && it.right <= viewport.right + 1f &&
        it.top >= viewport.top - 1f && it.bottom <= viewport.bottom + 1f
}

/** Position the real slot before the reader lowers its cover; never fly to a stale/offscreen rect. */
@Composable
internal fun PositionReturningLibraryBook(
    bookId: BookId?,
    keys: List<String>,
    columns: Int,
    listState: LazyListState,
    scrollable: Boolean,
    bounds: Map<String, Rect>,
    viewport: Rect,
    onPositioned: (BookId, Rect?) -> Unit,
) {
    PositionReturningLibraryItem(bookId?.let { "book:${it.value}" }, keys, columns, listState,
        scrollable, bounds, viewport) { _, positioned -> bookId?.let { onPositioned(it, positioned) } }
}

/** Also used by a landed stack whose old slot disappears when its source is removed. */
@Composable
internal fun PositionReturningLibraryItem(
    itemKey: String?,
    keys: List<String>,
    columns: Int,
    listState: LazyListState,
    scrollable: Boolean,
    bounds: Map<String, Rect>,
    viewport: Rect,
    onPositioned: (String, Rect?) -> Unit,
) {
    val latestOnPositioned by rememberUpdatedState(onPositioned)
    LaunchedEffect(itemKey, keys, columns, listState, scrollable, viewport) {
        val key = itemKey ?: return@LaunchedEffect
        // A previous acknowledgement belongs to the previous layout. Do not let a Fold
        // resize start (or continue) lowering toward that obsolete slot.
        latestOnPositioned(key, null)
        if (viewport.width <= 0f || viewport.height <= 0f) return@LaunchedEffect
        val index = keys.indexOf(key)
        if (index < 0) return@LaunchedEffect // The containing stack may still be opening.
        // Layout callbacks may still describe the old column count at composition time.
        // Let that layout finish before deciding whether the row needs to move.
        repeat(2) { withFrameNanos { } }
        if (visibleReturnBookBounds(bounds[key], viewport) == null && scrollable) {
            listState.scrollToItem(index / columns.coerceAtLeast(1))
        }
        // Let placement publish the new coordinates, including a newly composed lazy row.
        withFrameNanos { }
        // Keep the acknowledgement live until the return ends: a late scroll restoration
        // or placement must invalidate it rather than leaving a stale successful result.
        snapshotFlow { visibleReturnBookBounds(bounds[key], viewport) }
            .collect { measured -> latestOnPositioned(key, measured) }
    }
}
