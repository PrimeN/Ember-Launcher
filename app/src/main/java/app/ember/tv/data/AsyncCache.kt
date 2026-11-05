package app.ember.tv.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Concurrent readers share one successful load; invalidation wins over late completions. */
class AsyncCache<K : Any, V : Any> {
    private val locks = ConcurrentHashMap<K, Mutex>()
    private val values = mutableMapOf<K, V>()
    private val versions = mutableMapOf<K, Long>()
    fun peek(key: K): V? = synchronized(values) { values[key] }
    fun invalidate(key: K) = synchronized(values) {
        values.remove(key)
        ((versions[key] ?: 0L) + 1).also { versions[key] = it }
    }
    /** Only this refresh may restore its fallback; explicit invalidation and newer values win. */
    fun restore(key: K, value: V, version: Long): V? = synchronized(values) {
        if ((versions[key] ?: 0L) != version) null else values.getOrPut(key) { value }
    }
    suspend fun load(key: K, fetch: suspend () -> V): V = locks.getOrPut(key) { Mutex() }.withLock {
        peek(key)?.let { return@withLock it }
        val version = synchronized(values) { versions[key] ?: 0L }
        val result = fetch()
        currentCoroutineContext().ensureActive()
        synchronized(values) { if ((versions[key] ?: 0L) == version) values[key] = result }
        result
    }
}
