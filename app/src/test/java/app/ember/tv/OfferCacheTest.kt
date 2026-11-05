package app.ember.tv

import app.ember.tv.streaming.Offer
import app.ember.tv.streaming.OfferCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/** Offer loads coalesce per title but never wait behind another title's request. */
class OfferCacheTest {
    private fun offer(name: String) = Offer(1, name, "sub", null, null, null, null)

    @Test fun aSlowTitleDoesNotBlockAnotherTitle() = runBlocking {
        val cache = OfferCache({ 0L })
        val slowGate = CompletableDeferred<Unit>()
        val slow = async { cache.get("a", { true }) { slowGate.await(); listOf(offer("A")) } }
        yield()
        // Would time out if every title shared one lock.
        val other = withTimeout(2_000) { cache.get("b", { true }) { listOf(offer("B")) } }
        assertEquals("B", other.single().name)
        slowGate.complete(Unit)
        assertEquals("A", slow.await().single().name)
    }

    @Test fun theSameTitleIsLoadedOnceWhileFresh() = runBlocking {
        var loads = 0
        val cache = OfferCache({ 1_000L })
        val gate = CompletableDeferred<Unit>()
        val first = async { cache.get("t", { true }) { loads++; gate.await(); listOf(offer("T")) } }
        val second = async { cache.get("t", { true }) { loads++; listOf(offer("T2")) } }
        yield(); gate.complete(Unit)
        assertEquals("T", first.await().single().name)
        assertEquals("T", second.await().single().name)
        assertEquals(1, loads)
    }
}
