package app.ember.tv.iptv

import app.ember.tv.data.Net
import app.ember.tv.data.withResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

class IptvHttpException(val status: Int, val retryAfterSeconds: Long = 0) : Exception(when (status) {
    401, 403 -> "The provider rejected access. Check the login or account status."
    429 -> "The provider is limiting requests. Wait before refreshing again."
    else -> "The provider returned HTTP $status."
})

/** Per-provider rolling budget. Reservation and cooldown changes are atomic and monotonic. */
class ProviderRequestBudget(private val windowMillis: Long = 10_000, private val maximum: Int = 6) {
    private val calls = ArrayDeque<Long>()
    private var cooldown = 0L
    @Volatile var interactiveWaiting = 0
    @Synchronized fun reserve(now: Long, interactive: Boolean): Long {
        while (calls.firstOrNull()?.let { it <= now - windowMillis } == true) calls.removeFirst()
        val wait = maxOf((cooldown - now).coerceAtLeast(0),
            if (calls.size >= maximum) (calls.first() + windowMillis - now).coerceAtLeast(0) else 0,
            if (!interactive && interactiveWaiting > 0) 100 else 0)
        if (wait == 0L) calls.addLast(now)
        return wait
    }
    @Synchronized fun coolDown(now: Long, millis: Long) { cooldown = maxOf(cooldown, now + millis.coerceIn(0, 86_400_000)) }
    @Synchronized fun priority(delta: Int) { interactiveWaiting = (interactiveWaiting + delta).coerceAtLeast(0) }
}

/** No HTTP disk cache: IPTV request URLs and bodies may contain provider credentials. */
internal class ProviderRequestClient(
    client: okhttp3.OkHttpClient,
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val client = client.newBuilder().cache(null).retryOnConnectionFailure(false).build()
    private val budgets = ConcurrentHashMap<String, ProviderRequestBudget>()
    private val background = Semaphore(2)
    private val active = Semaphore(3)

    suspend fun <T> read(provider: String, url: String, agent: String, interactive: Boolean = false,
        consume: (InputStream) -> T): T {
        val operation: suspend () -> T = {
            val budget = budgets.getOrPut(provider) { ProviderRequestBudget() }
            var waitingForPriority = interactive
            if (interactive) budget.priority(1)
            fun releasePriority() {
                if (waitingForPriority) { waitingForPriority = false; budget.priority(-1) }
            }
            try {
                active.withPermit {
                    // Reserve only once a transport slot is available. Queued/cancelled calls
                    // must not consume allowance or build up expired reservations.
                    while (true) {
                        val wait = budget.reserve(now(), interactive)
                        if (wait == 0L) break
                        if (wait > 20_000) throw IptvHttpException(429, wait / 1000)
                        pause(wait.coerceAtMost(1000))
                    }
                    releasePriority()
                    var attempt = 0
                    while (true) {
                        try {
                            return@withPermit coroutineScope {
                                val call = client.newCall(Request.Builder().url(url)
                                    .header("User-Agent", agent.ifBlank { ChannelHeaders.DEFAULT_AGENT }).build())
                                withResponse(call) { r ->
                                    if (!r.isSuccessful) {
                                        val seconds = LiveRetryPolicy.retryAfterSeconds(r.header("Retry-After"))
                                            ?: if (r.code == 429) 2L else 0L
                                        if (r.code == 429) budget.coolDown(now(), seconds * 1000)
                                        throw IptvHttpException(r.code, seconds)
                                    }
                                    withContext(Dispatchers.IO) { r.body.byteStream().use(consume) }
                                }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            val status = (e as? IptvHttpException)?.status
                            val wait = LiveRetryPolicy.delayMillis(attempt, (e as? IptvHttpException)?.retryAfterSeconds?.takeIf { status == 429 || status == 503 })
                            if (attempt++ >= 2 || !LiveRetryPolicy.retryable(status) || wait > 5000 ||
                                (e !is java.io.IOException && e !is IptvHttpException) || e is java.util.zip.ZipException) throw e
                            pause(wait)
                            while (true) {
                                val delay = budget.reserve(now(), interactive)
                                if (delay == 0L) break
                                if (delay > 20_000) throw e
                                pause(delay.coerceAtMost(1000))
                            }
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") error("Unreachable")
                }
            } finally { releasePriority() }
        }
        return if (interactive) operation() else background.withPermit { operation() }
    }
}

object ProviderRequests {
    private val client by lazy { ProviderRequestClient(Net.client(20, 90)) }
    suspend fun <T> read(provider: String, url: String, agent: String, interactive: Boolean = false,
        consume: (InputStream) -> T): T = client.read(provider, url, agent, interactive, consume)

    fun failure(error: Throwable): String = when (error) {
        is IptvHttpException -> error.message.orEmpty()
        is java.io.IOException -> "Could not connect to the provider. Check the connection and try again."
        else -> "The provider response could not be read. Check the playlist or guide format."
    }
}
