package com.avstaim.listai.reader.rendering.cache

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class SurfaceStoreTest {
    @Test fun rendersHotPagesBeforePrefetchAndReusesAlreadyPreparedSurfaces() = runBlocking {
        val order = mutableListOf<Int>()
        val store = SurfaceStore<Int, Int>(this, Dispatchers.Unconfined, 64, { 8L }) { order += it; it }
        try {
            store.request(listOf(4, 5, 2, 3, 6, 7), listOf(8, 9))
            withTimeout(3000) { store.state.first { it.values.size == 8 } }
            assertEquals(listOf(4, 5, 2, 3, 6, 7, 8, 9), order)
            store.request(listOf(6, 7, 4, 5, 8, 9), listOf(10, 11))
            withTimeout(3000) { store.state.first { 11 in it.values } }
            assertEquals(listOf(4, 5, 2, 3, 6, 7, 8, 9, 10, 11), order)
            assertEquals(64L, store.state.value.bytes)
            assertEquals(2L, store.state.value.evictions)
        } finally { store.close() }
    }

    @Test fun reversalCancelsSlowPrefetchWhileCachedPageStaysAvailable() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val store = SurfaceStore<Int, Any>(this, Dispatchers.Unconfined, 64, { 8L }) {
            if (it == 3) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            Any()
        }
        try {
            store.request(listOf(1, 2), listOf(3))
            withTimeout(3000) { entered.await() }
            val active = store.state.value.values[1]
            store.request(listOf(1, 0), listOf(-1))
            withTimeout(3000) { store.state.first { -1 in it.values } }
            assertTrue(cancelled.isCompleted)
            assertSame(active, store.state.value.values[1])
            assertFalse(3 in store.state.value.values)
        } finally { store.close() }
    }

    @Test fun repeatedReprioritizationDoesNotOverlapUncooperativeRenderOrPublishStaleValue() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        var active = 0
        var maxActive = 0
        val store = SurfaceStore<Int, Int>(this, Dispatchers.Unconfined, 64, { 8L }) { key ->
            active++; maxActive = maxOf(active, maxActive); started += key
            try {
                if (key == 1) withContext(NonCancellable) { entered.complete(Unit); release.await() }
                key
            } finally { active-- }
        }
        try {
            store.request(listOf(1))
            withTimeout(3000) { entered.await() }
            store.request(listOf(2)); yield()
            store.request(listOf(3)); yield()
            assertEquals(listOf(1), started)
            release.complete(Unit)
            withTimeout(3000) { store.state.first { 3 in it.values } }
            assertEquals(listOf(1, 3), started)
            assertEquals(1, maxActive)
            assertEquals(setOf(3), store.state.value.values.keys)
        } finally { release.complete(Unit); store.close() }
    }

    @Test fun trimReleasesFarPagesButKeepsHotIdentityAndDoesNotResumePrefetch() = runBlocking {
        val store = SurfaceStore<Int, Any>(this, Dispatchers.Unconfined, 64, { 8L }) { Any() }
        try {
            store.request(listOf(2, 1, 3), listOf(4, 5))
            withTimeout(3000) { store.state.first { 5 in it.values } }
            val old = store.state.value
            store.trimMemory(); yield()
            assertEquals(setOf(1, 2, 3), store.state.value.values.keys)
            assertSame(old.values[2], store.state.value.values[2])
            assertEquals(24L, store.state.value.bytes)
            assertEquals(old.rendered, store.state.value.rendered)
        } finally { store.close() }
    }

    @Test fun closeInvalidatesLateResultsAndFutureRequests() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val store = SurfaceStore<Int, Int>(this, Dispatchers.Unconfined, 64, { 8L }) {
            withContext(NonCancellable) { entered.complete(Unit); release.await() }
            finished.complete(Unit)
            it
        }
        try {
            store.request(listOf(1))
            withTimeout(3000) { entered.await() }
            store.close()
            store.request(listOf(2))
            release.complete(Unit)
            withTimeout(3000) { finished.await() }; yield()
            assertTrue(store.state.value.values.isEmpty())
            assertEquals(0L, store.state.value.bytes)
        } finally { release.complete(Unit); store.close() }
    }

    @Test fun failedRenderDoesNotPreventOtherHotPagesAndNeverExceedsBudget() = runBlocking {
        val store = SurfaceStore<Int, Int>(this, Dispatchers.Unconfined, 8, { 8L }) {
            if (it == 1) error("Decode failed") else it
        }
        try {
            store.request(listOf(1, 2), listOf(3))
            withTimeout(3000) { store.state.first { 2 in it.values } }; yield()
            assertEquals(setOf(1), store.state.value.failedKeys)
            assertEquals(setOf(2), store.state.value.values.keys)
            assertEquals(8L, store.state.value.bytes)
        } finally { store.close() }
    }
}
