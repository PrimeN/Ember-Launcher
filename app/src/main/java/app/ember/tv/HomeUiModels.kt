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

/** What the sign-in dialog shows while the user approves on their phone. */
data class LinkUi(val trackerName: String, val code: String, val url: String)
/** State of the title screen: details, episode picker, sources and subtitles. */
data class DetailsUi(
    val meta: Meta,
    val episodes: List<Episode> = emptyList(),
    val season: Int? = null,
    val episode: Episode? = null,
    val loadingEpisodes: Boolean = false,
    val nativeSeasons: List<Int> = emptyList(),
    val episodeCursor: Int? = null,
    val streams: List<StreamOption> = emptyList(),
    /** Directors, top cast and a trailer from the addon's details, and from TMDB when a key is set. */
    val addonCredits: Credits? = null,
    val tmdbCredits: Credits? = null,
    /** Facts from AniList or TVDB for the line under the title (studio, network, status, next episode). */
    val facts: List<String> = emptyList(),
    /** Which metadata services added something, e.g. ["AniList", "TVDB"]. */
    val metaSources: List<String> = emptyList(),
    /** Ratings by source id, as shown ("8.5", "93%"). */
    val ratings: Map<String, String> = emptyMap(),
    /** Copies on your Plex, Jellyfin or Emby servers. */
    val serverStreams: List<StreamOption> = emptyList(),
    val loadingServer: Boolean = false,
    val subtitles: List<SubtitleOption> = emptyList(),
    val loadingStreams: Boolean = false,
    val note: String? = null,
    /** Where to watch, from Watchmode: every service with the title in your region. */
    val offers: List<Offer> = emptyList(),
    /** Addon links that open in another app (externalUrl, ytId, #73), shown with the services. */
    val addonLinks: List<Offer> = emptyList(),
    val loadingOffers: Boolean = false,
) {
    val seasons: List<Int> get() = nativeSeasons.ifEmpty { episodes.map { it.season }.distinct() }

    /** TMDB's people (they have photos and characters); the addon's trailer first, as Stremio plays it. */
    val credits: Credits?
        get() {
            if (addonCredits == null && tmdbCredits == null) return null
            // #263: with addon metadata first, the addon's cast is kept when it gives one.
            val addonPeople = addonCredits?.people?.ifEmpty { null }
            val tmdbPeople = tmdbCredits?.people?.ifEmpty { null }
            return Credits(
                // The addon's people, with TMDB's photos and characters where the addon has none.
                people = (if (app.ember.tv.data.AddonMetadata.first) addonPeople?.let { app.ember.tv.data.AddonCast.withFaces(it, tmdbPeople.orEmpty()) } ?: tmdbPeople
                    else tmdbPeople ?: addonPeople).orEmpty(),
                trailer = addonCredits?.trailer ?: tmdbCredits?.trailer,
            )
        }
}
