package app.ember.tv

import app.ember.tv.iptv.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class IptvProviderRequestsTest {
    private class Fixture(val codes: List<Int>, val retryAfter: String? = null) {
        val calls = AtomicInteger()
        val now = AtomicLong()
        val waits = mutableListOf<Long>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val code = codes[calls.getAndIncrement().coerceAtMost(codes.lastIndex)]
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body("provider data".toResponseBody()).apply { retryAfter?.let { header("Retry-After", it) } }.build()
        }.build()
        val client = ProviderRequestClient(http, now::get) { waits += it; now.addAndGet(it) }
        suspend fun read(): String = client.read("provider", "https://example.com/playlist?password=test-fixture", "", true) { it.bufferedReader().readText() }
    }
    @Test fun successfulResponseIsConsumedWithoutRetry() = runBlocking {
        val fixture = Fixture(listOf(200))
        assertEquals("provider data", fixture.read())
        assertEquals(1, fixture.calls.get()); assertTrue(fixture.waits.isEmpty())
    }
    @Test fun transientResponsesRetryWithBoundedBackoff() = runBlocking {
        val fixture = Fixture(listOf(503, 502, 200))
        assertEquals("provider data", fixture.read())
        assertEquals(3, fixture.calls.get()); assertEquals(listOf(500L, 1000L), fixture.waits)
        val failing = Fixture(listOf(500))
        val error = runCatching { failing.read() }.exceptionOrNull() as IptvHttpException
        assertEquals(500, error.status); assertEquals(3, failing.calls.get())
    }
    @Test fun deniedOrMissingChannelDoesNotRetryOrExposeUrl() = runBlocking {
        for (code in listOf(401, 403, 404)) {
            val fixture = Fixture(listOf(code, 200))
            val error = runCatching { fixture.read() }.exceptionOrNull() as IptvHttpException
            assertEquals(code, error.status); assertEquals(1, fixture.calls.get())
            assertFalse(error.message.orEmpty().contains("password")); assertFalse(error.message.orEmpty().contains("example.com"))
        }
    }
    @Test fun longProviderCooldownStopsAutomaticRetriesAndAlsoBlocksTheNextRefresh() = runBlocking {
        val fixture = Fixture(listOf(429, 200), "60")
        assertEquals(429, (runCatching { fixture.read() }.exceptionOrNull() as IptvHttpException).status)
        assertEquals(429, (runCatching { fixture.read() }.exceptionOrNull() as IptvHttpException).status)
        assertEquals(1, fixture.calls.get()); assertTrue(fixture.waits.isEmpty())
        fixture.now.set(60_000)
        assertEquals("provider data", fixture.read()); assertEquals(2, fixture.calls.get())
    }
    @Test fun shortProviderCooldownIsObservedBeforeRetry() = runBlocking {
        val fixture = Fixture(listOf(429, 200), "3")
        assertEquals("provider data", fixture.read()); assertEquals(listOf(3000L), fixture.waits)
        assertEquals(2, fixture.calls.get())
    }
    @Test fun parsingErrorsDoNotRetryTheDownload() = runBlocking {
        val fixture = Fixture(listOf(200))
        val result = runCatching { fixture.client.read("provider", "https://example.com/guide", "") { error("bad format") } }
        assertTrue(result.isFailure); assertEquals(1, fixture.calls.get()); assertTrue(fixture.waits.isEmpty())
    }
    @Test fun queuedRequestsDoNotReserveOrWaitBeforeTheyHaveATransportSlot() = runBlocking {
        exerciseQueuedRequests(checkRemainingAllowance = false)
    }

    @Test fun cancelledQueuedRequestsDoNotSpendTheProvidersRemainingAllowance() = runBlocking {
        exerciseQueuedRequests(checkRemainingAllowance = true)
    }

    private suspend fun exerciseQueuedRequests(checkRemainingAllowance: Boolean) = coroutineScope {
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = AtomicInteger(); val waits = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (calls.incrementAndGet() <= 3) check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("ok".toResponseBody()).build()
        }.build()
        val client = ProviderRequestClient(http, { 0L }) { waits.incrementAndGet(); awaitCancellation() }
        val jobs = mutableListOf<Deferred<String>>()
        fun request() = async(start = CoroutineStart.UNDISPATCHED) {
            client.read("queued-provider", "https://example.test/fixture", "", true) { it.bufferedReader().readText() }
        }.also { jobs += it }
        try {
            val running = List(3) { request() }
            val queued = List(if (checkRemainingAllowance) 3 else 4) { request() }
            assertEquals("Only requests with a transport slot may reserve provider budget", 0, waits.get())
            queued.forEach { it.cancel() }; queued.forEach { it.join() }
            release.countDown(); withTimeout(2000) { running.awaitAll() }
            if (checkRemainingAllowance) {
                val remaining = List(3) { request() }
                assertEquals("Cancelled queue entries cannot consume request allowance", 0, waits.get())
                assertEquals(listOf("ok", "ok", "ok"), withTimeout(2000) { remaining.awaitAll() })
            }
        } finally {
            release.countDown(); jobs.forEach { it.cancel() }; jobs.forEach { it.join() }
            http.dispatcher.cancelAll(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
    }

    @Test fun cancellationDuringBackoffNeverStartsAnotherRequest() = runBlocking {
        val fixture = Fixture(listOf(503, 200))
        val sleeping = CompletableDeferred<Unit>()
        val client = ProviderRequestClient(fixture.http, fixture.now::get) { sleeping.complete(Unit); awaitCancellation() }
        val job = launch { client.read("provider", "https://example.com/guide", "") { it.read() } }
        withTimeout(5000) { sleeping.await(); job.cancelAndJoin() }
        assertEquals(1, fixture.calls.get())
    }
}
