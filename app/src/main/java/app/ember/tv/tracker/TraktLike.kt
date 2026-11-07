package app.ember.tv.tracker

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.content.Context
import app.ember.tv.data.ApiKeys
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    /** A TV app is a public client: WeTrakr wants no client_secret at all (a wrong one burns the code). */
    protected open val publicClient = false
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
                    // Clients differ on the field name; sending both costs nothing.
                    if (tokenCodeField != "code") put("code", session.deviceCode)
                    put("client_id", clientId)
                    if (!publicClient) put("client_secret", clientSecret)
                },
                headers(false),
            )
        } catch (e: HttpException) {
            // Pending approval is normal; let the shared poller back off on 429.
            if (e.code == 400) return false
            // Trakt and WeTrakr share these answers.
            when (e.code) {
                404, 409, 410 -> error("The code expired or was already used. Try again.")
                418 -> error("Sign-in was declined on $name.")
            }
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
            // WeTrakr names the account in the token answer (#85).
            r.str("username")?.trim()?.takeIf { it.isNotEmpty() }?.let { put("$id.account", "@$it") }
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
                    if (!publicClient) {
                        put("client_id", clientId)
                        put("client_secret", clientSecret)
                        put("grant_type", "refresh_token")
                    }
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

    protected suspend fun authedDelete(path: String, body: JsonObject? = null): JsonElement = authorized { Http.delete(base + path, it, body) }

    /** GET with the response headers, for X-Pagination-* paging. */
    protected suspend fun authedGetPaged(path: String): Pair<JsonElement, okhttp3.Headers> {
        var headers: okhttp3.Headers? = null
        val body = authorized { sent -> Http.getPaged(base + path, sent).also { headers = it.second }.first }
        return body to (headers ?: okhttp3.Headers.headersOf())
    }

    /** Trakt allows about one write a second; batches wait between requests. */
    protected suspend fun pace() = kotlinx.coroutines.delay(1_100)

    /** The saved tokens, for a sign-out that revokes them on the service. */
    protected fun savedTokens(): Pair<String?, String?> = access() to store.get("$id.refresh")

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

    private val watchedFile = StampedKeysFile(java.io.File(context.filesDir, "trakt_watched_items.txt"))
    @Volatile private var watchedCache: Pair<String, List<SyncItem>>? = null
    private val watchedLock = Mutex()
    private val ratings = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<JsonElement>>>()

    override fun signedOut() {
        watchedFile.clear()
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
    override suspend fun watched(): Set<String> = syncWatched().flatMapTo(HashSet()) { it.keys }

    override suspend fun syncWatched(): List<SyncItem> = watchedLock.withLock {
        val activity = authedGet("sync/last_activities")
        val stamp = listOf(activity.obj("movies").str("watched_at"), activity.obj("episodes").str("watched_at")).joinToString("|")
        val current = watchedCache ?: watchedFile.read()?.let { (s, lines) -> s to lines.mapNotNull(SyncItem::decode) }?.also { watchedCache = it }
        if (current != null && current.first == stamp) return@withLock current.second
        val items = coroutineScope {
            val movies = async { authedGet("sync/watched/movies") }
            val shows = async { authedGet("sync/watched/shows") }
            traktWatchedItems(movies.await(), shows.await())
        }
        watchedCache = stamp to items
        watchedFile.write(stamp, items.mapTo(LinkedHashSet()) { it.encode() })
        items
    }

    // ---- two-way sync (#90) ----
    override val syncs = true

    override suspend fun syncRatings(): Map<SyncItem, Int> = coroutineScope {
        val movies = async { authedGet("sync/ratings/movies").list() }
        val shows = async { authedGet("sync/ratings/shows").list() }
        ratingRows(movies.await(), true) { it.int("rating") } + ratingRows(shows.await(), false) { it.int("rating") }
    }

    override suspend fun applySync(category: SyncCategory, add: List<SyncItem>, remove: List<SyncItem>) {
        val (addPath, removePath) = when (category) {
            SyncCategory.WATCHED -> "sync/history" to "sync/history/remove"
            SyncCategory.WATCHLIST -> "sync/watchlist" to "sync/watchlist/remove"
            SyncCategory.RATINGS -> return
        }
        add.batches().forEach { limited { authedPost(addPath, syncBatch(it)) }; pace() }
        remove.batches().forEach { limited { authedPost(removePath, syncBatch(it)) }; pace() }
    }

    override suspend fun applyRatings(set: Map<SyncItem, Int>, clear: List<SyncItem>) {
        set.keys.toList().batches().forEach { chunk ->
            limited { authedPost("sync/ratings", syncBatch(chunk, extra = { mapOf("rating" to JsonPrimitive(set.getValue(it))) })) }
            pace()
        }
        clear.batches().forEach { limited { authedPost("sync/ratings/remove", syncBatch(it)) }; pace() }
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
 * WeTrakr (#87, #88), following its published API (api.wetrakr.com), its own Kodi add-on and
 * NuvioTV's client: Trakt-style device sign-in as a public client, tracking lists by status
 * (playing, planning, watched), compact pages for history, and Trakt-style scrobbles.
 */
class WeTrakrTracker(context: Context) : TraktLikeTracker(
    context = context,
    id = "wetrakr",
    name = "WeTrakr",
    blurb = "Movies and shows: progress, history, ratings and watchlist.",
    base = "https://api.wetrakr.com/",
    clientIdKey = "WETRAKR_CLIENT_ID",
    clientSecretKey = "WETRAKR_CLIENT_SECRET",
    apiKeyHeader = "wetrakr-api-key",
    versionHeader = "wetrakr-api-version" to "1",
    tokenCodeField = "device_code",
) {
    override val publicClient = true
    override val watchlistAddPath = "sync/tracking"
    override val watchlistRemovePath = "sync/tracking/remove"
    override val watchlistStatus: String? = "planning"

    private val watchedFile = StampedKeysFile(java.io.File(context.filesDir, "wetrakr_watched_items.txt"))
    @Volatile private var watchedCache: Pair<String, List<SyncItem>>? = null
    private val watchedLock = Mutex()
    private val ratings = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<JsonElement>>>()
    /** Scrobble bodies of the Continue watching rows, so one can be removed (DELETE scrobble/playing). */
    private val playing = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()

    override fun signedOut() {
        watchedFile.clear()
        watchedCache = null
        ratings.clear()
        playing.clear()
    }

    /** Signing out also revokes the token on WeTrakr, in the background. */
    override fun disconnect() {
        val (access, refresh) = savedTokens()
        super.disconnect()
        if (access == null || refresh == null) return
        background.launch {
            runCatching {
                Http.post(base + "oauth/logout", buildJsonObject { put("refresh_token", refresh) },
                    headers(false) + ("Authorization" to "Bearer $access"))
            }
        }
    }

    // ---- reads ----

    override suspend fun continueWatching(): List<TrackedItem> = coroutineScope {
        val movies = async { pages("sync/tracking/playing/movies", "movie_level_1") }
        val episodes = async { pages("sync/tracking/playing/episodes", "show_level_1,episode_level_1") }
        val out = weTrakrPlaying(movies.await(), episodes.await(), name)
        playing.clear()
        out.forEach { (item, body) -> playing[item.key] = body }
        out.map { it.first }
    }

    override suspend fun watchlist(): List<TrackedItem> = coroutineScope {
        val movies = async { pages("sync/tracking/planning/movies", "movie_level_1") }
        val shows = async { pages("sync/tracking/planning/shows", "show_level_1") }
        movies.await().mapNotNull { weTrakrMedia(it, Kind.MOVIE, "wetrakr:w:m:", name) } +
            shows.await().mapNotNull { weTrakrMedia(it, Kind.SHOW, "wetrakr:w:s:", name) }
    }

    /** Every page of a full list (100 a page), following X-Pagination-Page-Count. */
    private suspend fun pages(path: String, extended: String): List<JsonElement> {
        val out = ArrayList<JsonElement>()
        for (page in 1..20) {
            val (body, headers) = authedGetPaged("$path?page=$page&limit=100&extended=$extended")
            val rows = body.list()
            out += rows
            val count = headers["X-Pagination-Page-Count"]?.trim()?.toIntOrNull()
            if (rows.isEmpty() || (count ?: page) <= page) break
        }
        return out
    }

    /** Every row of a compact list (5,000 a page), following the X-Pagination-Next cursor. */
    private suspend fun compact(path: String): List<JsonElement> {
        val out = ArrayList<JsonElement>()
        val seen = HashSet<String>()
        var after: String? = null
        repeat(50) {
            val query = "$path?compact=true&limit=5000" + (after?.let { "&after=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
            val (body, headers) = authedGetPaged(query)
            out += body.list()
            after = headers["X-Pagination-Next"]?.trim()?.takeIf { it.isNotEmpty() && seen.add(it) } ?: return out
        }
        return out
    }

    override fun needsAccountName() = isConnected() && store.get("$id.account") == null
    override suspend fun loadAccountName() {
        val root = runCatching { authedGet("account/settings") }.getOrNull() ?: return
        val user = root.obj("user") ?: root
        val label = user.str("display_name")?.trim()?.takeIf { it.isNotEmpty() } ?: user.str("username")?.let { "@$it" } ?: return
        if (isConnected()) store.put("$id.account", label)
    }

    override suspend fun watched(): Set<String> = syncWatched().flatMapTo(HashSet()) { it.keys }

    /** Watched movies, episodes and finished shows; downloaded again only when last_activities moves. */
    override suspend fun syncWatched(): List<SyncItem> = watchedLock.withLock {
        val activity = authedGet("sync/last_activities")
        val stamp = weTrakrWatchStamp(activity)
        val current = watchedCache ?: watchedFile.read()?.let { (s, lines) -> s to lines.mapNotNull(SyncItem::decode) }?.also { watchedCache = it }
        if (current != null && current.first == stamp) return@withLock current.second
        val items = coroutineScope {
            val movies = async { compact("sync/tracking/watched/movies") }
            val episodes = async { compact("sync/tracking/watched/episodes") }
            val shows = async { compact("sync/tracking/watched/shows") }
            weTrakrWatchedItems(movies.await(), episodes.await(), shows.await())
        }
        watchedCache = stamp to items
        watchedFile.write(stamp, items.mapTo(LinkedHashSet()) { it.encode() })
        items
    }

    // ---- two-way sync (#90) ----
    override val syncs = true

    override suspend fun syncRatings(): Map<SyncItem, Int> = coroutineScope {
        val movies = async { pages("sync/ratings/movies", "movie_level_1") }
        val shows = async { pages("sync/ratings/shows", "show_level_1") }
        ratingRows(movies.await(), true, ::weTrakrRating) + ratingRows(shows.await(), false, ::weTrakrRating)
    }

    /** Every item needs a status; episodes inherit it from their show. */
    override suspend fun applySync(category: SyncCategory, add: List<SyncItem>, remove: List<SyncItem>) {
        val (status, addPath, removePath) = when (category) {
            SyncCategory.WATCHED -> Triple("watched", "sync/tracking", "sync/tracking/remove/all")
            SyncCategory.WATCHLIST -> Triple("planning", "sync/tracking", "sync/tracking/remove")
            SyncCategory.RATINGS -> return
        }
        val fields = mapOf("status" to JsonPrimitive(status))
        add.batches().forEach { batchWrite(addPath, syncBatch(it, extra = { i -> if (i.isEpisode) emptyMap() else fields }, showExtra = fields)) }
        remove.batches().forEach { batchWrite(removePath, syncBatch(it, extra = { i -> if (i.isEpisode) emptyMap() else fields }, showExtra = fields)) }
    }

    override suspend fun applyRatings(set: Map<SyncItem, Int>, clear: List<SyncItem>) {
        set.keys.toList().batches().forEach { chunk ->
            batchWrite("sync/ratings", syncBatch(chunk, extra = { mapOf("rating" to JsonPrimitive(set.getValue(it))) }))
        }
        clear.batches().forEach { batchWrite("sync/ratings/remove", syncBatch(it)) }
    }

    /** A batch where a few unknown titles are fine: only the account limit (420) stops it. */
    private suspend fun batchWrite(path: String, body: JsonObject) {
        limited { authedPost(path, body) }
        pace()
    }

    // ---- writes ----

    /** WeTrakr answers 200 even when it can't place a title: notFound / errored say so. */
    private suspend fun write(path: String, body: JsonObject): Boolean {
        val r = limited { authedPost(path, body) }
        if (weTrakrUnresolved(r)) throw TrackerRefusal(id, "$name couldn't match this title.")
        return true
    }

    override suspend fun addToWatchlist(ref: TitleRef): Boolean =
        ref.hasIds && write("sync/tracking", weTrakrBody(ref, ids(ref), "planning"))
    override suspend fun removeFromWatchlist(ref: TitleRef): Boolean =
        ref.hasIds && write("sync/tracking/remove", weTrakrBody(ref, ids(ref), "planning"))

    override fun supportsHistory(ref: TitleRef) = ref.hasIds
    /** Watched adds a play; unwatched removes every play of that movie, episode or show. */
    override suspend fun markWatched(ref: TitleRef, season: Int?, episode: Int?, watched: Boolean): Boolean =
        ref.hasIds && write(if (watched) "sync/tracking" else "sync/tracking/remove/all", weTrakrBody(ref, ids(ref), "watched", season, episode))

    override suspend fun removePlayback(itemKey: String): Boolean {
        val body = playing[itemKey] ?: return false
        authedDelete("scrobble/playing", body)
        playing.remove(itemKey)
        return true
    }

    override val rates = true
    override suspend fun rate(ref: TitleRef, rating: Int?): Boolean {
        if (!ref.hasIds) return false
        val key = if (ref.kind == Kind.MOVIE) "movies" else "shows"
        val body = buildJsonObject {
            putJsonArray(key) { add(buildJsonObject { put("ids", ids(ref)); rating?.let { put("rating", it.coerceIn(1, 10)) } }) }
        }
        write(if (rating != null) "sync/ratings" else "sync/ratings/remove", body)
        ratings.remove(key)
        return true
    }

    override suspend fun ratingOf(ref: TitleRef): Int? {
        if (!ref.hasIds) return null
        val key = if (ref.kind == Kind.MOVIE) "movies" else "shows"
        val now = System.currentTimeMillis()
        val rows = ratings[key]?.takeIf { now - it.first < 10 * 60_000L }?.second
            ?: pages("sync/ratings/$key", if (ref.kind == Kind.MOVIE) "movie_level_1" else "show_level_1").also { ratings[key] = now to it }
        return rows.firstOrNull { sameTitle(it, ref) }?.let(::weTrakrRating)
    }

    /** Trakt's scrobble shape, plus the title and app version WeTrakr uses to resolve and label it. */
    override suspend fun scrobble(item: TrackedItem, state: ScrobbleState, progress: Float) {
        val body = weTrakrScrobbleBody(item, progress) ?: return
        authedPost("scrobble/${state.path}", body)
    }

    private companion object {
        val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    }
}

private const val TMDB_IMAGES = "https://image.tmdb.org/t/p/"

/** WeTrakr images are TMDB paths. */
internal fun weTrakrImage(path: String?, size: String = "w342"): String? = when {
    path.isNullOrBlank() -> null
    path.startsWith("https://") -> path
    path.startsWith("/") && !path.startsWith("//") -> TMDB_IMAGES + size + path
    else -> null
}

/** A compact movie or show row as a tracked item. */
internal fun weTrakrMedia(row: JsonElement, kind: Kind, keyPrefix: String, source: String): TrackedItem? {
    val ids = row.obj("ids")
    val id = row.long("id")
    val title = row.str("title") ?: row.str("name") ?: ""
    if (title.isBlank() && ids.long("tmdb") == null && ids.str("imdb") == null) return null
    return TrackedItem(
        key = keyPrefix + (id ?: ids.long("tmdb") ?: title.hashCode()),
        title = title,
        year = row.int("year") ?: (row.str("release_date") ?: row.str("first_air_date"))?.take(4)?.toIntOrNull(),
        kind = kind,
        poster = weTrakrImage(row.str("poster_path")),
        tmdbId = ids.long("tmdb"),
        imdbId = ids.str("imdb"),
        source = source,
    )
}

/** Continue watching from the playing lists, each with the scrobble body that removes it. */
internal fun weTrakrPlaying(movies: List<JsonElement>, episodes: List<JsonElement>, source: String): List<Pair<TrackedItem, JsonObject>> {
    val out = ArrayList<Pair<TrackedItem, JsonObject>>()
    for (row in movies) {
        val pct = row.obj("playback").dbl("progress_percent")?.toFloat()?.coerceIn(0f, 100f) ?: continue
        val item = weTrakrMedia(row, Kind.MOVIE, "wetrakr:p:m:", source) ?: continue
        val ids = row.obj("ids") ?: continue
        out += item.copy(progress = pct / 100f, detail = "${pct.toInt()}% watched") to buildJsonObject { putJsonObject("movie") { put("ids", ids) } }
    }
    for (row in episodes) {
        val pct = row.obj("playback").dbl("progress_percent")?.toFloat()?.coerceIn(0f, 100f) ?: continue
        val show = row.obj("show")
        val ids = row.obj("show_ids") ?: show.obj("ids") ?: continue
        val s = row.int("season_number") ?: continue
        val n = row.int("number") ?: continue
        val title = show.str("title") ?: continue
        out += TrackedItem(
            key = "wetrakr:p:e:${row.long("id") ?: "${ids.long("tmdb")}:$s:$n"}",
            title = title,
            year = show.int("year") ?: show.str("first_air_date")?.take(4)?.toIntOrNull(),
            kind = Kind.SHOW,
            poster = weTrakrImage(show.str("poster_path")),
            progress = pct / 100f,
            detail = "S$s · E$n",
            tmdbId = ids.long("tmdb"),
            imdbId = ids.str("imdb"),
            season = s,
            episode = n,
            source = source,
        ) to buildJsonObject {
            putJsonObject("show") { put("ids", ids) }
            putJsonObject("episode") { put("season", s); put("number", n) }
        }
    }
    return out
}

/** A tracking write: movies or shows with a status, optionally scoped to one episode. */
internal fun weTrakrBody(ref: TitleRef, ids: JsonObject, status: String, season: Int? = null, episode: Int? = null): JsonObject = buildJsonObject {
    if (ref.kind == Kind.MOVIE) putJsonArray("movies") { add(buildJsonObject { put("ids", ids); put("status", status) }) }
    else putJsonArray("shows") {
        add(buildJsonObject {
            put("ids", ids)
            put("status", status)
            if (season != null && episode != null) putJsonArray("seasons") {
                add(buildJsonObject {
                    put("number", season)
                    putJsonArray("episodes") { add(buildJsonObject { put("number", episode) }) }
                })
            }
        })
    }
}

/** True when a write answer lists anything under notFound or errored. */
internal fun weTrakrUnresolved(r: JsonElement?): Boolean = listOf("notFound", "not_found", "errored").any { key ->
    when (val v = (r as? JsonObject)?.get(key)) {
        is kotlinx.serialization.json.JsonArray -> v.isNotEmpty()
        is JsonObject -> v.values.any { (it as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true ||
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()?.let { n -> n > 0 } == true }
        else -> false
    }
}

/** Your rating on a ratings row: interactions.user.rating, a number or {rating, rated_at}. */
internal fun weTrakrRating(row: JsonElement): Int? {
    val user = row.obj("interactions").obj("user")
    val value = (user?.get("rating") as? JsonObject)?.dbl("rating") ?: user.dbl("rating") ?: row.dbl("rating")
    return value?.let { kotlin.math.round(it).toInt() }?.takeIf { it in 1..10 }
}

/** What moves when your watched history changes; the rest of last_activities is ignored. */
internal fun weTrakrWatchStamp(activity: JsonElement?): String =
    listOf("movies", "episodes", "shows").joinToString("|") { s ->
        listOf("last_tracking_watched_at", "last_tracking_removed_at").joinToString(",") { activity.obj(s).str(it).orEmpty() }
    }.takeIf { it.replace("|", "").replace(",", "").isNotEmpty() } ?: activity.str("all").orEmpty()

/** Watched keys from WeTrakr's compact watched lists (#88). Finished shows count as a whole. */
internal fun weTrakrWatchedKeys(movies: List<JsonElement>, episodes: List<JsonElement>, shows: List<JsonElement>): Set<String> =
    weTrakrWatchedItems(movies, episodes, shows).flatMapTo(HashSet()) { it.keys }

/** Watched movies, episodes and finished shows from WeTrakr (#88, #90). */
internal fun weTrakrWatchedItems(movies: List<JsonElement>, episodes: List<JsonElement>, shows: List<JsonElement>): List<SyncItem> {
    val out = ArrayList<SyncItem>()
    for (row in movies) { val i = row.obj("ids"); SyncItem.of(true, i.str("imdb"), i.long("tmdb"))?.let { out += it } }
    for (row in shows) { val i = row.obj("ids"); SyncItem.of(false, i.str("imdb"), i.long("tmdb"))?.let { out += it } }
    for (row in episodes) {
        val i = row.obj("show_ids") ?: row.obj("show").obj("ids") ?: continue
        val s = row.int("season_number") ?: continue
        val n = row.int("number") ?: continue
        SyncItem.of(false, i.str("imdb"), i.long("tmdb"), s, n)?.let { out += it }
    }
    return out
}

/** Scrobble body: a movie, or a show and an episode, with progress 0-100 and the app version. */
internal fun weTrakrScrobbleBody(item: TrackedItem, progress: Float, version: String = app.ember.tv.BuildConfig.VERSION_NAME): JsonObject? {
    if (item.tmdbId == null && item.imdbId.isNullOrBlank()) return null
    val ids = buildJsonObject {
        item.imdbId?.takeIf { it.isNotBlank() }?.let { put("imdb", it) }
        item.tmdbId?.let { put("tmdb", it) }
    }
    val movie = item.kind == Kind.MOVIE
    val s = item.season
    val e = item.episode
    if (!movie && (s == null || e == null)) return null
    return buildJsonObject {
        if (movie) putJsonObject("movie") { put("ids", ids) }
        else {
            putJsonObject("show") { put("ids", ids) }
            putJsonObject("episode") { put("season", s); put("number", e) }
        }
        put("progress", (progress * 100f).coerceIn(0f, 100f))
        put("app_version", version)
    }
}

/** True when a {ids:{imdb, tmdb}} block is the same title as [ref]. */
internal fun sameTitle(meta: JsonElement?, ref: TitleRef): Boolean {
    val ids = meta.obj("ids")
    return (!ref.imdbId.isNullOrBlank() && ids.str("imdb") == ref.imdbId) || (ref.tmdbId != null && ids.long("tmdb") == ref.tmdbId)
}

/** Watched keys from Trakt's sync/watched/movies and sync/watched/shows (#83). */
internal fun traktWatchedKeys(movies: JsonElement?, shows: JsonElement?): Set<String> =
    traktWatchedItems(movies, shows).flatMapTo(HashSet()) { it.keys }

/** Watched movies and episodes from Trakt (#83, #90). */
internal fun traktWatchedItems(movies: JsonElement?, shows: JsonElement?): List<SyncItem> {
    val out = ArrayList<SyncItem>()
    for (row in movies.list()) {
        val ids = row.obj("movie").obj("ids")
        SyncItem.of(true, ids.str("imdb"), ids.long("tmdb"))?.let { out += it }
    }
    for (row in shows.list()) {
        val ids = row.obj("show").obj("ids")
        val imdb = ids.str("imdb"); val tmdb = ids.long("tmdb")
        for (s in row.arr("seasons").list()) {
            val season = s.int("number") ?: continue
            for (e in s.arr("episodes").list()) e.int("number")?.let { n -> SyncItem.of(false, imdb, tmdb, season, n)?.let { out += it } }
        }
    }
    return out
}
