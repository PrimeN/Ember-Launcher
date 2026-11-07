package app.ember.tv.tracker

import android.content.Context
import app.ember.tv.data.ApiKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Never save a username after a failed or cancelled validation request. */
internal suspend fun linkPublicUsername(
    name: String,
    verify: suspend (String) -> Unit,
    save: (String) -> Unit,
): Boolean {
    val user = name.trim()
    if (user.isEmpty()) return false
    return try {
        verify(user)
        currentCoroutineContext().ensureActive()
        save(user)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}

internal fun aniListLists(response: JsonElement): JsonArray {
    // GraphQL can report failures in an HTTP 200 response. An empty valid list is
    // different from missing/private users, errors, or an unexpected response.
    check(response.arr("errors").isNullOrEmpty()) { "Could not read that AniList list." }
    return response.obj("data").obj("MediaListCollection").arr("lists")
        ?: error("AniList did not return a public list.")
}

/** Follow every MAL page, accepting only the same HTTPS API resource for the client ID. */
internal suspend fun malListPages(firstUrl: String, get: suspend (String) -> JsonElement): List<JsonElement> {
    val origin = java.net.URI(firstUrl)
    val seen = mutableSetOf<String>()
    val rows = mutableListOf<JsonElement>()
    var url: String? = firstUrl
    while (url != null) {
        val uri = java.net.URI(url)
        check(uri.scheme == "https" && uri.host == origin.host && uri.port == origin.port &&
            uri.path == origin.path && uri.userInfo == null && seen.add(url)) { "Invalid MyAnimeList next page." }
        currentCoroutineContext().ensureActive()
        val response = get(url)
        val page = response.arr("data") ?: error("MyAnimeList did not return a public list.")
        rows.addAll(page)
        url = response.obj("paging").str("next")?.takeIf { it.isNotBlank() }
        check(url == null || page.isNotEmpty()) { "MyAnimeList returned an empty continuation page." }
    }
    return rows
}

/**
 * Maps AniList and MyAnimeList ids to Kitsu ids (#81). Anime addons and stream addons accept
 * kitsu:N, but not anilist: or mal: ids. Answers never change, so they are kept for good;
 * a title Kitsu doesn't know is remembered too, and a network failure is simply tried later.
 */
internal class KitsuIds(
    private val prefs: android.content.SharedPreferences?,
    private val get: suspend (String) -> JsonElement = { Http.get(it, mapOf("Accept" to "application/vnd.api+json")) },
) {
    private val gate = kotlinx.coroutines.sync.Semaphore(4)

    suspend fun of(site: String, id: Long): Long? {
        val key = "$site:$id"
        prefs?.getLong(key, 0L)?.let { if (it > 0) return it; if (it < 0) return null }
        val q = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        val url = "https://kitsu.io/api/edge/mappings?${q("filter[externalSite]")}=${q(site)}&${q("filter[externalId]")}=$id&include=item"
        val r = try { gate.withPermit { get(url) } } catch (e: CancellationException) { throw e } catch (e: Exception) { return null }
        val kitsu = kitsuIdFrom(r)
        prefs?.edit()?.putLong(key, kitsu ?: -1L)?.apply()
        return kitsu
    }

    /** The AniList id for a Kitsu anime, for progress updates (#86). */
    suspend fun aniListOf(kitsuId: Long): Long? {
        val key = "anilist-of:$kitsuId"
        prefs?.getLong(key, 0L)?.let { if (it > 0) return it; if (it < 0) return null }
        val r = try { gate.withPermit { get("https://kitsu.io/api/edge/anime/$kitsuId/mappings") } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { return null }
        val id = r.arr("data").list().firstOrNull { it.obj("attributes").str("externalSite") == "anilist/anime" }
            ?.obj("attributes").str("externalId")?.toLongOrNull()
        prefs?.edit()?.putLong(key, id ?: -1L)?.apply()
        return id
    }

    /** Adds kitsu:N to each item that has an [idOf] and no IMDb or TMDB id. */
    suspend fun fill(items: List<TrackedItem>, site: String, idOf: (TrackedItem) -> Long?): List<TrackedItem> = coroutineScope {
        items.map { item -> async {
            val id = idOf(item)?.takeIf { item.imdbId == null && item.tmdbId == null } ?: return@async item
            of(site, id)?.let { item.copy(addonId = "kitsu:$it") } ?: item
        } }.awaitAll()
    }
}

/** The Kitsu anime id in a mappings response with include=item. */
internal fun kitsuIdFrom(r: JsonElement?): Long? =
    r.arr("included").list().firstOrNull { it.str("type") == "anime" }?.str("id")?.toLongOrNull()
        ?: r.arr("data").list().firstOrNull().obj("relationships").obj("item").obj("data")
            ?.takeIf { it.str("type") == "anime" }?.str("id")?.toLongOrNull()

/**
 * AniList. Public lists need no sign-in, so you link by username. With an access token
 * (Settings > API keys, pasted from the phone page) finished episodes update your progress (#86).
 */
class AniListTracker(context: Context) : Tracker {
    override val id = "anilist"
    override val name = "AniList"
    override val blurb = "Anime: what you are watching and plan to watch. Enter your username; add an access token in API keys to update your progress."
    override val mode = LinkMode.USERNAME
    override val configured = true
    private val token: String get() = ApiKeys.get("ANILIST_TOKEN")
    override val scrobbles: Boolean get() = token.isNotBlank()

    private val store = TokenStore(context)
    private val kitsu = KitsuIds(context.getSharedPreferences("kitsu_ids", Context.MODE_PRIVATE))
    private val endpoint = "https://graphql.anilist.co"
    private val query = """
        query(${'$'}u: String, ${'$'}s: [MediaListStatus]) {
          MediaListCollection(userName: ${'$'}u, type: ANIME, status_in: ${'$'}s) {
            lists { status entries { progress media { id idMal episodes seasonYear title { romaji english } coverImage { large } } } }
          }
        }
    """.trimIndent()

    private fun user() = store.get("anilist.user")
    override fun isConnected() = !user().isNullOrBlank()
    override fun accountLabel(): String? = user()?.let { "@$it" + if (scrobbles) " · updates your progress" else "" }
    override fun disconnect() = store.remove("anilist.user")

    // A token on its own links the account: the username comes from AniList.
    override fun needsAccountName() = scrobbles && user().isNullOrBlank()
    override suspend fun loadAccountName() {
        val name = runCatching { authed("query { Viewer { name } }", buildJsonObject {}).obj("data").obj("Viewer").str("name") }.getOrNull()
        if (!name.isNullOrBlank() && user().isNullOrBlank()) store.put("anilist.user", name)
    }

    private suspend fun authed(query: String, variables: JsonObject): JsonElement {
        val r = Http.post(endpoint, buildJsonObject { put("query", query); put("variables", variables) },
            mapOf("Accept" to "application/json", "Authorization" to "Bearer $token"))
        check(r.arr("errors").isNullOrEmpty()) { r.arr("errors").list().firstOrNull().str("message") ?: "AniList refused the update." }
        return r
    }

    /**
     * When an episode is finished, raises your AniList progress to it (never lowers it) and sets
     * Watching, or Completed on the last episode. The show is found by its Kitsu or AniList id.
     */
    override suspend fun scrobble(item: TrackedItem, state: ScrobbleState, progress: Float) {
        if (!scrobbles || state != ScrobbleState.STOP || progress < 0.8f) return
        val (mediaId, episode) = aniListTarget(item.key, item.episode, kitsu::aniListOf) ?: return
        val entry = authed(
            "query(\$id: Int) { Media(id: \$id, type: ANIME) { episodes mediaListEntry { progress status } } }",
            buildJsonObject { put("id", mediaId) },
        ).obj("data").obj("Media") ?: return
        val update = aniListProgressUpdate(entry.obj("mediaListEntry").int("progress") ?: 0,
            entry.obj("mediaListEntry").str("status"), entry.int("episodes"), episode) ?: return
        authed(
            "mutation(\$id: Int, \$p: Int, \$s: MediaListStatus) { SaveMediaListEntry(mediaId: \$id, progress: \$p, status: \$s) { id } }",
            buildJsonObject { put("id", mediaId); put("p", update.first); put("s", update.second) },
        )
    }

    override suspend fun linkUsername(name: String): Boolean {
        return linkPublicUsername(name,
            verify = { fetch(it, listOf("CURRENT", "PLANNING")) },
            save = { store.put("anilist.user", it) },
        )
    }

    override suspend fun continueWatching(): List<TrackedItem> {
        val u = user() ?: return emptyList()
        return fetch(u, listOf("CURRENT"))
    }

    override suspend fun watchlist(): List<TrackedItem> {
        val u = user() ?: return emptyList()
        return fetch(u, listOf("PLANNING"))
    }

    private suspend fun fetch(user: String, statuses: List<String>): List<TrackedItem> {
        val body = buildJsonObject {
            put("query", query)
            putJsonObject("variables") {
                put("u", user)
                put("s", JsonArray(statuses.map { JsonPrimitive(it) }))
            }
        }
        val r = Http.post(endpoint, body, mapOf("Accept" to "application/json"))
        val lists = aniListLists(r)
        return kitsu.fill(parseAniList(lists), "anilist/anime") { it.key.removePrefix("anilist:").toLongOrNull() }
    }

    private fun parseAniList(lists: JsonArray): List<TrackedItem> {
        return lists.flatMap { list ->
            val status = list.str("status")
            list.arr("entries").list().mapNotNull { e ->
                val media = e.obj("media") ?: return@mapNotNull null
                val title = media.obj("title")
                val name = title.str("english") ?: title.str("romaji") ?: return@mapNotNull null
                val eps = media.int("episodes")
                val watched = e.int("progress") ?: 0
                val current = status == "CURRENT"
                TrackedItem(
                    key = "anilist:${media.long("id")}",
                    title = name,
                    year = media.int("seasonYear"),
                    kind = Kind.ANIME,
                    poster = media.obj("coverImage").str("large"),
                    progress = if (current && eps != null && eps > 0) watched.toFloat() / eps else null,
                    detail = if (current) "Watched $watched" + (eps?.let { " of $it" } ?: "") + " episodes" else null,
                    source = this.name,
                )
            }
        }
    }
}

/**
 * MyAnimeList, read-only. Uses MAL's official API with a client id, which allows
 * reading public lists by username. Needs MAL_CLIENT_ID in local.properties.
 */
class MalTracker(context: Context) : Tracker {
    override val id = "mal"
    override val name = "MyAnimeList"
    override val blurb = "Anime: what you are watching and plan to watch. Enter your username."
    override val mode = LinkMode.USERNAME
    override val configured: Boolean get() = ApiKeys.get("MAL_CLIENT_ID").isNotBlank()
    override val scrobbles = false

    private val store = TokenStore(context)
    private val kitsu = KitsuIds(context.getSharedPreferences("kitsu_ids", Context.MODE_PRIVATE))
    private fun user() = store.get("mal.user")
    override fun isConnected() = !user().isNullOrBlank()
    override fun accountLabel(): String? = user()?.let { "@$it" }
    override fun disconnect() = store.remove("mal.user")

    override suspend fun linkUsername(name: String): Boolean {
        return linkPublicUsername(name,
            verify = { fetch(it, "watching") },
            save = { store.put("mal.user", it) },
        )
    }

    override suspend fun continueWatching(): List<TrackedItem> {
        val u = user() ?: return emptyList()
        return fetch(u, "watching")
    }

    override suspend fun watchlist(): List<TrackedItem> {
        val u = user() ?: return emptyList()
        return fetch(u, "plan_to_watch")
    }

    private suspend fun fetch(user: String, status: String): List<TrackedItem> {
        val url = "https://api.myanimelist.net/v2/users/${java.net.URLEncoder.encode(user, "UTF-8")}/animelist" +
            "?status=$status&limit=50&nsfw=true&fields=list_status,num_episodes,start_season"
        val rows = malListPages(url) { Http.get(it, mapOf("X-MAL-CLIENT-ID" to ApiKeys.get("MAL_CLIENT_ID"))) }
        return kitsu.fill(parseMal(rows, status), "myanimelist/anime") { it.key.removePrefix("mal:").toLongOrNull() }
    }

    private fun parseMal(rows: List<JsonElement>, status: String): List<TrackedItem> {
        return rows.mapNotNull { row ->
            val node = row.obj("node") ?: return@mapNotNull null
            val title = node.str("title") ?: return@mapNotNull null
            val eps = node.int("num_episodes")
            val watched = row.obj("list_status").int("num_episodes_watched") ?: 0
            val current = status == "watching"
            TrackedItem(
                key = "mal:${node.long("id")}",
                title = title,
                year = node.obj("start_season").int("year"),
                kind = Kind.ANIME,
                poster = node.obj("main_picture").str("large") ?: node.obj("main_picture").str("medium"),
                progress = if (current && eps != null && eps > 0) watched.toFloat() / eps else null,
                detail = if (current) "Watched $watched" + (eps?.let { " of $it" } ?: "") + " episodes" else null,
                source = name,
            )
        }
    }
}

/**
 * The AniList show and episode number for a play (#86): kitsu:ID:EP (Kitsu addon episodes)
 * or anilist:ID. Other ids (IMDb) can't be matched to an AniList entry reliably.
 */
internal suspend fun aniListTarget(key: String, episode: Int?, aniListOf: suspend (Long) -> Long?): Pair<Long, Int>? {
    val parts = key.split(':')
    val number = parts.getOrNull(2)?.toIntOrNull()?.let { if (parts.size >= 4) parts[3].toIntOrNull() ?: it else it } ?: episode ?: return null
    val media = when (parts.firstOrNull()) {
        "kitsu" -> parts.getOrNull(1)?.toLongOrNull()?.let { aniListOf(it) }
        "anilist" -> parts.getOrNull(1)?.toLongOrNull()
        else -> null
    } ?: return null
    return media to number
}

/** (progress, status) to save, or null when AniList already has this episode or later (#86). */
internal fun aniListProgressUpdate(current: Int, status: String?, episodes: Int?, watched: Int): Pair<Int, String>? {
    if (watched <= current && status != "PLANNING") return null
    val progress = maxOf(current, watched)
    val done = episodes != null && episodes > 0 && progress >= episodes
    return progress to when {
        done -> "COMPLETED"
        status == "REPEATING" -> "REPEATING"
        else -> "CURRENT"
    }
}
