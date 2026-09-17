package com.avstaim.listai.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.feature.reader.bookview.BookPresentation
import com.avstaim.listai.feature.reader.bookview.PhysicalBookView
import com.avstaim.listai.feature.reader.demo.FakeBookDocumentFactory
import com.avstaim.listai.feature.reader.demo.toDemoPages

/**
 * Attach to the ancestor Box containing the physical book and the guide. Observes gestures
 * after the book receives them, without ever consuming, injecting or replaying pointer input.
 * Guide buttons are excluded, including when they overlap the book's central tap region.
 */
fun Modifier.readerOnboardingGestures(state: ReaderOnboardingState): Modifier =
    onGloballyPositioned { state.gestureOrigin = it.positionInRoot() }
        .pointerInput(state, state.visible) {
            if (!state.visible) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val onCallout = state.calloutBounds?.contains(down.position + state.gestureOrigin) == true
                var current = down
                var maximumTravel = 0f
                var multiplePointers = false
                while (current.pressed) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (event.changes.count { it.pressed } > 1) multiplePointers = true
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    maximumTravel = maxOf(maximumTravel, (change.position - down.position).getDistance())
                    current = change
                }
                if (!onCallout && !multiplePointers && !current.pressed) {
                    state.performed(readerOnboardingGesture(
                        start = down.position,
                        end = current.position,
                        viewport = Size(size.width.toFloat(), size.height.toFloat()),
                        maxTravel = maximumTravel,
                        tapSlop = viewConfiguration.touchSlop,
                        durationMillis = current.uptimeMillis - down.uptimeMillis,
                        longPressMillis = viewConfiguration.longPressTimeoutMillis,
                    ))
                }
            }
        }

/** A translucent drawn hand and a small loose paper note over the actual reading surface. */
@Composable
fun ReaderOnboardingOverlay(
    state: ReaderOnboardingState,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    illustrationProgress: Float? = null,
) {
    if (!state.visible) return
    val step = state.step ?: return
    BackHandler { state.dismiss() }
    val phase by key(step) {
        val motion = rememberInfiniteTransition(label = "reading gesture demonstration")
        motion.animateFloat(0f, 1f,
            infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "finger position")
    }
    val paper = if (darkTheme) Color(0xFF2E3D4B) else Color(0xFFF5F8FB)
    val ink = if (darkTheme) Color(0xFFDEE7F0) else Color(0xFF203447)
    val accent = if (darkTheme) Color(0xFFA6CAE8) else Color(0xFF355F84)
    val shape = RoundedCornerShape(topStart = 2.dp, topEnd = 8.dp, bottomEnd = 3.dp, bottomStart = 2.dp)
    val (title, detail) = when (step) {
        ReaderOnboardingStep.MENU -> R.string.reader_onboarding_menu_title to R.string.reader_onboarding_menu_detail
        ReaderOnboardingStep.DRAG_FORWARD -> R.string.reader_onboarding_forward_title to R.string.reader_onboarding_forward_detail
        ReaderOnboardingStep.DRAG_BACKWARD -> R.string.reader_onboarding_backward_title to R.string.reader_onboarding_backward_detail
        ReaderOnboardingStep.TAP_LEFT -> R.string.reader_onboarding_left_title to R.string.reader_onboarding_left_detail
        ReaderOnboardingStep.TAP_RIGHT -> R.string.reader_onboarding_right_title to R.string.reader_onboarding_right_detail
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("reader_onboarding")) {
        val compact = maxHeight < 500.dp
        Canvas(Modifier.fillMaxSize()) {
            drawGuideFinger(step, illustrationProgress ?: phase, accent, paper)
        }
        Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = if (compact) 10.dp else 24.dp)
            .widthIn(max = 420.dp).fillMaxWidth()
            .onGloballyPositioned { state.calloutBounds = it.boundsInRoot() }
            .shadow(5.dp, shape, ambientColor = Color(0xFF102A41), spotColor = Color(0xFF102A41))
            .background(paper.copy(alpha = .97f), shape)
            .drawBehind {
                drawLine(accent.copy(alpha = .22f), Offset(5.dp.toPx(), 8.dp.toPx()),
                    Offset(5.dp.toPx(), size.height - 8.dp.toPx()), 1.dp.toPx())
            }
            .pointerInput(Unit) { detectTapGestures {} }
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 18.dp, end = 14.dp, top = if (compact) 9.dp else 14.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(title), Modifier.weight(1f), color = ink,
                    fontSize = if (compact) 16.sp else 18.sp,
                    lineHeight = if (compact) 19.sp else 22.sp,
                    fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.reader_onboarding_counter, step.ordinal + 1, ReaderOnboardingStep.entries.size),
                    color = accent, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("reader_onboarding_step"))
            }
            Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
            Text(stringResource(detail), color = ink.copy(alpha = .88f),
                fontSize = if (compact) 13.sp else 14.sp, lineHeight = if (compact) 17.sp else 20.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = state::dismiss, modifier = Modifier.testTag("reader_onboarding_skip")) {
                    Text(stringResource(R.string.reader_onboarding_skip), color = accent)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = state::next, modifier = Modifier.testTag("reader_onboarding_next")) {
                    Text(stringResource(if (step == ReaderOnboardingStep.TAP_RIGHT)
                        R.string.reader_onboarding_done else R.string.reader_onboarding_next), color = accent,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Animation never calls reader actions: only a real user gesture can change reading progress. */
private fun DrawScope.drawGuideFinger(step: ReaderOnboardingStep, phase: Float, ink: Color, paper: Color) {
    val fade = minOf((phase / .12f).coerceIn(0f, 1f), ((1f - phase) / .2f).coerceIn(0f, 1f))
    if (fade == 0f) return
    val drag = step == ReaderOnboardingStep.DRAG_FORWARD || step == ReaderOnboardingStep.DRAG_BACKWARD
    val travel = ((phase - .2f) / .5f).coerceIn(0f, 1f)
    val eased = travel * travel * (3f - 2f * travel)
    val fromX = when (step) {
        ReaderOnboardingStep.MENU -> .5f
        ReaderOnboardingStep.DRAG_FORWARD -> .77f
        ReaderOnboardingStep.DRAG_BACKWARD -> .23f
        ReaderOnboardingStep.TAP_LEFT -> .08f
        ReaderOnboardingStep.TAP_RIGHT -> .92f
    }
    val toX = when (step) {
        ReaderOnboardingStep.DRAG_FORWARD -> .27f
        ReaderOnboardingStep.DRAG_BACKWARD -> .73f
        else -> fromX
    }
    val point = Offset(size.width * (fromX + (toX - fromX) * eased), size.height * .5f)
    if (drag) {
        drawLine(ink.copy(alpha = fade * .24f), Offset(size.width * fromX, point.y), point,
            strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(ink.copy(alpha = fade * .2f), 7.dp.toPx(), Offset(size.width * fromX, point.y),
            style = Stroke(1.dp.toPx()))
    } else {
        val ripple = ((phase - .28f) / .4f).coerceIn(0f, 1f)
        drawCircle(ink.copy(alpha = (1f - ripple) * fade * .6f), (10f + 19f * ripple).dp.toPx(), point,
            style = Stroke(1.4.dp.toPx()))
    }
    val press = if (drag) 1f else 1f - .07f * (1f - kotlin.math.abs(phase - .38f) / .2f).coerceIn(0f, 1f)
    val unit = .88.dp.toPx() * press
    withTransform({
        translate(point.x, point.y)
        rotate(-16f, Offset.Zero)
        scale(unit, unit, Offset.Zero)
    }) {
        val hand = Path().apply {
            moveTo(-5f, 25f)
            lineTo(-5f, 1f)
            cubicTo(-5f, -7f, 6f, -7f, 6f, 1f)
            lineTo(6f, 20f)
            cubicTo(11f, 15f, 16f, 19f, 16f, 24f)
            cubicTo(21f, 19f, 26f, 23f, 26f, 28f)
            cubicTo(31f, 25f, 36f, 30f, 35f, 36f)
            lineTo(32f, 49f)
            cubicTo(29f, 59f, 10f, 59f, 4f, 51f)
            lineTo(-12f, 32f)
            cubicTo(-18f, 25f, -12f, 19f, -7f, 24f)
            close()
        }
        drawPath(hand, paper.copy(alpha = fade * .68f))
        drawPath(hand, ink.copy(alpha = fade * .78f), style = Stroke(1.6f, cap = StrokeCap.Round))
        drawLine(ink.copy(alpha = fade * .3f), Offset(6f, 22f), Offset(6f, 31f), 1.1f, StrokeCap.Round)
        drawLine(ink.copy(alpha = fade * .3f), Offset(16f, 25f), Offset(16f, 33f), 1.1f, StrokeCap.Round)
    }
}

@Preview(name = "Reading guide — Russian", widthDp = 412, heightDp = 892, locale = "ru")
@Composable
private fun ReaderOnboardingPreview() {
    ListaiTheme {
        Box(Modifier.fillMaxSize()) {
            ReaderDemoScreen(initialPageIndex = 4)
            val state = remember { ReaderOnboardingState(ReaderOnboardingSession(0)).apply { readerReady = true } }
            ReaderOnboardingOverlay(state, darkTheme = false, illustrationProgress = .4f)
        }
    }
}

@Preview(name = "Reading guide — dark spread", widthDp = 892, heightDp = 412, locale = "en")
@Composable
private fun ReaderOnboardingSpreadPreview() {
    ListaiTheme(darkTheme = true) {
        Box(Modifier.fillMaxSize()) {
            val document = remember { FakeBookDocumentFactory.create() }
            PhysicalBookView(document.toDemoPages(), document.metadata.title, null, Modifier.fillMaxSize(),
                forcedPresentation = BookPresentation.SPREAD, initialPageIndex = 4,
                darkTheme = true, outerPadding = 4.dp, coverInset = 3.dp)
            val state = remember { ReaderOnboardingState(ReaderOnboardingSession(1)).apply { readerReady = true } }
            ReaderOnboardingOverlay(state, darkTheme = true, illustrationProgress = .4f)
        }
    }
}
