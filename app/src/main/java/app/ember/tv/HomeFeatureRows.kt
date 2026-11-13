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

internal fun HomeViewModel.toggleFeature(id: String) {
    featuresOff = if (id in featuresOff) featuresOff - id else featuresOff + id
    prefs.featuresOff = featuresOff
    when (id) {
        app.ember.tv.data.Features.TRACKERS, app.ember.tv.data.Features.SMART_ROWS -> {
            smartRowsLoadedAt = 0L
            refreshTrackers()
        }
        app.ember.tv.data.Features.SERVERS -> refreshServers(force = true)
        app.ember.tv.data.Features.LIVE -> if (featureOn(id)) iptv.refresh(force = false)
    }
}

internal fun HomeViewModel.heroRows(source: String?): List<CatalogRow> =
    if (source == null || rows.any { HeroBanner.sourceKey(it) == source }) rows else rows + listOfNotNull(featuredRows[source])

internal suspend fun HomeViewModel.loadFeaturedRows(sources: Set<String>, defs: List<CatalogDef>, onHome: Set<String>, revision: Long) {
    featuredRows = featuredRows.filterKeys { it in sources && it !in onHome }
    for (key in sources - onHome) {
        if (featuredRows[key]?.let { featuredRevision[key] == revision } == true) continue
        val def = defs.firstOrNull { it.key == key && it.featurable } ?: continue
        val items = kotlinx.coroutines.withTimeoutOrNull(addonTimeout * 1000L) {
            runCatching { client.browse(def) }.getOrNull()
        }?.let { filterKidsItems(it) } ?: continue
        featuredRevision[key] = revision
        featuredRows = featuredRows + (key to CatalogRow(def.addon.name, def.originalLabel, items, catalogKey = def.key))
    }
}

internal fun HomeViewModel.enableTab(id: String) {
    val enabled = NavTabs.enable(nav, id)
    if (enabled != nav) {
        nav = enabled
        prefs.nav = nav
    }
}

internal fun HomeViewModel.toggleTab(id: String) {
    if (NavTabs.locked(id)) return
    if (id == NavTabs.LIVE) restoreLiveDefault = false
    nav = nav.map { if (it.first == id) it.first to !it.second else it }
    prefs.nav = nav
}

internal fun HomeViewModel.moveTab(id: String, delta: Int) {
    val i = nav.indexOfFirst { it.first == id }
    val j = i + delta
    if (i < 0 || j !in nav.indices) return
    val list = nav.toMutableList()
    val moved = list[i]
    list[i] = list[j]
    list[j] = moved
    nav = list
    prefs.nav = nav
}

internal fun HomeViewModel.resetTabs() {
    restoreLiveDefault = false
    nav = NavTabs.ALL.map { it.id to it.shownByDefault }
    prefs.nav = nav
}

internal fun HomeViewModel.toggleBackgroundRefresh() {
    backgroundRefresh = !backgroundRefresh
    prefs.backgroundRefresh = backgroundRefresh
    app.ember.tv.work.RefreshWorker.schedule(getApplication<Application>())
}
