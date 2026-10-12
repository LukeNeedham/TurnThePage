package com.avstaim.listai.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class ReaderOnboardingTest {
    @Test fun `first guide waits for stored preference and completed installations stay quiet`() {
        val fresh = ReaderOnboardingSession()
        assertNull(fresh.prepare(completed = null, request = 0L).step)
        assertNull(fresh.prepare(completed = true, request = 0L).step)
        assertEquals(ReaderOnboardingStep.MENU, fresh.prepare(completed = false, request = 0L).step)
    }

    @Test fun `matching actions walk through centre both drags and both outer edges`() {
        var session = ReaderOnboardingSession().prepare(false, 0L)
        for (step in ReaderOnboardingStep.entries.filter { it.gestureLesson }) {
            assertEquals(step, session.step)
            assertEquals(session, session.performed(null))
            ReaderOnboardingStep.entries.filter { it != step }.forEach { wrong ->
                assertEquals(session, session.performed(wrong))
            }
            session = session.performed(step)
        }
        assertEquals(ReaderOnboardingStep.BOOKMARKS, session.step)
        assertFalse(session.finished)
    }

    @Test fun `feature tips stay on their step during reading gestures and complete with next`() {
        var session = ReaderOnboardingSession(ReaderOnboardingStep.BOOKMARKS.ordinal)
        for (step in ReaderOnboardingStep.entries.filter { !it.gestureLesson }) {
            assertEquals(step, session.step)
            ReaderOnboardingStep.entries.forEach { gesture ->
                assertEquals(session, session.performed(gesture))
            }
            session = session.next()
        }
        assertTrue(session.finished)
        assertNull(session.step)
        assertEquals(session, session.next())
    }

    @Test fun `skip is immediate even before completion write and cannot restart automatically`() {
        val skipped = ReaderOnboardingSession().prepare(false, 0L).next().finish()
        assertTrue(skipped.finished)
        assertNull(skipped.step)
        assertEquals(skipped, skipped.prepare(false, 0L))
        assertEquals(skipped, skipped.prepare(null, 0L))
        assertEquals(skipped, skipped.prepare(true, 0L))
    }

    @Test fun `only a new explicit replay request restarts a finished guide`() {
        val completed = ReaderOnboardingSession().prepare(false, 0L).finish()
        var replay = completed.prepare(true, 1L)
        assertEquals(ReaderOnboardingStep.MENU, replay.step)
        assertFalse(replay.finished)
        replay = replay.next()
        assertEquals(replay, replay.prepare(true, 1L))
        replay = replay.finish()
        assertEquals(replay, replay.prepare(true, 1L))
        assertEquals(ReaderOnboardingStep.MENU, replay.prepare(true, 2L).step)
    }

    @Test fun `restored active session retains its step and handled replay request`() {
        val original = ReaderOnboardingSession().prepare(true, 7L).next().next()
        val restored = ReaderOnboardingSession(original.stepIndex, original.finished, original.replayRequest)
        assertEquals(ReaderOnboardingStep.DRAG_BACKWARD, restored.prepare(true, 7L).step)
        assertEquals(original, restored.prepare(null, 7L))
        val featureTip = original.copy(stepIndex = ReaderOnboardingStep.STACKS.ordinal)
        val restoredTip = ReaderOnboardingSession(featureTip.stepIndex, featureTip.finished, featureTip.replayRequest)
        assertEquals(ReaderOnboardingStep.STACKS, restoredTip.prepare(true, 7L).step)
    }

    @Test fun `readiness temporarily pauses guide without completing it`() {
        val state = ReaderOnboardingState(ReaderOnboardingSession(2))
        assertFalse(state.visible)
        state.performed(ReaderOnboardingStep.DRAG_BACKWARD)
        assertEquals(ReaderOnboardingStep.DRAG_BACKWARD, state.step)
        state.readerReady = true
        assertTrue(state.visible)
        state.performed(ReaderOnboardingStep.DRAG_BACKWARD)
        assertEquals(ReaderOnboardingStep.TAP_LEFT, state.step)
        state.readerReady = false
        assertFalse(state.visible)
        state.readerReady = true
        assertEquals(ReaderOnboardingStep.TAP_LEFT, state.step)
        state.dismiss()
        assertFalse(state.visible)
    }

    @Test fun `spread uses one viewport centre including the spine`() {
        val viewport = Size(1000f, 650f)
        assertEquals(ReaderOnboardingStep.MENU, gesture(Offset(500f, 325f), viewport = viewport))
        assertNull(gesture(Offset(250f, 325f), viewport = viewport))
        assertNull(gesture(Offset(750f, 325f), viewport = viewport))
        assertEquals(ReaderOnboardingStep.TAP_LEFT, gesture(Offset(80f, 325f), viewport = viewport))
        assertEquals(ReaderOnboardingStep.TAP_RIGHT, gesture(Offset(920f, 325f), viewport = viewport))
    }

    @Test fun `portrait centre edges and directional drags match demonstration`() {
        assertEquals(ReaderOnboardingStep.MENU, gesture(Offset(200f, 400f)))
        assertEquals(ReaderOnboardingStep.TAP_LEFT, gesture(Offset(32f, 400f)))
        assertEquals(ReaderOnboardingStep.TAP_RIGHT, gesture(Offset(368f, 400f)))
        assertEquals(ReaderOnboardingStep.DRAG_FORWARD, gesture(Offset(308f, 400f), Offset(108f, 410f)))
        assertEquals(ReaderOnboardingStep.DRAG_BACKWARD, gesture(Offset(92f, 400f), Offset(292f, 390f)))
    }

    @Test fun `page controls footers long presses vertical motion and aborted drags are ignored`() {
        assertNull(gesture(Offset(32f, 24f)))
        assertNull(gesture(Offset(368f, 776f)))
        assertNull(gesture(Offset(200f, 400f), duration = 900L))
        assertNull(gesture(Offset(200f, 400f), Offset(215f, 600f)))
        assertNull(gesture(Offset(200f, 400f), maximumTravel = 100f))
        assertNull(gesture(Offset(200f, 400f), Offset(230f, 400f)))
        assertNull(gesture(Offset(-30f, 400f), Offset(300f, 400f)))
        assertNull(gesture(Offset.Zero, viewport = Size.Zero))
    }

    private fun gesture(
        start: Offset,
        end: Offset = start,
        viewport: Size = Size(400f, 800f),
        maximumTravel: Float = (end - start).getDistance(),
        duration: Long = 150L,
    ): ReaderOnboardingStep? = readerOnboardingGesture(start, end, viewport, maximumTravel,
        tapSlop = 12f, durationMillis = duration, longPressMillis = 500L)
}
