package app.ember.tv.tracker

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Your Simkl library, kept on the TV and refreshed the way Simkl asks (#78): sync/activities
 * first, nothing more when it is unchanged, and only the changed entries (date_from) when it is.
 * A full pull happens once, and again only when something was removed from a list.
 */
internal class SimklLibrary(
    private val file: java.io.File?,
    private val fetch: suspend (String) -> JsonElement,
    private val now: () -> Long = System::currentTimeMillis,
) {
    internal data class State(val activities: JsonElement, val library: JsonObject)

    private val mutex = Mutex()
    private var state: State? = null
    private var checkedAt = 0L

    /** Entries by type ("movies", "shows", "anime"). Checks Simkl at most once a minute. */
    suspend fun entries(): JsonObject = mutex.withLock {
        val current = state ?: read().also { state = it }
        if (current != null && now() - checkedAt < 60_000L) return current.library
        val activities = fetch("sync/activities")
        val all = activities.str("all")
        val saved = current?.activities?.str("all")
        val next = when {
            current == null || all == null || saved == null -> full(activities)
            saved == all -> current
            removals(current.activities) != removals(activities) -> full(activities)
            else -> State(activities, merge(current.library, fetch("$ALL_ITEMS&date_from=" + java.net.URLEncoder.encode(saved, "UTF-8"))))
        }
        state = next
        checkedAt = now()
        if (next !== current) write(next)
        next.library
    }

    fun clear() {
        state = null
        checkedAt = 0L
        file?.delete()
    }

    private suspend fun full(activities: JsonElement) = State(activities, fetch(ALL_ITEMS) as? JsonObject ?: JsonObject(emptyMap()))

    private fun read(): State? = runCatching {
        val f = file?.takeIf { it.exists() } ?: return null
        val root = json.parseToJsonElement(f.readText()) as JsonObject
        State(root["activities"] ?: return null, root["library"] as? JsonObject ?: return null)
    }.getOrNull()

    private fun write(s: State) {
        val f = file ?: return
        runCatching {
            // Write beside it, then rename: a crash mid-write keeps the previous copy.
            val tmp = java.io.File(f.path + ".tmp")
            tmp.writeText(buildJsonObject { put("activities", s.activities); put("library", s.library) }.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    companion object {
        /**
         * Episodes come with each show, including completed and dropped ones (recorded episodes
         * only), so watched marks (#83) and sync (#90) need no extra calls.
         */
        const val ALL_ITEMS = "sync/all-items?extended=full&include_all_episodes=original"
        private val json = Json { ignoreUnknownKeys = true }

        private fun removals(a: JsonElement?) = listOf("movies", "tv_shows", "anime").map { a.obj(it).str("removed_from_list") }

        private fun entryId(type: String, e: JsonElement): String? =
            e.obj(if (type == "movies") "movie" else "show").obj("ids").long("simkl")?.toString()

        /** Changed entries replace the saved ones with the same Simkl id; new ones are added. */
        internal fun merge(library: JsonObject, delta: JsonElement): JsonObject {
            val changes = delta as? JsonObject ?: return library
            val types = (library.keys + changes.keys).filter { it in setOf("movies", "shows", "anime") }
            return JsonObject(types.associateWith { type ->
                val updated = changes.arr(type).list()
                val ids = updated.mapNotNull { entryId(type, it) }.toSet()
                JsonArray(library.arr(type).list().filter { entryId(type, it) !in ids } + updated)
            })
        }
    }
}
