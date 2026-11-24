package app.ember.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * #429: sources for a title are looked up before it's opened (the Continue watching title in
 * focus), so its title page has them at once. One title at a time; results keep three minutes.
 * The title page takes a finished or running lookup instead of asking the addon again.
 */
object SourceWarmup {
    const val KEEP_MS = 3 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Entry(val key: String, val at: Long, val lookups: Map<String, Deferred<Result<Pair<List<StreamOption>, List<app.ember.tv.streaming.Offer>>>?>>)
    @Volatile private var entry: Entry? = null
    private var job: Job? = null

    fun key(type: String, id: String) = "$type/$id"

    /** What the stream addons are asked for this title or episode: movie/tt… or series/tt…:S:E. */
    fun target(meta: Meta): Pair<String, String>? {
        val imdb = meta.imdb ?: meta.id.takeIf { it.startsWith("tt") } ?: return null
        return when {
            meta.type == "movie" -> "movie" to imdb
            meta.season != null && meta.episode != null -> "series" to "$imdb:${meta.season}:${meta.episode}"
            else -> null
        }
    }

    @Synchronized fun warm(client: AddonClient, addons: List<AddonInfo>, type: String, id: String, limitMs: Long, onResult: (AddonInfo, Result<*>?, Long) -> Unit = { _, _, _ -> }) {
        val key = key(type, id)
        val now = System.currentTimeMillis()
        entry?.let { if (it.key == key && now - it.at < KEEP_MS) return }
        job?.cancel()
        val parent = SupervisorJob()
        job = parent
        val lookups = addons.filter { it.supports("stream", type, id) }.associate { addon ->
            addon.url to scope.async(parent) {
                val started = System.currentTimeMillis()
                val r = kotlinx.coroutines.withTimeoutOrNull(limitMs) { runCatching { client.sources(addon, type, id) } }
                onResult(addon, r, System.currentTimeMillis() - started)
                r
            }
        }
        entry = Entry(key, now, lookups)
    }

    /** A lookup already made (or running) for this addon and title, if still fresh. */
    @Synchronized fun lookup(type: String, id: String, addonUrl: String): Deferred<Result<Pair<List<StreamOption>, List<app.ember.tv.streaming.Offer>>>?>? {
        val e = entry ?: return null
        if (e.key != key(type, id) || System.currentTimeMillis() - e.at >= KEEP_MS) return null
        val d = e.lookups[addonUrl] ?: return null
        if (d.isCancelled) return null
        return d
    }

    @Synchronized fun forget() { job?.cancel(); job = null; entry = null }
}
