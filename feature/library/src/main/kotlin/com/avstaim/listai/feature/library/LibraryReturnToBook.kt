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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

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
    val latestOnPositioned by rememberUpdatedState(onPositioned)
    LaunchedEffect(bookId, keys, columns, listState, scrollable, viewport) {
        val id = bookId ?: return@LaunchedEffect
        if (viewport.width <= 0f || viewport.height <= 0f) return@LaunchedEffect
        val key = "book:${id.value}"
        val index = keys.indexOf(key)
        if (index < 0) return@LaunchedEffect // The containing stack may still be opening.
        if (visibleReturnBookBounds(bounds[key], viewport) == null && scrollable) {
            listState.scrollToItem(index / columns.coerceAtLeast(1))
        }
        // Let placement publish the new coordinates, including a newly composed lazy row.
        withFrameNanos { }
        val measured = snapshotFlow { visibleReturnBookBounds(bounds[key], viewport) }
            .filterNotNull().first()
        latestOnPositioned(id, measured)
    }
}
