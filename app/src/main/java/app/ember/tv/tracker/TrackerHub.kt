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

    /** Tracker ids that are not told what you watch. Separate from Home (#80). */
    var quiet: Set<String> = emptySet()

    /** Connected trackers that receive what you watch and your marks. */
    private fun reporting() = trackers.filter { it.isConnected() && it.id !in quiet }

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

    /** [refused]: tracker id to the reason a service turned the change down for good (#82). */
    data class WatchlistResult(val succeeded: Map<String, String>, val pending: Set<String>, val refused: Map<String, String> = emptyMap())

    fun watchlistTargets(ref: TitleRef, add: Boolean): Set<String> = active()
        .filter { it.supportsWatchlist(ref, add) }.map { it.id }.toSet()

    /** Retry only the services still owed this change, retaining disconnected/disabled targets. */
    suspend fun writeWatchlist(ref: TitleRef, add: Boolean, targets: Set<String>): WatchlistResult = coroutineScope {
        val results = active().filter { it.id in targets && it.supportsWatchlist(ref, add) }
            .map { t -> async {
                val result = runCatching { if (add) t.addToWatchlist(ref) else t.removeFromWatchlist(ref) }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                t to result
            } }.awaitAll()
        val succeeded = results.filter { it.second.getOrDefault(false) }.associate { it.first.id to it.first.name }
        // A refusal (e.g. Trakt's free-account limit) is final: no point retrying it on every refresh.
        val refused = results.mapNotNull { (t, r) -> (r.exceptionOrNull() as? TrackerRefusal)?.let { t.id to (it.message ?: t.name) } }.toMap()
        WatchlistResult(succeeded, targets - succeeded.keys - refused.keys, refused)
    }

    /** Explicit watched / unwatched marks, sent as history to every tracker that takes them (#79). */
    suspend fun markWatched(ref: TitleRef, season: Int?, episode: Int?, watched: Boolean): Outcome =
        fanOut { t -> if (t.supportsHistory(ref)) t.markWatched(ref, season, episode, watched) else null }

    /** Your rating, 1 to 10, or null to clear it (#84). */
    suspend fun rate(ref: TitleRef, rating: Int?): Outcome = fanOut { t -> if (t.rates && ref.hasIds) t.rate(ref, rating) else null }

    /** Your rating from the first tracker that has one (#84). */
    suspend fun ratingOf(ref: TitleRef): Int? = trackers.filter { it.isConnected() && it.rates }.firstNotNullOfOrNull { t ->
        runCatching { t.ratingOf(ref) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
    }

    val canRate: Boolean get() = trackers.any { it.isConnected() && it.rates }

    data class Outcome(val sent: List<String>, val failed: List<String>, val refused: List<String>)

    private suspend fun fanOut(call: suspend (Tracker) -> Boolean?): Outcome = coroutineScope {
        val results = reporting().map { t -> async { t to runCatching { call(t) } } }.awaitAll()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        Outcome(
            sent = results.filter { it.second.getOrNull() == true }.map { it.first.name },
            failed = results.filter { it.second.isFailure && it.second.exceptionOrNull() !is TrackerRefusal }.map { it.first.name },
            refused = results.mapNotNull { (it.second.exceptionOrNull() as? TrackerRefusal)?.message },
        )
    }

    /**
     * Watched movies and episodes from the trackers shown on Home (#83).
     * Null when none of them could be read, so the last known marks stay.
     */
    suspend fun watched(): Set<String>? = coroutineScope {
        val results = active().map { t -> async { runCatching { t.watched() } } }.awaitAll()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val read = results.mapNotNull { it.getOrNull() }
        if (read.isEmpty() && results.any { it.isFailure }) null else read.flatten().toSet()
    }

    /** Reads account names that are not known yet (#85). One call per tracker, once. */
    suspend fun loadAccountNames(): Boolean {
        val missing = trackers.filter { it.needsAccountName() }
        missing.forEach { runCatching { it.loadAccountName() }.onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e } }
        return missing.isNotEmpty()
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
        // Hidden from Home still reports; only "Report what I watch" off stops it (#80).
        reporting().filter { it.scrobbles }.forEach {
            val result = runCatching { it.scrobble(item, state, progress) }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (result.isSuccess) completed += it.name
        }
        return completed
    }
}

/** " Sent to Trakt and Simkl." plus anything that could not be sent, for notices. */
fun TrackerHub.Outcome.describe(): String = buildString {
    if (sent.isNotEmpty()) append(" Sent to ${sent.joinToString(" and ")}.")
    if (failed.isNotEmpty()) append(" Couldn't reach ${failed.joinToString(" and ")}.")
    refused.forEach { append(" ").append(it) }
}
