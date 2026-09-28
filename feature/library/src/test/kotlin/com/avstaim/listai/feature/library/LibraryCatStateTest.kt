package com.avstaim.listai.feature.library

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.avstaim.listai.core.model.ShelfZoom
import org.junit.Assert.*
import org.junit.Test

class LibraryCatStateTest {
    @Test fun oneCatPeeksSettlesAndReactsThenReturnsToStaticSleep() {
        val hidden = LibraryCatState()
        val peeking = hidden.summon("stack", animate = true)
        assertEquals(LibraryCatPhase.PEEKING, peeking.phase)
        assertEquals(peeking, peeking.summon("another", animate = true))
        val asleep = peeking.settled().settled()
        assertEquals(LibraryCatPhase.SLEEPING, asleep.phase)
        assertEquals(asleep, asleep.settled())
        assertEquals(asleep, asleep.tap(animate = true).settled())
        assertEquals(hidden, asleep.hide(animate = true).settled())
    }

    @Test fun inactiveOrReducedMotionStopsWithoutSchedulingAnotherTransition() {
        val asleep = LibraryCatState().summon("shelf", animate = false)
        assertEquals(LibraryCatPhase.SLEEPING, asleep.phase)
        assertEquals(asleep, asleep.tap(animate = false))
        assertEquals(LibraryCatState(), asleep.hide(animate = false))
        assertEquals(LibraryCatState(), asleep.tap(true).hide(animate = false))
    }

    @Test fun placementKeepsBodyOutsideBookAndRetainsStableSupportAcrossAllScales() {
        val supports = listOf(LibraryCatSupport("stack", Rect(130f, 150f, 260f, 280f), true))
        ShelfZoom.entries.forEach { zoom ->
            val placement = requireNotNull(libraryCatPlacement(supports, Size(412f, 800f), 1f, zoom, "stack"))
            assertEquals("stack", placement.supportKey)
            assertFalse(placement.hitBounds.overlaps(supports.single().bounds))
            assertTrue(placement.bounds.left >= 0f && placement.bounds.right <= 412f)
            assertTrue(placement.bounds.top >= 0f && placement.bounds.bottom <= 800f)
        }
        assertNull(libraryCatPlacement(emptyList(), Size(412f, 800f), 1f, ShelfZoom.MEDIUM, "stack"))
    }

    @Test fun emptyShelfAndFoldPlacementAvoidControlsAndHinge() {
        val excluded = listOf(Rect(390f, 0f, 410f, 700f), Rect(0f, 600f, 800f, 700f))
        val cat = requireNotNull(libraryCatPlacement(emptyList(), Size(800f, 700f), 1f, ShelfZoom.MEDIUM,
            excludedBounds = excluded))
        assertEquals(FREE_SUPPORT, cat.supportKey)
        assertTrue(excluded.none { cat.bounds.overlaps(it) })
        assertNull(libraryCatPlacement(emptyList(), Size(20f, 20f), 1f, ShelfZoom.LARGE))
    }
}
