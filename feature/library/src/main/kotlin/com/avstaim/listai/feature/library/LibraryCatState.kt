package com.avstaim.listai.feature.library

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.avstaim.listai.core.model.ShelfZoom

/** Real item bounds in the local shelf viewport, without window/root offsets. */
data class LibraryCatSupport(val key: String, val bounds: Rect, val isStack: Boolean)

internal enum class LibraryCatPhase { HIDDEN, PEEKING, CLIMBING, SLEEPING, REACTING, LEAVING }

internal data class LibraryCatState(
    val phase: LibraryCatPhase = LibraryCatPhase.HIDDEN,
    val supportKey: String? = null,
) {
    fun summon(key: String, animate: Boolean): LibraryCatState =
        if (phase != LibraryCatPhase.HIDDEN) this
        else LibraryCatState(if (animate) LibraryCatPhase.PEEKING else LibraryCatPhase.SLEEPING, key)

    fun tap(animate: Boolean): LibraryCatState =
        if (phase == LibraryCatPhase.SLEEPING && animate) copy(phase = LibraryCatPhase.REACTING) else this

    fun hide(animate: Boolean): LibraryCatState =
        if (!animate || phase == LibraryCatPhase.HIDDEN) LibraryCatState() else copy(phase = LibraryCatPhase.LEAVING)

    fun settled(): LibraryCatState = when (phase) {
        LibraryCatPhase.PEEKING -> copy(phase = LibraryCatPhase.CLIMBING)
        LibraryCatPhase.CLIMBING, LibraryCatPhase.REACTING -> copy(phase = LibraryCatPhase.SLEEPING)
        LibraryCatPhase.LEAVING -> LibraryCatState()
        else -> this
    }
}

internal data class LibraryCatPlacement(val supportKey: String, val bounds: Rect, val hitBounds: Rect, val mirrored: Boolean)

/** The body rests above the book and its tail hangs beside it, outside the opening hit area. */
internal fun libraryCatPlacement(
    supports: List<LibraryCatSupport>,
    viewport: Size,
    density: Float,
    zoom: ShelfZoom,
    preferredKey: String? = null,
    excludedBounds: List<Rect> = emptyList(),
): LibraryCatPlacement? {
    if (viewport.width <= 0f || viewport.height <= 0f || density <= 0f) return null
    val width = when (zoom) { ShelfZoom.LARGE -> 134f; ShelfZoom.MEDIUM -> 114f; ShelfZoom.SMALL -> 94f } * density
    val height = width * 150f / 256f
    val viewportRect = Rect(0f, 0f, viewport.width, viewport.height)
    val candidates = if (preferredKey != null && preferredKey != FREE_SUPPORT)
        supports.filter { it.key == preferredKey }
    else supports.sortedByDescending { it.isStack }

    fun placement(key: String, left: Float, baseline: Float, mirrored: Boolean): LibraryCatPlacement? {
        val rect = Rect(left, baseline - height * .68f, left + width, baseline + height * .32f)
        if (rect.left < 0f || rect.top < 0f || rect.right > viewportRect.right || rect.bottom > viewportRect.bottom) return null
        if (excludedBounds.any { rect.overlaps(it) }) return null
        val body = Rect(rect.left + width * if (mirrored) .17f else .02f, rect.top,
            rect.left + width * if (mirrored) .98f else .83f, baseline)
        val tail = if (mirrored) Rect(rect.left, baseline - height * .22f, rect.left + width * .17f, rect.bottom)
            else Rect(rect.left + width * .83f, baseline - height * .22f, rect.right, rect.bottom)
        if (supports.any { body.overlaps(it.bounds) || tail.overlaps(it.bounds) }) return null
        return LibraryCatPlacement(key, rect, body, mirrored)
    }

    for (support in candidates) {
        placement(support.key, support.bounds.right - width * .83f, support.bounds.top, false)?.let { return it }
        placement(support.key, support.bounds.left - width * .17f, support.bounds.top, true)?.let { return it }
    }
    if (preferredKey != null && preferredKey != FREE_SUPPORT) return null
    // A free shelf edge also works for an entirely empty library. Stay away from bottom actions
    // and from a central Fold hinge by using the leading side of the viewport.
    val margin = 24f * density
    var baseline = (viewport.height - 116f * density).coerceAtLeast(height * .68f)
    while (baseline >= height * .68f) {
        placement(FREE_SUPPORT, margin, baseline, false)?.let { return it }
        baseline -= height + 12f * density
    }
    return null
}

internal const val FREE_SUPPORT = "library-cat-free-edge"
