package com.avstaim.listai.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.avstaim.listai.core.model.BookId

/** A move inserts into the destination's old slot, shifting the intervening books. */
internal fun moveShelfBook(ids: List<BookId>, source: BookId, destination: BookId): List<BookId> {
    val from = ids.indexOf(source)
    val to = ids.indexOf(destination)
    if (from < 0 || to < 0 || from == to) return ids
    return ids.toMutableList().apply { add(to, removeAt(from)) }
}

internal data class ShelfDrag(
    val id: BookId,
    val originalOrder: List<BookId>,
    val originalBounds: Rect,
    val start: Offset,
    val pointer: Offset = start,
    val moved: Boolean = false,
) {
    val topLeft: Offset get() = originalBounds.topLeft + pointer - start
}

/** Bounds stay in root coordinates, including during scroll; gestures live above the lazy rows. */
internal class LibraryReorderState(initialIds: List<BookId>) {
    var ids by mutableStateOf(initialIds)
    var drag by mutableStateOf<ShelfDrag?>(null)
    val bounds = mutableMapOf<BookId, Rect>()
    private var lastDestination: Pair<BookId, Rect>? = null

    fun start(pointer: Offset) {
        val entry = bounds.entries.firstOrNull { it.value.contains(pointer) && it.key in ids } ?: return
        lastDestination = null
        drag = ShelfDrag(entry.key, ids, entry.value, pointer)
    }

    fun move(delta: Offset, slop: Float) {
        val previous = drag ?: return
        val pointer = previous.pointer + delta
        drag = previous.copy(pointer = pointer, moved = previous.moved || (pointer - previous.start).getDistance() > slop)
        updateDestination()
    }

    fun updateDestination() {
        val active = drag?.takeIf { it.moved } ?: return
        val target = bounds.entries.firstOrNull { it.key != active.id && it.value.contains(active.pointer) }
        if (target == null) { lastDestination = null; return }
        val destination = target.key to target.value
        // Multiple pointer samples may arrive before Compose places the reordered row.
        if (destination == lastDestination) return
        lastDestination = destination
        ids = moveShelfBook(ids, active.id, target.key)
    }

    fun cancel() {
        drag?.let { ids = it.originalOrder }
        drag = null
        lastDestination = null
    }
}
