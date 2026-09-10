package com.avstaim.listai.reader.rendering.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SurfaceStoreState<K, V>(
    val values: Map<K, V> = emptyMap(),
    val bytes: Long = 0,
    val rendered: Long = 0,
    val evictions: Long = 0,
    val failedKeys: Set<K> = emptySet(),
)

/** Requests/cache run on the owner dispatcher. Exactly one background render may be in flight. */
class SurfaceStore<K, V>(
    private val scope: CoroutineScope,
    private val renderDispatcher: CoroutineDispatcher,
    maxBytes: Long,
    weigh: (V) -> Long,
    private val render: suspend (K) -> V,
) {
    private val cache = WeightedLruCache<K, V>(maxBytes, weigh)
    private val mutableState = MutableStateFlow(SurfaceStoreState<K, V>())
    val state = mutableState.asStateFlow()
    private var required = emptyList<K>()
    private var prefetch = emptyList<K>()
    private var worker: Job? = null
    private var generation = 0L
    private var rendered = 0L
    private var failed = emptySet<K>()
    private var closed = false
    private val renderLock = Mutex()

    fun request(required: List<K>, prefetch: List<K> = emptyList()) {
        if (closed) return
        val hot = required.distinct()
        val warm = prefetch.distinct().filterNot { it in hot }
        if (this.required == hot && this.prefetch == warm) return
        this.required = hot
        this.prefetch = warm
        schedule()
    }

    fun trimMemory() {
        if (closed) return
        prefetch = emptyList()
        cache.trimToPinned()
        schedule()
    }

    fun close() {
        closed = true
        generation++
        worker?.cancel()
        cache.clear()
        required = emptyList()
        prefetch = emptyList()
        mutableState.value = SurfaceStoreState()
    }

    private fun publish() {
        mutableState.value = SurfaceStoreState(cache.snapshot(), cache.bytes, rendered, cache.evictions, failed)
    }

    private fun schedule() {
        val ticket = ++generation
        worker?.cancel()
        cache.pin(required.toSet())
        required.forEach { cache[it] } // Current/adjacent pages become most recently used.
        failed = emptySet()
        publish()
        val ordered = required + prefetch
        worker = scope.launch {
            for (key in ordered) {
                ensureActive()
                if (cache[key] != null) continue
                try {
                    // A cancelled intermediate request must not break the serialization chain.
                    // Even an uncooperative renderer finishes before the next allocation begins.
                    val value = renderLock.withLock {
                        ensureActive()
                        withContext(renderDispatcher) { render(key) }
                    }
                    ensureActive()
                    if (closed || ticket != generation) return@launch
                    if (cache.put(key, value)) rendered++ else if (key in required) failed = failed + key
                    publish()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ensureActive()
                    if (closed || ticket != generation) return@launch
                    failed = failed + key
                    publish()
                }
            }
        }
    }
}
