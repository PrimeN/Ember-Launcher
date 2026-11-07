package app.ember.tv.tracker

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.content.Context
import app.ember.tv.data.ApiKeys
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Base for services that share Trakt's shape: device-code sign-in, bearer tokens,
 * and scrobble/start|pause|stop. Trakt and WeTrakr differ only in a few names.
 */
abstract class TraktLikeTracker(
    context: Context,
    final override val id: String,
    final override val name: String,
    final override val blurb: String,
    protected val base: String,
    private val clientIdKey: String,
    private val clientSecretKey: String,
    private val apiKeyHeader: String,
    private val versionHeader: Pair<String, String>,
    private val tokenCodeField: String,
) : Tracker {
    protected val store = TokenStore(context)

    final override val mode = LinkMode.DEVICE_CODE
    final override val scrobbles = true
    // Read on each use, so a key entered in Settings works without a restart.
    private val clientId: String get() = ApiKeys.get(clientIdKey)
    private val clientSecret: String get() = ApiKeys.get(clientSecretKey)

    protected open val requiresClientSecret = false
    override val configured: Boolean
        get() = clientId.isNotBlank() && (!requiresClientSecret || clientSecret.isNotBlank())

    private fun access() = store.get("$id.access")
    override fun isConnected() = !access().isNullOrBlank()
    override fun accountLabel(): String? = if (isConnected()) store.get("$id.account") ?: "Signed in" else null
    override fun disconnect() {
        store.remove("$id.access", "$id.refresh", "$id.account")
        signedOut()
    }
    /** Clears what was kept on the TV for this account. */
    protected open fun signedOut() {}

    protected fun headers(auth: Boolean = true): Map<String, String> {
        val h = mutableMapOf(apiKeyHeader to clientId, versionHeader.first to versionHeader.second)
        if (auth) access()?.let { h["Authorization"] = "Bearer $it" }
        return h
    }

    // ---- sign-in ----
    override suspend fun beginLink(): LinkSession {
        check(configured) { "$name app registration is missing from this build." }
        val r = Http.post(base + "oauth/device/code", buildJsonObject { put("client_id", clientId) }, headers(false))
        return LinkSession(
            userCode = r.str("user_code") ?: error("No code returned"),
            url = r.str("verification_url") ?: base,
            deviceCode = r.str("device_code") ?: error("No device code returned"),
            intervalSec = r.int("interval") ?: 5,
            expiresInSec = r.int("expires_in") ?: 600,
        )
    }

    override suspend fun pollLink(session: LinkSession): Boolean {
        val r = try {
            Http.post(
                base + "oauth/device/token",
                buildJsonObject {
                    put(tokenCodeField, session.deviceCode)
                    put("client_id", clientId)
                    put("client_secret", clientSecret)
                },
                headers(false),
            )
        } catch (e: HttpException) {
            // Pending approval is normal; let the shared poller back off on 429.
            if (e.code == 400) return false
            throw e
        }
        return saveTokens(r)
    }

    private fun saveTokens(r: JsonElement): Boolean {
        val token = r.str("access_token") ?: return false
        if (access() == null) signedOut() // a new sign-in starts from a clean slate
        store.putAll(buildMap {
            put("$id.access",token)
            r.str("refresh_token")?.let {put("$id.refresh",it)}
        })
        return true
    }

    /** WeTrakr uses oauth/token/refresh; Trakt overrides this. */
    protected open val refreshPath = "oauth/token/refresh"

    /** Trakt's refresh also requires the redirect_uri used for device sign-in. */
    protected open val refreshRedirectUri: String? = null

    /**
     * Gets a new access token. If the service refuses the refresh token, signs out,
     * so Settings shows "Not connected" instead of a silent, empty Continue watching.
     */
    private suspend fun refresh(failedAccess: String?): Boolean = TokenRefreshCoordinator.refresh(
        id, failedAccess, ::access,
    ) { renewTokens(failedAccess) }

    private suspend fun renewTokens(failedAccess: String?): Boolean {
        val refresh = store.get("$id.refresh") ?: run {
            disconnect()
            return false
        }
        fun unchanged() = access() == failedAccess && store.get("$id.refresh") == refresh
        return try {
            val r = Http.post(
                base + refreshPath,
                buildJsonObject {
                    put("refresh_token", refresh)
                    put("client_id", clientId)
                    put("client_secret", clientSecret)
                    put("grant_type", "refresh_token")
                    refreshRedirectUri?.let { put("redirect_uri", it) }
                },
                headers(false),
            )
            // A sign-out or a new sign-in during the request must not be overwritten.
            if (unchanged()) saveTokens(r) else isConnected()
        } catch (e: HttpException) {
            if (e.code in 400..403 && unchanged()) disconnect()
            false
        } catch (e: java.io.IOException) {
            false // offline; keep the sign-in and try again later
        }
    }

    /** Capture the token actually sent, so delayed 401s reuse an already-renewed token. */
    private suspend fun authorized(request: suspend (Map<String, String>) -> JsonElement): JsonElement {
        val sent = headers()
        val failedAccess = sent["Authorization"]?.removePrefix("Bearer ")
        return try {
            request(sent)
        } catch (e: HttpException) {
            if (e.code != 401 || !refresh(failedAccess)) throw e
            request(headers())
        }
    }

    protected suspend fun authedGet(path: String): JsonElement = authorized { Http.get(base + path, it) }

    protected suspend fun authedPost(path: String, body: JsonObject): JsonElement =
        authorized { Http.post(base + path, body, it) }

    protected suspend fun authedDelete(path: String): JsonElement = authorized { Http.delete(base + path, it) }

    // ---- watchlist writes ----
    protected open val watchlistAddPath = "sync/watchlist"
    protected open val watchlistRemovePath = "sync/watchlist/remove"

    /** WeTrakr wants a status on every item. Trakt does not. */
    protected open val watchlistStatus: String? = null
    protected open val supportsPlaybackRemoval = true

    private fun listBody(ref: TitleRef): JsonObject = buildJsonObject {
        val listKey = if (ref.kind == Kind.MOVIE) "movies" else "shows"
        putJsonArray(listKey) {
            add(
                buildJsonObject {
                    putJsonObject("ids") {
                        ref.imdbId?.takeIf { it.isNotBlank() }?.let { put("imdb", it) }
                        ref.tmdbId?.let { put("tmdb", it) }
                    }
                    watchlistStatus?.let { put("status", it) }
                }
            )
        }
    }

    override fun supportsWatchlist(ref: TitleRef, add: Boolean) = ref.hasIds

    override suspend fun addToWatchlist(ref: TitleRef): Boolean {
        if (!ref.hasIds) return false
        limited { authedPost(watchlistAddPath, listBody(ref)) }
        return true
    }

    override suspend fun removeFromWatchlist(ref: TitleRef): Boolean {
        if (!ref.hasIds) return false
        limited { authedPost(watchlistRemovePath, listBody(ref)) }
        return true
    }

    /** HTTP 420 is Trakt's account limit (e.g. a free account's watchlist size): retrying won't help (#82). */
    protected suspend fun <T> limited(call: suspend () -> T): T = try { call() } catch (e: HttpException) {
        if (e.code == 420) throw TrackerRefusal(id, "$name account limit reached, so $name was not changed. Free accounts have smaller limits.")
        throw e
    }

    protected fun ids(ref: TitleRef) = buildJsonObject {
        ref.imdbId?.takeIf { it.isNotBlank() }?.let { put("imdb", it) }
        ref.tmdbId?.let { put("tmdb", it) }
    }

    override suspend fun removePlayback(itemKey: String): Boolean {
        if (!supportsPlaybackRemoval || !itemKey.startsWith("$id:p:")) return false
        val playbackId = itemKey.substringAfterLast(':').toLongOrNull() ?: return false
        authedDelete("sync/playback/$playbackId")
        return true
    }

    // ---- scrobble ----
    override suspend fun scrobble(item: TrackedItem, state: ScrobbleState, progress: Float) {
        // Addon titles usually carry only an IMDb id, so accept either id.
        if (item.tmdbId == null && item.imdbId.isNullOrBlank()) return
        val pct = (progress * 100f).coerceIn(0f, 100f)
        val ids = buildJsonObject {
            item.imdbId?.takeIf { it.isNotBlank() }?.let { put("imdb", it) }
            item.tmdbId?.let { put("tmdb", it) }
        }
        val body = buildJsonObject {
            if (item.kind == Kind.MOVIE) {
                putJsonObject("movie") { put("ids", ids) }
            } else {
                val s = item.season ?: return@buildJsonObject
                val e = item.episode ?: return@buildJsonObject
                putJsonObject("show") { put("ids", ids) }
                putJsonObject("episode") { put("season", s); put("number", e) }
            }
            put("progress", pct)
        }
        if (body.size < 2) return
        authedPost("scrobble/${state.path}", body)
    }

    /** Reads {title, year, ids:{tmdb, imdb}} blocks used by Trakt-style responses. */
    protected fun item(
        key: String,
        meta: JsonElement?,
        kind: Kind,
        progress: Float? = null,
        detail: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ): TrackedItem? {
        val title = meta.str("title") ?: return null
        val ids = (meta as? JsonObject)?.get("ids")
        return TrackedItem(
            key = key,
            title = title,
            year = meta.int("year"),
            kind = kind,
            progress = progress,
            detail = detail,
            tmdbId = ids.long("tmdb"),
            imdbId = ids.str("imdb"),
            season = season,
            episode = episode,
            source = name,
            traktId = ids.long("trakt"),
        )
    }
}

class TraktTracker(context: Context) : TraktLikeTracker(
    context = context,
    id = "trakt",
    name = "Trakt",
    blurb = "Movies and shows: progress, history and watchlist.",
    base = "https://api.trakt.tv/",
    clientIdKey = "TRAKT_CLIENT_ID",
    clientSecretKey = "TRAKT_CLIENT_SECRET",
    apiKeyHeader = "trakt-api-key",
    versionHeader = "trakt-api-version" to "2",
    tokenCodeField = "code",
) {
    override val requiresClientSecret = true
    // Trakt refreshes through the same endpoint as sign-in.
    override val refreshPath = "oauth/token"
    override val refreshRedirectUri: String? = "urn:ietf:wg:oauth:2.0:oob"

    private val watchedFile = java.io.File(context.filesDir, "trakt_watched.txt")
    @Volatile private var watchedCache: Pair<String, Set<String>>? = null
    private val watchedLock = Mutex()
    private val ratings = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<JsonElement>>>()

    override fun signedOut() {
        watchedFile.delete()
        watchedCache = null
        ratings.clear()
    }

    // ---- account name (#85) ----
    override suspend fun loadAccountName() {
        val user = runCatching { authedGet("users/settings").obj("user") }.getOrNull() ?: return
        val label = user.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: user.str("username")?.let { "@$it" } ?: return
        if (isConnected()) store.put("$id.account", label)
    }
    override fun needsAccountName() = isConnected() && store.get("$id.account") == null

    // ---- watched marks and history (#79, #83) ----
    override fun supportsHistory(ref: TitleRef) = ref.hasIds
    override suspend fun markWatched(ref: TitleRef, season: Int?, episode: Int?, watched: Boolean): Boolean {
        if (!ref.hasIds) return false
        limited { authedPost(if (watched) "sync/history" else "sync/history/remove", historyBody(ref, season, episode, ids(ref))) }
        return true
    }

    /**
     * Every watched movie and episode. Trakt's last_activities says when history last changed,
     * so the full lists are only downloaded again after something was watched or removed.
     */
    override suspend fun watched(): Set<String> = watchedLock.withLock {
        val activity = authedGet("sync/last_activities")
        val stamp = listOf(activity.obj("movies").str("watched_at"), activity.obj("episodes").str("watched_at")).joinToString("|")
        val current = watchedCache ?: readWatched()?.also { watchedCache = it }
        if (current != null && current.first == stamp) return@withLock current.second
        val keys = coroutineScope {
            val movies = async { authedGet("sync/watched/movies") }
            val shows = async { authedGet("sync/watched/shows") }
            traktWatchedKeys(movies.await(), shows.await())
        }
        watchedCache = stamp to keys
        writeWatched(stamp, keys)
        keys
    }

    private fun readWatched(): Pair<String, Set<String>>? = runCatching {
        if (!watchedFile.exists()) return null
        val lines = watchedFile.readLines()
        val stamp = lines.firstOrNull()?.removePrefix("#")?.takeIf { lines.first().startsWith("#") } ?: return null
        stamp to lines.drop(1).filterTo(HashSet()) { it.isNotBlank() }
    }.getOrNull()

    private fun writeWatched(stamp: String, keys: Set<String>) {
        runCatching {
            val tmp = java.io.File(watchedFile.path + ".tmp")
            tmp.writeText("#" + stamp + "\n" + keys.joinToString("\n"))
            if (!tmp.renameTo(watchedFile)) { watchedFile.delete(); tmp.renameTo(watchedFile) }
        }
    }

    // ---- ratings (#84) ----
    override val rates = true
    override suspend fun rate(ref: TitleRef, rating: Int?): Boolean {
        if (!ref.hasIds) return false
        val key = if (ref.kind == Kind.MOVIE) "movies" else "shows"
        val body = buildJsonObject {
            putJsonArray(key) { add(buildJsonObject { put("ids", ids(ref)); rating?.let { put("rating", it.coerceIn(1, 10)) } }) }
        }
        limited { authedPost(if (rating != null) "sync/ratings" else "sync/ratings/remove", body) }
        ratings.remove(key)
        return true
    }

    override suspend fun ratingOf(ref: TitleRef): Int? {
        if (!ref.hasIds) return null
        val key = if (ref.kind == Kind.MOVIE) "movies" else "shows"
        val now = System.currentTimeMillis()
        val rows = ratings[key]?.takeIf { now - it.first < 10 * 60_000L }?.second
            ?: authedGet("sync/ratings/$key").list().also { ratings[key] = now to it }
        return rows.firstOrNull { sameTitle(it.obj(if (ref.kind == Kind.MOVIE) "movie" else "show"), ref) }?.int("rating")
    }

    override suspend fun continueWatching(): List<TrackedItem> =
        authedGet("sync/playback").list().mapNotNull { row ->
            val pct = row.dbl("progress")?.toFloat()?.div(100f)
            val rowId = row.long("id") ?: return@mapNotNull null
            when (row.str("type")) {
                "movie" -> item("trakt:p:$rowId", row.obj("movie"), Kind.MOVIE, pct, "${(pct ?: 0f).times(100).toInt()}% watched")
                "episode" -> {
                    val ep = row.obj("episode")
                    val s = ep.int("season")
                    val n = ep.int("number")
                    item("trakt:p:$rowId", row.obj("show"), Kind.SHOW, pct, "S$s · E$n", s, n)
                }
                else -> null
            }
        }

    override suspend fun watchlist(): List<TrackedItem> =
        authedGet("sync/watchlist").list().mapNotNull { row ->
            val type = row.str("type")
            val kind = if (type == "movie") Kind.MOVIE else Kind.SHOW
            val meta = row.obj(if (type == "movie") "movie" else "show")
            item("trakt:w:${row.long("id")}", meta, kind)
        }

    // ---- Personalized Home rows ----

    /** "Recommended for you": Trakt's personal picks, movies and shows mixed. */
    suspend fun recommendations(limit: Int = 20): List<TrackedItem> {
        val movies = runCatching { authedGet("recommendations/movies?limit=$limit&ignore_collected=true") }.getOrNull().list()
            .mapNotNull { item("trakt:r:m:${it.obj("ids").long("trakt")}", it, Kind.MOVIE) }
        val shows = runCatching { authedGet("recommendations/shows?limit=$limit&ignore_collected=true") }.getOrNull().list()
            .mapNotNull { item("trakt:r:s:${it.obj("ids").long("trakt")}", it, Kind.SHOW) }
        return interleave(movies, shows)
    }

    /** The most recent movie or show in your history, for "Because you watched". */
    suspend fun lastWatched(): TrackedItem? {
        val row = authedGet("sync/history?limit=1").list().firstOrNull() ?: return null
        return when (row.str("type")) {
            "movie" -> item("trakt:h:${row.long("id")}", row.obj("movie"), Kind.MOVIE)
            "episode" -> item("trakt:h:${row.long("id")}", row.obj("show"), Kind.SHOW)
            else -> null
        }
    }

    /**
     * "Up next": the next unwatched episode of the shows you watched most recently, oldest
     * unfinished first within each show. Finished shows and shows with nothing new are left out.
     */
    suspend fun upNext(shows: Int = 12): List<TrackedItem> = coroutineScope {
        val watched = authedGet("sync/watched/shows?extended=noseasons").list()
            .sortedByDescending { it.str("last_watched_at").orEmpty() }
            .take(shows)
        watched.map { row ->
            async {
                val show = row.obj("show")
                val id = show.obj("ids").long("trakt") ?: return@async null
                val progress = runCatching { authedGet("shows/$id/progress/watched?hidden=false&specials=false") }.getOrNull()
                val next = progress.obj("next_episode") ?: return@async null
                val s = next.int("season") ?: return@async null
                val n = next.int("number") ?: return@async null
                val epTitle = next.str("title")
                item("trakt:next:$id:$s:$n", show, Kind.SHOW, null, "Next: S$s · E$n" + (epTitle?.let { " · $it" } ?: ""), s, n)
            }
        }.mapNotNull { it.await() }
    }

    /** New episodes of the shows you follow, from today for a week: "Tue · S2 E5". */
    suspend fun airingThisWeek(days: Int = 7): List<TrackedItem> {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val day = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
        val clock = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        val todayStart = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
        return authedGet("calendars/my/shows/$today/$days").list().mapNotNull { row ->
            val ep = row.obj("episode")
            val s = ep.int("season") ?: return@mapNotNull null
            val n = ep.int("number") ?: return@mapNotNull null
            val at = row.str("first_aired")?.take(19)?.let { runCatching { iso.parse(it) }.getOrNull() }
            val whenText = at?.let { d ->
                when {
                    d.time < todayStart + 86_400_000L -> "Today " + clock.format(d)
                    d.time < todayStart + 2 * 86_400_000L -> "Tomorrow"
                    else -> day.format(d)
                }
            }
            val show = row.obj("show")
            item("trakt:cal:${show.obj("ids").long("trakt")}:$s:$n", show, Kind.SHOW, null, listOfNotNull(whenText, "S$s · E$n").joinToString(" · "), s, n)
        }.distinctBy { it.traktId }
    }

    /** Recently watched movies and shows, newest first, one entry per title. */
    suspend fun history(limit: Int = 40): List<TrackedItem> =
        authedGet("sync/history?limit=$limit").list().mapNotNull { row ->
            when (row.str("type")) {
                "movie" -> item("trakt:h:${row.long("id")}", row.obj("movie"), Kind.MOVIE)
                "episode" -> item("trakt:h:${row.long("id")}", row.obj("show"), Kind.SHOW)
                else -> null
            }
        }.distinctBy { it.traktId ?: it.title }

    /** Titles Trakt lists as related to [of]. */
    suspend fun related(of: TrackedItem, limit: Int = 20): List<TrackedItem> {
        val id = of.traktId ?: return emptyList()
        val isMovie = of.kind == Kind.MOVIE
        val path = if (isMovie) "movies/$id/related?limit=$limit" else "shows/$id/related?limit=$limit"
        return authedGet(path).list().mapNotNull {
            item("trakt:rel:${it.obj("ids").long("trakt")}", it, if (isMovie) Kind.MOVIE else Kind.SHOW)
        }
    }

    /** Your own Trakt lists, each as (list name, items). */
    suspend fun personalLists(maxLists: Int = 3, limit: Int = 30): List<Pair<String, List<TrackedItem>>> =
        authedGet("users/me/lists").list().take(maxLists).mapNotNull { l ->
            val name = l.str("name") ?: return@mapNotNull null
            val listId = l.obj("ids").long("trakt") ?: return@mapNotNull null
            val items = runCatching { authedGet("users/me/lists/$listId/items?limit=$limit") }.getOrNull().list()
                .mapNotNull { row ->
                    when (row.str("type")) {
                        "movie" -> item("trakt:l:${row.long("id")}", row.obj("movie"), Kind.MOVIE)
                        "show" -> item("trakt:l:${row.long("id")}", row.obj("show"), Kind.SHOW)
                        else -> null
                    }
                }
            if (items.isEmpty()) null else name to items
        }

    private fun <T> interleave(a: List<T>, b: List<T>): List<T> {
        val out = ArrayList<T>(a.size + b.size)
        for (i in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(i)?.let { out += it }
            b.getOrNull(i)?.let { out += it }
        }
        return out
    }
}

/**
 * WeTrakr endpoint, header and status mapping.
 * Request and response field names are best guesses and need a real-account test.
 */
class WeTrakrTracker(context: Context) : TraktLikeTracker(
    context = context,
    id = "wetrakr",
    name = "WeTrakr",
    blurb = "Movies, shows and anime: progress and watchlist.",
    base = "https://api.wetrakr.com/",
    clientIdKey = "WETRAKR_CLIENT_ID",
    clientSecretKey = "WETRAKR_CLIENT_SECRET",
    apiKeyHeader = "wetrakr-api-key",
    versionHeader = "wetrakr-api-version" to "1",
    tokenCodeField = "device_code",
) {
    // WeTrakr tracking-list paths and watchlist status.
    override val watchlistAddPath = "sync/tracking"
    override val watchlistRemovePath = "sync/tracking/remove"
    override val watchlistStatus: String? = "watchlist"

    // The old client only removed tracking entries; there is no known playback delete.
    override val supportsPlaybackRemoval = false

    override suspend fun continueWatching(): List<TrackedItem> =
        authedGet("sync/tracking/in_progress/all?limit=30").list().mapNotNull { row ->
            val pct = row.obj("playback").int("progress_percent")?.let { it / 100f }
            val show = row.obj("show")
            val isMovie = row.str("type") == "movie"
            val ids = if (show != null && !isMovie) show.get("ids") else (row as? JsonObject)?.get("ids")
            val s = row.int("season_number")
            val n = row.int("number")
            TrackedItem(
                key = "wetrakr:p:${row.long("id")}",
                title = show.str("title") ?: "",
                kind = if (isMovie) Kind.MOVIE else Kind.SHOW,
                progress = pct,
                detail = if (s != null && n != null) "S$s · E$n" else null,
                tmdbId = ids.long("tmdb"),
                imdbId = ids.str("imdb"),
                season = s,
                episode = n,
                source = name,
            ).takeIf { it.title.isNotBlank() || it.tmdbId != null }
        }

    override suspend fun watchlist(): List<TrackedItem> =
        authedGet("sync/tracking/watchlist/all?limit=40").list().mapNotNull { row ->
            val show = row.obj("show")
            val isMovie = row.str("type") == "movie"
            val ids = if (show != null && !isMovie) show.get("ids") else (row as? JsonObject)?.get("ids")
            TrackedItem(
                key = "wetrakr:w:${row.long("id")}",
                title = show.str("title") ?: "",
                kind = if (isMovie) Kind.MOVIE else Kind.SHOW,
                tmdbId = ids.long("tmdb"),
                imdbId = ids.str("imdb"),
                source = name,
            ).takeIf { it.title.isNotBlank() || it.tmdbId != null }
        }
}

/** True when a {ids:{imdb, tmdb}} block is the same title as [ref]. */
internal fun sameTitle(meta: JsonElement?, ref: TitleRef): Boolean {
    val ids = meta.obj("ids")
    return (!ref.imdbId.isNullOrBlank() && ids.str("imdb") == ref.imdbId) || (ref.tmdbId != null && ids.long("tmdb") == ref.tmdbId)
}

/** Watched keys from Trakt's sync/watched/movies and sync/watched/shows (#83). */
internal fun traktWatchedKeys(movies: JsonElement?, shows: JsonElement?): Set<String> {
    val out = HashSet<String>()
    for (row in movies.list()) {
        val ids = row.obj("movie").obj("ids")
        out += watchedKeys(true, ids.str("imdb"), ids.long("tmdb"), null, null)
    }
    for (row in shows.list()) {
        val ids = row.obj("show").obj("ids")
        val imdb = ids.str("imdb"); val tmdb = ids.long("tmdb")
        for (s in row.arr("seasons").list()) {
            val season = s.int("number") ?: continue
            for (e in s.arr("episodes").list()) e.int("number")?.let { out += watchedKeys(false, imdb, tmdb, season, it) }
        }
    }
    return out
}
