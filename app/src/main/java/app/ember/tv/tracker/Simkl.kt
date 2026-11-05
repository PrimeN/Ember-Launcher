package app.ember.tv.tracker

import android.content.Context
import app.ember.tv.data.ApiKeys
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Simkl covers movies, shows and anime. Sign-in uses Simkl's PIN flow.
 *
 * Response shapes used by this client:
 *  - sync/playback items: { id, progress, movie | show, episode { season, number } } with no "type" field.
 *  - sync/all-items/{type}: { movies: [{ status, movie }], shows: [{ status, show }], anime: [{ status, show }] }.
 *    Anime entries keep their details under "show".
 *  - ids: { simkl, imdb, tmdb, mal, anilist, ... }.
 */
class SimklTracker(context: Context) : Tracker {
    override val id = "simkl"
    override val name = "Simkl"
    override val blurb = "Movies, shows and anime: progress and watchlist."
    override val mode = LinkMode.DEVICE_CODE
    override val scrobbles = true
    override val configured: Boolean get() = clientId.isNotBlank()

    private val base = "https://api.simkl.com/"
    private val clientId get() = ApiKeys.get("SIMKL_CLIENT_ID")
    private val store = TokenStore(context)

    override fun isConnected() = !store.get("simkl.access").isNullOrBlank()
    override fun accountLabel(): String? = if (isConnected()) "Signed in" else null
    override fun disconnect() = store.remove("simkl.access")

    private fun headers(): Map<String, String> {
        val h = mutableMapOf("simkl-api-key" to clientId)
        store.get("simkl.access")?.let { h["Authorization"] = "Bearer $it" }
        return h
    }

    // ---- sign-in ----
    override suspend fun beginLink(): LinkSession {
        val r = Http.get("${base}oauth/pin?client_id=$clientId", headers())
        val code = r.str("user_code") ?: error("No code returned")
        return LinkSession(
            userCode = code,
            url = r.str("verification_url") ?: "https://simkl.com/pin",
            deviceCode = code, // Simkl polls with the user code itself
            intervalSec = r.int("interval") ?: 5,
            expiresInSec = r.int("expires_in") ?: 900,
        )
    }

    override suspend fun pollLink(session: LinkSession): Boolean {
        val r = Http.get("${base}oauth/pin/${session.deviceCode}?client_id=$clientId", headers())
        val token = r.str("access_token") ?: return false
        store.put("simkl.access", token)
        return true
    }

    // ---- reads ----
    override suspend fun continueWatching(): List<TrackedItem> {
        val out = ArrayList<TrackedItem>()
        // Partly watched movies and episodes.
        val playback = Http.get("${base}sync/playback?hide_watched=true", headers()).list()
        for (row in playback) {
            val pct = row.dbl("progress")?.toFloat()?.div(100f)
            val movie = row.obj("movie")
            val key = "simkl:p:${row.long("id")}"
            if (movie != null) {
                parse(key, movie, Kind.MOVIE, pct)?.let { out += it }
            } else {
                val show = row.obj("show") ?: continue
                val ep = row.obj("episode")
                parse(key, show, Kind.SHOW, pct, ep.int("season"), ep.int("number"))?.let { out += it }
            }
        }
        // Anime marked "watching". Simkl tracks anime by episode count, not by playback position.
        for (entry in allItems("anime")) {
            if (entry.str("status") != "watching") continue
            val show = entry.obj("show") ?: continue
            parse("simkl:a:${show.obj("ids").long("simkl")}", show, Kind.ANIME, detail = "Watching")?.let { out += it }
        }
        return out
    }

    override suspend fun watchlist(): List<TrackedItem> {
        val out = ArrayList<TrackedItem>()
        for ((type, kind) in listOf("movies" to Kind.MOVIE, "shows" to Kind.SHOW, "anime" to Kind.ANIME)) {
            for (entry in allItems(type)) {
                if (entry.str("status") != "plantowatch") continue
                val meta = entry.obj(if (type == "movies") "movie" else "show") ?: continue
                parse("simkl:w:${meta.obj("ids").long("simkl")}", meta, kind)?.let { out += it }
            }
        }
        return out
    }

    /** Preserve failures so the hub can retain the last successful offline list. */
    private suspend fun allItems(type: String): List<JsonElement> =
        Http.get("${base}sync/all-items/$type", headers()).arr(type)?.toList()
            ?: emptyList()

    private fun parse(
        key: String,
        meta: JsonObject,
        kind: Kind,
        progress: Float? = null,
        season: Int? = null,
        episode: Int? = null,
        detail: String? = null,
    ): TrackedItem? {
        val title = meta.str("title") ?: return null
        val ids = meta["ids"]
        return TrackedItem(
            key = key,
            title = title,
            year = meta.int("year"),
            kind = kind,
            poster = meta.str("poster")?.let { "https://simkl.in/posters/${it}_m.jpg" },
            progress = progress,
            detail = detail ?: if (season != null && episode != null) "S$season · E$episode" else null,
            tmdbId = ids.long("tmdb"),
            imdbId = ids.str("imdb"),
            season = season,
            episode = episode,
            source = name,
        )
    }

    // ---- writes ----
    private fun listKey(kind: Kind) = when (kind) {
        Kind.MOVIE -> "movies"
        Kind.SHOW -> "shows"
        Kind.ANIME -> "anime"
    }

    private fun idsObject(ref: TitleRef) = buildJsonObject {
        ref.imdbId?.takeIf { it.isNotBlank() }?.let { put("imdb", it) }
        ref.tmdbId?.let { put("tmdb", it) }
    }

    /** Simkl's add-to-list moves a title into a status list; "plantowatch" is the watchlist. */
    override fun supportsWatchlist(ref: TitleRef, add: Boolean) = add && ref.hasIds

    override suspend fun addToWatchlist(ref: TitleRef): Boolean {
        if (!ref.hasIds) return false
        val body = buildJsonObject {
            putJsonArray(listKey(ref.kind)) {
                add(buildJsonObject {
                    put("to", "plantowatch")
                    put("ids", idsObject(ref))
                })
            }
        }
        Http.post(base + "sync/add-to-list", body, headers())
        return true
    }

    /**
     * Not supported. Using sync/history/remove also deletes the
     * title's watch history on Simkl, which is too destructive for a watchlist button.
     * Remove the title on simkl.com instead.
     */
    override suspend fun removeFromWatchlist(ref: TitleRef): Boolean = false

    override suspend fun removePlayback(itemKey: String): Boolean {
        if (!itemKey.startsWith("simkl:p:")) return false
        val playbackId = itemKey.substringAfterLast(':').toLongOrNull() ?: return false
        Http.delete("${base}sync/playback/$playbackId", headers())
        return true
    }

    // ---- scrobble ----
    override suspend fun scrobble(item: TrackedItem, state: ScrobbleState, progress: Float) {
        if (item.tmdbId == null && item.imdbId.isNullOrBlank()) return
        val ids = buildJsonObject {
            item.imdbId?.takeIf { it.isNotBlank() }?.let { put("imdb", it) }
            item.tmdbId?.let { put("tmdb", it) }
        }
        val body = buildJsonObject {
            when (item.kind) {
                Kind.MOVIE -> putJsonObject("movie") { put("ids", ids) }
                Kind.SHOW -> {
                    val s = item.season ?: return@buildJsonObject
                    val e = item.episode ?: return@buildJsonObject
                    putJsonObject("show") { put("ids", ids) }
                    putJsonObject("episode") { put("season", s); put("number", e) }
                }
                Kind.ANIME -> {
                    val e = item.episode ?: return@buildJsonObject
                    putJsonObject("anime") { put("ids", ids) }
                    putJsonObject("episode") { put("number", e) }
                }
            }
            put("progress", (progress * 100f).coerceIn(0f, 100f))
        }
        if (body.size < 2) return
        Http.post(base + "scrobble/${state.path}", body, headers())
    }
}
