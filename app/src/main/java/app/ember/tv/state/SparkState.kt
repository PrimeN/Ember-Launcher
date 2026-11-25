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
class SparkState(private val context: Context, private val scope: CoroutineScope, private val prefs: app.ember.tv.data.Prefs, private val profileId: () -> String,
    /** #436: connected services that can give their whole history. */
    private val historySources: () -> List<app.ember.tv.tracker.Tracker> = { emptyList() }) {
    var rows by mutableStateOf<List<SparkRow>>(emptyList()); private set
    var summary by mutableStateOf<String?>(null); private set
    var builtAt by mutableStateOf(0L); private set
    var loading by mutableStateOf(false); private set
    /** No history to learn from yet. */
    var noHistory by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    /** #436: history pulled from your trackers for this profile: how many titles, from where, when. */
    var imported by mutableStateOf<Imported?>(null); private set
    var importing by mutableStateOf(false); private set
    var importNote by mutableStateOf<String?>(null); private set
    /** #437 "Keep everything": also keep each title's full details from Trakt (story, genres, runtime, rating…). */
    var keepEverything by mutableStateOf(prefs.sparkKeepEverything); private set
    fun updateKeepEverything(on: Boolean) {
        keepEverything = on; prefs.sparkKeepEverything = on
        if (on && imported != null) importHistory()
    }
    /** #437: bring your trackers' history in again on its own: "off", "daily" or "weekly". */
    var autoRefresh by mutableStateOf(prefs.sparkAutoRefresh); private set
    fun updateAutoRefresh(value: String) { autoRefresh = value; prefs.sparkAutoRefresh = value; refreshHistoryIfDue() }

    /** Pulls the history again when automatic refresh is on and the last pull is old enough. */
    private fun refreshHistoryIfDue() {
        val every = when (autoRefresh) { "daily" -> DAY_MS; "weekly" -> 7 * DAY_MS; else -> return }
        if (importing || historySources().isEmpty()) return
        if (System.currentTimeMillis() - (imported?.at ?: 0L) >= every) importHistory(quiet = true)
    }

    /** One film or show from a tracker, kept on the TV for Spark (ids, name, year, plays, last watched, your rating). */
    @Serializable
    data class ImportedTitle(val movie: Boolean, val imdb: String? = null, val tmdb: Long? = null, val name: String? = null, val year: Int? = null,
        val plays: Int = 1, val lastAt: Long? = null, val rating: Int? = null, val watched: Boolean = true,
        val details: app.ember.tv.tracker.TitleDetails? = null)

    @Serializable
    data class Imported(val titles: List<ImportedTitle> = emptyList(), val sources: List<String> = emptyList(), val at: Long = 0) {
        val rated get() = titles.count { it.rating != null }
        val detailed get() = titles.count { it.details != null }
    }

    @Serializable
    private data class Saved(
        val facts: Map<String, SparkFacts> = emptyMap(),
        val imdb: Map<String, String> = emptyMap(),
        val rows: List<SparkRow> = emptyList(),
        val summary: String? = null,
        val builtAt: Long = 0,
        val asks: List<SavedAsk> = emptyList(),
    )

    /** #438: an Ask Spark answer kept as a row (and a Home widget); asked again about once a week. */
    @Serializable
    data class SavedAsk(val id: String, val question: String, val row: SparkRow, val at: Long)

    // ---- Ask Spark (#438) ----
    var answer by mutableStateOf<SparkRow?>(null); private set
    var asking by mutableStateOf(false); private set
    var askError by mutableStateOf<String?>(null); private set
    var asks by mutableStateOf<List<SavedAsk>>(emptyList()); private set
    private var lastQuestion: String? = null
    private var askJob: Job? = null

    /** Asks your AI service; the titles it names are checked on TMDB, and those you've seen are left out. */
    fun ask(question: String) {
        val q = question.trim()
        if (q.length !in 3..300) { askError = "Ask in 3 to 300 characters."; return }
        if (!app.ember.tv.data.AiSettings.ready()) { askError = "Choose an AI service in Discover settings first."; return }
        askJob?.cancel()
        asking = true; askError = null; answer = null; lastQuestion = q
        val profile = profileId()
        askJob = scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { answerFor(q) } }
            if (profileId() != profile) return@launch
            result.onSuccess { row -> answer = row; if (row.items.isEmpty()) askError = "Spark found nothing new for that. Try asking another way." }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) askError = it.message?.takeIf { m -> m.length < 160 } ?: "The AI service didn't answer. Try again." }
            asking = false
        }
    }

    fun clearAnswer() { askJob?.cancel(); asking = false; answer = null; askError = null }

    /** Keeps the current answer as a row you can also put on Home (Create widget › Your lists). */
    fun saveAnswer() {
        val row = answer ?: return
        val q = lastQuestion ?: return
        val id = Integer.toHexString(q.lowercase().hashCode())
        val next = (asks.filterNot { it.id == id } + SavedAsk(id, q, row.copy(id = "ask:$id"), System.currentTimeMillis())).takeLast(MAX_ASKS)
        persistAsks(next)
        answer = null
    }

    fun removeAsk(id: String) = persistAsks(asks.filterNot { it.id == id })

    private fun persistAsks(next: List<SavedAsk>) {
        asks = next
        saved = saved.copy(asks = next)
        val profile = profileId(); val copy = saved
        scope.launch(Dispatchers.IO) { runCatching { file(profile).writeText(json.encodeToString(Saved.serializer(), copy)) } }
    }

    /** Saved answers older than a week are asked again, one per visit, quietly. */
    private fun refreshAsksIfDue() {
        if (!app.ember.tv.data.AiSettings.ready() || askJob?.isActive == true) return
        val due = asks.firstOrNull { System.currentTimeMillis() - it.at > 7 * DAY_MS } ?: return
        val profile = profileId()
        askJob = scope.launch {
            val row = runCatching { withContext(Dispatchers.IO) { answerFor(due.question) } }.getOrNull()
            if (profileId() != profile) return@launch
            persistAsks(asks.map { if (it.id == due.id) it.copy(row = (row?.takeIf { r -> r.items.isNotEmpty() } ?: it.row).copy(id = "ask:${it.id}"), at = System.currentTimeMillis()) else it })
        }
    }

    private suspend fun answerFor(question: String): SparkRow = coroutineScope {
        val content = app.ember.tv.discover.AiChat.ask(app.ember.tv.spark.SparkAsk.system(summary, java.time.LocalDate.now()), question)
        val picks = app.ember.tv.spark.SparkAsk.parse(content)
        val key = ApiKeys.get("TMDB_API_KEY")
        val seen = saved.facts.keys + saved.imdb.values + imported?.titles.orEmpty().mapNotNull { h -> h.tmdb?.let { (if (h.movie) "movie:" else "tv:") + it } }
        val found = picks.map { p -> async {
            runCatching {
                val tv = p.type == "tv"
                val url = "https://api.themoviedb.org/3/search/${if (tv) "tv" else "movie"}?api_key=$key&query=${java.net.URLEncoder.encode(p.title, "UTF-8")}"
                Http.metadata(url).arr("results").list().firstNotNullOfOrNull { r ->
                    val year = (r.str("release_date") ?: r.str("first_air_date"))?.take(4)?.toIntOrNull()
                    if (!app.ember.tv.spark.SparkAsk.matches(p, r.str("title") ?: r.str("name") ?: "", r.str("original_title") ?: r.str("original_name"), year)) null
                    else ExploreRepository.parseTitle(r, if (tv) ExploreKind.TV else ExploreKind.MOVIE)?.takeUnless { it.adult }
                        ?.let { t -> ((if (tv) "tv:" else "movie:") + t.tmdb) to t.meta.copy(badge = p.why.ifBlank { null }) }
                }
            }.getOrNull()
        } }.awaitAll().filterNotNull()
        val items = found.filter { it.first !in seen }.distinctBy { it.first }.map { it.second }
        SparkRow("ask", "\u201C$question\u201D", "Asked ${app.ember.tv.data.AiSettings.provider.label}", items)
    }

    /** Questions saved as rows, for the Home widget lists. */
    fun askLists(): List<Pair<String, String>> = asks.map { "spark:ask:${it.id}" to "Spark · ${it.question.take(60)}" }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private var saved = Saved()
    private var loadedFor: String? = null
    private var job: Job? = null

    private fun importFile(profile: String) = File(File(context.filesDir, "spark").apply { mkdirs() }, profile.replace(Regex("[^A-Za-z0-9_-]"), "_") + "-history.json")

    /**
     * #436: pulls your whole history and ratings from each connected service that can give it (Trakt
     * with dates; Simkl, WeTrakr and others without), keeps it for this profile, and builds new picks.
     */
    fun importHistory(quiet: Boolean = false) {
        if (importing) return
        val sources = historySources()
        if (sources.isEmpty()) { if (!quiet) importNote = "Connect Trakt, Simkl or WeTrakr to bring your history in."; return }
        val profile = profileId()
        val full = keepEverything
        importing = true; if (!quiet) importNote = null
        scope.launch {
            val pulled = withContext(Dispatchers.IO) { sources.map { t -> t.name to runCatching { t.tasteHistory(full) } } }
            val ok = pulled.filter { it.second.isSuccess }
            if (profileId() == profile && ok.isNotEmpty()) {
                val titles = LinkedHashMap<String, ImportedTitle>()
                for ((_, r) in ok) for (h in r.getOrThrow()) {
                    val k = (if (h.movie) "m:" else "s:") + (h.tmdb?.toString() ?: h.imdb ?: continue)
                    val old = titles[k]
                    titles[k] = ImportedTitle(h.movie, h.imdb ?: old?.imdb, h.tmdb ?: old?.tmdb, h.name ?: old?.name, h.year ?: old?.year,
                        maxOf(h.plays, old?.plays ?: 0), listOfNotNull(h.lastAt, old?.lastAt).maxOrNull(), h.rating ?: old?.rating, h.watched || old?.watched == true,
                        h.details ?: old?.details)
                }
                val next = Imported(titles.values.toList(), ok.map { it.first }, System.currentTimeMillis())
                imported = next
                withContext(Dispatchers.IO) { runCatching { importFile(profile).writeText(json.encodeToString(Imported.serializer(), next)) } }
                if (!quiet) importNote = "Brought in ${"%,d".format(next.titles.size)} titles from ${next.sources.joinToString(" and ")}" +
                    (if (next.rated > 0) ", with ${"%,d".format(next.rated)} ratings" else "") + "." +
                    pulled.filter { it.second.isFailure }.takeIf { it.isNotEmpty() }?.let { f -> " ${f.joinToString(" and ") { it.first }} didn't answer." }.orEmpty()
                importing = false
                build(force = true)
            } else {
                if (!quiet) importNote = "Couldn't reach ${sources.joinToString(" or ") { it.name }}. Try again in a moment."
                importing = false
            }
        }
    }

    /** #436: forgets the history brought in for this profile (what you play on the TV still counts). */
    fun forgetImported() {
        val profile = profileId()
        imported = null; importNote = "Imported history forgotten."
        scope.launch(Dispatchers.IO) { runCatching { importFile(profile).delete() } }
        build(force = true)
    }

    private fun file(profile: String) = File(File(context.filesDir, "spark").apply { mkdirs() }, profile.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    /** Shows what was built before, and builds again when it is a day old. */
    fun open() {
        load()
        if (System.currentTimeMillis() - builtAt > STALE_MS || rows.isEmpty()) build()
        refreshHistoryIfDue()
        refreshAsksIfDue()
    }

    /** This profile's saved Spark data, read once per profile. */
    private fun load() {
        val profile = profileId()
        if (loadedFor != profile) {
            loadedFor = profile
            saved = runCatching { json.decodeFromString(Saved.serializer(), file(profile).readText()) }.getOrDefault(Saved())
            rows = saved.rows; summary = saved.summary; builtAt = saved.builtAt; error = null; noHistory = false
            imported = runCatching { json.decodeFromString(Imported.serializer(), importFile(profile).readText()) }.getOrNull()
            asks = saved.asks; answer = null; askError = null
            importNote = null
            searchAnswer = null
        }
    }

    // ---- Ask Spark from Search (#439) ----
    /** The answer for [searchQuestion]; separate from the Spark page's own question. */
    var searchQuestion by mutableStateOf<String?>(null); private set
    var searchAnswer by mutableStateOf<SparkRow?>(null); private set
    var searchAsking by mutableStateOf(false); private set
    var searchError by mutableStateOf<String?>(null); private set
    /** #439: Search may offer Ask Spark for descriptions; "Ask automatically" asks without the press. */
    var searchOn by mutableStateOf(prefs.sparkSearch); private set
    fun updateSearchOn(on: Boolean) { searchOn = on; prefs.sparkSearch = on }
    var searchAuto by mutableStateOf(prefs.sparkSearchAuto); private set
    fun updateSearchAuto(on: Boolean) { searchAuto = on; prefs.sparkSearchAuto = on }
    private var searchJob: Job? = null

    fun askForSearch(query: String) {
        val q = query.trim()
        if (q.length !in 3..300 || !app.ember.tv.data.AiSettings.ready()) return
        if (q == searchQuestion && (searchAsking || searchAnswer != null)) return
        load()
        searchJob?.cancel()
        searchQuestion = q; searchAnswer = null; searchError = null; searchAsking = true
        val profile = profileId()
        searchJob = scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { answerFor(q) } }
            if (profileId() != profile || searchQuestion != q) return@launch
            result.onSuccess { searchAnswer = it; if (it.items.isEmpty()) searchError = "Spark found nothing new for that." }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) searchError = it.message?.takeIf { m -> m.length < 160 } ?: "The AI service didn't answer." }
            searchAsking = false
        }
    }

    /** Saves the Search answer as a Spark row (and Home widget list), like Ask Spark. */
    fun saveSearchAnswer() {
        val row = searchAnswer ?: return
        val q = searchQuestion ?: return
        answer = row; lastQuestion = q
        saveAnswer()
    }

    fun refresh() = build()

    /** #437: the row a Home widget shows: "spark:top", "spark:taste1".."spark:taste3", "spark:because", "spark:gems", "spark:language". */
    fun rowFor(slot: String): SparkRow? = when (slot) {
        "spark:top" -> rows.firstOrNull { it.id == "top" }
        "spark:taste1", "spark:taste2", "spark:taste3" -> rows.filter { it.id.startsWith("genre:") }.getOrNull(slot.last().digitToInt() - 1)
        "spark:because" -> rows.firstOrNull { it.id.startsWith("because:") }
        "spark:gems" -> rows.firstOrNull { it.id == "gems" }
        "spark:language" -> rows.firstOrNull { it.id.startsWith("lang:") }
        else -> asks.firstOrNull { slot == "spark:ask:${it.id}" }?.row
    }

    private fun build(force: Boolean = false) {
        if (force) job?.cancel() else if (job?.isActive == true) return
        if (!Tmdb.available) return
        val profile = profileId()
        loading = true; error = null
        job = scope.launch {
            val history = imported
            val result = runCatching { withContext(Dispatchers.IO) { make(saved, history) } }
            if (profileId() != profile) { loading = false; return@launch }
            result.onSuccess { built ->
                val next = built.copy(asks = asks)
                saved = next
                rows = next.rows; summary = next.summary; builtAt = next.builtAt
                noHistory = next.summary == null
                withContext(Dispatchers.IO) { runCatching { file(profile).writeText(json.encodeToString(Saved.serializer(), next)) } }
            }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = "Spark couldn't reach TMDB. Try again in a moment." }
            loading = false
        }
    }

    private class Seen(val meta: Meta, var episodes: Int, var finished: Boolean, var progress: Float, var at: Long)

    private suspend fun make(before: Saved, history: Imported?): Saved = coroutineScope {
        val key = ApiKeys.get("TMDB_API_KEY")
        val now = System.currentTimeMillis()
        // ---- what you watched on this TV: one entry per title ----
        val titles = LinkedHashMap<String, Seen>()
        for (p in LocalLibrary.plays) {
            val k = p.meta.titleKey()
            val s = titles.getOrPut(k) { Seen(p.meta, 0, false, 0f, p.at) }
            s.episodes++; s.finished = s.finished || p.finished; s.progress = maxOf(s.progress, p.progress); s.at = maxOf(s.at, p.at)
        }
        if (history == null) for (m in LocalLibrary.trackerHistory) titles.getOrPut(m.titleKey()) { Seen(m, 1, true, 1f, now - 30L * DAY_MS) }
        if (titles.isEmpty() && history?.titles.isNullOrEmpty()) return@coroutineScope before.copy(rows = emptyList(), summary = null, builtAt = now)

        // ---- TMDB ids ----
        val imdb = before.imdb.toMutableMap()
        suspend fun find(i: String): String? {
            imdb[i]?.let { return it }
            val found = runCatching { Http.metadata("https://api.themoviedb.org/3/find/$i?api_key=$key&external_source=imdb_id") }.getOrNull() ?: return null
            val id = found.arr("movie_results").list().firstOrNull().long("id")?.let { "movie:$it" }
                ?: found.arr("tv_results").list().firstOrNull().long("id")?.let { "tv:$it" } ?: return null
            imdb[i] = id
            return id
        }
        suspend fun keyOf(m: Meta): String? {
            m.tmdbId?.let { return (if (m.type != "movie") "tv:" else "movie:") + it }
            return find(m.imdb ?: return null)
        }
        // Weighted seeds from the TV...
        val local = titles.values.sortedByDescending { it.at }.take(MAX_SEEDS)
            .map { s -> async { keyOf(s.meta)?.let { k ->
                SparkSeed(k, s.meta.name, SparkModel.seedWeight(s.finished, s.progress, s.episodes, (now - s.at).coerceAtLeast(0) / DAY_MS.toDouble()), s.at)
            } } }.awaitAll().filterNotNull()
        // ...and from your trackers' whole history (#436). Titles with only an IMDb id are looked up for the most recent few.
        var lookups = 0
        val fromTrackers = ArrayList<SparkSeed>()
        for (h in history?.titles.orEmpty().sortedByDescending { it.lastAt ?: 0 }) {
            val k = h.tmdb?.let { (if (h.movie) "movie:" else "tv:") + it }
                ?: h.imdb?.let { i -> imdb[i] ?: if (lookups++ < 50) find(i) else null } ?: continue
            val at = h.lastAt ?: (history!!.at - 365L * DAY_MS)
            val w = SparkModel.importedWeight(h.plays, h.rating, h.watched, !h.movie, (now - at).coerceAtLeast(0) / DAY_MS.toDouble())
            if (w != 0.0) fromTrackers += SparkSeed(k, h.name ?: "", w, at)
        }
        val seeds = (local + fromTrackers).groupBy { it.key }.map { (_, same) ->
            same.reduce { a, b -> SparkSeed(a.key, a.name.ifBlank { b.name }, (a.weight + b.weight).coerceIn(-2.0, 12.0), maxOf(a.at, b.at)) }
        }

        // ---- each title's facts (kept for six months; only the titles that say most about you) ----
        val facts = before.facts.filterValues { now - it.fetchedAt < FACTS_MS }.toMutableMap()
        // #437: titles kept with their full details need no TMDB lookup: Trakt's genres, language and year are used.
        for (h in history?.titles.orEmpty()) {
            val d = h.details ?: continue
            val k = h.tmdb?.let { (if (h.movie) "movie:" else "tv:") + it } ?: h.imdb?.let { imdb[it] } ?: continue
            if (k in facts) continue
            val genres = app.ember.tv.spark.SparkNames.traktGenres(d.genres, tv = !h.movie)
            if (genres.isEmpty() && d.language == null) continue
            facts[k] = SparkFacts(k, h.name.orEmpty(), genres, h.year, d.language, now)
        }
        val wanted = (seeds.sortedByDescending { kotlin.math.abs(it.weight) }.take(MAX_FACTS) + seeds.sortedByDescending { it.at }.take(MAX_SEEDS))
            .distinctBy { it.key }.filter { it.key !in facts }
        for (chunk in wanted.chunked(8)) chunk.map { s -> async {
            runCatching {
                val (type, id) = s.key.split(':')
                val j = Http.metadata("https://api.themoviedb.org/3/$type/$id?api_key=$key")
                SparkFacts(s.key, s.name.ifBlank { j.str("title") ?: j.str("name") ?: "" }, j.arr("genres").list().mapNotNull { it.int("id") },
                    (j.str("release_date") ?: j.str("first_air_date"))?.take(4)?.toIntOrNull(), j.str("original_language"), now)
            }.getOrNull()
        } }.awaitAll().filterNotNull().forEach { facts[it.key] = it }
        val named = seeds.map { s -> if (s.name.isBlank()) s.copy(name = facts[s.key]?.name.orEmpty()) else s }
        val taste = SparkModel.taste(named, facts)


        // ---- candidates: TMDB's picks for your strongest titles, and the best of your favourite kinds ----
        fun parse(r: JsonElement, tv: Boolean, from: SparkSeed?): SparkCandidate? {
            val t = ExploreRepository.parseTitle(r, if (tv) ExploreKind.TV else ExploreKind.MOVIE)?.takeUnless { it.adult } ?: return null
            return SparkCandidate(t.meta, (if (tv) "tv:" else "movie:") + t.tmdb, t.genreIds, r.str("original_language"),
                t.date?.take(4)?.toIntOrNull(), t.rating, t.votes, listOfNotNull(from?.name), from?.weight ?: 0.0)
        }
        val strong = named.filter { it.name.isNotBlank() }.filter { it.weight > 0 }.sortedByDescending { it.weight }.take(10)
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

        // Nothing you have already played, rated or that your trackers say you watched.
        val excluded = seeds.map { it.key }.toSet() + imdb.values
        val rows = SparkModel.rows(candidates, taste, named.filter { it.name.isNotBlank() }.sortedByDescending { it.at }, excluded)
        val keep = seeds.mapTo(HashSet()) { it.key }
        Saved(facts.filterKeys { it in keep }, imdb, rows, SparkModel.summary(taste), now)
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val STALE_MS = 20 * 60 * 60 * 1000L
        const val FACTS_MS = 180 * DAY_MS
        const val MAX_SEEDS = 40
        /** Titles whose facts are looked up: those that say most about you. */
        const val MAX_FACTS = 300
        const val MAX_ASKS = 5
        val MOVIE_GENRES = setOf(28, 12, 16, 35, 80, 99, 18, 10751, 14, 36, 27, 10402, 9648, 10749, 878, 53, 10752, 37)
        val TV_GENRES = setOf(16, 35, 80, 99, 18, 10751, 9648, 37, 10759, 10762, 10765, 10768)
    }
}
