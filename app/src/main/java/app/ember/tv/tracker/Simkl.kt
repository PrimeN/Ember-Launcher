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
 *  - sync/all-items: { movies: [{ status, movie }], shows: [{ status, show, seasons }], anime: [{ status, show }] }.
 *    Anime entries keep their details under "show". The library is kept on the TV (#78).
 *  - ids: { simkl, imdb, tmdb, mal, anilist, ... }.
 */
class SimklTracker(context: Context) : Tracker {
    override val id = "simkl"
    override val name = "Simkl"
    override val blurb = "Movies, shows and anime: progress, history, ratings and watchlist."
    override val mode = LinkMode.DEVICE_CODE
    override val scrobbles = true
    override val configured: Boolean get() = clientId.isNotBlank()

    private val base = "https://api.simkl.com/"
    private val clientId get() = ApiKeys.get("SIMKL_CLIENT_ID")
    private val store = TokenStore(context)
    // A new file name: the old copy was saved without the episodes of completed shows.
    private val library = SimklLibrary(java.io.File(context.filesDir, "simkl_library_v2.json"), { get(it) })

    override fun isConnected() = !store.get("simkl.access").isNullOrBlank()
    override fun accountLabel(): String? = if (isConnected()) store.get("simkl.account") ?: "Signed in" else null
    override fun disconnect() {
        store.remove("simkl.access", "simkl.account")
        library.clear()
    }

    /** Simkl requires client_id, app-name and app-version on every call, and a User-Agent (#78). */
    internal fun url(path: String) = simklUrl(base, path, clientId)

    private fun headers(): Map<String, String> {
        val h = mutableMapOf("simkl-api-key" to clientId, "User-Agent" to USER_AGENT)
        store.get("simkl.access")?.let { h["Authorization"] = "Bearer $it" }
        return h
    }

    /** A rejected token signs you out, so Settings says "Not connected" instead of failing quietly. */
    private suspend fun <T> authed(call: suspend () -> T): T = try { call() } catch (e: HttpException) {
        if (e.code == 401 && isConnected()) disconnect()
        throw e
    }
    private suspend fun get(path: String) = authed { Http.get(url(path), headers()) }
    private suspend fun post(path: String, body: JsonObject) = authed { Http.post(url(path), body, headers()) }

    // ---- sign-in ----
    override suspend fun beginLink(): LinkSession {
        val r = Http.get(url("oauth/pin"), headers())
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
        val r = Http.get(url("oauth/pin/${session.deviceCode}"), headers())
        val token = r.str("access_token") ?: return false
        library.clear()
        store.put("simkl.access", token)
        return true
    }

    override suspend fun loadAccountName() {
        val name = runCatching { get("users/settings").obj("user").str("name") }.getOrNull()?.trim()
        if (!name.isNullOrEmpty() && isConnected()) store.put("simkl.account", name)
    }
    override fun needsAccountName() = isConnected() && store.get("simkl.account") == null

    // ---- reads ----
    override suspend fun continueWatching(): List<TrackedItem> {
        val out = ArrayList<TrackedItem>()
        // Partly watched movies and episodes.
        val playback = get("sync/playback?hide_watched=true").list()
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
        for (entry in library.entries().arr("anime").list()) {
            if (entry.str("status") != "watching") continue
            val show = entry.obj("show") ?: continue
            parse("simkl:a:${show.obj("ids").long("simkl")}", show, Kind.ANIME, detail = "Watching")?.let { out += it }
        }
        return out
    }

    override suspend fun watchlist(): List<TrackedItem> {
        val out = ArrayList<TrackedItem>()
        val entries = library.entries()
        for ((type, kind) in listOf("movies" to Kind.MOVIE, "shows" to Kind.SHOW, "anime" to Kind.ANIME)) {
            for (entry in entries.arr(type).list()) {
                if (entry.str("status") != "plantowatch") continue
                val meta = entry.obj(if (type == "movies") "movie" else "show") ?: continue
                parse("simkl:w:${meta.obj("ids").long("simkl")}", meta, kind)?.let { out += it }
            }
        }
        return out
    }

    /** Completed movies and shows, and each watched episode, from the library on the TV (#83). */
    override suspend fun watched(): Set<String> = simklWatchedKeys(library.entries())

    // ---- two-way sync (#90) ----
    override val syncs = true
    /** Simkl can only take a title off the watchlist by deleting its history, so sync never does. */
    override val removesFromWatchlist = false

    override suspend fun syncWatched(): List<SyncItem> = simklWatchedItems(library.entries())

    override suspend fun syncRatings(): Map<SyncItem, Int> {
        val movies = get("sync/ratings/movies")
        val shows = get("sync/ratings/shows")
        return ratingRows(movies.rows("movies"), true) { it.int("user_rating") ?: it.int("rating") } +
            ratingRows(shows.rows("shows"), false) { it.int("user_rating") ?: it.int("rating") }
    }

    override suspend fun applySync(category: SyncCategory, add: List<SyncItem>, remove: List<SyncItem>) {
        when (category) {
            SyncCategory.WATCHED -> {
                add.batches().forEach { post("sync/history", syncBatch(it)) }
                remove.batches().forEach { post("sync/history/remove", syncBatch(it)) }
            }
            SyncCategory.WATCHLIST -> {
                // Adding to plan-to-watch would move a title out of Watching or Completed: only titles
                // that aren't in your Simkl library at all are added.
                val entries = library.entries()
                val known = SyncIndex(listOf("movies", "shows", "anime").flatMap { type ->
                    entries.arr(type).list().mapNotNull { e ->
                        val ids = e.obj(if (type == "movies") "movie" else "show").obj("ids")
                        SyncItem.of(type == "movies", ids.str("imdb"), ids.long("tmdb"))
                    }
                })
                add.filter { it !in known }.batches().forEach { batch ->
                    post("sync/add-to-list", syncBatch(batch, extra = { mapOf("to" to kotlinx.serialization.json.JsonPrimitive("plantowatch")) }))
                }
            }
            SyncCategory.RATINGS -> Unit
        }
    }

    override suspend fun applyRatings(set: Map<SyncItem, Int>, clear: List<SyncItem>) {
        set.keys.toList().batches().forEach { chunk ->
            post("sync/ratings", syncBatch(chunk, extra = { mapOf("rating" to kotlinx.serialization.json.JsonPrimitive(set.getValue(it))) }))
        }
        clear.batches().forEach { post("sync/ratings/remove", syncBatch(it)) }
    }

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
        post("sync/add-to-list", body)
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
        authed { Http.delete(url("sync/playback/$playbackId"), headers()) }
        return true
    }

    /** Explicit watched / unwatched marks go to history, not through a scrobble (#79). */
    override fun supportsHistory(ref: TitleRef) = ref.hasIds
    override suspend fun markWatched(ref: TitleRef, season: Int?, episode: Int?, watched: Boolean): Boolean {
        if (!ref.hasIds) return false
        post(if (watched) "sync/history" else "sync/history/remove", historyBody(ref, season, episode, idsObject(ref)))
        return true
    }

    override val rates = true
    override suspend fun rate(ref: TitleRef, rating: Int?): Boolean {
        if (!ref.hasIds) return false
        val key = if (ref.kind == Kind.MOVIE) "movies" else "shows"
        val body = buildJsonObject {
            putJsonArray(key) { add(buildJsonObject { put("ids", idsObject(ref)); rating?.let { put("rating", it.coerceIn(1, 10)) } }) }
        }
        post(if (rating != null) "sync/ratings" else "sync/ratings/remove", body)
        return true
    }
    override suspend fun ratingOf(ref: TitleRef): Int? {
        val entries = library.entries()
        val types = if (ref.kind == Kind.MOVIE) listOf("movies") else listOf("shows", "anime")
        return types.flatMap { entries.arr(it).list() }.firstOrNull { e ->
            val ids = e.obj(if (ref.kind == Kind.MOVIE) "movie" else "show").obj("ids")
            (ref.imdbId != null && ids.str("imdb") == ref.imdbId) || (ref.tmdbId != null && ids.long("tmdb") == ref.tmdbId)
        }?.int("user_rating")
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
        post("scrobble/${state.path}", body)
    }

    companion object {
        val USER_AGENT: String get() = "EmberTV/" + app.ember.tv.BuildConfig.VERSION_NAME
    }
}

/** Adds Simkl's required client_id, app-name and app-version to a path (#78). */
internal fun simklUrl(base: String, path: String, clientId: String, version: String = app.ember.tv.BuildConfig.VERSION_NAME): String {
    fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    return base + path + (if ('?' in path) "&" else "?") + "client_id=${enc(clientId)}&app-name=ember-tv&app-version=${enc(version)}"
}

/**
 * History body shared by Trakt and Simkl: a movie, one episode, or a whole show (#79).
 * Anime goes under "shows" too; both services accept it there.
 */
internal fun historyBody(ref: TitleRef, season: Int?, episode: Int?, ids: JsonObject): JsonObject = buildJsonObject {
    if (ref.kind == Kind.MOVIE) putJsonArray("movies") { add(buildJsonObject { put("ids", ids) }) }
    else putJsonArray("shows") {
        add(buildJsonObject {
            put("ids", ids)
            if (season != null && episode != null) putJsonArray("seasons") {
                add(buildJsonObject {
                    put("number", season)
                    putJsonArray("episodes") { add(buildJsonObject { put("number", episode) }) }
                })
            }
        })
    }
}

/** Watched keys (see watchedKeys) from a Simkl library (#83). */
internal fun simklWatchedKeys(library: JsonElement): Set<String> = simklWatchedItems(library).flatMapTo(HashSet()) { it.keys }

/** Completed movies and shows and each watched episode; anime is marked so sync leaves it out (#90). */
internal fun simklWatchedItems(library: JsonElement): List<SyncItem> {
    val out = ArrayList<SyncItem>()
    for (e in library.arr("movies").list()) {
        if (e.str("status") != "completed") continue
        val ids = e.obj("movie").obj("ids")
        SyncItem.of(true, ids.str("imdb"), ids.long("tmdb"))?.let { out += it }
    }
    for (type in listOf("shows", "anime")) for (e in library.arr(type).list()) {
        val ids = e.obj("show").obj("ids")
        val imdb = ids.str("imdb"); val tmdb = ids.long("tmdb")
        val anime = type == "anime"
        if (e.str("status") == "completed") SyncItem.of(false, imdb, tmdb, anime = anime)?.let { out += it }
        for (s in e.arr("seasons").list()) {
            val season = s.int("number") ?: continue
            for (ep in s.arr("episodes").list()) ep.int("number")?.let { n -> SyncItem.of(false, imdb, tmdb, season, n, anime)?.let { out += it } }
        }
    }
    return out
}
