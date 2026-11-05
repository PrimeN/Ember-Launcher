package app.ember.tv.light

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class TvReadRequest(val packages: Set<String>, val watch: Boolean, val previews: Boolean)

/** Joins platform tables by channel ownership, then translates records into Ember media IDs. */
internal class TvProgramReader(private val source: TvProgramSource, private val ownPackage: String) {
    suspend fun read(request: TvReadRequest, now: Long): List<LightMedia> {
        if (request.packages.isEmpty() || (!request.watch && !request.previews)) return emptyList()
        val packages = request.packages - ownPackage
        val channels = safely { source.channels() }.filter { it.id >= 0 && it.preview && it.owner in packages }
        val selected = LinkedHashMap<Long, TvChannelRecord>()
        val ambiguous = channels.groupBy { it.id }.filterValues { rows -> rows.map { it.owner }.distinct().size > 1 }.keys
        for (ownerChannels in channels.filter { it.id !in ambiguous }.groupBy { it.owner }.values) {
            val resume = ownerChannels.filter { LightMediaProvider.resumeChannel(it.name) }
            val recommendations = ownerChannels.filterNot { LightMediaProvider.resumeChannel(it.name) }
            for (channel in resume) selected[channel.id] = channel
            val primary = recommendations.firstOrNull { it.browsable } ?: recommendations.firstOrNull()
            if (primary != null) selected[primary.id] = primary
        }
        val output = ArrayList<LightMedia>()
        if (request.watch) {
            for (record in safely { source.watchNext() }) {
                currentCoroutineContext().ensureActive()
                if (record.owner in packages) materialize(record, record.owner, true, true, now)?.let(output::add)
            }
        }
        for (record in safely { source.previews() }) {
            currentCoroutineContext().ensureActive()
            if (record.channelId in ambiguous) continue
            val channel = selected[record.channelId]
            if (channel == null && channels.isNotEmpty()) continue
            if (channel != null && record.owner.isNotEmpty() && record.owner != channel.owner) continue
            val owner = channel?.owner ?: record.owner
            if (owner !in packages) continue
            val continuing = channel != null && LightMediaProvider.resumeChannel(channel.name)
            if (if (continuing) !request.watch else !request.previews) continue
            materialize(record, owner, continuing, false, now)?.let(output::add)
        }
        // Bound the cached pool while retaining independent space for each app and feed type.
        val buckets = LinkedHashMap<Pair<String, Boolean>, MutableList<LightMedia>>()
        output.forEach { buckets.getOrPut(it.pkg to it.continuing) { ArrayList() }.add(it) }
        val retained = buckets.values.flatMap { bucket ->
            bucket.sortedByDescending { if (it.continuing) it.engaged else it.rank }.take(8)
        }
        return retained.filter { it.continuing }.sortedByDescending { it.engaged }.take(80) +
            retained.filterNot { it.continuing }.take(80)
    }

    private suspend fun <T> safely(read: suspend () -> List<T>): List<T> = try {
        read().also { currentCoroutineContext().ensureActive() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        emptyList()
    }

    private fun materialize(row: TvProgramRecord, owner: String, continuing: Boolean, watchRow: Boolean, now: Long): LightMedia? {
        if (row.id < 0 || row.title.isBlank() || row.link.isBlank() || row.link.length > 8192) return null
        val duration = row.duration.coerceAtLeast(0)
        val position = row.position.coerceAtLeast(0)
        val engagement = if (row.engagement in 1 until 100_000_000_000L) row.engagement * 1000 else row.engagement
        val kind = if (watchRow) row.watchType else 0
        if (!LightMediaProvider.eligible(kind, row.browsable, position, duration, engagement, now)) return null
        val episode = buildList {
            row.season.takeIf { it.isNotBlank() }?.let { add("S$it") }
            row.episode.takeIf { it.isNotBlank() }?.let { add("E$it") }
            row.episodeTitle.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(" · ")
        return LightMedia(
            id = "$owner:${if (watchRow) "watch" else "preview"}:${row.id}",
            pkg = owner, title = row.title.take(240), description = episode.ifBlank { row.synopsis.take(320) },
            artwork = LightMediaProvider.validArtwork(row.thumbnail) ?: LightMediaProvider.validArtwork(row.poster),
            intent = row.link, progress = if (continuing && duration > 0 && position > 0)
                (position.toDouble() / duration).coerceIn(0.0, 1.0).toFloat() else null,
            engaged = engagement, continuing = continuing, duration = duration, position = position, rank = row.weight,
        )
    }
}

/** One in-flight provider load, four bounded snapshots; invalidation rejects a late result. */
internal class TvProgramCache(private val now: () -> Long) {
    private data class Snapshot(val written: Long, val items: List<LightMedia>)
    private val reads = Mutex()
    private val snapshots = LinkedHashMap<TvReadRequest, Snapshot>()
    private var revision = 0L
    fun invalidate() = synchronized(snapshots) { revision++; snapshots.clear() }

    suspend fun get(request: TvReadRequest, load: suspend () -> List<LightMedia>): List<LightMedia> = reads.withLock {
        val key = request.copy(packages = request.packages.toSet())
        repeat(2) {
            val started = synchronized(snapshots) {
                snapshots[key]?.takeIf { now() - it.written in 0 until 120_000 }?.let { return@withLock it.items }
                revision
            }
            val loaded = load().toList()
            currentCoroutineContext().ensureActive()
            synchronized(snapshots) {
                if (revision == started) {
                    snapshots.remove(key)
                    snapshots[key] = Snapshot(now(), loaded)
                    while (snapshots.size > 4) snapshots.remove(snapshots.keys.first())
                    return@withLock loaded
                }
            }
        }
        emptyList()
    }
}
