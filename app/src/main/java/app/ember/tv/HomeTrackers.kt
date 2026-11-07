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
import app.ember.tv.tracker.describe
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
        hub.quiet = hub.disabled
        hub.health.retain(emptySet())
        continueItems = emptyList()
        trackerWatchlist = emptyList()
        libraryHistory = emptyList()
        viewModelScope.launch { refreshSmartRows(force = true) }
        return
    }
    hub.disabled = trackersOff
    hub.quiet = trackersQuiet
    if (hub.trakt() == null) {
        libraryHistory = emptyList()
        LocalLibrary.saveTrackerHistory(emptyList())
    }
    val watchedOwner=profiles.profileId
    trackerRefreshJob = viewModelScope.launch {
        // Watchlist changes made while a tracker could not be reached go out first, then the
        // watched marks, ratings and finished plays that were owed (#89).
        if (only == null) sendPendingWatchlist()
        if (only == null) hub.sendQueued()
        // Account names for Settings, read once after sign-in (#85).
        if (only == null && hub.loadAccountNames()) trackerRev++
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
        // Everything you've watched on your trackers, for watched marks (#83). Each service only
        // downloads its lists again when its history changed.
        if (only == null) hub.watched()?.let { keys ->
            ensureActive()
            remoteWatched = keys
            withContext(Dispatchers.IO) { remoteWatchedFile.write(keys) }
        }
        saveSnapshot()
        if (only == null) refreshSmartRows()
        if (only == null) syncTrackers()
    }
}

private fun Meta.syncItem() = app.ember.tv.tracker.SyncItem.of(type == "movie", imdb, tmdbId, anime = type == "anime")

/**
 * Keep trackers in sync (#90): one round, at most every 15 minutes unless [force]. The first
 * round after switching it on copies everything across (the back-fill).
 */
internal fun HomeViewModel.syncTrackers(force: Boolean = false, allowMassRemoval: Boolean = false) {
    if (!trackerSyncOn || !personalOn || !featureOn(Features.TRACKERS) || trackerSyncBusy) return
    val now = System.currentTimeMillis()
    if (!force && now - trackerSyncAt < 15 * 60_000L) return
    val trackers = hub.trackers.filter { it.syncs && it.isConnected() && it.id !in hub.quiet }
    if (trackers.isEmpty()) { trackerSyncStatus = "Connect Trakt, Simkl or WeTrakr to sync."; return }
    trackerSyncAt = now
    trackerSyncBusy = true
    trackerSyncStatus = "Syncing…"
    val profile = profiles.profileId
    val local = app.ember.tv.tracker.TrackerSyncEngine.Local(
        id = app.ember.tv.tracker.TrackerSyncEngine.localId(profile),
        watchlist = watchlist.mapNotNull { it.syncItem() },
        removeWatched = { items -> viewModelScope.launch { forgetWatched(profile, items) } },
        removeFromWatchlist = { items -> viewModelScope.launch { removeFromLocalWatchlist(profile, items) } },
    )
    viewModelScope.launch {
        try {
            val r = withContext(Dispatchers.Default) { trackerSync.run(trackers, local, allowMassRemoval) }
            trackerSyncHeld = r.held
            val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
            trackerSyncStatus = buildString {
                append("Synced at $time")
                if (r.copied > 0) append(" · copied ${r.copied}")
                if (r.removed > 0) append(" · removed ${r.removed}")
                if (r.copied + r.removed == 0) append(" · everything matches")
                if (r.failed.isNotEmpty()) append(". Couldn't reach ${r.failed.joinToString(" and ")}; it will try again.")
                r.refused.forEach { append(" ").append(it) }
            }
            if (r.copied + r.removed > 0) { trackersAt = 0L; smartRowsLoadedAt = 0L; refreshTrackers() }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            trackerSyncStatus = "Sync stopped: ${e.message.orEmpty()}"
        } finally { trackerSyncBusy = false }
    }
}

/** Switches syncing on (starting with the back-fill) or off. */
internal fun HomeViewModel.toggleTrackerSync() {
    trackerSyncOn = !trackerSyncOn
    prefs.trackerSync = trackerSyncOn
    trackerSyncHeld = emptyMap()
    if (trackerSyncOn) syncTrackers(force = true) else trackerSyncStatus = null
}

/** Mirrors removals that were held back because a tracker suddenly reported a large drop. */
internal fun HomeViewModel.applyHeldRemovals() {
    trackerSyncHeld = emptyMap()
    syncTrackers(force = true, allowMassRemoval = true)
}

/** A title unwatched on a tracker loses Ember's watched mark too (#90). */
private suspend fun HomeViewModel.forgetWatched(profile: String, items: List<app.ember.tv.tracker.SyncItem>) {
    val keys = items.flatMapTo(HashSet()) { it.keys }
    if (keys.isEmpty()) return
    val loaded = withContext(Dispatchers.IO) { watchedStore.forget(profile, keys) }
    if (profiles.profileId == profile) { watchedIndex = loaded; watchedProfile = profile }
    remoteWatched = remoteWatched - keys
}

/** A title taken off the watchlist on a tracker leaves Ember's watchlist too (#90). */
private fun HomeViewModel.removeFromLocalWatchlist(profile: String, items: List<app.ember.tv.tracker.SyncItem>) {
    if (profiles.profileId != profile) return
    val gone = app.ember.tv.tracker.SyncIndex(items)
    val kept = watchlist.filterNot { m -> m.syncItem()?.let { it in gone } == true }
    if (kept.size == watchlist.size) return
    watchlist = kept
    prefs.watchlist = kept
    trackerWatchlist = trackerWatchlist.filterNot { m -> m.syncItem()?.let { it in gone } == true }
}

/** Loads the last known tracker watched marks at start, before any network (#83). */
internal suspend fun HomeViewModel.restoreRemoteWatched() {
    val keys = withContext(Dispatchers.IO) { remoteWatchedFile.read() }
    if (remoteWatched.isEmpty()) remoteWatched = keys
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
        // A refusal is final (#82): it is not queued again, and the notice says why.
        result.refused.values.firstOrNull()?.let { watchlistRefusal = it }
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
    remoteWatched = emptySet()
    // Another account signed in later must not be compared with this one (#90).
    viewModelScope.launch(Dispatchers.IO) { trackerSnapshots.forget(id) }
    viewModelScope.launch(Dispatchers.IO) { remoteWatchedFile.clear() }
    trackerMessage = null
    trackerRev++
    smartRowsLoadedAt = 0L
    refreshTrackers()
}

/** "Report what I watch" for one tracker, separate from showing it on Home (#80). */
internal fun HomeViewModel.toggleTrackerReports(id: String) {
    trackersQuiet = if (id in trackersQuiet) trackersQuiet - id else trackersQuiet + id
    prefs.trackersQuiet = trackersQuiet
    hub.quiet = trackersQuiet
    trackerRev++
}

internal fun HomeViewModel.toggleTracker(id: String) {
    trackersOff = if (id in trackersOff) trackersOff - id else trackersOff + id
    prefs.trackersOff = trackersOff
    trackerRev++
    smartRowsLoadedAt = 0L
    refreshTrackers()
}

/** True when the title can be rated on a connected tracker (#84). */
internal fun HomeViewModel.canRate(meta: Meta): Boolean =
    personalOn && featureOn(Features.TRACKERS) && hub.canRate && meta.toRef().hasIds

/** Reads your rating for the title from your trackers (#84). */
internal fun HomeViewModel.loadMyRating(meta: Meta) {
    if (!canRate(meta)) return
    val id = meta.id
    val owner = profiles.profileId
    viewModelScope.launch {
        val rating = hub.ratingOf(meta.toRef())
        ensureActive()
        if (profiles.profileId == owner && myRating?.first != id) myRating = id to rating
    }
}

/** Sends your rating, 1 to 10, or clears it, on every tracker that takes ratings (#84). */
internal fun HomeViewModel.rateTitle(meta: Meta, rating: Int?) {
    if (!canRate(meta)) return
    myRating = meta.id to rating
    val owner = profiles.profileId
    viewModelScope.launch {
        val out = hub.rate(meta.toRef(), rating?.coerceIn(1, 10))
        ensureActive()
        if (profiles.profileId == owner) notice = (if (rating == null) "Rating cleared." else "Rated $rating out of 10.") + out.describe()
    }
}
