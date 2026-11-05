package app.ember.tv

import app.ember.tv.tracker.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TrackerRecoveryTest {
    private class Service(override val id: String) : Tracker {
        override val name = id
        override val blurb = ""
        override val mode = LinkMode.DEVICE_CODE
        override val configured = true
        override val scrobbles = false
        var connected = true
        var failure = false
        var calls = 0
        var savedCalls = 0
        var items = listOf(TrackedItem("$id:one", id, poster = "poster", progress = .3f))
        var gate: (suspend () -> Unit)? = null
        override fun isConnected() = connected
        override fun accountLabel(): String? = null
        override fun disconnect() { connected = false }
        override suspend fun continueWatching(): List<TrackedItem> {
            calls++
            gate?.invoke()
            if (failure) error("offline")
            return items
        }
        override suspend fun watchlist(): List<TrackedItem> { savedCalls++; if (failure) error("offline"); return items }
    }

    @Test fun targetedRetryKeepsOtherServicesAndDoesNotFetchThemAgain() = runBlocking {
        val a = Service("a"); val b = Service("b"); val hub = TrackerHub(listOf(a, b))
        assertEquals(2, hub.continueWatchingOrNull()!!.size)
        b.items = listOf(TrackedItem("b:new", "New title", poster = "poster"))
        val retried = hub.continueWatchingOrNull(setOf("b"))!!
        assertEquals(setOf("a:one", "b:new"), retried.map { it.key }.toSet())
        assertEquals(1, a.calls); assertEquals(2, b.calls)
    }

    @Test fun partialFailureKeepsOnlyFailedSourcesSavedDataAndEmptySuccessClearsOldData() = runBlocking {
        val a = Service("a"); val b = Service("b"); val hub = TrackerHub(listOf(a, b))
        hub.continueWatchingOrNull()
        a.items = emptyList(); b.failure = true
        assertEquals(listOf("b:one"), hub.continueWatchingOrNull()!!.map { it.key })
        assertTrue(hub.health.entries.getValue("b:progress").cached)
        b.failure = false; b.items = emptyList()
        assertTrue(hub.continueWatchingOrNull(setOf("b"))!!.isEmpty())
        assertFalse(hub.health.entries.getValue("b:progress").failed)
    }

    @Test fun disconnectedAndDisabledSourcesCannotLeakThroughRetryCache() = runBlocking {
        val a = Service("a"); val b = Service("b"); val hub = TrackerHub(listOf(a, b))
        hub.continueWatchingOrNull()
        hub.disabled = setOf("b")
        assertEquals(listOf("a:one"), hub.continueWatchingOrNull(setOf("a"))!!.map { it.key })
        a.connected = false
        assertTrue(hub.continueWatchingOrNull()!!.isEmpty())
        assertTrue(hub.health.entries.isEmpty())
    }

    @Test fun firstOfflineLoadIsFailureRatherThanAnEmptyAccount() = runBlocking {
        val a = Service("a").apply { failure = true }
        val hub = TrackerHub(listOf(a))
        assertNull(hub.continueWatchingOrNull())
        assertTrue(hub.health.entries.getValue("a:progress").failed)
        assertFalse(hub.health.entries.getValue("a:progress").cached)
    }

    @Test fun watchlistAndProgressRetryCachesStaySeparate() = runBlocking {
        val a = Service("a"); val hub = TrackerHub(listOf(a))
        hub.continueWatchingOrNull()
        a.items = emptyList()
        assertTrue(hub.watchlistOrNull()!!.isEmpty())
        a.failure = true
        assertEquals(1, hub.continueWatchingOrNull()!!.size)
        assertTrue(hub.watchlistOrNull()!!.isEmpty())
        assertEquals(2, a.calls); assertEquals(2, a.savedCalls)
    }

    @Test fun cancelledRequestCannotSeedRetryCacheWithLateContent() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val a = Service("a").apply { gate = { entered.complete(Unit); withContext(NonCancellable) { release.await() } } }
        val hub = TrackerHub(listOf(a))
        val job = launch { hub.continueWatchingOrNull() }
        entered.await(); job.cancel(); release.complete(Unit); job.join()
        a.gate = null; a.failure = true
        assertNull(hub.continueWatchingOrNull())
        assertNull(hub.health.entries.getValue("a:progress").lastSuccess)
    }
}
