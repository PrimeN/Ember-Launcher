package app.ember.tv

import app.ember.tv.data.playbackKey

import kotlinx.coroutines.sync.withLock
import app.ember.tv.data.WatchedKeys
import android.app.Application
import androidx.lifecycle.viewModelScope
import app.ember.tv.data.LocalLibrary
import app.ember.tv.data.Meta
import app.ember.tv.data.LocalActivity
import app.ember.tv.player.PlayRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.ember.tv.tracker.Kind
import app.ember.tv.tracker.TrackedItem
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

// HomeViewModel: watchlist, Continue Watching, resume labels and marking titles watched.
internal fun HomeViewModel.isSaved(meta: Meta) = meta in savedTitles

/** Saves or removes on this device and on every connected tracker that has a watchlist. */
internal fun HomeViewModel.toggleWatchlist(meta: Meta) {
    val owner = profiles.profileId
    if (isSaved(meta)) {
        val remote = serverWatchlistItems.filter { it.sameTitle(meta) }
        val serverIdentity = servers.accountFingerprint()
        LocalLibrary.hideList("watchlist", meta)
        watchlist = watchlist.filterNot { it.sameTitle(meta) }
        trackerWatchlist = trackerWatchlist.filterNot { it.sameTitle(meta) }
        prefs.watchlist = watchlist
        viewModelScope.launch {
            remote.forEach { item -> runCatching {
                if (servers.accountFingerprint() != serverIdentity) return@forEach
                if (item.id.startsWith("plexwatchlist:")) servers.plex.removeFromWatchlist(item.id.removePrefix("plexwatchlist:"))
                else item.serverRef?.let { servers.favorite(it, false) }
            }.onFailure { ensureActive() } }
            watchlistRefusal = null
            val names = syncWatchlistChange(add = false, meta = meta)
            if (profiles.profileId == owner) notice = "Removed from watchlist" + names.syncedSuffix(meta) + watchlistRefusal?.let { " $it" }.orEmpty()
            LocalLibrary.saveTrackerWatchlist(trackerWatchlist)
        }
    } else {
        LocalLibrary.revealList("watchlist", meta)
        watchlist = watchlist + meta.withoutServerSecrets()
        prefs.watchlist = watchlist
        viewModelScope.launch {
            watchlistRefusal = null
            val names = syncWatchlistChange(add = true, meta = meta)
            if (profiles.profileId == owner) notice = "Added to watchlist" + names.syncedSuffix(meta) + watchlistRefusal?.let { " $it" }.orEmpty()
        }
    }
}

/** True for items that came from a tracker's Continue watching. */
internal fun HomeViewModel.isContinueItem(meta: Meta) = !LocalLibrary.isListHidden("continue", meta) && (
    LocalLibrary.inProgress.any { it.sameTitle(meta) } || continueItems.any { it.sameTitle(meta) } ||
        serverRows.any { row -> row.inProgress && row.items.any { it.sameTitle(meta) } })

internal fun HomeViewModel.removeFromContinue(meta: Meta) {
    val trackerKeys = continueItems.filter { it.sameTitle(meta) }.map { it.id }
    val serverRef = meta.serverRef ?: serverRows.filter { it.inProgress }.flatMap { it.items }.firstOrNull { it.sameTitle(meta) }?.serverRef
    val owner = profiles.profileId
    val serverIdentity = servers.accountFingerprint()
    LocalLibrary.hideList("continue", meta)
    continueItems = continueItems.filterNot { it.sameTitle(meta) }
    LocalLibrary.saveTrackerProgress(continueItems)
    notice = "Removed from Continue watching."
    viewModelScope.launch {
        trackerKeys.forEach { runCatching { hub.removePlayback(it) }.onFailure { ensureActive() } }
        if (serverRef != null && servers.accountFingerprint() == serverIdentity)
            runCatching { servers.hideResume(serverRef) }.onFailure { ensureActive() }
        if (profiles.profileId == owner) notice = "Removed from Continue watching on Ember."
    }
}

/** "Resume from 42:10" (or "Resume at 37%") when [req] has a resume point, else null. */
internal fun HomeViewModel.resumeLabel(req: app.ember.tv.player.PlayRequest): String? {
    val saved = app.ember.tv.player.PositionStore(getApplication<Application>(),LocalActivity.scope(profiles.profile)).get(req.resumeKey)
    return app.ember.tv.data.ResumeAction.label(saved,req.resumeFraction)
}

/**
 * Marks the open movie, or the chosen episode, as watched: on your trackers (Trakt, Simkl,
 * WeTrakr), on your media server when it has the title, and on this TV.
 */
internal fun HomeViewModel.markWatched() {
    val d = details ?: return
    val m = d.meta
    val ep = d.episode
    if(detailsWatched){setTitleWatched(if(ep!=null)WatchedKeys.episode(m,ep) else m,false);return}
    if(m.type!="movie" && ep==null && m.season==null){setTitleWatched(m,true);return}
    markWatchedItem(m, ep?.season ?: m.season, ep?.episode ?: m.episode, m.playbackKey(ep), ep?.serverRef ?: d.serverStreams.firstOrNull()?.serverRef ?: m.serverRef)
}

internal fun HomeViewModel.markTitleWatched(meta: Meta) {
    if(isWatched(meta)){setTitleWatched(meta,false);return}
    if (meta.type != "movie" && (meta.season == null || meta.episode == null)) {setTitleWatched(meta,true);return}
    markWatchedItem(meta, meta.season, meta.episode,
        if (meta.type == "movie" || app.ember.tv.mediaserver.ServerRef.of(meta) != null) meta.playbackKey()
        else "${meta.imdb ?: meta.id}:${meta.season}:${meta.episode}", meta.serverRef)
}

internal fun HomeViewModel.markWatchedItem(m: Meta, season: Int?, episode: Int?, key: String, serverRef: String?) {
    val activityScope=LocalActivity.scope(profiles.profile)
    val serverIdentity=servers.accountFingerprint()
    app.ember.tv.player.PositionStore(getApplication<Application>(),activityScope).clear(key)
    val activeProfile = profiles.profileId
    viewModelScope.launch {
        val marked=m.copy(season=season,episode=episode)
        val loaded=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){watchedStore.mark(activeProfile,marked,true)}
        if(profiles.profileId!=activeProfile)return@launch
        watchedIndex=loaded
        watchedProfile=activeProfile
        // Sent as history (#79): a 100% scrobble is ignored by Trakt when it was scrobbled recently.
        val reported = if (personalOn) hub.markWatched(m.toRef(), season, episode, true).sent else emptyList()
        val onServer = serverRef?.let {ref->watchedServerMutex.withLock {profiles.profileId==activeProfile && isWatched(marked) && servers.accountFingerprint()==serverIdentity && servers.markWatched(ref)}} == true
        ensureActive()
        if (profiles.profileId != activeProfile || !isWatched(marked)) return@launch
        notice = "Marked as watched on this TV." +
            if (reported.isNotEmpty()) " Reported to ${reported.joinToString()}${if (onServer) " and your server" else ""}."
            else if (onServer) " Updated on your server." else ""
        if (season == null) continueItems = continueItems.filterNot { it.id == m.id }
        LocalLibrary.started(key, m.copy(season = season, episode = episode),activityScope)
        LocalLibrary.finished(key,activityScope)
        refreshTrackers()
    }
}
