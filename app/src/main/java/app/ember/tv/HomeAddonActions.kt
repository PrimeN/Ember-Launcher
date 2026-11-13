package app.ember.tv

import app.ember.tv.data.Collections as CollectionStore
import app.ember.tv.data.CollectionSource
import app.ember.tv.data.HomeWidget
import app.ember.tv.data.HomeWidgets
import app.ember.tv.state.WidgetsState
import app.ember.tv.data.HomeTitleVisibility
import app.ember.tv.data.playbackKey
import app.ember.tv.home.LauncherExtrasState
import app.ember.tv.home.PersonalHomes
import app.ember.tv.data.AppRepository
import app.ember.tv.data.appInventoryChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.ember.tv.data.WatchedStore
import app.ember.tv.data.WatchedIndex
import app.ember.tv.tracker.TrackerWatchedFile
import app.ember.tv.tracker.SyncSnapshots
import app.ember.tv.tracker.TrackerSyncEngine
import app.ember.tv.tracker.describe
import app.ember.tv.data.WatchedKeys
import app.ember.tv.data.KidsContentPolicy
import app.ember.tv.data.KidsMetadata
import app.ember.tv.data.mediaKey
import app.ember.tv.data.TitleIndex
import app.ember.tv.data.AppAccess
import app.ember.tv.data.DevicePerformance
import app.ember.tv.state.UiRefreshQueue
import app.ember.tv.state.StartupPresentation
import app.ember.tv.data.mergeTitlesIndexed
import androidx.compose.runtime.derivedStateOf
import app.ember.tv.phone.PhoneSetupController
import app.ember.tv.data.ConfigurationGeneration
import app.ember.tv.discover.ExploreRepository
import app.ember.tv.discover.CollectionExplore
import app.ember.tv.data.AddonAccess
import app.ember.tv.state.ExploreState
import app.ember.tv.state.BackupState
import app.ember.tv.state.CatalogsState
import app.ember.tv.state.TitleState
import app.ember.tv.state.HomeSectionsState
import app.ember.tv.state.LookState
import app.ember.tv.state.DiscoverState
import app.ember.tv.state.SearchProvider
import app.ember.tv.state.SearchState
import app.ember.tv.state.WhereToWatchState
import app.ember.tv.state.PlayerSettingsState
import app.ember.tv.state.MetaSettingsState
import app.ember.tv.state.SourceFormatState
import app.ember.tv.state.ProfilesState
import app.ember.tv.state.IptvSetupState
import app.ember.tv.state.PlayingState
import app.ember.tv.state.CollectionsState
import app.ember.tv.state.SettingsAccess
import app.ember.tv.state.PinState
import app.ember.tv.state.SleepState
import app.ember.tv.state.UpdatesState
import app.ember.tv.state.WeatherState
import app.ember.tv.ui.Ui
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import app.ember.tv.ui.FocusMemory
import app.ember.tv.data.HomeShortcuts
import android.app.Application
import app.ember.tv.state.ServerBrowserState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ember.tv.data.AddonClient
import app.ember.tv.data.LocalLibrary
import app.ember.tv.data.Features
import app.ember.tv.data.AppEntry
import app.ember.tv.data.CatalogRow
import app.ember.tv.data.HeroBanner
import app.ember.tv.data.Meta
import app.ember.tv.data.LocalActivity
import app.ember.tv.data.Prefs
import app.ember.tv.iptv.IptvController
import app.ember.tv.iptv.IptvSource
import app.ember.tv.tracker.TokenStore
import app.ember.tv.data.MetaConfig
import app.ember.tv.data.MetaEnhance
import app.ember.tv.data.MetaExtra
import app.ember.tv.data.RatingSource
import app.ember.tv.data.Ratings
import app.ember.tv.data.PosterConfig
import app.ember.tv.data.Posters
import app.ember.tv.data.Person
import app.ember.tv.data.Credits
import app.ember.tv.data.ApiKeys
import app.ember.tv.data.Backup
import app.ember.tv.data.StreamFormatDefaults
import app.ember.tv.data.StreamFormatter
import android.net.Uri
import kotlinx.serialization.json.JsonObject
import app.ember.tv.data.AddonInfo
import app.ember.tv.data.CatalogDef
import app.ember.tv.data.NavTabs
import app.ember.tv.streaming.Offer
import app.ember.tv.streaming.ServiceApps
import app.ember.tv.streaming.StreamingService
import app.ember.tv.streaming.Watchmode
import app.ember.tv.streaming.TmdbWatch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import app.ember.tv.data.Episode
import app.ember.tv.data.StreamOption
import app.ember.tv.data.StreamParser
import app.ember.tv.data.SubtitleOption
import app.ember.tv.player.PlayRequest
import app.ember.tv.player.PlayerApp
import app.ember.tv.player.PlayerApps
import app.ember.tv.mediaserver.FoundServer
import app.ember.tv.mediaserver.LanDiscovery
import app.ember.tv.mediaserver.MediaServers
import app.ember.tv.mediaserver.ServerQuery
import app.ember.tv.screensaver.ArtSources
import app.ember.tv.screensaver.Photos
import app.ember.tv.screensaver.ScreensaverFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.ember.tv.tracker.Kind
import app.ember.tv.tracker.LinkSession
import app.ember.tv.tracker.LinkTasks
import app.ember.tv.weather.Place
import app.ember.tv.weather.Weather
import app.ember.tv.tracker.SmartRows
import app.ember.tv.tracker.Tmdb
import app.ember.tv.tracker.TitleRef
import app.ember.tv.tracker.TrackedItem
import app.ember.tv.tracker.str
import app.ember.tv.tracker.list
import app.ember.tv.tracker.arr
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import app.ember.tv.tracker.TrackerHub
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

/** Before a change is saved: never save over the stored list before it has been read. */
internal fun HomeViewModel.ensureAddons() { if (!addonsLoaded) { addons = prefs.addons; addonsLoaded = true } }

internal fun HomeViewModel.pauseAddon(url: String, paused: Boolean) {
    if (url !in addons) return
    val key = AddonAccess.key(url)
    pausedAddons = if (paused) pausedAddons + key else pausedAddons - key
    prefs.pausedAddons = pausedAddons
    AddonAccess.paused = pausedAddons
    if (paused) AddonClient.cancel(url)
    collections.closeFolder(); collections.invalidatePages()
    discover.reset(); refreshRows()
    title.details?.let { title.retrySources() }
}

internal fun HomeViewModel.refreshAddon(url: String) {
    if (!addonAvailable(url)) { notice = "Resume this add-on before refreshing it."; return }
    client.forget(url); discover.reset(); collections.invalidatePages(); refreshRows(refetch = true)
}

internal suspend fun HomeViewModel.loadAddonInfos(): List<AddonInfo> = coroutineScope {
    val permits = kotlinx.coroutines.sync.Semaphore(if (lowPowerDevice()) 2 else 4)
    activeAddons.map { url ->
        async { permits.acquire(); try {
            kotlinx.coroutines.withTimeoutOrNull(addonTimeout * 1000L) { runCatching { client.manifest(url) }.getOrNull() }
        } finally { permits.release() } }
    }.awaitAll().filterNotNull()
        // An addon that declares adult content is never used on a kids profile (#71).
        .filterNot { it.adult && profiles.profile.kids }
}

// ---- addons ----
/** Returns an error message, or null when the addon was added. */
internal fun HomeViewModel.addAddon(link: String): String? {
    val url = link.trim().replace("stremio://", "https://")
    if (url.toHttpUrlOrNull()?.pathSegments?.lastOrNull() != "manifest.json") {
        return "The link should end in manifest.json."
    }
    ensureAddons()
    if (url in addons) return "That addon is already added."
    if (!prefs.saveAddons(addons + url)) return "Encrypted storage is unavailable. The add-on was not added."
    addons = addons + url
    catalogs.addonAdded(url)
    discover.reset()
    refreshRows()
    return null
}

/** Gives an addon its own name on Home, title screens and Discover; blank goes back to the manifest's name. */
internal fun HomeViewModel.renameAddon(url: String, name: String) {
    val n = name.trim()
    val map = prefs.addonNames.toMutableMap().apply { if (n.isEmpty()) remove(AddonAccess.key(url)) else put(AddonAccess.key(url), n) }
    prefs.addonNames = map
    AddonClient.renames = map
    discover.reset()
    refreshRows(refetch = false)
}

internal fun HomeViewModel.removeAddon(url: String) {
    ensureAddons()
    if (!prefs.saveAddons(addons - url)) { notice = "Encrypted storage is unavailable. The add-on was kept."; return }
    if (AddonAccess.key(url) in prefs.addonNames) renameAddon(url, "")
    AddonClient.cancel(url); client.forget(url); collections.invalidatePages()
    addons = addons - url
    pausedAddons = pausedAddons - AddonAccess.key(url)
    prefs.pausedAddons = pausedAddons; AddonAccess.paused = pausedAddons
    discover.reset(); refreshRows()
}

/**
 * #267: addons are asked in this order, for title details (after the catalog's own addon) and for
 * sources and subtitles; catalogs never moved by hand follow it too.
 */
internal fun HomeViewModel.moveAddon(url: String, step: Int) {
    ensureAddons()
    val next = AddonOrder.moved(addons, url, step) ?: return
    if (!prefs.saveAddons(next)) { notice = "Encrypted storage is unavailable. The add-on order was kept."; return }
    addons = next
    discover.reset(); refreshRows(refetch = false)
}

/** #267: the addon list with one addon moved by [step] places; null when nothing moves. */
internal object AddonOrder {
    fun moved(list: List<String>, url: String, step: Int): List<String>? {
        val i = list.indexOf(url); if (i < 0) return null
        val j = (i + step).coerceIn(0, list.lastIndex); if (i == j) return null
        return list.toMutableList().apply { add(j, removeAt(i)) }
    }
}

internal fun HomeViewModel.addonLabel(url: String): String = (addonInfo[url] ?: client.cachedManifest(url))?.name ?: "Add-on ${addons.indexOf(url) + 1}"

internal fun HomeViewModel.updateAddonTimeout(seconds: Int) {
    addonTimeout = seconds
    prefs.addonTimeout = seconds
}
