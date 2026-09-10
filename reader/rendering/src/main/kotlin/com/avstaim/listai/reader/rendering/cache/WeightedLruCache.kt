package com.avstaim.listai.reader.rendering.cache

/** Owner-thread confined. Eviction drops ownership; a consumer may still hold an immutable value. */
class WeightedLruCache<K, V>(val maxBytes: Long, private val weigh: (V) -> Long) {
    init { require(maxBytes > 0) }
    private data class Entry<V>(val value: V, val bytes: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(16, .75f, true)
    private var pinned = emptySet<K>()
    var bytes: Long = 0; private set
    var evictions: Long = 0; private set
    val size: Int get() = entries.size

    operator fun get(key: K): V? = entries[key]?.value
    fun pin(keys: Set<K>) { pinned = keys }

    /** Never exceeds the budget, including when existing entries are pinned. */
    fun put(key: K, value: V): Boolean {
        val weight = weigh(value)
        require(weight >= 0)
        if (weight > maxBytes) return false
        val old = entries[key]
        val protectedBytes = entries.filterKeys { it in pinned && it != key }.values.sumOf { it.bytes }
        if (protectedBytes + weight > maxBytes) return false
        if (old != null) { entries.remove(key); bytes -= old.bytes }
        evictUntil(maxBytes - weight)
        entries[key] = Entry(value, weight)
        bytes += weight
        return true
    }

    fun snapshot(): Map<K, V> = entries.mapValues { it.value.value }
    fun trimToPinned() { evictUntil(0) }
    fun clear() { entries.clear(); pinned = emptySet(); bytes = 0 }

    private fun evictUntil(target: Long) {
        val iterator = entries.iterator()
        while (bytes > target && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in pinned) { bytes -= entry.value.bytes; iterator.remove(); evictions++ }
        }
    }
}
