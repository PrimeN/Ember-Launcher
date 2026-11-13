package app.ember.tv.data

import app.ember.tv.tracker.arr
import app.ember.tv.tracker.str
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.util.Locale

/** Cursor progress follows provider pages, not the number of titles that survive mapping/dedup. */
data class CollectionPage(val items: List<Meta>, val nextSkip: Int, val end: Boolean, val fingerprint: String? = null) {
    companion object {
        fun fingerprint(value: Any): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toString().toByteArray(Charsets.UTF_8)).toHex()
    }
}

/** Credential-bearing requests do not enter the HTTP disk cache. Test clients need no real API keys. */
internal class CollectionRequests(
    client: OkHttpClient = Net.client(10, 30),
    /** #246: the signed-in Trakt account's token, so your own and private lists load. */
    private val traktToken: () -> String? = { Collections.traktToken?.invoke() },
    private val key: (String) -> String = ApiKeys::get,
) {
    private val http = client.newBuilder().cache(null).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun page(source: CollectionSource, skip: Int): CollectionPage {
        require(skip >= 0)
        return if (source.provider.equals("trakt", true)) trakt(source, skip)
            else if (source.provider.equals(HomeWidgets.MDBLIST, true)) mdblist(source, skip) else tmdb(source, skip)
    }

    /** A public Trakt list, for choosing one as a Home widget's source (#161). */
    data class ListRef(val id: Long, val name: String, val by: String, val items: Int, val likes: Int, val description: String)

    /**
     * Trakt lists to choose from: your own ("mine") and liked ("liked") when signed in, Trakt's popular
     * or trending lists, or a search. #246: Trakt now answers the bare popular/trending addresses with
     * nothing; they take a "personal" or "official" ending.
     */
    suspend fun traktLists(tab: String, query: String): List<ListRef> {
        val clientId = key("TRAKT_CLIENT_ID").ifBlank { error("Trakt lists need a client id.") }
        val q = query.trim()
        val path = traktListsPath(tab, q)
        if ((tab == "mine" || tab == "liked") && q.isEmpty() && traktToken() == null) error("Sign in to Trakt in Settings > Trackers to see your lists.")
        val url = "https://api.trakt.tv/$path".toHttpUrl().newBuilder().apply { if (q.isNotEmpty()) addQueryParameter("query", q.take(100)) }
            .addQueryParameter("limit", if (tab == "mine" && q.isEmpty()) "100" else "30").build()
        val result = traktRequest(url, clientId)
        return parseTraktLists(result.body)
    }

    /** A Trakt request, signed in when an account is, and again without the sign-in if Trakt refuses it. */
    private suspend fun traktRequest(url: okhttp3.HttpUrl, clientId: String): Reply {
        fun build(token: String?) = Request.Builder().url(url).header("trakt-api-version", "2").header("trakt-api-key", clientId)
            .header("Content-Type", "application/json").header("User-Agent", TRAKT_AGENT).apply { token?.let { header("Authorization", "Bearer $it") } }.build()
        val token = traktToken()
        return try { request(build(token)) } catch (e: IllegalStateException) {
            if (token != null && e.message?.contains("HTTP 401") == true) request(build(null)) else throw e
        }
    }

    /**
     * MDBList (#161): a list by number or user/list-name. Movies and shows come back together;
     * [CollectionSource.mediaType] narrows them. Artwork comes by IMDb id, as for Trakt lists.
     */
    private suspend fun mdblist(s: CollectionSource, skip: Int): CollectionPage {
        val apiKey = key("MDBLIST_API_KEY").ifBlank { error("MDBList lists need your MDBList API key.") }
        val path = s.catalogId?.let(HomeWidgets::mdblistPath) ?: error("This MDBList source has no list.")
        val url = "https://api.mdblist.com/lists/$path/items".toHttpUrl().newBuilder()
            .addQueryParameter("apikey", apiKey).addQueryParameter("limit", "100").addQueryParameter("offset", skip.toString()).build()
        val root = request(Request.Builder().url(url).build()).body
        val (items, raw) = parseMdblist(root, s.mediaType)
        return CollectionPage(items, skip + 100, raw < 100, CollectionPage.fingerprint(root))
    }

    companion object {
        /** Trakt asks API clients to identify themselves (#166). */
        const val TRAKT_AGENT = "EmberTV/1.0 (Android TV; https://github.com/PrimeN/Ember-Launcher)"
        /** Where each kind of list comes from. */
        internal fun traktListsPath(tab: String, query: String): String = when {
            query.isNotBlank() -> "search/list"
            tab == "mine" -> "users/me/lists"
            tab == "liked" -> "users/likes/lists"
            tab == "trending" -> "lists/trending/personal"
            tab == "official" -> "lists/popular/official"
            else -> "lists/popular/personal"
        }

        /** Lists come wrapped ({"list": …}) from popular, trending, search and likes, and bare from your own. */
        internal fun parseTraktLists(body: JsonElement): List<ListRef> = (body as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val list = (o["list"] as? JsonObject) ?: o.takeIf { it["ids"] is JsonObject } ?: return@mapNotNull null
            val id = ((list["ids"] as? JsonObject)?.get("trakt") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            fun text(o: JsonObject?, name: String) = (o?.get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()
            fun number(name: String) = (list[name] as? JsonPrimitive)?.intOrNull ?: 0
            ListRef(id, text(list, "name").ifBlank { return@mapNotNull null }, text(list["user"] as? JsonObject, "username"),
                number("item_count"), number("likes"), text(list, "description").replace(Regex("\\s+"), " ").trim().take(200))
        }.distinctBy { it.id }

        /** The titles and how many rows the page held (to know whether another page follows). */
        internal fun parseMdblist(root: JsonElement, mediaType: String?): Pair<List<Meta>, Int> {
            val o = root as? JsonObject ?: error("The collection provider returned an unreadable page.")
            fun rows(field: String) = (o[field] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            fun titles(field: String, type: String) = rows(field).mapNotNull { row ->
                val imdb = (row["imdb_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("tt") } ?: return@mapNotNull null
                ((row["rank"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE) to Meta(id = imdb, type = type, name = (row["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                    releaseInfo = (row["release_year"] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }, imdbId = imdb,
                    poster = "https://images.metahub.space/poster/medium/$imdb/img", background = "https://images.metahub.space/background/medium/$imdb/img")
            }
            val movies = if (mediaType.equals("tv", true)) emptyList() else titles("movies", "movie")
            val shows = if (mediaType.equals("movie", true)) emptyList() else titles("shows", "series")
            // #170: a mixed list keeps its own order (each row's rank), not every movie before any show.
            return (movies + shows).sortedBy { it.first }.map { it.second } to (rows("movies").size + rows("shows").size)
        }
    }

    private data class Reply(val body: JsonElement, val pageCount: Int? = null)
    private suspend fun request(request: Request): Reply = coroutineScope {
        withResponse(http.newCall(request)) { response ->
            withContext(Dispatchers.IO) {
                check(response.isSuccessful) { "The collection provider returned HTTP ${response.code}." }
                // #246: an empty answer (HTTP 204) is no titles, not a broken page.
                val text = response.body.string()
                Reply(if (text.isBlank()) JsonArray(emptyList()) else json.parseToJsonElement(text),
                    response.header("X-Pagination-Page-Count")?.toIntOrNull()?.takeIf { it >= 0 })
            }
        }
    }

    private suspend fun tmdb(s: CollectionSource, skip: Int): CollectionPage {
        val apiKey = key("TMDB_API_KEY").ifBlank { error("TMDB sources need an API key.") }
        val page = skip / 20 + 1
        val sourceType = s.tmdbSourceType?.uppercase(Locale.ROOT) ?: "DISCOVER"
        val movie = sourceType == "COLLECTION" || !s.mediaType.equals("tv", true) && sourceType != "NETWORK"
        val kind = if (movie) "movie" else "tv"
        val base = "https://api.themoviedb.org/3"
        fun discover(extra: Map<String, String>): String {
            val q = LinkedHashMap<String, String>()
            s.sortBy?.takeIf { it.isNotBlank() && it != "original" }?.let {
                q["sort_by"] = if (movie) it.replace("first_air_date", "primary_release_date")
                    else it.replace("primary_release_date", "first_air_date")
            }
            q.putAll(filterParams(s.filters, movie))
            q.putAll(extra)
            return "$base/discover/$kind".toHttpUrl().newBuilder().apply {
                q.forEach { (name, value) -> addQueryParameter(name, value) }
            }.build().toString()
        }
        val id = s.tmdbId
        val (endpoint, field) = when (sourceType) {
            "LIST" -> "$base/list/${id ?: error("TMDB list without an id")}" to "items"
            "COLLECTION" -> "$base/collection/${id ?: error("TMDB collection without an id")}" to "parts"
            "COMPANY" -> discover(mapOf("with_companies" to (id ?: error("TMDB company without an id")).toString())) to "results"
            "NETWORK" -> discover(mapOf("with_networks" to (id ?: error("TMDB network without an id")).toString())) to "results"
            "PERSON" -> discover(mapOf("with_people" to (id ?: error("TMDB person without an id")).toString())) to "results"
            "DIRECTOR" -> discover(mapOf("with_crew" to (id ?: error("TMDB director without an id")).toString())) to "results"
            else -> discover(emptyMap()) to "results"
        }
        if (page > 1 && field == "parts") return CollectionPage(emptyList(), page * 20, true)
        val url = endpoint.toHttpUrl().newBuilder().addQueryParameter("api_key", apiKey)
            .addQueryParameter("page", page.toString()).build()
        val result = request(Request.Builder().url(url).build()).body
        val raw = result.arr(field) ?: error("The collection provider returned an unreadable page.")
        val total = (result as? JsonObject)?.get("total_pages")?.jsonPrimitive?.intOrNull
        return CollectionPage(raw.mapNotNull { tmdbMeta(it, movie) }, page * 20,
            field == "parts" || raw.isEmpty() || total?.let { page >= it } == true, CollectionPage.fingerprint(raw))
    }

    private fun tmdbMeta(r: JsonElement, movieDefault: Boolean): Meta? {
        val o = r as? JsonObject ?: return null
        val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return null
        val movie = when (o.str("media_type")) { "movie" -> true; "tv" -> false; "person" -> return null; else -> movieDefault }
        val name = (if (movie) o.str("title") else o.str("name")) ?: o.str("title") ?: o.str("name") ?: return null
        return Meta(id = "tmdb:$id", type = if (movie) "movie" else "series", name = name,
            poster = o.str("poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" },
            background = o.str("backdrop_path")?.let { "https://image.tmdb.org/t/p/w1280$it" },
            description = o.str("overview"), releaseInfo = (o.str("release_date") ?: o.str("first_air_date"))?.take(4), tmdbId = id)
    }

    /** Rating, vote, date and year limits: Older collection exports save an unused limit as 0, and TMDB then
     * returns nothing (vote_average.lte=0). A 0 limit is never meaningful, so it is left out. */
    private val limits = setOf("voteAverageGte", "voteAverageLte", "voteCountGte", "releaseDateGte", "releaseDateLte", "year")

    internal fun filterParams(f: Map<String, JsonElement>?, movie: Boolean): Map<String, String> {
        fun v(k: String) = (f?.get(k) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
            ?.takeIf { k !in limits || it.toDoubleOrNull() != 0.0 }
        val date = if (movie) "primary_release_date" else "first_air_date"
        return buildMap {
            mapOf("withGenres" to "with_genres", "withoutGenres" to "without_genres",
                "releaseDateGte" to "$date.gte", "releaseDateLte" to "$date.lte",
                "voteAverageGte" to "vote_average.gte", "voteAverageLte" to "vote_average.lte",
                "voteCountGte" to "vote_count.gte", "withOriginalLanguage" to "with_original_language",
                "withOriginCountry" to "with_origin_country", "withKeywords" to "with_keywords",
                "withoutKeywords" to "without_keywords", "withCompanies" to "with_companies",
                "withoutCompanies" to "without_companies", "withNetworks" to "with_networks",
                "year" to if (movie) "primary_release_year" else "first_air_date_year",
                "watchRegion" to "watch_region", "withWatchProviders" to "with_watch_providers",
                "withoutWatchProviders" to "without_watch_providers").forEach { (input, output) -> v(input)?.let { put(output, it) } }
        }
    }

    private suspend fun trakt(s: CollectionSource, skip: Int): CollectionPage {
        val clientId = key("TRAKT_CLIENT_ID").ifBlank { error("Trakt lists need a client id.") }
        val id = s.traktListId ?: error("Trakt list without an id")
        val type = if (s.mediaType.equals("tv", true)) "show" else "movie"
        val sort = s.sortBy?.lowercase(Locale.ROOT)?.takeIf { it in setOf("rank", "added", "title", "released", "runtime", "popularity", "percentage", "votes") } ?: "rank"
        val how = s.sortHow?.lowercase(Locale.ROOT)?.takeIf { it in setOf("asc", "desc") } ?: "asc"
        val page = skip / 100 + 1
        val url = "https://api.trakt.tv/lists/$id/items/$type/$sort/$how".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "100").addQueryParameter("page", page.toString()).build()
        // #246: signed in, so your private lists load too.
        val result = traktRequest(url, clientId)
        val raw = result.body as? JsonArray ?: error("The collection provider returned an unreadable page.")
        val items = raw.mapNotNull { e ->
            val o = (e as? JsonObject)?.get(type) as? JsonObject ?: return@mapNotNull null
            val ids = o["ids"] as? JsonObject
            val imdb = ids?.str("imdb")?.takeIf { it.isNotBlank() }
            val tmdb = (ids?.get("tmdb") as? JsonPrimitive)?.longOrNull
            Meta(id = imdb ?: tmdb?.let { "tmdb:$it" } ?: return@mapNotNull null,
                type = if (type == "movie") "movie" else "series", name = o.str("title") ?: return@mapNotNull null,
                releaseInfo = (o["year"] as? JsonPrimitive)?.contentOrNull, imdbId = imdb, tmdbId = tmdb,
                poster = imdb?.let { "https://images.metahub.space/poster/medium/$it/img" },
                background = imdb?.let { "https://images.metahub.space/background/medium/$it/img" })
        }
        return CollectionPage(items, page * 100, raw.isEmpty() || result.pageCount?.let { page >= it } == true, CollectionPage.fingerprint(raw))
    }
}
