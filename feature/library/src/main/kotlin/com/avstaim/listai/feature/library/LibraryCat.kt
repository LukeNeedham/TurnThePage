package com.avstaim.listai.feature.library

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.avstaim.listai.core.model.ShelfZoom
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlin.math.abs
import kotlin.math.roundToInt

/** Passive decoration: the library's single gesture arbiter owns all hit testing and input. */
@Composable
fun LibraryCat(
    modifier: Modifier = Modifier,
    active: Boolean,
    darkTheme: Boolean,
    zoom: ShelfZoom,
    summonEvent: Long,
    tapEvent: Long,
    hideEvent: Long,
    supports: List<LibraryCatSupport>,
    onBoundsChanged: (Rect?) -> Unit,
    excludedBounds: List<Rect> = emptyList(),
) {
    var state by remember { mutableStateOf(LibraryCatState()) }
    val runningAnimation = remember { AtomicReference<Job?>(null) }
    val view = LocalView.current
    val owner = view.findViewTreeLifecycleOwner()
    var foreground by remember(owner) { mutableStateOf(owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false
            if (!foreground) {
                // ON_STOP can pause the window's recomposer before it sees foreground=false.
                // Cancel immediately, rather than waiting for a composition or a later resume.
                runningAnimation.getAndSet(null)?.cancel()
                state = LibraryCatState()
                onBoundsChanged(null)
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer); runningAnimation.getAndSet(null)?.cancel(); onBoundsChanged(null) }
    }
    val enabled = active && foreground
    val inspection = LocalInspectionMode.current
    val animate = !inspection && ValueAnimator.areAnimatorsEnabled()
    var lastSummon by remember { mutableStateOf(summonEvent) }
    var lastTap by remember { mutableStateOf(tapEvent) }
    var lastHide by remember { mutableStateOf(hideEvent) }
    val visibility = remember { Animatable(0f) }
    val arrival = remember { Animatable(0f) }
    val reaction = remember { Animatable(0f) }
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val resting = libraryCatPlacement(supports, viewport, density.density, zoom, state.supportKey, excludedBounds)
        LaunchedEffect(enabled, summonEvent, tapEvent, hideEvent, resting?.supportKey) {
            if (!enabled) {
                state = LibraryCatState()
            } else if (hideEvent != lastHide) {
                state = state.hide(animate)
            } else if (state.phase != LibraryCatPhase.HIDDEN && resting == null) {
                state = LibraryCatState()
            } else if (summonEvent != lastSummon && resting != null) {
                state = state.summon(resting.supportKey, animate)
            } else if (tapEvent != lastTap) {
                state = state.tap(animate)
            }
            lastSummon = summonEvent; lastTap = tapEvent; lastHide = hideEvent
        }
        LaunchedEffect(state.phase, enabled) {
            runningAnimation.set(currentCoroutineContext().job)
            if (!enabled || state.phase == LibraryCatPhase.HIDDEN) {
                visibility.snapTo(0f); arrival.snapTo(0f); reaction.snapTo(0f)
                return@LaunchedEffect
            }
            when (state.phase) {
                LibraryCatPhase.PEEKING -> {
                    visibility.animateTo(.62f, tween(180))
                    delay(220)
                    state = state.settled()
                }
                LibraryCatPhase.CLIMBING -> {
                    visibility.animateTo(1f, tween(120))
                    arrival.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
                    state = state.settled()
                }
                LibraryCatPhase.REACTING -> {
                    reaction.animateTo(1f, tween(140))
                    reaction.animateTo(-.6f, tween(260))
                    reaction.animateTo(0f, tween(340))
                    state = state.settled()
                }
                LibraryCatPhase.LEAVING -> {
                    visibility.animateTo(0f, tween(180))
                    state = state.settled()
                }
                LibraryCatPhase.SLEEPING -> {
                    visibility.snapTo(1f); arrival.snapTo(1f); reaction.snapTo(0f)
                    // A sleeping cat schedules neither frames nor a timer.
                }
                LibraryCatPhase.HIDDEN -> Unit
            }
        }
        val visible = enabled && state.phase != LibraryCatPhase.HIDDEN && resting != null
        SideEffect { onBoundsChanged(resting?.hitBounds?.takeIf { visible }) }
        if (visible && resting != null) {
            val bounds = resting.bounds
            LibraryCatArtwork(
                modifier = Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                    .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
                    .testTag("library_cat")
                    .graphicsLayer {
                        alpha = visibility.value
                        translationY = (1f - arrival.value) * 9f * density.density
                        scaleX = if (resting.mirrored) -1f else 1f
                    },
                darkTheme = darkTheme,
                eyeOpening = abs(reaction.value),
                tailSwing = reaction.value,
            )
        }
    }
}
