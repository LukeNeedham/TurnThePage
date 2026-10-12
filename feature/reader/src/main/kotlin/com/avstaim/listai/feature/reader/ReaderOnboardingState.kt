package com.avstaim.listai.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.avstaim.listai.feature.reader.bookview.readerMenuTapBounds
import kotlin.math.abs

enum class ReaderOnboardingStep(val gestureLesson: Boolean = false) {
    MENU(true), DRAG_FORWARD(true), DRAG_BACKWARD(true), TAP_LEFT(true), TAP_RIGHT(true),
    BOOKMARKS, ILLUSTRATIONS, STACKS, SEARCH, SHELF_ZOOM, TEXT_ZOOM, PDF_ZOOM,
}

/** This state owns instructions only. It has no book location and cannot turn a page. */
internal data class ReaderOnboardingSession(
    val stepIndex: Int = -1,
    val finished: Boolean = false,
    val replayRequest: Long = 0L,
) {
    val step: ReaderOnboardingStep? get() = ReaderOnboardingStep.entries.getOrNull(stepIndex)

    fun prepare(completed: Boolean?, request: Long): ReaderOnboardingSession = when {
        request > 0L && request != replayRequest -> ReaderOnboardingSession(0, replayRequest = request)
        !finished && step == null && completed == false -> copy(stepIndex = 0)
        else -> this
    }

    fun next(): ReaderOnboardingSession = when {
        step == null -> this
        stepIndex == ReaderOnboardingStep.entries.lastIndex -> finish()
        else -> copy(stepIndex = stepIndex + 1)
    }

    fun performed(gesture: ReaderOnboardingStep?): ReaderOnboardingSession =
        if (gesture != null && gesture.gestureLesson && gesture == step) next() else this

    fun finish(): ReaderOnboardingSession = copy(stepIndex = -1, finished = true)
}

@Stable
class ReaderOnboardingState internal constructor(initial: ReaderOnboardingSession = ReaderOnboardingSession()) {
    internal var session by mutableStateOf(initial)
    internal var readerReady by mutableStateOf(false)
    internal var gestureOrigin = Offset.Zero
    internal var calloutBounds: Rect? = null

    val step: ReaderOnboardingStep? get() = session.step
    val visible: Boolean get() = readerReady && step != null

    fun next() { session = session.next() }
    fun dismiss() { session = session.finish() }
    internal fun performed(gesture: ReaderOnboardingStep?) {
        if (visible) session = session.performed(gesture)
    }
}

/**
 * Pass null until DataStore has loaded, and ready only after the shelf flight and cover opening.
 * Use a saved, increasing replayRequest for the Settings action. A dismissed guide hides
 * synchronously; DataStore can finish writing later without starting the guide again.
 * Temporarily setting ready=false preserves the current step (for example while a menu is open).
 */
@Composable
fun rememberReaderOnboardingState(
    completed: Boolean?,
    readerReady: Boolean,
    replayRequest: Long = 0L,
    onComplete: () -> Unit,
): ReaderOnboardingState {
    val state = rememberSaveable(saver = listSaver<ReaderOnboardingState, Any>(
        save = { listOf(it.session.stepIndex, it.session.finished, it.session.replayRequest) },
        restore = { ReaderOnboardingState(ReaderOnboardingSession(it[0] as Int, it[1] as Boolean, it[2] as Long)) },
    )) { ReaderOnboardingState() }
    val complete by rememberUpdatedState(onComplete)
    SideEffect {
        state.readerReady = readerReady
        state.session = state.session.prepare(completed, replayRequest)
    }
    LaunchedEffect(state.session.finished, state.session.replayRequest) {
        if (state.session.finished) complete()
    }
    return state
}

/** Coordinates deliberately use the whole viewport, so a spread has one menu at its spine. */
internal fun readerOnboardingGesture(
    start: Offset,
    end: Offset,
    viewport: Size,
    maxTravel: Float,
    tapSlop: Float,
    durationMillis: Long,
    longPressMillis: Long,
): ReaderOnboardingStep? {
    if (viewport.width <= 0f || viewport.height <= 0f || !Rect(Offset.Zero, viewport).contains(start)) return null
    val delta = end - start
    if (abs(delta.x) >= maxOf(tapSlop * 2f, viewport.width * .16f) && abs(delta.x) > abs(delta.y) * 1.2f) {
        return if (delta.x < 0f) ReaderOnboardingStep.DRAG_FORWARD else ReaderOnboardingStep.DRAG_BACKWARD
    }
    if (maxTravel > tapSlop || durationMillis > longPressMillis) return null
    if (readerMenuTapBounds(viewport).contains(start)) return ReaderOnboardingStep.MENU
    // The upper controls and printed page numbers are not page-turn demonstrations.
    if (start.y !in viewport.height * .24f..viewport.height * .76f) return null
    return when {
        start.x < viewport.width * .18f -> ReaderOnboardingStep.TAP_LEFT
        start.x > viewport.width * .82f -> ReaderOnboardingStep.TAP_RIGHT
        else -> null
    }
}
