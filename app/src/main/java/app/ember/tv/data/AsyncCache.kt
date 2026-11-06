package app.ember.tv.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Concurrent readers share one successful load; invalidation wins over late completions. */
class AsyncCache<K : Any, V : Any>(private val kind: CacheKind? = null) {
    private val locks = ConcurrentHashMap<K, Mutex>()
    private val values = mutableMapOf<K, V>()
    private val versions = mutableMapOf<K, Long>()
    private var epoch = kind?.let(CachePolicy::epoch)
    private fun syncEpoch() {
        val current = kind?.let(CachePolicy::epoch)
        if (current != epoch) {
            epoch = current; values.clear()
            versions.keys.toList().forEach { versions[it] = (versions[it] ?: 0) + 1 }
        }
    }
    fun peek(key: K): V? = synchronized(values) { syncEpoch(); values[key] }
    fun invalidate(key: K) = synchronized(values) {
        syncEpoch()
        values.remove(key)
        ((versions[key] ?: 0L) + 1).also { versions[key] = it }
    }
    /** Only this refresh may restore its fallback; explicit invalidation and newer values win. */
    fun restore(key: K, value: V, version: Long): V? = synchronized(values) {
        syncEpoch()
        if ((versions[key] ?: 0L) != version) null else values.getOrPut(key) { value }
    }
    suspend fun load(key: K, fetch: suspend () -> V): V = locks.getOrPut(key) { Mutex() }.withLock {
        peek(key)?.let { return@withLock it }
        val version = synchronized(values) { syncEpoch(); versions.getOrPut(key) { 0L } }
        val result = fetch()
        currentCoroutineContext().ensureActive()
        synchronized(values) { syncEpoch(); if ((versions[key] ?: 0L) == version) values[key] = result }
        result
    }
}
