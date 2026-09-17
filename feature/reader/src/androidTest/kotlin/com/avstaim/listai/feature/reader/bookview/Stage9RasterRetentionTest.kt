package com.avstaim.listai.feature.reader.bookview

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android bitmaps plus the real serialized cache, independent of pinned UI diagnostics. */
@RunWith(AndroidJUnit4::class)
class Stage9RasterRetentionTest {
    @Test fun distantPageWindowsEvictPixelsReuseWarmRastersAndReleaseSessionOwnership() = runBlocking {
        val document = BookDocument(BookId("stage9-native-retention"), BookMetadata("Window retention"),
            List(120) { index -> BookBlock.Paragraph(BlockId("body-$index"), listOf(InlineNode.Text(
                "Page $index keeps its words in the lightweight index while optional bitmaps leave the cache. ".repeat(32)))) })
        for (mode in PageMode.entries) {
            val key = LayoutKey(if (mode == PageMode.SPREAD) 1200 else 600, 960, mode)
            val snapshot = withContext(Dispatchers.Default) {
                IncrementalPaginationEngine(AndroidBookMeasurer(document, "Missing image"))
                    .paginate(document, null, key).last()
            }
            assertTrue(snapshot.complete)
            assertTrue(snapshot.pages.size > 100)
            withContext(Dispatchers.Main.immediate) {
                val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val presentation = if (mode == PageMode.SPREAD) BookPresentation.SPREAD else BookPresentation.SINGLE
                val position = ReaderPosition(snapshot.pages.first().start)
                val session = PaginationSession(key, document, "Missing image", owner)
                try {
                    session.updateIndex(snapshot, position)
                    suspend fun warm(state: PhysicalBookState.Open) {
                        val plan = bookSurfacePlan(state, presentation, snapshot.pages.size)
                        val wanted = (plan.required + plan.prefetch).map(::ReaderSurfaceKey)
                        val cache = withTimeout(15_000) { session.store.state.first { cache ->
                            assertTrue("Raster failures must fail the walk", cache.failedKeys.isEmpty())
                            wanted.all { it in cache.values }
                        } }
                        session.updateCache(cache)
                        assertTrue(cache.bytes in 1..PAGE_CACHE_BYTES)
                        assertEquals(cache.bytes, cache.values.values.sumOf { it.byteCount })
                        val scene = requireNotNull(session.ready)
                        assertTrue("The scene publishes only its current/adjacent/prefetch window",
                            scene.surfaces.count { it != null } <= 8)
                    }
                    val first = PhysicalBookState.Open(0)
                    warm(first)
                    val heldScene = requireNotNull(session.ready)
                    val heldBitmap = requireNotNull(heldScene.surfaces[0]?.paginated?.bitmap)
                    var state = first
                    // Visit enough distinct windows to exceed the 64 MiB raster budget.
                    repeat(40) { step ->
                        state = PhysicalBookState.Open((1 + step * 2).coerceAtMost(snapshot.pages.lastIndex - 3))
                        session.request(state, presentation)
                        warm(state)
                    }
                    assertTrue("Distant reading must evict cold pixels", session.store.state.value.evictions > 0)
                    assertFalse("A scene may still own an evicted bitmap during its curl", heldBitmap.isRecycled)
                    assertSame(heldBitmap, heldScene.surfaces[0]?.paginated?.bitmap)

                    val before = session.store.state.value.rendered
                    val ready = session.ready
                    repeat(120) { session.request(state, presentation) }
                    assertEquals("Warm frame requests must not enqueue fresh rasters", before, session.store.state.value.rendered)
                    assertSame("Warm frames reuse the exact immutable page window", ready, session.ready)

                    val required = bookSurfacePlan(state, presentation, snapshot.pages.size).required.map(::ReaderSurfaceKey).toSet()
                    session.trimMemory()
                    val trimmed = session.store.state.value
                    assertEquals("Memory pressure retains only pinned neighbours", required, trimmed.values.keys)
                    assertTrue(trimmed.values.size <= if (mode == PageMode.SPREAD) 6 else 3)
                    session.close()
                    assertNull(session.ready)
                    assertTrue(session.store.state.value.values.isEmpty())
                    assertEquals(0L, session.store.state.value.bytes)
                    // Eviction/disposal drops ownership, never recycles a GPU-visible scene.
                    assertFalse(heldBitmap.isRecycled)
                } finally {
                    session.close()
                    owner.cancel()
                }
            }
        }
    }
}
