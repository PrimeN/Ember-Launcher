package app.ember.tv.data

import app.ember.tv.R
import app.ember.tv.tracker.Http
import app.ember.tv.tracker.arr
import app.ember.tv.tracker.dbl
import app.ember.tv.tracker.int
import app.ember.tv.tracker.list
import app.ember.tv.tracker.obj
import app.ember.tv.tracker.str
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.util.Locale

/** A rating service shown on title screens (Settings > Ratings). */
data class RatingSource(
    val id: String,
    val label: String,
    /** Rating-service logo URL, or blank to use the bundled copy. Rights remain with its owner. */
    val iconUrl: String,
    /** Bundled logo shown while a remote image loads, when unavailable, or for local-only icons. */
    val fallbackIcon: Int,
    /** Icon box width in dp; wordmark logos are wider than square ones. */
    val iconWidth: Int = 22,
    /** What the service needs, in words, for Settings. */
    val needs: String,
)

object Ratings {
    val SOURCES = listOf(
        RatingSource(
            "trakt", "Trakt",
            "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3d/Trakt.tv-favicon.svg/250px-Trakt.tv-favicon.svg.png",
            R.drawable.rating_trakt,
            needs = "Uses the Trakt client ID (API keys). No sign-in needed.",
        ),
        RatingSource(
            "imdb", "IMDb",
            "https://upload.wikimedia.org/wikipedia/commons/thumb/6/69/IMDB_Logo_2016.svg/960px-IMDB_Logo_2016.svg.png",
            R.drawable.rating_imdb, iconWidth = 44,
            needs = "From your addon's details (Cinemeta has it), or MDBList / OMDb.",
        ),
        RatingSource(
            "metacritic", "Metacritic",
            "https://upload.wikimedia.org/wikipedia/commons/f/f2/Metacritic_M.png",
            R.drawable.rating_metacritic,
            needs = "Needs an MDBList or OMDb key (API keys).",
        ),
        RatingSource(
            "tomatoes", "Rotten Tomatoes",
            "https://upload.wikimedia.org/wikipedia/commons/thumb/5/5b/Rotten_Tomatoes.svg/250px-Rotten_Tomatoes.svg.png",
            R.drawable.rating_tomatoes,
            needs = "Needs an MDBList or OMDb key (API keys).",
        ),
        RatingSource(
            "tmdb", "TMDB",
            "",
            R.drawable.rating_tmdb, iconWidth = 52,
            needs = "Needs a TMDB or MDBList key (API keys).",
        ),
        RatingSource(
            "anilist", "AniList",
            "https://upload.wikimedia.org/wikipedia/commons/thumb/6/61/AniList_logo.svg/250px-AniList_logo.svg.png",
            R.drawable.rating_anilist,
            needs = "Free, no key. Anime only, matched by title and year.",
        ),
    )
    val byId = SOURCES.associateBy { it.id }

    /** Sources in the user's order, then any new ones. */
    fun ordered(order: List<String>): List<RatingSource> =
        order.mapNotNull { byId[it] } + SOURCES.filter { it.id !in order }

    // ---- fetching ----

    /**
     * Ratings for a title, as display text by source id (e.g. "imdb" to "8.5", "tomatoes" to "93%").
     * Only [wanted] sources are asked for; each one fails quietly.
     */
    suspend fun load(meta: Meta, addonImdbRating: String?, wanted: Set<String>): Map<String, String> = coroutineScope {
        val movie = meta.type == "movie"
        val imdb = meta.imdb
        val out = LinkedHashMap<String, String>()

        val mdb = async { if (needsMdb(wanted, addonImdbRating)) mdbList(movie, imdb, meta.tmdbId) else null }
        val omdb = async { if (needsMdb(wanted, addonImdbRating) && ApiKeys.get("MDBLIST_API_KEY").isBlank()) omdb(imdb) else null }
        val trakt = async { if ("trakt" in wanted) trakt(movie, imdb) else null }
        val tmdb = async { if ("tmdb" in wanted && ApiKeys.get("MDBLIST_API_KEY").isBlank()) tmdb(movie, meta.tmdbId, imdb) else null }
        val anilist = async { if ("anilist" in wanted && looksLikeAnime(meta)) anilist(meta) else null }

        val m = mdb.await().orEmpty()
        val o = omdb.await().orEmpty()
        if ("imdb" in wanted) (addonImdbRating?.takeIf { it.toDoubleOrNull()?.let { v -> v > 0 } == true } ?: m["imdb"] ?: o["imdb"])?.let { out["imdb"] = it }
        if ("trakt" in wanted) (trakt.await() ?: m["trakt"])?.let { out["trakt"] = it }
        if ("tmdb" in wanted) (m["tmdb"] ?: tmdb.await())?.let { out["tmdb"] = it }
        if ("metacritic" in wanted) (m["metacritic"] ?: o["metacritic"])?.let { out["metacritic"] = it }
        if ("tomatoes" in wanted) (m["tomatoes"] ?: o["tomatoes"])?.let { out["tomatoes"] = it }
        if ("anilist" in wanted) anilist.await()?.let { out["anilist"] = it }
        out
    }

    private fun needsMdb(wanted: Set<String>, addonImdb: String?) =
        "metacritic" in wanted || "tomatoes" in wanted || ("imdb" in wanted && addonImdb == null) || "tmdb" in wanted

    private fun one(v: Double) = String.format(Locale.US, "%.1f", v)

    /** MDBList: one call for IMDb, TMDB, Trakt, Metacritic and Rotten Tomatoes. Free plan: 1,000 calls a day. */
    private suspend fun mdbList(movie: Boolean, imdb: String?, tmdbId: Long?): Map<String, String>? {
        val key = ApiKeys.get("MDBLIST_API_KEY").takeIf { it.isNotBlank() } ?: return null
        val type = if (movie) "movie" else "show"
        val path = imdb?.let { "imdb/$type/$it" } ?: tmdbId?.let { "tmdb/$type/$it" } ?: return null
        return runCatching {
            val j = Http.get("https://api.mdblist.com/$path?apikey=${enc(key)}")
            val out = HashMap<String, String>()
            j.arr("ratings").list().forEach { r ->
                val value = r.dbl("value")
                val score = r.dbl("score")
                when (r.str("source")) {
                    "imdb" -> value?.takeIf { it > 0 }?.let { out["imdb"] = one(it) }
                    "tmdb" -> (value ?: score)?.takeIf { it > 0 }?.let { out["tmdb"] = one(if (it > 10) it / 10 else it) }
                    "trakt" -> (score ?: value)?.takeIf { it > 0 }?.let { out["trakt"] = "${(if (it <= 10) it * 10 else it).toInt()}%" }
                    "metacritic" -> (value ?: score)?.takeIf { it > 0 }?.let { out["metacritic"] = it.toInt().toString() }
                    "tomatoes" -> (value ?: score)?.takeIf { it > 0 }?.let { out["tomatoes"] = "${it.toInt()}%" }
                }
            }
            out
        }.getOrNull()
    }

    /** OMDb: IMDb, Rotten Tomatoes and Metacritic. Free key: 1,000 calls a day. Used when there is no MDBList key. */
    private suspend fun omdb(imdb: String?): Map<String, String>? {
        val key = ApiKeys.get("OMDB_API_KEY").takeIf { it.isNotBlank() } ?: return null
        imdb ?: return null
        return runCatching {
            val j = Http.get("https://www.omdbapi.com/?i=$imdb&apikey=${enc(key)}")
            val out = HashMap<String, String>()
            j.str("imdbRating")?.toDoubleOrNull()?.let { out["imdb"] = one(it) }
            j.arr("Ratings").list().forEach { r ->
                val v = r.str("Value") ?: return@forEach
                when (r.str("Source")) {
                    "Rotten Tomatoes" -> out["tomatoes"] = v
                    "Metacritic" -> out["metacritic"] = v.substringBefore('/')
                }
            }
            j.str("Metascore")?.toIntOrNull()?.let { out.putIfAbsent("metacritic", it.toString()) }
            out
        }.getOrNull()
    }

    /** Trakt's public rating (0-10), shown as a percentage like trakt.tv does. */
    private suspend fun trakt(movie: Boolean, imdb: String?): String? {
        val id = ApiKeys.get("TRAKT_CLIENT_ID").takeIf { it.isNotBlank() } ?: return null
        imdb ?: return null
        return runCatching {
            val j = Http.get(
                "https://api.trakt.tv/${if (movie) "movies" else "shows"}/$imdb/ratings",
                mapOf("trakt-api-version" to "2", "trakt-api-key" to id),
            )
            j.dbl("rating")?.takeIf { it > 0 }?.let { "${(it * 10).toInt()}%" }
        }.getOrNull()
    }

    private suspend fun tmdb(movie: Boolean, tmdbId: Long?, imdb: String?): String? {
        val key = ApiKeys.get("TMDB_API_KEY").takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val type = if (movie) "movie" else "tv"
            val id = tmdbId ?: imdb?.let {
                Http.get("https://api.themoviedb.org/3/find/$it?api_key=$key&external_source=imdb_id")
                    .arr(if (movie) "movie_results" else "tv_results").list().firstOrNull().str("id")?.toLongOrNull()
            } ?: return null
            Http.get("https://api.themoviedb.org/3/$type/$id?api_key=$key").dbl("vote_average")?.takeIf { it > 0 }?.let { one(it) }
        }.getOrNull()
    }

    /** Anime titles: an anime type, an anime id (kitsu, mal, anilist), or an Anime/Animation genre. */
    fun looksLikeAnime(meta: Meta): Boolean =
        meta.type == "anime" ||
            listOf("kitsu:", "mal:", "anilist:").any { meta.id.startsWith(it) } ||
            meta.genres.orEmpty().any { it.equals("Anime", true) || it.equals("Animation", true) }

    /** AniList average score, from a title search checked against the name and year. */
    private suspend fun anilist(meta: Meta): String? = runCatching {
        val year = meta.releaseInfo?.take(4)?.toIntOrNull()
        val body = buildJsonObject {
            put(
                "query",
                "query(\$q:String){Page(perPage:5){media(search:\$q,type:ANIME){averageScore seasonYear startDate{year} title{romaji english native}}}}",
            )
            putJsonObject("variables") { put("q", meta.name) }
        }
        val list = Http.post("https://graphql.anilist.co", body).obj("data").obj("Page").arr("media").list()
        fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }
        val want = norm(meta.name)
        val match = list.firstOrNull { m ->
            val t = m.obj("title")
            val names = listOf(t.str("english"), t.str("romaji"), t.str("native")).map { norm(it) }
            val y = m.int("seasonYear") ?: m.obj("startDate").int("year")
            want in names && (year == null || y == null || kotlin.math.abs(y - year) <= 1)
        }
        match.int("averageScore")?.takeIf { it > 0 }?.let { "$it%" }
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
