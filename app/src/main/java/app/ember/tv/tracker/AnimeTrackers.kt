package app.ember.tv.tracker

import android.content.Context
import app.ember.tv.data.ApiKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 * AniList, read-only. Public lists need no sign-in, so you link by username.
 * Progress updates need AniList's OAuth, which is awkward on a TV, so they are left for later.
 */
class AniListTracker(context: Context) : Tracker {
    override val id = "anilist"
    override val name = "AniList"
    override val blurb = "Anime: what you are watching and plan to watch. Enter your username."
    override val mode = LinkMode.USERNAME
    override val configured = true
    override val scrobbles = false

    private val store = TokenStore(context)
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
    override fun accountLabel(): String? = user()?.let { "@$it" }
    override fun disconnect() = store.remove("anilist.user")

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
