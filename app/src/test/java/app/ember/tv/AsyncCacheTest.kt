package app.ember.tv

import app.ember.tv.data.AsyncCache
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AsyncCacheTest {
    @Test fun concurrentReadersFetchOneManifest() = runBlocking {
        val cache = AsyncCache<String, String>()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val requests = List(4) { async {
            cache.load("provider") { calls++; entered.complete(Unit); release.await(); "manifest" }
        } }
        entered.await(); yield(); assertEquals(1, calls)
        release.complete(Unit)
        assertEquals(listOf("manifest", "manifest", "manifest", "manifest"), requests.awaitAll())
        assertEquals(1, calls)
    }
    @Test fun invalidationDuringRequestPreventsStaleReseeding() = runBlocking {
        val cache = AsyncCache<String, String>()
        val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val old = async { cache.load("provider") { entered.complete(Unit); release.await(); "old" } }
        entered.await(); cache.invalidate("provider"); release.complete(Unit); old.await()
        assertNull(cache.peek("provider"))
        assertEquals("new", cache.load("provider") { "new" })
    }
    @Test fun cancelledOwnerDoesNotPoisonWaitingReaders() = runBlocking {
        val cache = AsyncCache<String, String>()
        val entered = CompletableDeferred<Unit>()
        val owner = launch { cache.load("provider") { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        val waiter = async { cache.load("provider") { "fresh" } }
        owner.cancelAndJoin()
        assertEquals("fresh", waiter.await())
    }
    @Test fun failuresAreNotCached() = runBlocking {
        val cache = AsyncCache<String, String>()
        assertTrue(runCatching { cache.load("provider") { error("offline") } }.isFailure)
        assertEquals("recovered", cache.load("provider") { "recovered" })
    }
}
