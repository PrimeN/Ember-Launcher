package app.ember.tv

import app.ember.tv.tracker.Http
import app.ember.tv.tracker.HttpException
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import okio.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TrackerHttpCancellationTest {
    private class Fixture(private val text: String = "{\"ok\":true}", private val status: Int = 200, val blockBody: Boolean = false) {
        val entered = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val closed = AtomicBoolean()
        val call = FakeCall()
        inner class FakeCall : Call by OkHttpClient().newCall(Request.Builder().url("https://example.test/fixture").build()) {
            lateinit var callback: Callback
            private val cancelled = AtomicBoolean()
            override fun enqueue(responseCallback: Callback) { callback = responseCallback }
            override fun execute(): Response = error("Unexpected blocking HTTP call")
            override fun cancel() { cancelled.set(true); released.countDown() }
            override fun isCanceled() = cancelled.get()
            override fun timeout() = Timeout()
            fun deliver() {
                val input = if (blockBody) object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        entered.complete(Unit)
                        check(released.await(5, TimeUnit.SECONDS)) { "Cancelled call did not release its body" }
                        throw IOException("cancelled fixture")
                    }
                    override fun timeout() = Timeout()
                    override fun close() { closed.set(true); released.countDown() }
                } else object : ForwardingSource(Buffer().writeUtf8(text)) {
                    override fun close() { closed.set(true); super.close() }
                }
                val body = object : ResponseBody() {
                    private val data = input.buffer()
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = if (blockBody) -1L else text.toByteArray().size.toLong()
                    override fun source() = data
                }
                callback.onResponse(this, Response.Builder().request(request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("fixture").header("Retry-After", "3").body(body).build())
            }
        }
    }

    @Test fun trackerJsonResponseIsParsedAndClosed() = runBlocking {
        val fixture = Fixture()
        val task = async(start = CoroutineStart.UNDISPATCHED) { Http.read(fixture.call) }
        fixture.call.deliver()
        assertEquals("true", withTimeout(2000) { task.await() }.jsonObject.getValue("ok").jsonPrimitive.content)
        assertTrue(fixture.closed.get())
    }

    @Test fun blankResponseRemainsJsonNull() = runBlocking {
        val fixture = Fixture("")
        val task = async(start = CoroutineStart.UNDISPATCHED) { Http.read(fixture.call) }
        fixture.call.deliver(); assertEquals(JsonNull, withTimeout(2000) { task.await() }); assertTrue(fixture.closed.get())
    }

    @Test fun httpFailureRetainsStatusAndRetryAfterAndClosesItsBody() = runBlocking {
        val fixture = Fixture("fixture refusal", 429)
        val task = async(start = CoroutineStart.UNDISPATCHED) { runCatching { Http.read(fixture.call) } }
        fixture.call.deliver()
        val error = withTimeout(2000) { task.await() }.exceptionOrNull() as HttpException
        assertEquals(429, error.code); assertEquals(3L, error.retryAfterSec); assertTrue(fixture.closed.get())
    }

    @Test fun cancellingAfterHeadersCancelsTheTransportAndReleasesItsBlockedBody() = runBlocking {
        val fixture = Fixture(blockBody = true)
        val task = launch(start = CoroutineStart.UNDISPATCHED) { Http.read(fixture.call) }
        try {
            fixture.call.deliver(); withTimeout(2000) { fixture.entered.await() }
            task.cancel(); withTimeout(2000) { task.join() }
            assertTrue(fixture.call.isCanceled()); assertTrue(fixture.closed.get()); assertTrue(task.isCancelled)
        } finally { task.cancel(); fixture.released.countDown() }
    }

    @Test fun privateResponseLimitRejectsOversizedBodyAndClosesIt() = runBlocking {
        val fixture = Fixture("{\"value\":\"" + "x".repeat(100) + "\"}")
        val task = async(start = CoroutineStart.UNDISPATCHED) { runCatching { Http.read(fixture.call, 32) } }
        fixture.call.deliver()
        assertTrue(withTimeout(2000) { task.await() }.isFailure); assertTrue(fixture.closed.get())
    }

    @Test fun boundedPrivateResponseCanStillParseValidJson() = runBlocking {
        val fixture = Fixture()
        val task = async(start = CoroutineStart.UNDISPATCHED) { Http.read(fixture.call, 100) }
        fixture.call.deliver()
        assertEquals("true", withTimeout(2000) { task.await() }.jsonObject.getValue("ok").jsonPrimitive.content)
        assertTrue(fixture.closed.get())
    }
}
