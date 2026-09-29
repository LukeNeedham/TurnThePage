package com.avstaim.listai.feature.library

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.avstaim.listai.core.model.ShelfZoom

internal data class ShelfItemDrag(
    val key: String,
    val originalKeys: List<String>,
    val bounds: Rect,
    val start: Offset,
    val pointer: Offset = start,
    val moved: Boolean = false,
    val dropKey: String? = null,
    val hoverKey: String? = null,
    val hoverStartedAt: Long = 0,
    val armed: Boolean = false,
) { val topLeft get() = bounds.topLeft + pointer - start }

/**
 * One monotonic timeline for event timestamps, stationary timeouts and scrolling layouts.
 * Injected/queued events may lag behind a completed timeout; they must never rewind a dwell.
 */
internal class LibraryGestureClock(private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private var logicalTime = 0L
    private var sampledAt = elapsedMillis()

    fun start(eventTime: Long): Long {
        logicalTime = eventTime
        sampledAt = elapsedMillis()
        return logicalTime
    }

    fun now(): Long {
        val sample = elapsedMillis()
        logicalTime += (sample - sampledAt).coerceAtLeast(0)
        sampledAt = sample
        return logicalTime
    }

    fun advanceTo(eventTime: Long): Long {
        logicalTime = maxOf(now(), eventTime)
        return logicalTime
    }
}

/** The only arbiter for shelf tap/hold/drag/group/pinch/pull. Nothing is durable until release. */
internal class LibraryShelfGestureState {
    val clock = LibraryGestureClock()
    var keys by mutableStateOf(emptyList<String>())
    val bounds = mutableStateMapOf<String, Rect>()
    var drag by mutableStateOf<ShelfItemDrag?>(null)
    private var lastDestination: Pair<String, Rect>? = null

    fun start(key: String, pointer: Offset) {
        val rect = bounds[key] ?: return
        lastDestination = null
        drag = ShelfItemDrag(key, keys, rect, pointer)
    }

    fun move(pointer: Offset, slop: Float, time: Long, canGroup: (String, String) -> Boolean) {
        val old = drag ?: return
        val moved = old.moved || (pointer - old.start).getDistance() > slop
        var next = old.copy(pointer = pointer, moved = moved)
        if (moved) {
            val target = bounds.entries.firstOrNull { it.key != old.key && it.key in keys && it.value.contains(pointer) }
            val groupable = target != null && canGroup(old.key, target.key)
            val center = target?.let { entry ->
                val rect = entry.value
                if (entry.key.startsWith("stack:")) {
                    // A physical stack lies at the bottom of its navigation slot. Its spines
                    // and bottom edge are the obvious drop target, not an invisible book center.
                    val inset = if (old.hoverKey == entry.key) .02f else .06f
                    Rect(rect.left + rect.width * inset, rect.top + rect.height * .1f,
                        rect.right - rect.width * inset, rect.bottom)
                } else {
                    // Creating a new group remains deliberate; tolerate modest center jitter.
                    val horizontalInset = if (old.hoverKey == entry.key) .18f else .23f
                    val verticalInset = if (old.hoverKey == entry.key) .15f else .2f
                    Rect(rect.left + rect.width * horizontalInset, rect.top + rect.height * verticalInset,
                        rect.right - rect.width * horizontalInset, rect.bottom - rect.height * verticalInset)
                }
            }
            val hover = target?.takeIf { groupable && center?.contains(pointer) == true }?.key
            next = next.copy(dropKey = target?.key)
            next = if (hover != old.hoverKey) next.copy(hoverKey = hover, hoverStartedAt = time, armed = false) else next
            if (!groupable) {
                val destination = target?.let { it.key to it.value }
                if (destination != null && destination != lastDestination) {
                    val from = keys.indexOf(old.key); val to = keys.indexOf(destination.first)
                    if (from >= 0 && to >= 0) keys = keys.toMutableList().apply { add(to, removeAt(from)) }
                }
                lastDestination = destination
            } else {
                // Do not move a possible grouping target when entering its outer edge. A real
                // finger crosses that edge before reaching the center; moving it here makes
                // the target escape before the dwell can start. Quick drops still reorder.
                lastDestination = null
            }
        }
        drag = next
    }

    fun refreshAfterScroll(slop: Float, canGroup: (String, String) -> Boolean) {
        drag?.let { move(it.pointer, slop, clock.now(), canGroup) }
    }

    fun armHover(time: Long): Boolean {
        val current = drag ?: return false
        if (current.hoverKey == null || current.armed || time - current.hoverStartedAt < hoverDelay(current.hoverKey)) return false
        drag = current.copy(armed = true)
        return true
    }

    fun release(): ShelfItemDrag? = drag.also { drag = null; lastDestination = null }
    fun cancel() { drag?.let { keys = it.originalKeys }; drag = null; lastDestination = null }
    companion object {
        const val GROUP_HOVER_MS = 550L
        // Existing containers accept release immediately. Passing over one commits nothing.
        fun hoverDelay(key: String): Long = if (key.startsWith("stack:")) 0L else GROUP_HOVER_MS
    }
}

internal fun Modifier.libraryShelfInput(
    enabled: Boolean,
    state: LibraryShelfGestureState,
    origin: () -> Offset,
    zoom: () -> ShelfZoom,
    isAtTop: () -> Boolean,
    isSearch: () -> Boolean,
    canGroup: (String, String) -> Boolean,
    onZoom: (ShelfZoom) -> Unit,
    onSearch: () -> Unit,
    canCloseSearch: () -> Boolean,
    onCloseSearch: () -> Unit,
    onHold: () -> Unit,
    onGroupReady: () -> Unit,
    onDrop: (ShelfItemDrag, List<String>) -> Unit,
    onActions: (String) -> Unit,
    onBlankTap: (Offset, Long) -> Unit,
    decorationHit: (Offset) -> Boolean = { false },
    onActivity: () -> Unit,
): Modifier = pointerInput(enabled) {
    if (!enabled) { state.cancel(); return@pointerInput }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val initial = down.position
        val initialRoot = initial + origin()
        val decoration = decorationHit(initial)
        if (decoration) down.consume()
        val hit = if (decoration) null else state.bounds.entries.firstOrNull { it.key in state.keys && it.value.contains(initialRoot) }?.key
        val atTop = isAtTop()
        val pinch = LibraryPinchGesture(zoom())
        var multiTouch = false
        var moved = false
        var holdEligible = hit != null
        var searchOpened = false
        var completed = false
        var lastTime = state.clock.start(down.uptimeMillis)
        val slop = viewConfiguration.touchSlop
        try {
            while (true) {
                lastTime = state.clock.now()
                val drag = state.drag
                val holdRemaining = if (holdEligible && drag == null) (viewConfiguration.longPressTimeoutMillis - (lastTime - down.uptimeMillis)).coerceAtLeast(1) else Long.MAX_VALUE
                val hoverRemaining = if (drag?.hoverKey != null && !drag.armed) (LibraryShelfGestureState.hoverDelay(drag.hoverKey) - (lastTime - drag.hoverStartedAt)).coerceAtLeast(1) else Long.MAX_VALUE
                val timeout = minOf(holdRemaining, hoverRemaining)
                val event = if (timeout == Long.MAX_VALUE) awaitPointerEvent(PointerEventPass.Initial)
                    else withTimeoutOrNull(timeout) { awaitPointerEvent(PointerEventPass.Initial) }
                if (event == null) {
                    lastTime = state.clock.advanceTo(lastTime + timeout)
                    if (state.drag == null && holdEligible && hit != null) {
                        state.start(hit, initialRoot)
                        holdEligible = false
                        onHold(); onActivity()
                    } else if (state.armHover(lastTime)) onGroupReady()
                    continue
                }
                lastTime = state.clock.advanceTo(event.changes.maxOf { it.uptimeMillis })
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    if (!multiTouch) { state.cancel(); onActivity() }
                    multiTouch = true; holdEligible = false
                    event.changes.forEach { it.consume() }
                    pinch.update((pressed[0].position - pressed[1].position).getDistance())?.let(onZoom)
                    continue
                }
                if (multiTouch) {
                    event.changes.forEach { it.consume() }
                    if (pressed.isEmpty()) { completed = true; break }
                    continue
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                // Event uptime also recognizes a hold when a delayed frame (or a test clock)
                // delivers the first post-hold movement before the timeout coroutine resumes.
                if (holdEligible && state.drag == null && lastTime - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) {
                    state.start(hit!!, initialRoot); holdEligible = false; onHold(); onActivity()
                }
                val delta = change.position - initial
                if (delta.getDistance() > slop) {
                    if (!moved) onActivity()
                    moved = true; holdEligible = false
                }
                if (decoration) change.consume()
                if (state.drag != null) {
                    change.consume()
                    // Validate the release position too: an up outside the target must not
                    // commit a group that had armed over a different book earlier.
                    if (!isSearch()) state.move(change.position + origin(), slop, lastTime, canGroup)
                    if (state.armHover(lastTime)) onGroupReady()
                    if (!change.pressed) {
                        val keys = state.keys
                        state.release()?.let { final -> if (final.moved) onDrop(final, keys) else onActions(final.key) }
                        completed = true; break
                    }
                } else {
                    if (searchOpened) change.consume()
                    else if (canCloseSearch() && delta.y < -slop && kotlin.math.abs(delta.x) < -delta.y * .55f) {
                        // Scrolling toward later books folds an unused search away, including
                        // short shelves that do not need a scrollable list. Keep this gesture
                        // claimed until up so changing the surface cannot open a book or search.
                        change.consume(); searchOpened = true; holdEligible = false
                        onActivity(); onCloseSearch()
                    }
                    else if (!isSearch() && shouldOpenPullSearch(atTop, false, delta.x, delta.y, 72f * density)) {
                        change.consume(); searchOpened = true; holdEligible = false
                        onActivity(); onSearch()
                    }
                    if (!change.pressed) {
                        if (!moved && !searchOpened && hit == null && lastTime - down.uptimeMillis <= 250) onBlankTap(change.position, change.uptimeMillis)
                        else if (hit == null) onActivity()
                        completed = true; break
                    }
                }
            }
        } finally { if (!completed) state.cancel() }
    }
}
