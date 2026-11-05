package app.ember.tv.iptv

/** Numbers are resolved in the current authorized order; ambiguous provider numbers require choice. */
object LiveRemote {
    fun digit(keyCode: Int): String? = when (keyCode) {
        in 7..16 -> (keyCode - 7).toString() // Android KEYCODE_0..9
        in 144..153 -> (keyCode - 144).toString() // Numpad
        else -> null
    }
    fun matches(channels: List<Channel>, digits: String): List<Channel> = digits.toIntOrNull()?.let { number ->
        channels.filter { it.number == number }
    }.orEmpty()
    fun previous(current: String, history: List<String>, available: List<Channel>): Channel? =
        history.firstNotNullOfOrNull { key -> available.firstOrNull { it.key == key && key != current } }
}

object LiveRetryPolicy {
    /** Retry-After allows either seconds or an RFC 1123 HTTP date. */
    fun retryAfterSeconds(value: String?, now: Long = System.currentTimeMillis()): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        text.toLongOrNull()?.let { return it.coerceIn(0, 86_400) }
        return runCatching {
            val until = java.time.ZonedDateTime.parse(text, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            ((until - now + 999) / 1000).coerceIn(0, 86_400)
        }.getOrNull()
    }

    fun retryable(status: Int?) = status == null || status == 408 || status == 429 || status in 500..599
    fun delayMillis(attempt: Int, retryAfterSeconds: Long? = null): Long =
        maxOf((500L shl attempt.coerceIn(0, 3)), (retryAfterSeconds ?: 0).coerceIn(0, 86_400) * 1000).coerceAtMost(60_000)
}

/** A brief successful reconnect cannot replenish retries; sustained playback can. */
class LiveRetryBudget(private val maximum: Int = 2, private val stableMillis: Long = 60_000) {
    var attempts = 0; private set
    private var readySince: Long? = null
    private fun rearm(now: Long) {
        if (readySince?.let { now - it >= stableMillis } == true) attempts = 0
    }
    fun playback(ready: Boolean, now: Long) {
        if (ready) { if (readySince == null) readySince = now }
        else { rearm(now); readySince = null }
    }
    fun next(now: Long): Int? {
        rearm(now); readySince = null
        return if (attempts < maximum) attempts++ else null
    }
    fun reset() { attempts = 0; readySince = null }
}
