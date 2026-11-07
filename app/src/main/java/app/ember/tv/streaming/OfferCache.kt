package app.ember.tv.streaming

import app.ember.tv.data.toHex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.Locale

/**
 * Session-only public offers: bounded, expiring and separated by provider credential.
 * Requests for the same title share one load; different titles load independently, so a slow
 * provider reply for one title never holds up another title's page.
 */
internal class OfferCache(private val clock: () -> Long, private val capacity: Int = 96) {
    private class Flight { val mutex = Mutex(); var users = 0 }
    private val flights = HashMap<String, Flight>()
    private val values = LinkedHashMap<String, Pair<Long, List<Offer>>>(16, .75f, true)

    private fun fresh(id: String): List<Offer>? = synchronized(values) {
        val now = clock()
        values[id]?.takeIf { now - it.first in 0 until 6 * 60 * 60_000L &&
            it.second.all { offer -> offer.expiresAt?.let { expires -> now < expires } != false }
        }?.second
    }

    suspend fun get(id: String, current: () -> Boolean, load: suspend () -> List<Offer>): List<Offer> {
        val flight = synchronized(flights) { flights.getOrPut(id) { Flight() }.also { it.users++ } }
        try {
            return flight.mutex.withLock {
                currentCoroutineContext().ensureActive()
                check(current()) { "Streaming provider configuration changed." }
                fresh(id)?.let { return@withLock it }
                val result = load()
                currentCoroutineContext().ensureActive()
                check(current()) { "Streaming provider configuration changed." }
                synchronized(values) {
                    values[id] = clock() to result
                    while (values.size > capacity) values.remove(values.keys.first())
                }
                result
            }
        } finally {
            synchronized(flights) { flight.users--; if (flight.users == 0) flights.remove(id) }
        }
    }
}

internal object OfferRequests {
    fun country(value: String) = value.uppercase(Locale.ROOT).also {
        require(it.matches(Regex("[A-Z]{2}"))) { "Use a two-letter country code." }
    }
    fun imdb(value: String?) = value?.takeIf { it.matches(Regex("tt[0-9]{5,12}")) }
    fun identity(credential: String, region: String, title: String): String {
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(credential.toByteArray())
            .toHex()
        return "$fingerprint:$region:$title"
    }
}
