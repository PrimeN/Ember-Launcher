package app.ember.tv.iptv

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Bounded fetches publish independently; serialized callbacks preserve ordered source state. */
object ProgressiveSources {
    suspend fun <S, R> load(sources: List<S>, fetch: suspend (S) -> R, completed: (S, Result<R>) -> Unit) = coroutineScope {
        val permits = Semaphore(2)
        val publish = Mutex()
        sources.map { source -> async {
            val result = permits.withPermit { runCatching { fetch(source) } }
            ensureActive()
            publish.withLock { ensureActive(); completed(source, result) }
        } }.awaitAll()
        Unit
    }
}
