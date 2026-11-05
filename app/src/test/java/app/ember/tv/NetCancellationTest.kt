package app.ember.tv

import app.ember.tv.data.Net
import app.ember.tv.data.withResponse
import kotlinx.coroutines.*
import okhttp3.*
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class NetCancellationTest {
    @Test fun sharedResponseHelperClosesAConsumedBodyAndFinishesItsCloser() {
        val dispatcher=QueuedDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val call=FakeCall();var closed=false;var text:String?=null
        try {
            val job=scope.launch {text=withResponse(call){it.body.string()}}
            dispatcher.drain();call.callback.onResponse(call,trackedResponse(call){closed=true});dispatcher.drain()
            assertEquals("body",text);assertTrue(closed);assertTrue(call.isCanceled());assertTrue(job.isCompleted)
        } finally {scope.cancel();dispatcher.drain()}
    }

    @Test fun sharedResponseHelperClosesOnHandlerFailure() {
        val dispatcher=QueuedDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val call=FakeCall();var closed=false;var failure:Throwable?=null
        try {
            val job=scope.launch {failure=runCatching {withResponse(call){error("parse failure")}}.exceptionOrNull()}
            dispatcher.drain();call.callback.onResponse(call,trackedResponse(call){closed=true});dispatcher.drain()
            assertEquals("parse failure",failure?.message);assertTrue(closed);assertTrue(call.isCanceled());assertTrue(job.isCompleted)
        } finally {scope.cancel();dispatcher.drain()}
    }

    @Test fun sharedResponseHelperCancelsAfterResponseDeliveryWhileConsumerIsActive() {
        val dispatcher=QueuedDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val call=FakeCall();var closed=false;var entered=false
        try {
            val job=scope.launch {withResponse(call){entered=true;awaitCancellation()}}
            dispatcher.drain();call.callback.onResponse(call,trackedResponse(call){closed=true});dispatcher.drain()
            assertTrue(entered);assertFalse(closed)
            job.cancel();dispatcher.drain()
            assertTrue(call.isCanceled());assertTrue(closed);assertTrue(job.isCompleted)
        } finally {scope.cancel();dispatcher.drain()}
    }

    private fun trackedResponse(call:Call,onClosed:()->Unit):Response {
        val body=object:ResponseBody() {
            private val input=object:ForwardingSource(Buffer().writeUtf8("body")) {
                override fun close() {onClosed();super.close()}
            }.buffer()
            override fun contentType():MediaType?=null
            override fun contentLength()=4L
            override fun source():BufferedSource=input
        }
        return Response.Builder().request(call.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
    }

    @Test fun cancellationBeforeDeliveryClosesTheResponseAndCancelsTheCall() {
        exercise(cancelBeforeResponse = false)
    }

    @Test fun lateResponseAfterCancellationIsClosed() {
        exercise(cancelBeforeResponse = true)
    }

    private fun exercise(cancelBeforeResponse: Boolean) {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val call = FakeCall()
        var closed = false
        var delivered = false
        val body = object : ResponseBody() {
            private val input = object : ForwardingSource(Buffer().writeUtf8("body")) {
                override fun close() { closed = true; super.close() }
            }.buffer()
            override fun contentType(): MediaType? = null
            override fun contentLength() = 4L
            override fun source(): BufferedSource = input
        }
        val response = Response.Builder().request(call.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(body).build()
        try {
            val job = scope.launch { with(Net) { call.await() }.use { delivered = true } }
            dispatcher.drain()
            if (cancelBeforeResponse) job.cancel()
            call.callback.onResponse(call, response)
            if (!cancelBeforeResponse) job.cancel()
            dispatcher.drain()
            assertTrue(call.isCanceled())
            assertTrue(closed)
            assertFalse(delivered)
        } finally { scope.cancel(); dispatcher.drain() }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private class FakeCall : Call by OkHttpClient().newCall(
        Request.Builder().url("https://example.com/").build(),
    ) {
        lateinit var callback: Callback
        private var cancelled = false
        override fun request() = Request.Builder().url("https://example.com/").build()
        override fun execute(): Response = error("unexpected synchronous call")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = ::callback.isInitialized
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = FakeCall()
    }
}
