package app.ember.tv.tracker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Keeps trackers in sync with each other and with Ember's own watchlist and marks (#90).
 *
 * Each round reads every tracker, compares it with what was seen last round ([SyncMerge]) and
 * writes the difference in batches. The snapshot saved is what each tracker held when it was
 * read, never what Ember hoped to write: a write that didn't stick can then neither come back
 * as a removal nor bounce back as an addition. A write that fails outright keeps the old
 * snapshots, so the next round works out the same changes and tries again.
 */
internal class TrackerSyncEngine(private val snapshots: SyncSnapshots) {

    /** Ember's own side: its watchlist, and what to do when a title is removed on a tracker. */
    class Local(
        /** Ember's watchlist is per profile, so each profile is its own participant. */
        val id: String,
        val watchlist: List<SyncItem>,
        val removeWatched: (List<SyncItem>) -> Unit,
        val removeFromWatchlist: (List<SyncItem>) -> Unit,
    )

    data class Report(
        val copied: Int = 0,
        val removed: Int = 0,
        /** Tracker name to how many titles it suddenly dropped (removals held back). */
        val held: Map<String, Int> = emptyMap(),
        val failed: List<String> = emptyList(),
        val refused: List<String> = emptyList(),
    ) {
        operator fun plus(o: Report) = Report(copied + o.copied, removed + o.removed, held + o.held,
            (failed + o.failed).distinct(), (refused + o.refused).distinct())
    }

    suspend fun run(trackers: List<Tracker>, local: Local?, allowMassRemoval: Boolean): Report {
        val names = trackers.associate { it.id to it.name }
        val watched = readAll(trackers) { t -> t.syncWatched().filter { (it.movie || it.isEpisode) && !it.anime } }
        var report = syncSets(SyncCategory.WATCHED, trackers, watched, allowMassRemoval, names) { removed ->
            local?.removeWatched?.invoke(removed)
        }
        val lists = readAll(trackers) { t -> t.syncWatchlist().filter { !it.anime && !it.isEpisode } }.toMutableMap()
        if (local != null) lists[local.id] = local.watchlist.filter { !it.anime && !it.isEpisode }.distinctTitles()
        // Never add a title to a watchlist on a service where you've already watched it: some
        // services move a title between lists, which would take it out of Watched.
        val watchedShows = watched.mapValues { (_, items) -> SyncIndex(items + items.filter { it.isEpisode }.map { it.show() }) }
        report += syncSets(SyncCategory.WATCHLIST, trackers, lists, allowMassRemoval, names,
            acceptsRemovals = trackers.filter { it.removesFromWatchlist }.map { it.id }.toSet() + listOfNotNull(local?.id),
            localId = local?.id,
            skipAdd = { id, item -> watchedShows[id]?.contains(item) == true }) { removed ->
            local?.removeFromWatchlist?.invoke(removed)
        }
        report += syncRatings(trackers, allowMassRemoval, names)
        return report
    }

    private suspend fun <T> readAll(trackers: List<Tracker>, read: suspend (Tracker) -> T): Map<String, T> = coroutineScope {
        // A tracker that can't be read sits this round out; it is never treated as empty.
        trackers.map { t -> async { t.id to runCatching { read(t) }.onFailure { if (it is CancellationException) throw it }.getOrNull() } }
            .awaitAll().mapNotNull { (id, v) -> v?.let { id to it } }.toMap()
    }

    private suspend fun syncSets(
        category: SyncCategory,
        trackers: List<Tracker>,
        current: Map<String, List<SyncItem>>,
        allowMassRemoval: Boolean,
        names: Map<String, String>,
        acceptsRemovals: Set<String> = current.keys,
        localId: String? = null,
        skipAdd: (String, SyncItem) -> Boolean = { _, _ -> false },
        onRemoved: (List<SyncItem>) -> Unit,
    ): Report {
        val saved = snapshots.readItems(category)
        val previous = saved.filterKeys { !it.startsWith("+") && !it.startsWith("-") }
        val echoAdded = saved.filterKeys { it.startsWith("+") }.mapKeys { it.key.drop(1) }
        val echoRemoved = saved.filterKeys { it.startsWith("-") }.mapKeys { it.key.drop(1) }
        val writable = trackers.map { it.id }.toSet()
        val plan = SyncMerge.plan(current, previous, receivesAdds = writable, acceptsRemovals = acceptsRemovals,
            allowMassRemoval = allowMassRemoval, echoAdded = echoAdded, echoRemoved = echoRemoved)
        var frozen = false
        val failed = ArrayList<String>(); val refused = ArrayList<String>()
        val wroteAdd = HashMap<String, List<SyncItem>>(); val wroteRemove = HashMap<String, List<SyncItem>>()
        for (t in trackers) {
            val add = plan.adds[t.id].orEmpty().filterNot { skipAdd(t.id, it) }
            val remove = plan.removes[t.id].orEmpty()
            if (add.isEmpty() && remove.isEmpty()) continue
            try {
                t.applySync(category, add, remove)
                if (add.isNotEmpty()) wroteAdd[t.id] = add
                if (remove.isNotEmpty()) wroteRemove[t.id] = remove
            } catch (e: CancellationException) { throw e
            } catch (e: TrackerRefusal) { refused += e.message ?: t.name
            } catch (e: Exception) { failed += t.name; frozen = true }
        }
        val localRemovals = localId?.let { plan.removes[it] }.orEmpty()
        if (localRemovals.isNotEmpty()) { onRemoved(localRemovals); wroteRemove[localId!!] = localRemovals }
        else if (category == SyncCategory.WATCHED && plan.removedAnywhere.isNotEmpty()) onRemoved(plan.removedAnywhere)
        if (!frozen) {
            // A held-back drop keeps that tracker's old snapshot, so it is asked about again; a
            // tracker that couldn't be read keeps its old one too.
            snapshots.writeItems(category, previous + current.filterKeys { it !in plan.blocked } +
                wroteAdd.mapKeys { "+" + it.key } + wroteRemove.mapKeys { "-" + it.key })
        }
        return Report(wroteAdd.values.sumOf { it.size }, wroteRemove.values.sumOf { it.size },
            plan.blocked.mapKeys { names[it.key] ?: it.key }, failed, refused)
    }

    private suspend fun syncRatings(trackers: List<Tracker>, allowMassRemoval: Boolean, names: Map<String, String>): Report {
        val current = readAll(trackers) { it.syncRatings().filterKeys { item -> !item.anime } }
        val saved = snapshots.readRatings()
        val previous = saved.filterKeys { !it.startsWith("+") && !it.startsWith("-") }
        val echoSet = saved.filterKeys { it.startsWith("+") }.mapKeys { it.key.drop(1) }
        val echoCleared = saved.filterKeys { it.startsWith("-") }.mapKeys { it.key.drop(1) }.mapValues { it.value.keys.toList() }
        val plan = SyncMerge.ratings(current, previous, allowMassRemoval, echoSet, echoCleared)
        var frozen = false
        val failed = ArrayList<String>(); val refused = ArrayList<String>()
        val wroteSet = HashMap<String, Map<SyncItem, Int>>(); val wroteClear = HashMap<String, Map<SyncItem, Int>>()
        for (t in trackers) {
            val set = plan.set[t.id].orEmpty()
            val clear = plan.clear[t.id].orEmpty()
            if (set.isEmpty() && clear.isEmpty()) continue
            try {
                t.applyRatings(set, clear)
                if (set.isNotEmpty()) wroteSet[t.id] = set
                if (clear.isNotEmpty()) wroteClear[t.id] = clear.associateWith { 0 }
            } catch (e: CancellationException) { throw e
            } catch (e: TrackerRefusal) { refused += e.message ?: t.name
            } catch (e: Exception) { failed += t.name; frozen = true }
        }
        if (!frozen) snapshots.writeRatings(previous + current.filterKeys { it !in plan.blocked } +
            wroteSet.mapKeys { "+" + it.key } + wroteClear.mapKeys { "-" + it.key })
        return Report(wroteSet.values.sumOf { it.size }, wroteClear.values.sumOf { it.size },
            plan.blocked.mapKeys { names[it.key] ?: it.key }, failed, refused)
    }

    companion object {
        fun localId(profile: String) = "ember:$profile"
    }
}
