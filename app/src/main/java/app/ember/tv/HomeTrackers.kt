package app.ember.tv

import kotlinx.coroutines.sync.withLock
import androidx.lifecycle.viewModelScope
import app.ember.tv.data.LocalLibrary
import app.ember.tv.data.Features
import app.ember.tv.data.CatalogRow
import app.ember.tv.data.Meta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.ember.tv.tracker.LinkSession
import app.ember.tv.tracker.SmartRows
import app.ember.tv.tracker.Tmdb
import app.ember.tv.tracker.list
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

// HomeViewModel: trackers (Trakt, Simkl, ...), linking, watchlist sync and smart rows.
/** [force] false (coming back to Ember) skips it when the last refresh was under a minute ago. */
internal fun HomeViewModel.refreshTrackers(force: Boolean = true, only: Set<String>? = null) {
    val now = System.currentTimeMillis()
    if (!force && now - trackersAt < 60_000L) return
    trackersAt = now
    trackerRefreshJob?.cancel()
    smartRowsRevision++
    if (!featureOn(app.ember.tv.data.Features.TRACKERS)) {
        // Trackers off: nothing is loaded, and the player does not report either.
        hub.disabled = hub.trackers.map { it.id }.toSet()
        hub.health.retain(emptySet())
        continueItems = emptyList()
        trackerWatchlist = emptyList()
        libraryHistory = emptyList()
        viewModelScope.launch { refreshSmartRows(force = true) }
        return
    }
    hub.disabled = trackersOff
    if (hub.trakt() == null) {
        libraryHistory = emptyList()
        LocalLibrary.saveTrackerHistory(emptyList())
    }
    val watchedOwner=profiles.profileId
    trackerRefreshJob = viewModelScope.launch {
        // Watchlist changes made while a tracker could not be reached go out first.
        if (only == null) sendPendingWatchlist()
        // When every tracker failed (offline), keep what is shown rather than emptying the rows.
        // What they said is also kept on the TV for the next start without network.
        hub.continueWatchingOrNull(only)?.let { list ->
            ensureActive()
            val retained = continueItems.filter { old -> hub.health.entries[old.id.substringBefore(':') + ":progress"]?.failed == true }
            continueItems = mergeTitles(list.map { it.toMeta() }, retained)
            LocalLibrary.saveTrackerProgress(continueItems)
        }
        hub.watchlistOrNull(only)?.let { list ->
            ensureActive()
            val retained = trackerWatchlist.filter { old -> hub.health.entries[old.id.substringBefore(':') + ":watchlist"]?.failed == true }
            trackerWatchlist = mergeTitles(list.map { it.toMeta() }, retained)
            LocalLibrary.saveTrackerWatchlist(trackerWatchlist)
        }
        hub.trakt()?.takeIf { only == null || "trakt" in only }?.let { trakt ->
            hub.health.start("trakt:history", "Trakt · Recently watched")
            val result = runCatching { trakt.history() }
            ensureActive()
            hub.health.finish("trakt:history", result.isSuccess, result.isFailure && libraryHistory.isNotEmpty())
            result.getOrNull()?.let { items ->
                val fresh = hub.enrichAll(items).map { it.toMeta() }
                ensureActive()
                libraryHistory = fresh
                LocalLibrary.saveTrackerHistory(libraryHistory)
                val learned=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){watchedStore.learn(watchedOwner,fresh)}
                ensureActive()
                if(profiles.profileId==watchedOwner){watchedIndex=learned;watchedProfile=watchedOwner}
            }
        }
        saveSnapshot()
        if (only == null) refreshSmartRows()
    }
}

internal fun HomeViewModel.retryTracker(statusId: String) = refreshTrackers(only = setOf(statusId.substringBefore(':')))

internal suspend fun HomeViewModel.syncPendingWatchlist(p: app.ember.tv.data.PendingWatchlist): List<String> =
    watchlistSync.withLock {
        if (p !in LocalLibrary.pending) return@withLock emptyList()
        val ref = p.meta.toRef()
        val targets = p.targets ?: hub.watchlistTargets(ref, p.add)
        // Preserve legacy entries while offline; they have no recorded destination ids yet.
        if (p.targets == null && targets.isEmpty() && ref.hasIds) return@withLock emptyList()
        val result = hub.writeWatchlist(ref, p.add, targets)
        LocalLibrary.remaining(p, result.pending)
        result.succeeded.values.toList()
    }

internal suspend fun HomeViewModel.sendPendingWatchlist() {
    for (p in LocalLibrary.pending) syncPendingWatchlist(p)
}

internal suspend fun HomeViewModel.syncWatchlistChange(add: Boolean, meta: Meta): List<String> {
    val ref = meta.toRef()
    val targets = hub.watchlistTargets(ref, add)
    // Queue before the network call, including an empty target set to supersede an old change.
    LocalLibrary.queue(add, meta, targets)
    val pending = LocalLibrary.pending.first { it.meta.sameTitle(meta) }
    return syncPendingWatchlist(pending)
}

/** These rows take several requests, so they reload at most every 30 minutes unless forced. */
internal suspend fun HomeViewModel.refreshSmartRows(force: Boolean = false) {
    val now = System.currentTimeMillis()
    if (!force && now - smartRowsLoadedAt < 30 * 60 * 1000L) return
    val revision = ++smartRowsRevision
    if (!featureOn(app.ember.tv.data.Features.SMART_ROWS) || !featureOn(app.ember.tv.data.Features.TRACKERS) && !Tmdb.available) {
        smartRows = emptyList()
        return
    }
    // Extra rows switched off in Home layout are not loaded at all.
    val off = smartRowsOff + SmartRows.ALL.map { it.first }.filter { !sectionOn(smartSection(it)) }
    val loaded = SmartRows(hub).load(off).map { r ->
        CatalogRow(addonName = r.source, title = r.title, items = r.items.map { it.toMeta() }, catalogKey = "smart:${r.id}")
    }
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    if (revision != smartRowsRevision) return
    // An empty result may mean a network failure: keep the rows shown and retry on the next visit.
    if (loaded.isNotEmpty()) smartRowsLoadedAt = now
    if (loaded.isNotEmpty() || force) smartRows = loaded
    updateScreensaver()
    saveSnapshot()
}

internal fun HomeViewModel.toggleSmartRow(id: String) {
    smartRowsOff = if (id in smartRowsOff) smartRowsOff - id else smartRowsOff + id
    prefs.smartRowsOff = smartRowsOff
    viewModelScope.launch { refreshSmartRows(force = true) }
}

/** Shows a sign-in code, then waits for approval on the phone. Used by trackers and Plex. */
internal fun HomeViewModel.runLink(
    owner: String,
    name: String,
    begin: suspend () -> LinkSession,
    poll: suspend (LinkSession) -> Boolean,
    report: (String) -> Unit,
    onConnected: () -> Unit,
) {
    link = null
    linkTasks.start(owner) {
        try {
            val s = begin()
            link = LinkUi(name, s.userCode, s.url)
            if (app.ember.tv.tracker.awaitDeviceLink(s, poll)) {
                link = null
                report("$name connected.")
                onConnected()
                return@start
            }
            link = null
            report("The $name code expired. Try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            link = null
            report("Could not connect to $name. ${e.message.orEmpty()}")
        }
    }
}

internal fun HomeViewModel.startLink(trackerId: String) {
    val t = hub.byId(trackerId)
    trackerMessage = null
    runLink(t.id, t.name, { t.beginLink() }, { t.pollLink(it) }, { trackerMessage = it }) {
        trackerRev++
        smartRowsLoadedAt = 0L
        refreshTrackers()
    }
}

internal fun HomeViewModel.cancelLink() {
    linkTasks.cancel()
    link = null
}

internal fun HomeViewModel.linkUsername(trackerId: String, name: String) {
    val t = hub.byId(trackerId)
    link = null
    trackerMessage = "Checking ${t.name} username..."
    linkTasks.start(trackerId) {
        val ok = try { t.linkUsername(name) } catch (e: CancellationException) { throw e }
            catch (e: Exception) { false }
        ensureActive()
        trackerMessage = if (ok) "${t.name} linked." else
            "Could not read that ${t.name} list. Check the name and that the list is public."
        trackerRev++
        if (ok) refreshTrackers()
    }
}

internal fun HomeViewModel.disconnectTracker(id: String) {
    linkTasks.cancel(id)
    hub.byId(id).disconnect()
    trackerMessage = null
    trackerRev++
    smartRowsLoadedAt = 0L
    refreshTrackers()
}

internal fun HomeViewModel.toggleTracker(id: String) {
    trackersOff = if (id in trackersOff) trackersOff - id else trackersOff + id
    prefs.trackersOff = trackersOff
    trackerRev++
    smartRowsLoadedAt = 0L
    refreshTrackers()
}
