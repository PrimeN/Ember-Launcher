package app.ember.tv.data

import app.ember.tv.tracker.Http
import app.ember.tv.tracker.arr
import app.ember.tv.tracker.int
import app.ember.tv.tracker.list
import app.ember.tv.tracker.long
import app.ember.tv.tracker.obj
import app.ember.tv.tracker.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Settings > Metadata: AniList for anime, TVDB for TV shows. Saved as JSON in preferences. */
@Serializable
data class MetaConfig(
    // ---- AniList (anime) ----
    val anilistOn: Boolean = false,
    val anilistDescription: Boolean = true,
    val anilistArtwork: Boolean = true,
    val anilistGenres: Boolean = true,
    /** Studio, status and next episode under the title. */
    val anilistDetails: Boolean = true,
    /** "addon" keeps the addon's title; "english" or "romaji" use AniList's. */
    val anilistTitle: String = "addon",
    // ---- TVDB (TV shows) ----
    val tvdbOn: Boolean = false,
    val tvdbDescription: Boolean = true,
    val tvdbArtwork: Boolean = true,
    /** Episode names, plots, air dates and thumbnails. */
    val tvdbEpisodes: Boolean = true,
    /** Network and status under the title. */
    val tvdbDetails: Boolean = true,
    /** Also use TVDB for anime (AniList still wins where both give something). */
    val tvdbForAnime: Boolean = true,
    /** TVDB language code for descriptions and episode names, e.g. "eng". */
    val tvdbLanguage: String = "eng",
    // ---- both ----
    /** True: replace what the addon gave. False: only fill in what is missing. */
    val replace: Boolean = false,
    /** #263: the catalog addon's own details, logo, backgrounds and cast come first. */
    val addonFirst: Boolean = true,
)

/** What a provider found, before it is merged into the title screen. */
data class MetaExtra(
    val source: String,
    val name: String? = null,
    val description: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val genres: List<String> = emptyList(),
    /** Short facts for the line under the title, e.g. "Studio MAPPA", "Airing", "Next: E8 in 3 days". */
    val facts: List<String> = emptyList(),
    /** Episode details by (season, episode). */
    val episodes: Map<Pair<Int, Int>, EpisodeExtra> = emptyMap(),
)

data class EpisodeExtra(val title: String?, val overview: String?, val aired: String?, val thumbnail: String?)

object MetaEnhance {
    val TVDB_LANGUAGES = listOf(
        "eng" to "English", "spa" to "Spanish", "fra" to "French", "deu" to "German", "ita" to "Italian",
        "por" to "Portuguese", "pt" to "Portuguese (Brazil)", "nld" to "Dutch", "pol" to "Polish", "rus" to "Russian",
        "tur" to "Turkish", "ara" to "Arabic", "jpn" to "Japanese", "kor" to "Korean", "zho" to "Chinese",
        "hin" to "Hindi", "swe" to "Swedish", "ces" to "Czech",
    )

    // ---- AniList ----

    /** AniList entry for an anime, found by title and checked against the year. Free, no key. */
    suspend fun anilist(meta: Meta, c: MetaConfig): MetaExtra? = runCatching {
        val year = meta.releaseInfo?.take(4)?.toIntOrNull()
        val body = buildJsonObject {
            put(
                "query",
                "query(\$q:String){Page(perPage:5){media(search:\$q,type:ANIME){" +
                    "title{romaji english native} description(asHtml:false) genres status episodes seasonYear startDate{year} " +
                    "coverImage{extraLarge} bannerImage studios(isMain:true){nodes{name}} " +
                    "nextAiringEpisode{episode timeUntilAiring}}}}",
            )
            putJsonObject("variables") { put("q", meta.name) }
        }
        val list = Http.post("https://graphql.anilist.co", body).obj("data").obj("Page").arr("media").list()
        fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }
        val want = norm(meta.name)
        val m = list.firstOrNull { m ->
            val t = m.obj("title")
            val y = m.int("seasonYear") ?: m.obj("startDate").int("year")
            want in listOf(t.str("english"), t.str("romaji"), t.str("native")).map { norm(it) } &&
                (year == null || y == null || kotlin.math.abs(y - year) <= 1)
        } ?: return null
        val t = m.obj("title")
        val next = m.obj("nextAiringEpisode")
        val facts = listOfNotNull(
            m.obj("studios").arr("nodes").list().firstOrNull().str("name")?.let { "Studio $it" },
            when (m.str("status")) {
                "RELEASING" -> "Airing"
                "FINISHED" -> "Finished"
                "NOT_YET_RELEASED" -> "Not yet aired"
                "HIATUS" -> "On hiatus"
                "CANCELLED" -> "Cancelled"
                else -> null
            },
            m.int("episodes")?.let { "$it episodes" },
            next?.int("episode")?.let { ep ->
                val days = (next.long("timeUntilAiring") ?: 0L) / 86_400
                "Next: E$ep " + when (days) {
                    0L -> "today"
                    1L -> "tomorrow"
                    else -> "in $days days"
                }
            },
        )
        MetaExtra(
            source = "AniList",
            name = when (c.anilistTitle) {
                "english" -> t.str("english") ?: t.str("romaji")
                "romaji" -> t.str("romaji")
                else -> null
            },
            description = m.str("description")?.let { clean(it) },
            poster = m.obj("coverImage").str("extraLarge"),
            background = m.str("bannerImage"),
            genres = m.arr("genres").list().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            facts = facts,
        )
    }.getOrNull()

    /** AniList descriptions carry a little HTML (<br>, <i>) even in plain mode. */
    private fun clean(s: String) = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    // ---- TVDB (v4 API) ----

    private var tvdbToken: String? = null
    private var tvdbTokenKey: String? = null
    private var tvdbTokenAt = 0L

    /** Logs in with the project key (and subscriber PIN, if set). Tokens last a month; renewed weekly here. */
    private suspend fun tvdbLogin(): String? {
        val key = ApiKeys.get("TVDB_API_KEY").takeIf { it.isNotBlank() } ?: return null
        val pin = ApiKeys.get("TVDB_PIN")
        val fresh = System.currentTimeMillis() - tvdbTokenAt < 7L * 24 * 3600 * 1000
        if (tvdbToken != null && tvdbTokenKey == key + pin && fresh) return tvdbToken
        val body = buildJsonObject {
            put("apikey", key)
            if (pin.isNotBlank()) put("pin", pin)
        }
        val token = Http.post("https://api4.thetvdb.com/v4/login", body).obj("data").str("token") ?: return null
        tvdbToken = token
        tvdbTokenKey = key + pin
        tvdbTokenAt = System.currentTimeMillis()
        return token
    }

    /** TVDB series details and episodes, found by IMDb id (or TMDB id). Needs a TVDB API key. */
    suspend fun tvdb(meta: Meta, c: MetaConfig): MetaExtra? = runCatching {
        val token = tvdbLogin() ?: return null
        val h = mapOf("Authorization" to "Bearer $token", "Accept-Language" to c.tvdbLanguage)
        val base = "https://api4.thetvdb.com/v4"
        val remote = meta.imdb ?: meta.tmdbId?.toString() ?: return null
        val found = Http.metadata("$base/search/remoteid/$remote", h).arr("data").list()
        val id = found.firstNotNullOfOrNull { it.obj("series").long("id") } ?: return null

        val s = Http.metadata("$base/series/$id/extended?meta=translations&short=true", h).obj("data")
        val lang = c.tvdbLanguage
        val overview = s.obj("translations").arr("overviewTranslations").list()
            .firstOrNull { it.str("language") == lang }.str("overview") ?: s.str("overview")
        val facts = listOfNotNull(
            (s.obj("originalNetwork").str("name") ?: s.obj("latestNetwork").str("name"))?.let { "Network $it" },
            s.obj("status").str("name")?.let { if (it == "Continuing") "Airing" else it },
        )
        val background = s.arr("artworks").list().firstOrNull { it.int("type") == 3 }.str("image")

        val episodes = HashMap<Pair<Int, Int>, EpisodeExtra>()
        if (c.tvdbEpisodes) {
            // Pages of 500; two pages cover almost every show.
            for (page in 0..3) {
                val j = Http.metadata("$base/series/$id/episodes/default/$lang?page=$page", h)
                val eps = j.obj("data").arr("episodes").list()
                eps.forEach { e ->
                    val season = e.int("seasonNumber") ?: return@forEach
                    val number = e.int("number") ?: return@forEach
                    episodes[season to number] = EpisodeExtra(
                        title = e.str("name")?.takeIf { it.isNotBlank() },
                        overview = e.str("overview")?.takeIf { it.isNotBlank() },
                        aired = e.str("aired")?.take(10),
                        thumbnail = e.str("image")?.let { img -> if (img.startsWith("http")) img else "https://artworks.thetvdb.com$img" },
                    )
                }
                if (j.obj("links").str("next") == null || eps.isEmpty()) break
            }
        }
        MetaExtra(
            source = "TVDB",
            description = overview?.takeIf { it.isNotBlank() },
            poster = s.str("image"),
            background = background,
            facts = facts,
            episodes = episodes,
        )
    }.getOrNull()
}
