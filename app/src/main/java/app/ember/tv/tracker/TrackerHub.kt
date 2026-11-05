package app.ember.tv.tracker

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive

/** Holds every tracker, merges what they return, and fans scrobbles out to all of them. */
class TrackerHub internal constructor(val trackers: List<Tracker>) {
    constructor(context: Context) : this(listOf(
        TraktTracker(context),
        WeTrakrTracker(context),
        SimklTracker(context),
        AniListTracker(context),
        MalTracker(context),
    ))

    val health = app.ember.tv.data.SourceHealth("trackers")
    private val progressCache = mutableMapOf<String, List<TrackedItem>>()
    private val watchlistCache = mutableMapOf<String, List<TrackedItem>>()

    /** Tracker ids switched off for Home. They stay signed in. */
    var disabled: Set<String> = emptySet()

    fun byId(id: String) = trackers.first { it.id == id }

    private fun active() = trackers.filter { it.isConnected() && it.id !in disabled }

    suspend fun continueWatching(): List<TrackedItem> = continueWatchingOrNull().orEmpty()
    suspend fun watchlist(): List<TrackedItem> = watchlistOrNull().orEmpty()

    suspend fun continueWatchingOrNull(only: Set<String>? = null): List<TrackedItem>? =
        gatherOrNull("progress", "In progress", progressCache, only) { it.continueWatching() }
            ?.sortedByDescending { it.progress ?: 0f }
    suspend fun watchlistOrNull(only: Set<String>? = null): List<TrackedItem>? =
        gatherOrNull("watchlist", "Watchlist", watchlistCache, only) { it.watchlist() }

    private suspend fun gatherOrNull(
        kind: String, label: String, cache: MutableMap<String, List<TrackedItem>>,
        only: Set<String>?, load: suspend (Tracker) -> List<TrackedItem>,
    ): List<TrackedItem>? = coroutineScope {
        health.cancelLoading(health.entries.keys.filter { it.endsWith(":$kind") }.toSet())
        val enabled = active()
        val ids = enabled.map { it.id }.toSet()
        cache.keys.retainAll(ids)
        health.retain(ids.flatMap { listOf("$it:progress", "$it:watchlist", "$it:history") }.toSet())
        val targets = enabled.filter { only == null || it.id in only }
        targets.forEach { health.start("${it.id}:$kind", it.name + " · " + label) }
        val results = targets.map { t -> async { t to runCatching { load(t) } } }.awaitAll()
        ensureActive()
        results.forEach { (tracker, result) ->
            result.onSuccess { cache[tracker.id] = it }
            health.finish("${tracker.id}:$kind", result.isSuccess, result.isFailure && cache[tracker.id]?.isNotEmpty() == true, failure=result.exceptionOrNull()?.let(app.ember.tv.data.SourceFailure::from))
        }
        if (targets.isNotEmpty() && results.all { it.second.isFailure } && cache.isEmpty()) return@coroutineScope null
        val all = enabled.flatMap { cache[it.id].orEmpty() }
        val merged = LinkedHashMap<String, TrackedItem>()
        for (item in all) {
            val old = merged[item.dedupeKey]
            merged[item.dedupeKey] = when {
                old == null -> item
                (item.progress ?: 0f) > (old.progress ?: 0f) -> item.copy(poster = item.poster ?: old.poster)
                else -> old.copy(poster = old.poster ?: item.poster)
            }
        }
        enrichAll(merged.values.toList())
    }

    suspend fun enrichAll(items: List<TrackedItem>): List<TrackedItem> = coroutineScope {
        items.map { async { enrich(it) } }.awaitAll()
    }

    /**
     * Fills in a title and poster from TMDB when the tracker did not send them.
     * Without a TMDB key, falls back to Stremio's image service by IMDb id.
     */
    private suspend fun enrich(item: TrackedItem): TrackedItem {
        if (item.poster != null && item.title.isNotBlank()) return item
        val info = item.tmdbId?.let { Tmdb.lookup(item.kind, it) }
        val enriched = if (info == null) item else item.copy(
            title = item.title.ifBlank { info.title },
            year = item.year ?: info.year,
            poster = item.poster ?: info.poster,
        )
        if (enriched.poster == null && !enriched.imdbId.isNullOrBlank()) {
            return enriched.copy(poster = "https://images.metahub.space/poster/medium/${enriched.imdbId}/img")
        }
        return enriched
    }

    /** The Trakt tracker when it is connected and switched on for Home. */
    fun trakt(): TraktTracker? = (trackers.firstOrNull { it.id == "trakt" } as? TraktTracker)
        ?.takeIf { it.isConnected() && it.id !in disabled }

    data class WatchlistResult(val succeeded: Map<String, String>, val pending: Set<String>)

    fun watchlistTargets(ref: TitleRef, add: Boolean): Set<String> = active()
        .filter { it.supportsWatchlist(ref, add) }.map { it.id }.toSet()

    /** Retry only the services still owed this change, retaining disconnected/disabled targets. */
    suspend fun writeWatchlist(ref: TitleRef, add: Boolean, targets: Set<String>): WatchlistResult = coroutineScope {
        val succeeded = active().filter { it.id in targets && it.supportsWatchlist(ref, add) }
            .map { t -> async {
                val ok = runCatching { if (add) t.addToWatchlist(ref) else t.removeFromWatchlist(ref) }.getOrDefault(false)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (ok) t.id to t.name else null
            } }.awaitAll().filterNotNull().toMap()
        WatchlistResult(succeeded, targets - succeeded.keys)
    }

    /** Removes a Continue watching entry from the tracker it came from. */
    suspend fun removePlayback(itemKey: String): Boolean {
        val tracker = trackers.firstOrNull { itemKey.startsWith("${it.id}:") } ?: return false
        if (!tracker.isConnected()) return false
        return runCatching { tracker.removePlayback(itemKey) }.getOrDefault(false)
    }

    /** Sends progress to every connected tracker that accepts it. Used by the player later. */
    suspend fun scrobble(item: TrackedItem, state: ScrobbleState, progress: Float): List<String> {
        val completed = mutableListOf<String>()
        trackers.filter { it.isConnected() && it.scrobbles && it.id !in disabled }.forEach {
            val result = runCatching { it.scrobble(item, state, progress) }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (result.isSuccess) completed += it.name
        }
        return completed
    }
}
