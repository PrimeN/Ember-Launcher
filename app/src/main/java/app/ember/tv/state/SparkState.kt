package app.ember.tv.state

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.ApiKeys
import app.ember.tv.data.LocalLibrary
import app.ember.tv.data.Meta
import app.ember.tv.data.titleKey
import app.ember.tv.discover.ExploreKind
import app.ember.tv.discover.ExploreRepository
import app.ember.tv.spark.SparkCandidate
import app.ember.tv.spark.SparkFacts
import app.ember.tv.spark.SparkModel
import app.ember.tv.spark.SparkRow
import app.ember.tv.spark.SparkSeed
import app.ember.tv.tracker.Http
import app.ember.tv.tracker.Tmdb
import app.ember.tv.tracker.arr
import app.ember.tv.tracker.int
import app.ember.tv.tracker.list
import app.ember.tv.tracker.long
import app.ember.tv.tracker.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * #435 Spark: picks made on this TV from what this profile watches. Your plays (and your trackers'
 * history) say what you like; TMDB is asked for titles like them; Spark scores and groups them into
 * named rows, each pick with its reason. Built about once a day; kept per profile on the TV.
 */
class SparkState(private val context: Context, private val scope: CoroutineScope, private val profileId: () -> String) {
    var rows by mutableStateOf<List<SparkRow>>(emptyList()); private set
    var summary by mutableStateOf<String?>(null); private set
    var builtAt by mutableStateOf(0L); private set
    var loading by mutableStateOf(false); private set
    /** No history to learn from yet. */
    var noHistory by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    @Serializable
    private data class Saved(
        val facts: Map<String, SparkFacts> = emptyMap(),
        val imdb: Map<String, String> = emptyMap(),
        val rows: List<SparkRow> = emptyList(),
        val summary: String? = null,
        val builtAt: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private var saved = Saved()
    private var loadedFor: String? = null
    private var job: Job? = null

    private fun file(profile: String) = File(File(context.filesDir, "spark").apply { mkdirs() }, profile.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    /** Shows what was built before, and builds again when it is a day old. */
    fun open() {
        val profile = profileId()
        if (loadedFor != profile) {
            loadedFor = profile
            saved = runCatching { json.decodeFromString(Saved.serializer(), file(profile).readText()) }.getOrDefault(Saved())
            rows = saved.rows; summary = saved.summary; builtAt = saved.builtAt; error = null; noHistory = false
        }
        if (System.currentTimeMillis() - builtAt > STALE_MS || rows.isEmpty()) build()
    }

    fun refresh() = build()

    private fun build() {
        if (job?.isActive == true) return
        if (!Tmdb.available) return
        val profile = profileId()
        loading = true; error = null
        job = scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { make(saved) } }
            if (profileId() != profile) { loading = false; return@launch }
            result.onSuccess { next ->
                saved = next
                rows = next.rows; summary = next.summary; builtAt = next.builtAt
                noHistory = next.summary == null
                withContext(Dispatchers.IO) { runCatching { file(profile).writeText(json.encodeToString(Saved.serializer(), next)) } }
            }.onFailure { error = "Spark couldn't reach TMDB. Try again in a moment." }
            loading = false
        }
    }

    private class Seen(val meta: Meta, var episodes: Int, var finished: Boolean, var progress: Float, var at: Long)

    private suspend fun make(before: Saved): Saved = coroutineScope {
        val key = ApiKeys.get("TMDB_API_KEY")
        val now = System.currentTimeMillis()
        // ---- what you watched: one entry per title ----
        val titles = LinkedHashMap<String, Seen>()
        for (p in LocalLibrary.plays) {
            val k = p.meta.titleKey()
            val s = titles.getOrPut(k) { Seen(p.meta, 0, false, 0f, p.at) }
            s.episodes++; s.finished = s.finished || p.finished; s.progress = maxOf(s.progress, p.progress); s.at = maxOf(s.at, p.at)
        }
        for (m in LocalLibrary.trackerHistory) titles.getOrPut(m.titleKey()) { Seen(m, 1, true, 1f, now - 30L * DAY_MS) }
        if (titles.isEmpty()) return@coroutineScope before.copy(rows = emptyList(), summary = null, builtAt = now)

        // ---- TMDB ids, and each title's facts (kept for a month) ----
        val imdb = before.imdb.toMutableMap()
        suspend fun keyOf(m: Meta): String? {
            val tv = m.type != "movie"
            val type = if (tv) "tv" else "movie"
            m.tmdbId?.let { return "$type:$it" }
            val i = m.imdb ?: return null
            imdb[i]?.let { return it }
            val found = runCatching { Http.metadata("https://api.themoviedb.org/3/find/$i?api_key=$key&external_source=imdb_id") }.getOrNull() ?: return null
            val id = found.arr("movie_results").list().firstOrNull().long("id")?.let { "movie:$it" }
                ?: found.arr("tv_results").list().firstOrNull().long("id")?.let { "tv:$it" } ?: return null
            imdb[i] = id
            return id
        }
        val recent = titles.values.sortedByDescending { it.at }.take(MAX_SEEDS)
        val keyed = recent.map { s -> async { keyOf(s.meta)?.let { it to s } } }.awaitAll().filterNotNull()
        val facts = before.facts.filterValues { now - it.fetchedAt < FACTS_MS }.toMutableMap()
        keyed.filter { it.first !in facts }.map { (k, s) -> async {
            runCatching {
                val (type, id) = k.split(':')
                val j = Http.metadata("https://api.themoviedb.org/3/$type/$id?api_key=$key")
                SparkFacts(k, s.meta.name, j.arr("genres").list().mapNotNull { it.int("id") },
                    (j.str("release_date") ?: j.str("first_air_date"))?.take(4)?.toIntOrNull(), j.str("original_language"), now)
            }.getOrNull()
        } }.awaitAll().filterNotNull().forEach { facts[it.key] = it }

        val seeds = keyed.map { (k, s) ->
            val days = (now - s.at).coerceAtLeast(0) / DAY_MS.toDouble()
            SparkSeed(k, s.meta.name, SparkModel.seedWeight(s.finished, s.progress, s.episodes, days), s.at)
        }.distinctBy { it.key }
        val taste = SparkModel.taste(seeds, facts)

        // ---- candidates: TMDB's picks for your strongest titles, and the best of your favourite kinds ----
        fun parse(r: JsonElement, tv: Boolean, from: SparkSeed?): SparkCandidate? {
            val t = ExploreRepository.parseTitle(r, if (tv) ExploreKind.TV else ExploreKind.MOVIE)?.takeUnless { it.adult } ?: return null
            return SparkCandidate(t.meta, (if (tv) "tv:" else "movie:") + t.tmdb, t.genreIds, r.str("original_language"),
                t.date?.take(4)?.toIntOrNull(), t.rating, t.votes, listOfNotNull(from?.name), from?.weight ?: 0.0)
        }
        val strong = seeds.filter { it.weight > 0 }.sortedByDescending { it.weight }.take(10)
        val fromSeeds = strong.map { s -> async {
            val (type, id) = s.key.split(':')
            runCatching {
                Http.metadata("https://api.themoviedb.org/3/$type/$id/recommendations?api_key=$key").arr("results").list()
                    .mapNotNull { parse(it, type == "tv", s) }
            }.getOrDefault(emptyList())
        } }
        val page = (1..3).random()
        val byKind = taste.topGenres(3, min = 0.1).flatMap { g ->
            listOfNotNull(if (g in MOVIE_GENRES) "movie" else null, if (g in TV_GENRES) "tv" else null).map { type -> async {
                runCatching {
                    Http.metadata("https://api.themoviedb.org/3/discover/$type?api_key=$key&with_genres=$g&sort_by=vote_average.desc" +
                        "&vote_count.gte=${if (type == "movie") 400 else 150}&without_genres=99,10770&page=$page").arr("results").list()
                        .mapNotNull { parse(it, type == "tv", null) }
                }.getOrDefault(emptyList())
            } }
        }
        val byLanguage = taste.otherLanguage?.let { lang -> listOf("movie", "tv").map { type -> async {
            runCatching {
                Http.metadata("https://api.themoviedb.org/3/discover/$type?api_key=$key&with_original_language=$lang&sort_by=popularity.desc&vote_count.gte=100").arr("results").list()
                    .mapNotNull { parse(it, type == "tv", null) }
            }.getOrDefault(emptyList())
        } } }.orEmpty()
        val candidates = (fromSeeds + byKind + byLanguage).awaitAll().flatten()
        if (candidates.isEmpty() && strong.isNotEmpty()) throw java.io.IOException("TMDB gave nothing")

        // Nothing you have already played or that your trackers say you watched.
        val excluded = keyed.map { it.first }.toSet() + imdb.values
        val rows = SparkModel.rows(candidates, taste, seeds.sortedByDescending { it.at }, excluded)
        Saved(facts.filterKeys { k -> keyed.any { it.first == k } || facts.size < 400 }, imdb, rows, SparkModel.summary(taste), now)
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val STALE_MS = 20 * 60 * 60 * 1000L
        const val FACTS_MS = 30 * DAY_MS
        const val MAX_SEEDS = 40
        val MOVIE_GENRES = setOf(28, 12, 16, 35, 80, 99, 18, 10751, 14, 36, 27, 10402, 9648, 10749, 878, 53, 10752, 37)
        val TV_GENRES = setOf(16, 35, 80, 99, 18, 10751, 9648, 37, 10759, 10762, 10765, 10768)
    }
}
