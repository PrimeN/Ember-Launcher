package app.ember.tv.discover

import app.ember.tv.data.toHex
import app.ember.tv.data.ApiKeys
import app.ember.tv.data.Meta
import app.ember.tv.tracker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDate

interface ExploreSource {
    val configured: Boolean
    suspend fun page(query: ExploreQuery, kind: ExploreKind, region: String, page: Int): ExplorePage
    /** A complete credit-based candidate set, or null to use the ranked discovery endpoint. */
    suspend fun people(query: ExploreQuery, kind: ExploreKind): List<ExploreTitle>?
    suspend fun matchesExtras(title: ExploreTitle, query: ExploreQuery, kind: ExploreKind, region: String): Boolean
    suspend fun invalidate() {}
    suspend fun awards(ids: List<Long>, query: ExploreQuery): Set<Long>
    /** #252: shows are matched by their TMDB TV id. */
    suspend fun awards(ids: List<Long>, query: ExploreQuery, kind: ExploreKind): Set<Long> = awards(ids, query)
}
data class ExplorePage(val titles: List<ExploreTitle>, val totalPages: Int, val limited: Boolean = false)

/** Public metadata only. Bounded memory/disk caches never store API keys or full request URLs. */
class ExploreRepository(
    private val cacheDir: File? = null,
    private val key: () -> String = { ApiKeys.get("TMDB_API_KEY") },
    private val get: suspend (String, Map<String, String>) -> JsonElement = { url, headers -> Http.get(url, headers + ("Cache-Control" to "no-store")) },
    private val clock: () -> Long = System::currentTimeMillis,
) : ExploreSource {
    override val configured get() = key().isNotBlank()
    private val json = Json { ignoreUnknownKeys = true }
    private val gate = Semaphore(4)
    private val memory = object : LinkedHashMap<String, Pair<Long, JsonElement>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, JsonElement>>) = size > 128
    }
    override suspend fun invalidate() {
        if (app.ember.tv.data.CacheManagement.initialized) app.ember.tv.data.CacheManagement.clear(setOf(app.ember.tv.data.CacheKind.CATALOGS,app.ember.tv.data.CacheKind.METADATA))
        synchronized(memory) { memory.clear() }
        withContext(Dispatchers.IO) { cacheDir?.listFiles()?.filter { it.extension == "json" }?.forEach { it.delete() } }
    }
    private suspend fun cached(id: String, ttl: Long = 15 * 60_000L, load: suspend () -> JsonElement): JsonElement {
        if (app.ember.tv.data.CacheManagement.initialized) {
            val kind = if (id.startsWith("tmdb:discover/")) app.ember.tv.data.CacheKind.CATALOGS else app.ember.tv.data.CacheKind.METADATA
            return json.parseToJsonElement(app.ember.tv.data.CacheManagement.json(kind, "explore:$id") { load().toString() })
        }
        val now = clock()
        synchronized(memory) { memory[id]?.takeIf { now - it.first in 0 until ttl }?.let { return it.second } }
        val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).toHex()
        val file = cacheDir?.let { File(it, "$name.json") }
        val saved = withContext(Dispatchers.IO) { runCatching {
            file?.takeIf { it.isFile && it.length() <= 2_000_000 }?.let {
                val obj = json.parseToJsonElement(it.readText())
                val stamp = obj.long("at") ?: return@let null
                obj.obj("value")?.takeIf { now - stamp in 0 until ttl }?.let { v -> stamp to v }
            }
        }.getOrNull() }
        if (saved != null) { synchronized(memory) { memory[id] = saved }; return saved.second }
        val result = load()
        currentCoroutineContext().ensureActive()
        synchronized(memory) { memory[id] = now to result }
        withContext(Dispatchers.IO) {
            runCatching {
                file?.let {
                    app.ember.tv.data.AtomicFile.write(it, buildJsonObject { put("at", now); put("value", result) }.toString())
                    it.parentFile?.listFiles()?.filter { f -> f.extension == "json" }?.sortedByDescending { f -> f.lastModified() }
                        ?.drop(128)?.forEach { f -> f.delete() }
                }
            }
        }
        return result
    }
    private suspend fun tmdb(path: String, params: Map<String, String> = emptyMap(), ttl: Long = 15 * 60_000L): JsonElement {
        val credential = key().takeIf { it.isNotBlank() } ?: error("Add a TMDB API key in Settings > API keys to use Explore.")
        val query = params.toSortedMap().map { (k,v) -> "${enc(k)}=${enc(v)}" }.joinToString("&")
        return cached("tmdb:$path?$query:${app.ember.tv.data.CachePolicy.digest(credential)}", ttl) { gate.withPermit {
            val data = get("https://api.themoviedb.org/3/$path?api_key=${enc(credential)}&$query", emptyMap())
            val valid = data is JsonObject && data.bool("success") != false && when {
                path.startsWith("discover/") || path.startsWith("search/") || path.startsWith("watch/providers/") -> data.arr("results") != null
                path.endsWith("_credits") -> data.arr("cast") != null && data.arr("crew") != null
                path.endsWith("watch/providers") -> data.obj("results") != null
                else -> data.long("id") != null
            }
            check(valid) { "TMDB returned an invalid response." }
            data
        } }
    }
    override suspend fun page(query: ExploreQuery, kind: ExploreKind, region: String, page: Int): ExplorePage {
        val params = parameters(query, kind, region, page)
        val response = tmdb("discover/${kind.path}", params)
        val results = response.arr("results") ?: error("TMDB returned an invalid discovery response.")
        return ExplorePage(results.mapNotNull { parseTitle(it, kind) }, (response.int("total_pages") ?: 1).coerceIn(0, 500), (response.int("total_pages") ?: 1) > 500)
    }
    override suspend fun people(query: ExploreQuery, kind: ExploreKind): List<ExploreTitle>? {
        if (query.actors.isEmpty() && query.directors.isEmpty()) return null
        suspend fun credits(ref: ExploreRef, director: Boolean): List<ExploreTitle> {
            val data = tmdb("person/${ref.id}/${kind.path}_credits", ttl = 24 * 60 * 60_000L)
            val list = data.arr(if (director) "crew" else "cast") ?: error("TMDB returned incomplete person credits.")
            return list.filter { !director || it.str("job") == "Director" }.mapNotNull { parseTitle(it, kind) }
        }
        val actors = query.actors.map { credits(it, false) }.flatten().distinctBy { it.tmdb }
        val directors = query.directors.map { credits(it, true) }.flatten().distinctBy { it.tmdb }
        val result = when {
            query.actors.isEmpty() -> directors
            query.directors.isEmpty() -> actors
            else -> { val ids = directors.map { it.tmdb }.toSet(); actors.filter { it.tmdb in ids } }
        }
        return ExploreRanking.sort(result, query.sort)
    }
    override suspend fun matchesExtras(title: ExploreTitle, query: ExploreQuery, kind: ExploreKind, region: String): Boolean {
        if (query.companies.isNotEmpty()) {
            val detail = tmdb("${kind.path}/${title.tmdb}", ttl = 24 * 60 * 60_000L)
            val companies = detail.arr("production_companies") ?: error("Company information is unavailable.")
            if (companies.none { c -> query.companies.any { it.id == c.long("id") } }) return false
        }
        if (query.providers.isNotEmpty()) {
            val response = tmdb("${kind.path}/${title.tmdb}/watch/providers", ttl = 60 * 60_000L)
            val regions = response.obj("results") ?: error("Streaming information is unavailable.")
            val offers = regions.obj(region) ?: return false
            val types = if (query.includeRentBuy) listOf("flatrate","free","ads","rent","buy") else listOf("flatrate","free","ads")
            val ids = types.flatMap { offers.arr(it).list() }.mapNotNull { it.long("provider_id") }.toSet()
            if (query.providers.none { it.id in ids }) return false
        }
        return true
    }
    suspend fun providers(region: String): List<ExploreRef> = coroutineScope {
        listOf("movie", "tv").map { type -> async {
            tmdb("watch/providers/$type", mapOf("watch_region" to region), 24 * 60 * 60_000L).arr("results")?.mapNotNull { p ->
                val id = p.long("provider_id") ?: return@mapNotNull null
                val name = p.str("provider_name") ?: return@mapNotNull null
                ExploreRef(id, name, p.str("logo_path")?.let { "https://image.tmdb.org/t/p/w92$it" })
            } ?: error("The service list is unavailable.")
        } }.awaitAll().flatten().distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }
    suspend fun search(text: String, company: Boolean): List<ExploreRef> {
        val q = text.trim(); if (q.length < 2) return emptyList()
        return tmdb("search/${if (company) "company" else "person"}", mapOf("query" to q, "include_adult" to "false"))
            .arr("results")?.filter { it.bool("adult") != true }?.take(20)?.mapNotNull { p ->
                val id = p.long("id") ?: return@mapNotNull null
                val name = p.str("name") ?: return@mapNotNull null
                val known = p.arr("known_for")?.firstOrNull()?.let { it.str("title") ?: it.str("name") }
                val note = if (company) p.str("origin_country")?.takeIf { it.isNotBlank() } else listOfNotNull(p.str("known_for_department"), known).joinToString(" · ").ifBlank { null }
                ExploreRef(id, name, (p.str("profile_path") ?: p.str("logo_path"))?.let { "https://image.tmdb.org/t/p/w185$it" }, note)
            } ?: emptyList()
    }
    /** Bio, birth and what they're known for, for the cast & crew popup (#92). Cached a day. */
    data class PersonInfo(val biography: String?, val born: String?, val place: String?, val knownFor: String?, val photo: String?)

    suspend fun personInfo(id: Long): PersonInfo? {
        if (id <= 0) return null
        val p = tmdb("person/$id", ttl = 24 * 60 * 60_000L)
        return PersonInfo(
            biography = p.str("biography")?.trim()?.takeIf { it.isNotEmpty() },
            born = p.str("birthday")?.takeIf { it.length >= 4 },
            place = p.str("place_of_birth")?.trim()?.takeIf { it.isNotEmpty() },
            knownFor = p.str("known_for_department")?.takeIf { it.isNotBlank() },
            photo = p.str("profile_path")?.let { "https://image.tmdb.org/t/p/w342$it" },
        )
    }

    /** On-demand, cached public credits; never guesses between people who share a name. */
    suspend fun filmography(person: ExploreRef, crew: Boolean): List<Meta> {
        require(person.id > 0)
        val data = tmdb("person/${person.id}/combined_credits", ttl = 24 * 60 * 60_000L)
        return withContext(Dispatchers.Default) {
            data.arr(if (crew) "crew" else "cast").list().mapNotNull { credit ->
                ensureActive()
                val kind = when (credit.str("media_type")) {
                    "movie" -> ExploreKind.MOVIE
                    "tv" -> ExploreKind.TV
                    else -> return@mapNotNull null
                }
                val title=parseTitle(credit,kind)?.takeUnless {it.adult} ?: return@mapNotNull null
                title to title.date?.let {runCatching {LocalDate.parse(it)}.getOrNull()}
            }.sortedWith(compareByDescending<Pair<ExploreTitle,LocalDate?>> {it.second}.thenByDescending {it.first.popularity})
                .distinctBy {it.first.meta.type to it.first.tmdb}.take(100).map {it.first.meta.copy(adult=false)}
        }
    }

    override suspend fun awards(ids: List<Long>, query: ExploreQuery): Set<Long> = awards(ids, query, ExploreKind.MOVIE)

    override suspend fun awards(ids: List<Long>, query: ExploreQuery, kind: ExploreKind): Set<Long> {
        if (ids.isEmpty() || query.oscars == OscarMode.ANY) return ids.toSet()
        val sparql = awardQuery(ids, query, kind)
        val response = cached("wikidata:$sparql", 7 * 24 * 60 * 60_000L) { gate.withPermit {
            val data = get("https://query.wikidata.org/sparql?format=json&query=${enc(sparql)}", mapOf(
                "Accept" to "application/sparql-results+json",
                "User-Agent" to "EmberTV/1.0 (Android TV; https://github.com/PrimeN/Ember-Launcher)",
            ))
            check(data.obj("results").arr("bindings") != null) { "Award records are unavailable." }
            data
        } }
        val bindings = response.obj("results").arr("bindings") ?: error("Award records are unavailable. Retry without changing your filters.")
        return bindings.mapNotNull { it.obj("tmdb").str("value")?.toLongOrNull() }.filter { it in ids }.toSet()
    }
    companion object {
        /** Only an unambiguous exact name may be selected without user confirmation. */
        fun personIdentity(person: app.ember.tv.data.Person, candidates: List<ExploreRef>): ExploreRef? =
            person.tmdbId?.takeIf {it>0}?.let {ExploreRef(it,person.name,person.photo)}
                ?: candidates.filter {it.name.equals(person.name,true)}.singleOrNull()
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        fun parameters(query: ExploreQuery, kind: ExploreKind, region: String, page: Int, today: LocalDate = LocalDate.now()): Map<String,String> = buildMap {
            require(kind != ExploreKind.ALL && page in 1..500)
            put("include_adult", "false"); put("include_video", "false"); put("page", "$page")
            put("sort_by", if (query.sort == ExploreSort.NEWEST) if (kind == ExploreKind.MOVIE) "primary_release_date.desc" else "first_air_date.desc" else query.sort.api)
            val date = if (kind == ExploreKind.MOVIE) "primary_release_date" else "first_air_date"
            put("$date.lte", "${query.toYear?.let { "$it-12-31" }?.takeIf { it < today.toString() } ?: today}")
            query.fromYear?.let { put("$date.gte", "$it-01-01") }
            query.minRating?.let { put("vote_average.gte", "$it") }; query.maxRating?.let { put("vote_average.lte", "$it") }
            if (query.movieVotes(kind) > 0) put("vote_count.gte", "${query.movieVotes(kind)}")
            // OR is a safe server-side superset; grouped Match all is checked before the cap.
            ExploreGenres.selected(query).flatMap { it.ids(kind) }.distinct().takeIf { it.isNotEmpty() }
                ?.let { put("with_genres", it.joinToString("|")) }
            if (query.companies.isNotEmpty()) put("with_companies", query.companies.joinToString("|") { "${it.id}" })
            if (query.providers.isNotEmpty()) {
                put("watch_region", region)
                put("with_watch_providers", query.providers.joinToString("|") { "${it.id}" })
                put("with_watch_monetization_types", if (query.includeRentBuy) "flatrate|free|ads|rent|buy" else "flatrate|free|ads")
            }
        }
        fun parseTitle(r: JsonElement, kind: ExploreKind): ExploreTitle? {
            val id = r.long("id")?.takeIf { it > 0 } ?: return null
            val name = (r.str("title") ?: r.str("name"))?.takeIf { it.isNotBlank() } ?: return null
            val date = r.str(if (kind == ExploreKind.MOVIE) "release_date" else "first_air_date")
            val score = r.dbl("vote_average")?.takeIf { it.isFinite() } ?: 0.0
            val votes = r.int("vote_count") ?: 0
            return ExploreTitle(Meta(id = "tmdb:${kind.path}:$id", type = if (kind == ExploreKind.MOVIE) "movie" else "series",
                name = name, tmdbId = id, releaseInfo = date?.take(4), description = r.str("overview"),
                poster = r.str("poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" },
                background = r.str("backdrop_path")?.let { "https://image.tmdb.org/t/p/w780$it" },
                badge = if (votes > 0) "TMDB %.1f · %,d votes".format(java.util.Locale.US, score, votes) else null),
                r.arr("genre_ids").list().mapNotNull { (it as? JsonPrimitive)?.intOrNull }.toSet(), date,
                r.dbl("popularity")?.takeIf { it.isFinite() } ?: 0.0, score, votes, r.bool("adult") == true)
        }
        fun awardQuery(ids: List<Long>, query: ExploreQuery, kind: ExploreKind = ExploreKind.MOVIE): String {
            require(ids.size <= 40 && ids.all { it > 0 })
            val properties = if (query.oscars == OscarMode.WIN) listOf("P166") else listOf("P166", "P1411")
            val branches = properties.flatMap { p -> listOf(
                "{ ?film wdt:$p ?award . }",
                "{ ?recipient p:$p ?statement . ?statement ps:$p ?award ; pq:P1686 ?film . FILTER NOT EXISTS { ?statement wikibase:rank wikibase:DeprecatedRank } }",
            ) }.joinToString(" UNION ")
            // #252: any category of the chosen award, or the award itself (a festival's top prize is a single award).
            val body = AwardBodies.of(query.award)
            val category = body.best?.takeIf { query.bestPicture }?.let { "?award (wdt:P31|wdt:P279)* wd:${it.first} ." }
                ?: "?award (wdt:P361|wdt:P31|wdt:P279)* wd:${body.qid} ."
            // Films by their TMDB movie id (P4947), shows by their TMDB TV id (P4983).
            val tmdbProperty = if (kind == ExploreKind.TV) "P4983" else "P4947"
            return """PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
PREFIX p: <http://www.wikidata.org/prop/>
PREFIX ps: <http://www.wikidata.org/prop/statement/>
PREFIX pq: <http://www.wikidata.org/prop/qualifier/>
PREFIX wikibase: <http://wikiba.se/ontology#>
SELECT DISTINCT ?tmdb WHERE {
VALUES ?tmdb { ${ids.distinct().joinToString(" ") { "\"$it\"" }} }
?film wdt:$tmdbProperty ?tmdb .
{ $branches }
$category
} LIMIT 5000"""
        }
    }
}
