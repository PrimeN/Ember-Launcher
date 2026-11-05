package app.ember.tv

import app.ember.tv.data.KidsContentPolicy
import android.app.Application
import androidx.lifecycle.viewModelScope
import app.ember.tv.screensaver.ArtSources
import app.ember.tv.screensaver.Photos
import app.ember.tv.screensaver.ScreensaverFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

// HomeViewModel: screensaver sources, photo folders and daily art collections.
internal fun HomeViewModel.toggleScreensaverSource(id: String) {
    val turningOn = id !in screensaverSources
    screensaverSources = if (turningOn) screensaverSources + id else screensaverSources - id
    feed.sources = screensaverSources
    if (turningOn && (id == ScreensaverFeed.ART || id == ScreensaverFeed.SPACE)) refreshCollections(force = true)
    if (id == ScreensaverFeed.PHOTOS) refreshPhotos()
}

/** Re-checks picture access and lists folders. Called after the permission prompt too. */
internal fun HomeViewModel.refreshPhotos() {
    photosJob?.cancel()
    val ctx = getApplication<Application>()
    photosAllowed = Photos.granted(ctx)
    if (!photosAllowed) {
        photoFolders = emptyList()
        return
    }
    photosJob = viewModelScope.launch {
        photoFolders = withContext(Dispatchers.IO) { runCatching { Photos.folders(ctx) }.getOrDefault(emptyList()) }
    }
}

/** null means all folders. */
internal fun HomeViewModel.updatePhotoFolder(name: String?) {
    photoFolder = name
    feed.photoFolder = name
}

/** Fetches new paintings and space pictures once a day, only for the sources that are on. */
internal fun HomeViewModel.refreshCollections(force: Boolean = false) {
    val on = feed.sources
    if (ScreensaverFeed.ART !in on && ScreensaverFeed.SPACE !in on) return
    val now = System.currentTimeMillis()
    if (!force && now - feed.collectionsFetchedAt < 24 * 60 * 60 * 1000L) return
    feed.collectionsFetchedAt = now
    artCollectionsJob?.cancel()
    artCollectionsJob = viewModelScope.launch {
        if (ScreensaverFeed.ART in on) {
            val art = runCatching { ArtSources.museum() }.getOrNull()
            ensureActive()
            art?.takeIf { it.isNotEmpty() }?.let { feed.saveArt(it) }
        }
        if (ScreensaverFeed.SPACE in on) {
            val space = runCatching { ArtSources.space() }.getOrNull()
            ensureActive()
            space?.takeIf { it.isNotEmpty() }?.let { feed.saveSpace(it) }
        }
    }
}

/** Gives the screensaver fresh backdrops from what Home is showing. */
internal fun HomeViewModel.updateScreensaver() {
    feed.update((heroItems + serverRows.flatMap { it.items } + smartRows.flatMap { it.items } + rows.flatMap { it.items }).filter(::contentAllowed),clearEmpty=KidsContentPolicy.restricted(profile))
}
