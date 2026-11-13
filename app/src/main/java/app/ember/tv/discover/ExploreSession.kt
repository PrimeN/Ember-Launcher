package app.ember.tv.discover

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate

/** Filtering precedes selection. Bounded work can resume without skipping candidates. */
class ExploreSession(
    val query: ExploreQuery, val region: String, private val source: ExploreSource,
    private val limit: Int = 100, private val today: LocalDate = LocalDate.now(),
) {
    private class Stream(val kind: ExploreKind) {
        var page = 1; var end = false; var capped = false
        var seeded = false; var seeds: List<ExploreTitle>? = null
        val matches = mutableListOf<ExploreTitle>(); val seen = mutableSetOf<Long>()
    }
    private val streams = query.kinds().map { Stream(it) }
    val results: List<ExploreTitle> get() = if (streams.size == 1) streams.first().matches.take(limit)
        else ExploreRanking.mix(streams[0].matches, streams[1].matches, limit)
    private fun quota(stream: Stream): Int = if (streams.size == 1) limit else {
        val other = streams.first { it !== stream }
        if (other.end) (limit - other.matches.size.coerceAtMost(limit / 2)).coerceAtLeast(limit / 2) else (limit + 1) / 2
    }
    val complete: Boolean get() = streams.all { it.end } ||
        (results.size >= limit && streams.all { it.end || it.matches.size >= quota(it) })
    val limitReached get() = streams.any { it.capped }
    val pagesChecked get() = streams.sumOf { it.page - 1 }
    suspend fun advance(budget: Int = 8, publish: (List<ExploreTitle>) -> Unit = {}) {
        require(query.error(today) == null) { query.error(today).orEmpty() }
        check(source.configured) { "Add a TMDB API key in Settings > API keys to use Explore." }
        var work = 0
        while (!complete && work < budget) {
            val stream = streams.firstOrNull { !it.end && it.matches.size < quota(it) } ?: break
            if (!stream.seeded) {
                stream.seeds = source.people(query, stream.kind)
                currentCoroutineContext().ensureActive()
                stream.seeded = true
            }
            val seeds = stream.seeds
            val page = if (seeds == null) source.page(query, stream.kind, region, stream.page) else
                ExplorePage(seeds.drop((stream.page - 1) * 20).take(20), (seeds.size + 19) / 20)
            currentCoroutineContext().ensureActive()
            val fresh = page.titles.filter { it.tmdb !in stream.seen }.distinctBy { it.tmdb }
            val basic = fresh.filter { it.matches(query, stream.kind, today) }
            val verified = if (seeds != null && (query.providers.isNotEmpty() || query.companies.isNotEmpty())) coroutineScope {
                val gate = Semaphore(4)
                basic.map { item -> async { gate.withPermit { item.takeIf { source.matchesExtras(it, query, stream.kind, region) } } } }
                    .awaitAll().filterNotNull()
            } else basic
            val awards = if (query.oscars == OscarMode.ANY) verified else {
                val ids = source.awards(verified.map { it.tmdb }, query, stream.kind)
                verified.filter { it.tmdb in ids }
            }
            currentCoroutineContext().ensureActive()
            // Commit the cursor only after all filters succeed; Retry rechecks a failed page.
            stream.matches.addAll(awards)
            stream.seen.addAll(fresh.map { it.tmdb })
            stream.end = page.titles.isEmpty() || stream.page >= page.totalPages || stream.page >= 500
            if (stream.end && page.limited) stream.capped = true
            stream.page++
            work++
            publish(results)
        }
    }
}
