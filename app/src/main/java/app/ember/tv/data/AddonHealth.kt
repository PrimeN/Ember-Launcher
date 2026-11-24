package app.ember.tv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.content.edit

/**
 * #430: each stream addon's recent health from the lookups Ember already makes (no polling).
 * OK, Slow (slow answers, a timeout or the last one failed) or Down (the last two failed).
 * Kept by a hash of the addon's address, never the address itself.
 */
object AddonHealth {
    enum class Level(val label: String) { OK("OK"), SLOW("Slow"), DOWN("Down") }
    enum class Outcome { OK, TIMEOUT, FAILED }
    data class Sample(val outcome: Outcome, val ms: Long)

    const val SLOW_MS = 6_000L
    private const val KEEP = 10

    private var prefs: SharedPreferences? = null
    private val samples = HashMap<String, List<Sample>>()
    private val levels = mutableStateMapOf<String, Level>()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("addon-health", Context.MODE_PRIVATE)
    }

    private fun key(url: String) = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).toHex()

    fun outcome(result: Result<*>?): Outcome = when {
        result == null -> Outcome.TIMEOUT
        result.isSuccess -> Outcome.OK
        result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException || result.exceptionOrNull() is java.net.SocketTimeoutException -> Outcome.TIMEOUT
        else -> Outcome.FAILED
    }

    fun level(samples: List<Sample>): Level? {
        if (samples.isEmpty()) return null
        val last = samples.takeLast(2)
        if (last.size == 2 && last.all { it.outcome != Outcome.OK }) return Level.DOWN
        if (samples.last().outcome != Outcome.OK) return Level.SLOW
        if (samples.takeLast(3).any { it.outcome == Outcome.TIMEOUT }) return Level.SLOW
        val times = samples.filter { it.outcome == Outcome.OK }.takeLast(5).map { it.ms }.sorted()
        return if (times.isNotEmpty() && times[times.size / 2] > SLOW_MS) Level.SLOW else Level.OK
    }

    private fun load(k: String): List<Sample> = samples.getOrPut(k) {
        prefs?.getString(k, null).orEmpty().split(';').mapNotNull { p ->
            val (o, ms) = p.split(',').takeIf { it.size == 2 } ?: return@mapNotNull null
            Sample(Outcome.entries.getOrNull(o.toIntOrNull() ?: -1) ?: return@mapNotNull null, ms.toLongOrNull() ?: return@mapNotNull null)
        }
    }

    @Synchronized fun record(url: String, outcome: Outcome, ms: Long) {
        val k = key(url)
        val list = (load(k) + Sample(outcome, ms)).takeLast(KEEP)
        samples[k] = list
        level(list)?.let { levels[k] = it }
        prefs?.edit { putString(k, list.joinToString(";") { "${it.outcome.ordinal},${it.ms}" }) }
    }

    /** Null until the addon has been asked for sources. Read during drawing, so it updates live. */
    fun of(url: String): Level? {
        val k = key(url)
        levels[k]?.let { return it }
        return synchronized(this) { level(load(k)) }
    }

    @Synchronized fun forget(url: String) {
        val k = key(url); samples.remove(k); levels.remove(k); prefs?.edit { remove(k) }
    }
}
