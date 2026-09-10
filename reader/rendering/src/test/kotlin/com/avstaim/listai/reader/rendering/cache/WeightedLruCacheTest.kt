package com.avstaim.listai.reader.rendering.cache

import org.junit.Assert.*
import org.junit.Test

class WeightedLruCacheTest {
    private data class Surface(val bytes: Long)
    private fun cache(bytes: Long = 10) = WeightedLruCache<Int, Surface>(bytes) { it.bytes }

    @Test fun leastRecentlyUsedEntryIsEvictedByBytesNotCount() {
        val cache = cache()
        cache.put(1, Surface(4)); cache.put(2, Surface(4))
        cache[1]
        assertTrue(cache.put(3, Surface(5)))
        assertEquals(setOf(1, 3), cache.snapshot().keys)
        assertEquals(9L, cache.bytes)
        assertEquals(1L, cache.evictions)
    }

    @Test fun pinnedPagesSurvivePressureAndInadmissiblePrefetchDoesNotEvictAnything() {
        val cache = cache()
        val active = Surface(6)
        cache.put(1, active); cache.put(2, Surface(4))
        cache.pin(setOf(1))
        assertFalse(cache.put(3, Surface(5)))
        assertEquals(setOf(1, 2), cache.snapshot().keys)
        cache.trimToPinned()
        assertSame(active, cache[1])
        assertEquals(6L, cache.bytes)
        assertEquals(setOf(1), cache.snapshot().keys)
    }

    @Test fun replacingPinnedEntryAccountsForOldWeightAndRejectsOversizedValue() {
        val cache = cache()
        cache.put(1, Surface(6)); cache.put(2, Surface(4)); cache.pin(setOf(1, 2))
        assertTrue(cache.put(1, Surface(3)))
        assertEquals(7L, cache.bytes)
        assertFalse(cache.put(1, Surface(11)))
        assertEquals(3L, cache[1]?.bytes)
        assertEquals(7L, cache.bytes)
    }

    @Test fun evictionAndClearDoNotInvalidatePreviouslyPublishedSnapshot() {
        val cache = cache()
        val surface = Surface(10)
        cache.put(1, surface)
        val retained = cache.snapshot()
        cache.put(2, Surface(10))
        cache.clear()
        assertSame(surface, retained[1])
        assertEquals(0L, cache.bytes)
        assertEquals(0, cache.size)
    }

    @Test fun longBidirectionalWalkStaysWithinBudgetAndKeepsAllHotPages() {
        val cache = cache(64)
        for (position in (0..1000) + (1000 downTo 0)) {
            val hot = (position..position + 5).toSet()
            cache.pin(hot)
            hot.forEach { assertTrue(cache.put(it, Surface(8))) }
            cache.put(position + 6, Surface(8)); cache.put(position + 7, Surface(8))
            assertTrue(cache.bytes <= 64)
            assertTrue(cache.snapshot().keys.containsAll(hot))
            assertTrue(cache.size <= 8)
        }
    }
}
