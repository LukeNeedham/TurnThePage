package com.avstaim.listai.feature.reader.bookview

import com.avstaim.listai.core.model.ReaderPreferences
import org.junit.Assert.*
import org.junit.Test

class ReaderFontZoomTest {
    @Test fun `off centre physical hinge stays masked in fixed viewport coordinates`() {
        val viewport = androidx.compose.ui.geometry.Rect(0f, 0f, 1000f, 800f)
        val hinge = androidx.compose.ui.geometry.Rect(220f, 24f, 260f, 776f)
        val regions = readerPinchHingeSafeRegions(viewport, hinge)
        assertEquals(listOf(
            androidx.compose.ui.geometry.Rect(0f, 0f, 220f, 800f),
            androidx.compose.ui.geometry.Rect(260f, 0f, 1000f, 800f)), regions)
        for (y in listOf(0f, 400f, 799f)) {
            assertFalse(regions.any { it.contains(androidx.compose.ui.geometry.Offset(240f, y)) })
            assertTrue(regions.any { it.contains(androidx.compose.ui.geometry.Offset(10f, y)) })
            assertTrue(regions.any { it.contains(androidx.compose.ui.geometry.Offset(900f, y)) })
        }
        assertEquals(listOf(viewport), readerPinchHingeSafeRegions(viewport, null))
    }

    @Test fun `small intentional moves preview immediately but commit only after release`() {
        val gesture = ReaderPinchSession()
        gesture.update(2)
        val moving = gesture.update(2, 1.025f)
        assertEquals(1.025f, moving.previewFactor, .0001f)
        assertNull(moving.completedFactor)
        assertEquals(18.2f, readerFontSizeAfterZoom(18f, moving.previewFactor), .001f)
        assertNotNull(gesture.update(0).completedFactor)
    }

    @Test fun `second finger cancels curl once and release of only one finger cannot publish`() {
        val gesture = ReaderPinchSession()
        assertFalse(gesture.update(1).ownsGesture)
        val claim = gesture.update(2)
        assertTrue(claim.ownsGesture)
        assertTrue(claim.cancelPageTurn)
        assertNull(claim.completedFactor)
        assertFalse(gesture.update(2, 1.4f).cancelPageTurn)
        val remainingFinger = gesture.update(1)
        assertTrue(remainingFinger.ownsGesture)
        assertFalse(remainingFinger.finished)
        assertNull(remainingFinger.completedFactor)
        val release = gesture.update(0)
        assertTrue(release.ownsGesture)
        assertTrue(release.finished)
        assertEquals(1.4f, requireNotNull(release.completedFactor), .001f)
        assertNull(gesture.update(0).completedFactor)
    }

    @Test fun `jitter and a pinch returning to its original distance do not reflow`() {
        for (factors in listOf(listOf(1.003f), listOf(.997f), listOf(1.5f, 1f / 1.5f))) {
            val gesture = ReaderPinchSession()
            gesture.update(2)
            factors.forEach { assertNull(gesture.update(2, it).completedFactor) }
            assertNull(gesture.update(0).completedFactor)
        }
    }

    @Test fun `pinch thresholds are symmetric for enlargement and reduction`() {
        for (factor in listOf(1.07f, 1f / 1.07f)) {
            val gesture = ReaderPinchSession()
            gesture.update(2)
            gesture.update(2, factor)
            assertEquals(factor, requireNotNull(gesture.update(0).completedFactor), .001f)
        }
    }

    @Test fun `remaining fingers and a newly added finger keep the same claimed transaction`() {
        val gesture = ReaderPinchSession()
        gesture.update(2)
        gesture.update(2, 1.2f)
        assertTrue(gesture.update(1, 5f).ownsGesture)
        assertFalse(gesture.update(3, 1.25f).cancelPageTurn)
        assertTrue(gesture.update(1).ownsGesture)
        assertEquals(1.5f, requireNotNull(gesture.update(0).completedFactor), .001f)
    }

    @Test fun `cancellation suppresses font and PDF completion while still swallowing remaining contact`() {
        for (cancelThroughEvent in listOf(false, true)) {
            val gesture = ReaderPinchSession()
            assertTrue(gesture.update(2).cancelPageTurn)
            gesture.update(2, 1.7f)
            if (cancelThroughEvent) gesture.update(1, cancelled = true) else gesture.cancel()
            assertTrue(gesture.update(1).ownsGesture)
            val release = gesture.update(0)
            assertTrue(release.finished)
            assertNull(release.completedFactor)
        }
    }

    @Test fun `invalid distance ratios cannot poison a later finite pinch`() {
        val gesture = ReaderPinchSession()
        gesture.update(2)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f).forEach {
            assertNull(gesture.update(2, it).completedFactor)
        }
        gesture.update(2, 1.3f)
        assertEquals(1.3f, requireNotNull(gesture.update(0).completedFactor), .001f)
    }

    @Test fun `returning after a large enlargement does not reflow`() {
        val gesture = ReaderPinchSession()
        gesture.update(2)
        gesture.update(2, 6f)
        gesture.update(2, 1f / 6f)
        assertNull(gesture.update(0).completedFactor)
    }

    @Test fun `returning after a large reduction does not reflow`() {
        val gesture = ReaderPinchSession()
        gesture.update(2)
        gesture.update(2, .1f)
        gesture.update(2, 10f)
        assertNull(gesture.update(0).completedFactor)
    }

    @Test fun `only the completed ratio is clamped after an excursion`() {
        val gesture = ReaderPinchSession()
        gesture.update(2)
        gesture.update(2, 10f)
        gesture.update(2, .2f)
        assertEquals(2f, requireNotNull(gesture.update(0).completedFactor), .001f)
    }

    @Test fun `large finite transforms stay bounded without overflowing accumulation`() {
        val gesture = ReaderPinchSession()
        gesture.update(2)
        repeat(16) { gesture.update(2, Float.MAX_VALUE) }
        gesture.update(2, .5f)
        assertEquals(16f, requireNotNull(gesture.update(0).completedFactor), .001f)
        val reduction = ReaderPinchSession()
        reduction.update(2)
        reduction.update(2, Float.MIN_VALUE)
        assertEquals(.0625f, requireNotNull(reduction.update(0).completedFactor), .001f)
    }

    @Test fun `font size uses the preference limits and ignores invalid transforms`() {
        assertEquals(24f, readerFontSizeAfterZoom(18f, 16f / 9f), .001f)
        assertEquals(12f, readerFontSizeAfterZoom(18f, .01f), .001f)
        assertEquals(32f, readerFontSizeAfterZoom(18f, Float.MAX_VALUE), .001f)
        for (factor in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) {
            assertEquals(18f, readerFontSizeAfterZoom(18f, factor), .001f)
        }
        assertEquals(18f, readerFontSizeAfterZoom(Float.NaN, 1f), .001f)
        for (size in listOf(12f, 18f, 32f)) for (factor in listOf(.001f, .7f, 1f, 1.8f, Float.MAX_VALUE)) {
            ReaderPreferences(fontSizeSp = readerFontSizeAfterZoom(size, factor))
        }
    }
}
