package app.ember.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * One network stack for the whole app: addons, trackers, servers, subtitles, IPTV, updates and
 * images share a connection pool instead of each part opening its own. API clients share a
 * dispatcher and 64 MB HTTP cache. Artwork uses its own bounded queue and Coil's image cache.
 * Each part still sets its own time limits with [client] or [artworkClient].
 */
object Net {
    private var cacheDir: File? = null

    /** Called first thing in EmberApp, so the cache lives in the app's cache folder. */
    fun init(context: Context) {
        cacheDir = File(context.cacheDir, "http")
    }

    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .apply { cacheDir?.let { cache(Cache(it, 64L * 1024 * 1024)) } }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
            .also { it.dispatcher.maxRequestsPerHost = 8 }
    }

    /** Artwork has its own bounded queue so slow addon/metadata calls cannot occupy its slots. */
    private val artworkDispatcher by lazy { okhttp3.Dispatcher().apply {maxRequests=16;maxRequestsPerHost=8} }
    fun artworkClient(connect:Long=10,read:Long=30):OkHttpClient = client(connect,read).newBuilder()
        .dispatcher(artworkDispatcher).cache(null).build()

    /** The shared client with its own time limits (seconds). */
    fun client(connect: Long, read: Long): OkHttpClient = base.newBuilder()
        .connectTimeout(connect, TimeUnit.SECONDS)
        .readTimeout(read, TimeUnit.SECONDS)
        .build()

    /** Runs a call without blocking a thread; cancelling the coroutine (e.g. a time limit) cancels the request. */
    suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, discarded, _ -> discarded.close() }
            }
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        })
    }
}

/**
 * Runs [call] and hands its response to [block], closing it afterwards. Cancelling the coroutine
 * cancels the request, also while [block] is still reading the body ([Net.await] alone stops
 * covering the call once the response has arrived). [block] may return early from its caller.
 */
suspend inline fun <T> CoroutineScope.withResponse(call: Call, block: (Response) -> T): T {
    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try {
        return with(Net) { call.await() }.use(block)
    } finally { closer.cancel() }
}

/** Thrown when a remote reply is larger than the part of Ember reading it allows. */
class ResponseTooLargeException(limit: Long) : IOException("The reply is larger than ${limit / (1024 * 1024)} MB.")

/**
 * Reads a body as UTF-8 text, refusing replies over [maxBytes] instead of holding all of them in
 * memory. Third-party addons and imported files can otherwise send anything.
 */
fun okhttp3.ResponseBody.boundedText(maxBytes: Long): String {
    if (contentLength() > maxBytes) throw ResponseTooLargeException(maxBytes)
    val source = source()
    if (source.request(maxBytes + 1)) throw ResponseTooLargeException(maxBytes)
    // After the size check, preserve OkHttp's declared-charset and BOM decoding.
    return string()
}

/** Size limits for replies that are read whole into memory. */
object ResponseLimits {
    /** Addon manifests, catalogs, metadata, streams and subtitles. Large catalogs are a few MB. */
    const val ADDON = 16L * 1024 * 1024
    /** Imported collection files and badge packs. */
    const val IMPORT = 8L * 1024 * 1024
}
