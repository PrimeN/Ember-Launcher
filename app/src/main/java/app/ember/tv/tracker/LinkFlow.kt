package app.ember.tv.tracker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One account-link operation at a time, including public username validation. */
internal class LinkTasks(private val scope: CoroutineScope) {
    private var owner: String? = null
    private var job: Job? = null

    fun start(id: String, block: suspend CoroutineScope.() -> Unit) {
        cancel()
        owner = id
        job = scope.launch(block = block)
    }

    fun cancel(id: String? = null) {
        if (id != null && owner != id) return
        job?.cancel()
        job = null
        owner = null
    }
}

/** Monotonic expiry and persistent backoff; never poll after the code has expired. */
internal suspend fun awaitDeviceLink(
    session: LinkSession,
    poll: suspend (LinkSession) -> Boolean,
    now: () -> Long = { System.nanoTime() / 1_000_000 },
    pause: suspend (Long) -> Unit = { delay(it) },
): Boolean {
    val deadline = now() + session.expiresInSec.coerceAtLeast(0) * 1000L
    var interval = session.intervalSec.coerceAtLeast(3) * 1000L
    while (true) {
        val remaining = deadline - now()
        if (remaining <= 0) return false
        if (remaining <= interval) { pause(remaining); return false }
        pause(interval)
        currentCoroutineContext().ensureActive()
        val timeLeft = deadline - now()
        if (timeLeft <= 0) return false
        try {
            val linked = withTimeoutOrNull(timeLeft) { poll(session) } ?: return false
            currentCoroutineContext().ensureActive()
            if (linked) return true
        } catch (e: HttpException) {
            if (e.code != 429) throw e
            interval = maxOf(interval + 5000L, (e.retryAfterSec ?: 0) * 1000L)
        }
    }
}
