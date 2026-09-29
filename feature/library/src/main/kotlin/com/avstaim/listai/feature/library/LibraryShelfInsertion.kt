package com.avstaim.listai.feature.library

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

internal data class ShelfInsertion(val targetKey: String, val marker: Rect)

/**
 * Resolve both covers and the space between them against stationary slots. The result
 * uses the destination's original index, matching the repository's remove/insert command.
 * Lazy rows may be absent; their durable indices still come from [keys], never visibility.
 */
internal fun shelfInsertion(
    keys: List<String>, bounds: Map<String, Rect>, source: String, pointer: Offset,
    viewport: Rect? = null,
): ShelfInsertion? {
    if (viewport != null && !viewport.contains(pointer)) return null
    val sourceIndex = keys.indexOf(source)
    if (sourceIndex < 0) return null
    val visible = bounds.entries.filter { it.key in keys && it.value.width > 0f && it.value.height > 0f }
    if (visible.isEmpty()) return null
    val hit = visible.firstOrNull { it.value.contains(pointer) }
    if (hit?.key == source) return null
    val rowAnchor = visible.minBy { entry ->
        val rect = entry.value
        when { pointer.y < rect.top -> rect.top - pointer.y; pointer.y > rect.bottom -> pointer.y - rect.bottom; else -> 0f }
    }.value
    val row = visible.filter { abs(it.value.top - rowAnchor.top) < 2f }.sortedBy { it.value.left }
    // A quick cover drop keeps the familiar "move into this slot" behaviour. Gaps and
    // row ends are explicit before/after insertions, including a partially filled last row.
    val rawSlot = if (hit != null) {
        val hitIndex = keys.indexOf(hit.key)
        hitIndex + if (sourceIndex < hitIndex) 1 else 0
    } else if (row.size == 1 && pointer.y < rowAnchor.top) {
        keys.indexOf(row.first().key)
    } else if (row.size == 1 && pointer.y > rowAnchor.bottom) {
        keys.indexOf(row.first().key) + 1
    } else {
        val right = row.firstOrNull { pointer.x < it.value.center.x }
        if (right != null) keys.indexOf(right.key) else keys.indexOf(row.last().key) + 1
    }
    val finalIndex = (rawSlot - if (sourceIndex < rawSlot) 1 else 0).coerceIn(keys.indices)
    if (finalIndex == sourceIndex) return null
    val right = row.firstOrNull { keys.indexOf(it.key) >= rawSlot }
    val left = row.lastOrNull { keys.indexOf(it.key) < rawSlot }
    val reference = right?.value ?: left?.value ?: rowAnchor
    val gap = row.zipWithNext().map { (a, b) -> b.value.left - a.value.right }.filter { it > 0f }.minOrNull()
        ?: (reference.width * .2f)
    val x = when {
        left != null && right != null -> (left.value.right + right.value.left) / 2f
        right != null -> right.value.left - gap / 2f
        else -> reference.right + gap / 2f
    }
    return ShelfInsertion(keys[finalIndex], Rect(x, rowAnchor.top, x, rowAnchor.bottom))
}
