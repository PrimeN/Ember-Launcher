package app.ember.tv.tracker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private val IMDB_ID = Regex("^tt\\d+$")

/**
 * One movie, show or episode as trackers know it (#90). Trackers name the same title with
 * different ids (one sends IMDb, another TMDB, a third both), so two items are the same title
 * when any of their [keys] match.
 */
data class SyncItem(
    val movie: Boolean,
    val imdb: String?,
    val tmdb: Long?,
    val season: Int? = null,
    val episode: Int? = null,
    /** Anime numbering differs between services, so anime stays out of cross-tracker sync. */
    val anime: Boolean = false,
) {
    val keys: List<String> get() = watchedKeys(movie, imdb, tmdb, season, episode)
    val isEpisode: Boolean get() = !movie && season != null && episode != null
    val hasIds: Boolean get() = keys.isNotEmpty()
    fun ref() = TitleRef(if (movie) Kind.MOVIE else Kind.SHOW, tmdb, imdb, "", null)
    fun ids(): JsonObject = buildJsonObject {
        imdb?.let { put("imdb", it) }
        tmdb?.let { put("tmdb", it) }
    }
    /** The show this episode belongs to, for grouping a batch. */
    fun show() = copy(season = null, episode = null)
    fun encode(): String = listOf(if (movie) "m" else "s", imdb.orEmpty(), tmdb?.toString().orEmpty(),
        season?.toString().orEmpty(), episode?.toString().orEmpty(), if (anime) "a" else "").joinToString(",")

    companion object {
        fun of(movie: Boolean, imdb: String?, tmdb: Long?, season: Int? = null, episode: Int? = null, anime: Boolean = false): SyncItem? =
            SyncItem(movie, imdb?.trim()?.takeIf { IMDB_ID.matches(it) }, tmdb?.takeIf { it > 0 },
                season?.takeIf { !movie && episode != null }, episode?.takeIf { !movie && season != null }, anime).takeIf { it.hasIds }

        fun decode(line: String): SyncItem? {
            val p = line.split(',')
            if (p.size < 5 || p[0] !in setOf("m", "s")) return null
            return of(p[0] == "m", p[1].ifEmpty { null }, p[2].toLongOrNull(), p[3].toIntOrNull(), p[4].toIntOrNull(), p.getOrNull(5) == "a")
        }

        fun of(item: TrackedItem): SyncItem? = of(item.kind == Kind.MOVIE, item.imdbId, item.tmdbId, anime = item.kind == Kind.ANIME)
    }
}

enum class SyncCategory { WATCHED, WATCHLIST, RATINGS }

/** Finds items by any of their ids. */
internal class SyncIndex(items: Collection<SyncItem>) {
    private val byKey = HashMap<String, SyncItem>()
    init { items.forEach { item -> item.keys.forEach { byKey.putIfAbsent(it, item) } } }
    operator fun contains(item: SyncItem) = item.keys.any { it in byKey }
    fun find(item: SyncItem): SyncItem? = item.keys.firstNotNullOfOrNull { byKey[it] }
}

/** Distinct by identity: the first item with any shared id wins, and keeps the ids of later copies. */
internal fun Collection<SyncItem>.distinctTitles(): List<SyncItem> {
    val out = ArrayList<SyncItem>()
    val index = HashMap<String, Int>()
    for (item in this) {
        val at = item.keys.firstNotNullOfOrNull { index[it] }
        if (at == null) {
            out += item
            item.keys.forEach { index[it] = out.size - 1 }
        } else {
            val merged = out[at].let { it.copy(imdb = it.imdb ?: item.imdb, tmdb = it.tmdb ?: item.tmdb) }
            out[at] = merged
            merged.keys.forEach { index[it] = at }
        }
    }
    return out
}

/**
 * A batch write in the shape Trakt, Simkl and WeTrakr share: movies, whole shows, and episodes
 * grouped under their show and season. [extra] adds fields such as a rating or a status;
 * [showExtra] adds fields on a show that only carries episodes (WeTrakr's inherited status).
 */
internal fun syncBatch(
    items: List<SyncItem>,
    extra: (SyncItem) -> Map<String, JsonPrimitive> = { emptyMap() },
    showExtra: Map<String, JsonPrimitive> = emptyMap(),
): JsonObject = buildJsonObject {
    val movies = items.filter { it.movie }
    val shows = items.filter { !it.movie && !it.isEpisode }
    val episodes = items.filter { it.isEpisode }.groupBy { it.show().encode() }
    if (movies.isNotEmpty()) putJsonArray("movies") { movies.forEach { add(buildJsonObject { put("ids", it.ids()) }.plusFields(extra(it))) } }
    if (shows.isNotEmpty() || episodes.isNotEmpty()) putJsonArray("shows") {
        shows.forEach { add(buildJsonObject { put("ids", it.ids()) }.plusFields(extra(it))) }
        episodes.values.forEach { eps ->
            add(buildJsonObject {
                put("ids", eps.first().ids())
                putJsonArray("seasons") {
                    eps.groupBy { it.season!! }.toSortedMap().forEach { (season, list) ->
                        add(buildJsonObject {
                            put("number", season)
                            putJsonArray("episodes") { list.forEach { add(buildJsonObject { put("number", it.episode!!) }.plusFields(extra(it))) } }
                        })
                    }
                }
            }.plusFields(showExtra))
        }
    }
}

private fun JsonObject.plusFields(fields: Map<String, JsonPrimitive>) = if (fields.isEmpty()) this else JsonObject(this + fields)

/** Splits a write into requests of at most [size] items. */
internal fun <T> List<T>.batches(size: Int = 200): List<List<T>> = chunked(size)

/**
 * The three-way merge behind two-way sync (#90). Each tracker's current list is compared with
 * what Ember saw there last time: what appeared anywhere is copied everywhere, what disappeared
 * anywhere is removed everywhere. A tracker seen for the first time (or the first run, the
 * back-fill) only adds. An addition beats a removal of the same title.
 */
internal object SyncMerge {
    data class Plan(
        val adds: Map<String, List<SyncItem>>,
        val removes: Map<String, List<SyncItem>>,
        /** Trackers whose sudden drop was not mirrored, with how many titles went missing. */
        val blocked: Map<String, Int>,
        /** Titles removed anywhere this round, for Ember's own marks and watchlist. */
        val removedAnywhere: List<SyncItem>,
    )

    /** A drop bigger than this in one round is held back: a bad read must not erase history elsewhere. */
    fun massive(removed: Int, before: Int) = removed > maxOf(25, before / 5)

    /**
     * [echoAdded] / [echoRemoved]: what Ember itself wrote to each tracker last round. Finding
     * those changes there now is Ember's own echo, not something you did, so it doesn't count:
     * otherwise a title copied last round would look newly added and undo a real removal.
     */
    fun plan(
        current: Map<String, List<SyncItem>>,
        previous: Map<String, List<SyncItem>>,
        receivesAdds: Set<String> = current.keys,
        acceptsRemovals: Set<String> = current.keys,
        allowMassRemoval: Boolean = false,
        echoAdded: Map<String, List<SyncItem>> = emptyMap(),
        echoRemoved: Map<String, List<SyncItem>> = emptyMap(),
    ): Plan {
        val added = ArrayList<SyncItem>()
        val removed = ArrayList<SyncItem>()
        val blocked = HashMap<String, Int>()
        for ((id, now) in current) {
            val before = previous[id]
            if (before == null) { added += now; continue }
            val beforeIndex = SyncIndex(before)
            val nowIndex = SyncIndex(now)
            val echoAdd = SyncIndex(echoAdded[id].orEmpty())
            val echoRemove = SyncIndex(echoRemoved[id].orEmpty())
            added += now.filter { it !in beforeIndex && it !in echoAdd }
            val gone = before.filter { it !in nowIndex && it !in echoRemove }
            if (gone.isNotEmpty() && !allowMassRemoval && massive(gone.size, before.size)) blocked[id] = gone.size
            else removed += gone
        }
        val addIndex = SyncIndex(added)
        val removals = removed.filter { it !in addIndex }.distinctTitles()
        val additions = added.distinctTitles()
        val removalIndex = SyncIndex(removals)
        val adds = current.filterKeys { it in receivesAdds }.mapValues { (_, now) ->
            val have = SyncIndex(now)
            additions.filter { it !in have && it !in removalIndex }
        }.filterValues { it.isNotEmpty() }
        val removes = current.filterKeys { it in acceptsRemovals }.mapValues { (_, now) ->
            now.filter { it in removalIndex }
        }.filterValues { it.isNotEmpty() }
        return Plan(adds, removes, blocked, removals)
    }

    data class RatingPlan(val set: Map<String, Map<SyncItem, Int>>, val clear: Map<String, List<SyncItem>>, val blocked: Map<String, Int>)

    /**
     * Ratings: a new or changed rating anywhere is copied to the trackers that differ; a rating
     * cleared anywhere is cleared everywhere. The back-fill only fills gaps and never overwrites.
     */
    fun ratings(
        current: Map<String, Map<SyncItem, Int>>,
        previous: Map<String, Map<SyncItem, Int>>,
        allowMassRemoval: Boolean = false,
        echoSet: Map<String, Map<SyncItem, Int>> = emptyMap(),
        echoCleared: Map<String, List<SyncItem>> = emptyMap(),
    ): RatingPlan {
        val changed = LinkedHashMap<SyncItem, Int>()
        val fills = LinkedHashMap<SyncItem, Int>()
        val cleared = ArrayList<SyncItem>()
        val blocked = HashMap<String, Int>()
        for ((id, now) in current) {
            val before = previous[id]
            if (before == null) {
                now.forEach { (item, r) -> if (SyncIndex(fills.keys).find(item) == null) fills[item] = r }
                continue
            }
            val beforeIndex = SyncIndex(before.keys)
            val echo = echoSet[id].orEmpty()
            val echoIndex = SyncIndex(echo.keys)
            now.forEach { (item, r) ->
                val old = beforeIndex.find(item)?.let { before[it] }
                val ours = echoIndex.find(item)?.let { echo[it] } == r
                if (old != r && !ours && SyncIndex(changed.keys).find(item) == null) changed[item] = r
            }
            val nowIndex = SyncIndex(now.keys)
            val echoClear = SyncIndex(echoCleared[id].orEmpty())
            val gone = before.keys.filter { it !in nowIndex && it !in echoClear }
            if (gone.isNotEmpty() && !allowMassRemoval && massive(gone.size, before.size)) blocked[id] = gone.size
            else cleared += gone
        }
        val changedIndex = SyncIndex(changed.keys)
        val clears = cleared.filter { it !in changedIndex }.distinctTitles()
        val clearIndex = SyncIndex(clears)
        val set = current.mapValues { (_, now) ->
            val index = SyncIndex(now.keys)
            val out = LinkedHashMap<SyncItem, Int>()
            changed.forEach { (item, r) -> if (index.find(item)?.let { now[it] } != r) out[item] = r }
            fills.forEach { (item, r) -> if (item !in index && item !in clearIndex && SyncIndex(out.keys).find(item) == null) out[item] = r }
            out
        }.filterValues { it.isNotEmpty() }
        val clear = current.mapValues { (_, now) -> now.keys.filter { it in clearIndex } }.filterValues { it.isNotEmpty() }
        return RatingPlan(set, clear, blocked)
    }
}

/** What Ember last saw on each tracker, per category, kept on the TV (#90). */
internal class SyncSnapshots(private val dir: java.io.File?) {
    private fun file(category: SyncCategory) = dir?.let { java.io.File(it, "tracker_sync_${category.name.lowercase()}.txt") }

    /** Tracker id to its lines (encoded items, or "item=rating" for ratings). */
    fun read(category: SyncCategory): Map<String, List<String>> = runCatching {
        val f = file(category)?.takeIf { it.exists() } ?: return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        f.forEachLine { line ->
            if (line.startsWith("#")) current = out.getOrPut(line.removePrefix("#")) { ArrayList() }
            else if (line.isNotBlank()) current?.add(line)
        }
        out
    }.getOrDefault(emptyMap())

    fun write(category: SyncCategory, data: Map<String, List<String>>) {
        val f = file(category) ?: return
        runCatching {
            val tmp = java.io.File(f.path + ".tmp")
            tmp.bufferedWriter().use { w -> data.forEach { (id, lines) -> w.write("#$id\n"); lines.forEach { w.write(it); w.write("\n") } } }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    fun clear() { SyncCategory.values().forEach { file(it)?.delete() } }

    /** Forgets one tracker, e.g. after signing out: another account must not be compared with this one. */
    fun forget(id: String) = SyncCategory.values().forEach { c -> read(c).takeIf { id in it }?.let { write(c, it - id) } }

    /** Echo sections ("+id" written adds, "-id" written removals) sit beside each tracker's snapshot. */
    fun readItems(category: SyncCategory) = read(category).mapValues { (_, l) -> l.mapNotNull(SyncItem::decode) }
    fun writeItems(category: SyncCategory, data: Map<String, List<SyncItem>>) = write(category, data.mapValues { (_, l) -> l.map { it.encode() } })
    fun readRatings() = read(SyncCategory.RATINGS).mapValues { (_, l) ->
        l.mapNotNull { line -> val r = line.substringAfterLast('=').toIntOrNull(); SyncItem.decode(line.substringBeforeLast('='))?.let { i -> r?.let { i to it } } }.toMap()
    }
    fun writeRatings(data: Map<String, Map<SyncItem, Int>>) = write(SyncCategory.RATINGS, data.mapValues { (_, m) -> m.map { (i, r) -> "${i.encode()}=$r" } })
}

/**
 * Changes owed to trackers that could not be reached: watched marks, ratings and finished plays
 * (#89). Kept on the TV and sent on the next refresh; the player and Home share it.
 */
internal object TrackerQueue {
    data class Op(
        val type: String, // "watched", "unwatched", "rate"
        val item: SyncItem,
        val rating: Int? = null,
        val targets: Set<String>,
    )

    @Volatile private var file: java.io.File? = null
    private val lock = Any()

    fun init(dir: java.io.File) { if (file == null) file = java.io.File(dir, "tracker_queue.txt") }

    fun all(): List<Op> = synchronized(lock) { read() }

    fun add(op: Op) {
        if (op.targets.isEmpty()) return
        synchronized(lock) {
            // A newer change to the same title replaces an older one (watched, then unwatched).
            val kept = read().filterNot { it.type.isMark() == op.type.isMark() && it.item.encode() == op.item.encode() }
            write((kept + op).takeLast(500))
        }
    }

    /** Records which trackers still owe [op]; it is dropped once none do. */
    fun update(op: Op, remaining: Set<String>) = synchronized(lock) {
        val list = read().map { if (it == op) it.copy(targets = remaining) else it }.filter { it.targets.isNotEmpty() }
        write(list)
    }

    fun clear() = synchronized(lock) { file?.delete(); Unit }

    private fun String.isMark() = this == "watched" || this == "unwatched"

    private fun read(): List<Op> = runCatching {
        val f = file?.takeIf { it.exists() } ?: return emptyList()
        f.readLines().mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 4) return@mapNotNull null
            val item = SyncItem.decode(p[1]) ?: return@mapNotNull null
            Op(p[0], item, p[2].toIntOrNull(), p[3].split(';').filter { it.isNotBlank() }.toSet())
        }
    }.getOrDefault(emptyList())

    private fun write(ops: List<Op>) {
        val f = file ?: return
        runCatching {
            val tmp = java.io.File(f.path + ".tmp")
            tmp.writeText(ops.joinToString("\n") { "${it.type}|${it.item.encode()}|${it.rating ?: ""}|${it.targets.joinToString(";")}" })
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}

/** Rows like Trakt's {rating, movie|show:{ids}} or Simkl's {user_rating, movie|show}. */
internal fun ratingRows(rows: List<JsonElement>, movie: Boolean, value: (JsonElement) -> Int?): Map<SyncItem, Int> {
    val out = LinkedHashMap<SyncItem, Int>()
    for (row in rows) {
        val meta = row.obj(if (movie) "movie" else "show") ?: (row as? JsonObject)
        val ids = meta.obj("ids")
        val item = SyncItem.of(movie, ids.str("imdb"), ids.long("tmdb")) ?: continue
        val r = value(row)?.takeIf { it in 1..10 } ?: continue
        out[item] = r
    }
    return out
}

internal fun JsonElement?.rows(key: String): List<JsonElement> = (this as? JsonArray)?.toList() ?: this.arr(key).list()
