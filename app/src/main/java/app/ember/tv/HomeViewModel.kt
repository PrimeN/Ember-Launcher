package app.ember.tv

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
            return Credits(
                people = tmdbCredits?.people?.ifEmpty { null } ?: addonCredits?.people.orEmpty(),
                trailer = addonCredits?.trailer ?: tmdbCredits?.trailer,
            )
        }
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    internal val prefs = Prefs(app)
    internal val deviceCaps: DevicePerformance.Capabilities by lazy { DevicePerformance.capabilities(app) }
    internal fun lowPowerDevice(): Boolean = deviceCaps.let {
        DevicePerformance.resolve(look.devicePerformance, it.lowRam, it.heapMb, it.totalBytes) == "low"
    }
    internal val refreshQueue = UiRefreshQueue(viewModelScope, ::lowPowerDevice)
    internal val startupPresentation = StartupPresentation()
    internal var uiPresented = false
    internal var cachedHomeLoaded = false
    fun onUiPresented() {
        uiPresented = true
        startupPresentation.firstFrameShown()
        if (cachedHomeLoaded) refreshQueue.presented()
    }
    fun onUiActive(active: Boolean) = refreshQueue.setActive(active)
    fun onUiInteraction() = refreshQueue.interaction()
    internal suspend fun awaitUiPresentation() = refreshQueue.awaitPresentation()
    internal val client = AddonClient()
    val hub = TrackerHub(app)
    internal val linkTasks = LinkTasks(viewModelScope)
    // Capture this before any automatic source addition saves the navigation defaults.
    internal var restoreLiveDefault = !prefs.hasSavedNav

    internal var allApps: List<AppEntry> = emptyList()

    var apps by mutableStateOf<List<AppEntry>>(emptyList()); internal set
    var watchlist by mutableStateOf(prefs.watchlist); internal set
    var hidden by mutableStateOf(prefs.hidden); internal set
    // Encrypted: read on IO as the first startup step (#46), not on the main thread while the
    // view model is built. Everything that uses the list runs after that step.
    var addons by mutableStateOf<List<String>>(emptyList()); internal set
    @Volatile private var addonsLoaded = false
    /** Before a change is saved: never save over the stored list before it has been read. */
    private fun ensureAddons() { if (!addonsLoaded) { addons = prefs.addons; addonsLoaded = true } }
    var pausedAddons by mutableStateOf(prefs.pausedAddons); private set
    val activeAddons: List<String> get() = addons.filter { AddonAccess.key(it) !in pausedAddons }
    init { AddonAccess.paused = pausedAddons }
    fun addonAvailable(url: String) = AddonAccess.key(url) !in pausedAddons
    fun pauseAddon(url: String, paused: Boolean) {
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
    fun refreshAddon(url: String) {
        if (!addonAvailable(url)) { notice = "Resume this add-on before refreshing it."; return }
        client.forget(url); discover.reset(); collections.invalidatePages(); refreshRows(refetch = true)
    }
    /** The banner is a Home section (Settings > Home layout). */
    val heroOn: Boolean get() = sectionOn(HOME_HERO)
    var homeFeatureAction by mutableStateOf<String?>(null)
    var pendingAppLaunch by mutableStateOf<AppEntry?>(null)
    val personalHomes = PersonalHomes(prefs)
    val launcherExtras = LauncherExtrasState(prefs)
    val look = LookState(prefs, viewModelScope)
    internal val watchedStore by lazy {WatchedStore(app)}
    internal val watchedServerMutex=Mutex()
    internal var watchedIndex by mutableStateOf(WatchedIndex())
    internal var watchedProfile by mutableStateOf<String?>(null)
    val watchedReady:Boolean get()=watchedProfile==profiles.profileId
    suspend fun reloadWatched() {
        val profile=profiles.profileId
        val loaded=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){watchedStore.load(profile)}
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if(profiles.profileId==profile){watchedIndex=loaded;watchedProfile=profile}
        if(remoteWatched.isEmpty())restoreRemoteWatched()
    }
    /** What your trackers say you watched (#83); used on profiles with personal rows. */
    internal var remoteWatched by mutableStateOf<Set<String>>(emptySet())
    internal val remoteWatchedFile by lazy { TrackerWatchedFile(java.io.File(app.filesDir, "tracker_watched.txt")) }
    fun isWatched(meta:Meta):Boolean=(if(watchedProfile==profiles.profileId)watchedIndex else WatchedIndex()).watched(meta,if(personalOn)remoteWatched else emptySet())
    fun isEpisodeWatched(show:Meta,ep:Episode):Boolean=isWatched(WatchedKeys.episode(show,ep))
    fun setTitleWatched(meta:Meta,value:Boolean) {
        val profile=profiles.profileId
        val serverIdentity=servers.accountFingerprint()
        viewModelScope.launch {
            val loaded=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){watchedStore.mark(profile,meta,value)}
            if(profiles.profileId!=profile)return@launch
            watchedIndex=loaded
            watchedProfile=profile
            notice=if(value)"Marked as watched on this TV." else "Marked as unwatched on this TV."
            val ref=app.ember.tv.mediaserver.ServerRef.of(meta)?.value
            if(ref!=null)watchedServerMutex.withLock {if(profiles.profileId==profile && isWatched(meta)==value && servers.accountFingerprint()==serverIdentity)servers.markWatched(ref,value)}
            // Your trackers hear about it too, as history rather than a fake scrobble (#79).
            if(personalOn && featureOn(app.ember.tv.data.Features.TRACKERS)) {
                val sent=hub.markWatched(meta.toRef(),meta.season,meta.episode,value)
                if(profiles.profileId==profile)notice=(if(value)"Marked as watched on this TV." else "Marked as unwatched on this TV.")+sent.describe()
            }
        }
    }

    // ---- trackers ----
    var continueItems by mutableStateOf<List<Meta>>(emptyList()); internal set
    var trackerWatchlist by mutableStateOf<List<Meta>>(emptyList()); internal set
    var trackersOff by mutableStateOf(prefs.trackersOff); internal set
    var trackersQuiet by mutableStateOf(prefs.trackersQuiet); internal set
    /** Your tracker rating for the open title: (title id, rating) (#84). */
    var myRating by mutableStateOf<Pair<String, Int?>?>(null); internal set
    /** Keep trackers in sync (#90): the switch, what the last round did, and drops held back. */
    var trackerSyncOn by mutableStateOf(prefs.trackerSync); internal set
    var trackerSyncStatus by mutableStateOf<String?>(null); internal set
    var trackerSyncHeld by mutableStateOf<Map<String, Int>>(emptyMap()); internal set
    var trackerSyncBusy by mutableStateOf(false); internal set
    internal var trackerSyncAt = 0L
    internal val trackerSnapshots by lazy { SyncSnapshots(app.filesDir) }
    internal val trackerSync by lazy { TrackerSyncEngine(trackerSnapshots) }
    /** Why the last watchlist change was turned down by a tracker, if it was (#82). */
    internal var watchlistRefusal: String? = null
    var trackerRev by mutableIntStateOf(0); internal set
    var link by mutableStateOf<LinkUi?>(null); internal set
    var trackerMessage by mutableStateOf<String?>(null); internal set

    /** Local watchlist first, then tracker items that are not already in it (nor waiting to be removed from them). */
    val combinedWatchlist: List<Meta> by derivedStateOf {
            LocalLibrary.version
            if (!personalOn) emptyList()
            else {
                val removing = TitleIndex(LocalLibrary.pending.filter { !it.add }.map { it.meta })
                mergeTitles(
                    watchlist.filter { personalListIncludes("watchlist", it, "ember") },
                    trackerWatchlist.filter { it !in removing && personalListIncludes("watchlist", it, "other") },
                    serverWatchlistItems.filter { personalListIncludes("watchlist", it, "other") },
                ).filterNot { LocalLibrary.isListHidden("watchlist", it) }
            }
    }

    /** One entry per title, the first list winning. */
    internal fun mergeTitles(vararg lists: List<Meta>): List<Meta> = mergeTitlesIndexed(*lists)

    /**
     * In progress: what you played in Ember (kept on the TV, so it works without trackers and
     * offline), then your trackers' Continue watching, then your media servers' Continue.
     */
    // Worked out once per change (not on every redraw): derivedStateOf follows the lists it reads.
    val inProgressItems: List<Meta> by androidx.compose.runtime.derivedStateOf {
        LocalLibrary.version
        if (!personalOn) emptyList()
        else mergeTitles(LocalLibrary.inProgress.filter { personalListIncludes("continue", it, "ember") },
            continueItems.filter { personalListIncludes("continue", it, "other") },
            serverRows.filter { it.inProgress }.flatMap { it.items }.filter { personalListIncludes("continue", it, "other") })
            .filterNot { LocalLibrary.isListHidden("continue", it) }
    }

    /** Recently watched: Ember's own plays, then your Trakt history (last copy kept for offline). */
    val recentItems: List<Meta> by androidx.compose.runtime.derivedStateOf {
        LocalLibrary.version
        if (!personalOn) emptyList() else mergeTitles(LocalLibrary.recent.filter { personalListIncludes("recent", it, "ember") },
            libraryHistory.filter { !LocalLibrary.isHidden(it) && personalListIncludes("recent", it, "trakt") })
    }

    /** Hold OK on a title in In progress, Recently watched or the watchlist page: takes it off that list. */
    fun removeFromList(which: String, meta: Meta) {
        when (which) {
            app.ember.tv.data.NavTabs.PROGRESS -> removeFromContinue(meta)
            app.ember.tv.data.NavTabs.RECENT -> {
                LocalLibrary.hideRecent(meta)
                notice = "Removed from Recently watched."
            }
            else -> if (isSaved(meta)) toggleWatchlist(meta)
        }
    }

    /** Recently watched page: Clear. Ember's finished plays go; Trakt's entries stay hidden here. */
    fun clearRecent() {
        LocalLibrary.clearRecent(recentItems)
        notice = "Recently watched cleared."
    }

    // ---- features that can be switched off (Settings > Features) ----
    var featuresOff by mutableStateOf(prefs.featuresOff); private set

    fun featureOn(id: String) = id !in featuresOff

    fun toggleFeature(id: String) {
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

    internal var trackersAt = 0L
    internal var trackerRefreshJob: Job? = null

    val trackerHealth get() = hub.health.entries.values

    internal val trackersConnected: Boolean
        get() = featureOn(app.ember.tv.data.Features.TRACKERS) && hub.trackers.any { it.isConnected() && it.id !in hub.disabled }

    internal val watchlistSync = kotlinx.coroutines.sync.Mutex()

    // ---- extra Home rows ----
    var smartRows by mutableStateOf<List<CatalogRow>>(emptyList()); internal set
    var smartRowsOff by mutableStateOf(prefs.smartRowsOff); internal set
    internal var smartRowsLoadedAt = 0L
    internal var smartRowsRevision = 0L

    internal fun TrackedItem.toMeta() = Meta(
        // Anime from AniList / MyAnimeList open by their Kitsu id, which anime addons know (#81).
        id = addonId ?: key,
        type = when (kind) {
            Kind.MOVIE -> "movie"
            Kind.SHOW -> "series"
            Kind.ANIME -> "anime"
        },
        name = title.ifBlank { "Untitled" },
        progressSource = source.takeIf { it.isNotBlank() },
        poster = poster,
        description = detail,
        badge = detail,
        releaseInfo = year?.toString(),
        progress = progress,
        imdbId = imdbId,
        tmdbId = tmdbId,
        season = season,
        episode = episode,
    )

    // ---- details, sources and subtitles ----

    // ---- the title screen: details, sources, subtitles (state/TitleState.kt) ----
    val title: TitleState = TitleState(
        scope = viewModelScope,
        client = client,
        servers = { servers },
        watch = { watch },
        metadata = { metadata },
        featureOn = ::featureOn,
        addonInfos = { loadAddonInfos() },
        addonTimeout = { addonTimeout },
        noteSlow = ::noteSlow,
        serversConnected = { plexConnected || serverAccounts.isNotEmpty() },
    )
    val details: DetailsUi? get() = title.details?.takeIf {contentAllowed(it.meta)}
    fun contentAllowed(meta:Meta)=KidsContentPolicy.allows(profile,meta)
    val liveAllowed:Boolean get()=KidsContentPolicy.liveAllowed(profile)
    internal val kidsMetadata by lazy {KidsMetadata()}
    internal suspend fun filterKidsItems(items:List<Meta>):List<Meta> {
        val owner=profile
        val shown=kidsMetadata.filter(owner,items)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        return if(profile==owner)shown else emptyList()
    }
    /** Filmographies follow the same metadata enrichment and profile lock as Home/Search. */
    suspend fun filterPersonTitles(items:List<Meta>):List<Meta> = filterKidsItems(items)
    fun openDetails(meta: Meta) {
        if(contentAllowed(meta))title.open(meta) else notice="This title is outside this profile's content lock."
    }
    fun selectSeason(season: Int) = title.selectSeason(season)
    fun selectEpisode(ep: Episode) = title.selectEpisode(ep)
    fun moreServerEpisodes() = title.moreServerEpisodes()
    fun closeDetails() = title.close()

    // ---- playing (players and autoplay in state/PlayingState.kt) ----
    val playing = PlayingState(getApplication<Application>(), prefs)

    /** Builds what the player needs for a source on the open title screen. */
    fun playRequest(s: StreamOption): PlayRequest? {
        val d = details ?: return null
        if(!contentAllowed(d.meta))return null
        serverPlaybackDirty = true
        val m = d.meta
        val ep = d.episode
        if (d.loadingEpisodes && ep == null) return null
        // Use the tracker's progress only when it belongs to this exact episode.
        val trackerProgress = m.progress?.takeIf { ep == null || (ep.season == m.season && ep.episode == m.episode) }
        // Kept on the TV for In progress and Recently watched (the player adds how far you get).
        LocalLibrary.started(
            m.playbackKey(ep),
            if (ep == null) m else m.copy(serverRef = ep.serverRef ?: m.serverRef, serverSeriesRef = ep.serverSeriesRef ?: m.serverSeriesRef, season = ep.season, episode = ep.episode, badge = "S${ep.season} · E${ep.episode}" + ep.title.takeIf { it.isNotBlank() }?.let { "  $it" }.orEmpty()),
        )
        return PlayRequest(
            stream = s,
            title = when {
                ep != null -> "${m.name} S${ep.season}E${ep.episode}"
                m.season != null && m.episode != null -> "${m.name} S${m.season}E${m.episode}"
                else -> m.name
            },
            kind = when (m.type) {
                "movie" -> Kind.MOVIE
                "anime" -> Kind.ANIME
                else -> Kind.SHOW
            },
            imdbId = m.imdb,
            tmdbId = m.tmdbId,
            season = ep?.season ?: m.season,
            episode = ep?.episode ?: m.episode,
            resumeKey = m.playbackKey(ep),
            resumeFraction = ep?.progress ?: trackerProgress,
            subtitles = (s.serverPlayback?.parts?.firstOrNull()?.subtitles.orEmpty() + d.subtitles).distinctBy { it.url },
            seriesName = m.name,
            serverRef = s.serverRef,
            nextEpisodes = if (ep == null) emptyList() else {
                val i = d.episodes.indexOfFirst { it.id == ep.id }
                if (i < 0) emptyList() else d.episodes.drop(i + 1).take(30)
            },
            details = app.ember.tv.player.PlayerDetails(
                name = m.name,
                logo = app.ember.tv.data.Posters.logoUrl(m, app.ember.tv.data.PosterConfig()),
                year = m.releaseInfo?.take(4),
                rating = d.ratings["imdb"]?.let { "IMDb $it" } ?: d.ratings.entries.firstOrNull()?.let { "${it.key.uppercase()} ${it.value}" },
                episodeTitle = ep?.title?.takeIf { it.isNotBlank() },
                synopsis = ep?.overview?.takeIf { !it.isNullOrBlank() } ?: m.description,
                cast = d.credits?.people.orEmpty().take(20),
                backdrop = m.background,
            ),
            profileId = profiles.profileId,
            contentMeta = m,
        )
    }

    internal suspend fun loadAddonInfos(): List<AddonInfo> = coroutineScope {
        val permits = kotlinx.coroutines.sync.Semaphore(if (lowPowerDevice()) 2 else 4)
        activeAddons.map { url ->
            async { permits.acquire(); try {
                kotlinx.coroutines.withTimeoutOrNull(addonTimeout * 1000L) { runCatching { client.manifest(url) }.getOrNull() }
            } finally { permits.release() } }
        }.awaitAll().filterNotNull()
            // An addon that declares adult content is never used on a kids profile (#71).
            .filterNot { it.adult && profiles.profile.kids }
    }

    // ---- media servers ----
    val servers = MediaServers(app)
    val serverBrowser = ServerBrowserState(app, servers, viewModelScope)
    var serverRows by mutableStateOf<List<CatalogRow>>(emptyList()); internal set
    val serverSections get() = app.ember.tv.data.ServerPresentation.sections(serverRows.map { row ->
        val list = if (row.inProgress) "continue" else if (row.catalogKey?.endsWith(":watchlist") == true) "watchlist" else null
        if (list == null) row else row.copy(items = row.items.filterNot { LocalLibrary.isListHidden(list, it) })
    }, serverLibraries)
    val serverHeroItems get() = app.ember.tv.data.ServerPresentation.hero(serverSections,look.serverSectionsOff,look.serverHeroSource)
    var serverAccounts by mutableStateOf(servers.accounts.servers()); internal set
    var plexConnected by mutableStateOf(servers.plex.connected); internal set
    var plexUserName by mutableStateOf(servers.accounts.plexUserName); internal set
    internal var serverUserJob: Job? = null
    var serverMessage by mutableStateOf<String?>(null); internal set
    /** Servers already reported as having signed Ember out, so the notice shows once (#94). */
    internal var signedOutNoticed: Set<String> = emptySet()
    /** Provider ids ("plex" or a server key) that refused Ember's sign-in on the last load (#94). */
    val serversSignedOut: Set<String> get() = servers.signedOut()
    internal var serverPlaybackDirty = false
    internal var serversLoadedAt = 0L
    internal var serverRefreshJob: Job? = null
    internal var initialSyncJob:Job?=null
    internal var serverRefreshRevision=0L
    internal var librariesRevision=0L

    // ---- My media tab: your server libraries ----
    val serverHealth get() = servers.health.entries.values

    var serverLibraries by mutableStateOf<List<app.ember.tv.mediaserver.ServerLibrary>>(emptyList()); internal set
    var librariesLoading by mutableStateOf(false); internal set
    internal var librariesAt = 0L
    internal var librariesJob: Job? = null

    // ---- weather (state/WeatherState.kt) ----
    internal val feed = ScreensaverFeed(app)
    val weather = WeatherState(app, prefs, viewModelScope, feed)

    // ---- finding servers on the network ----
    var found by mutableStateOf<List<FoundServer>?>(null); internal set
    var discovering by mutableStateOf(false); internal set

    // ---- screensaver sources ----
    var screensaverSources by mutableStateOf(feed.sources); internal set
    var photoFolder by mutableStateOf(feed.photoFolder); internal set
    var photoFolders by mutableStateOf<List<Pair<String, Int>>>(emptyList()); internal set
    var photosAllowed by mutableStateOf(false); internal set
    internal var photosJob: Job? = null
    internal var artCollectionsJob: Job? = null

    val appAccess: AppAccess by derivedStateOf { AppAccess(apps, profile.apps, hidden, appsPageOff) }
    val visibleApps: List<AppEntry> by derivedStateOf { appAccess.home }

    /** Up to ten allowed titles from the chosen Home catalog, or a balanced automatic mix. */
    /**
     * First pages of hero/carousel catalogs that are not on Home (#68), so any catalog can feed the
     * hero. Loaded by [loadFeaturedRows]; a catalog on Home uses its Home row instead.
     */
    var featuredRows by mutableStateOf<Map<String, CatalogRow>>(emptyMap()); private set
    private fun heroRows(source: String?): List<CatalogRow> =
        if (source == null || rows.any { HeroBanner.sourceKey(it) == source }) rows else rows + listOfNotNull(featuredRows[source])
    val heroItems: List<Meta> by derivedStateOf { HeroBanner.items(heroRows(look.heroCatalog), look.heroCatalog, ::homeTitleVisible) }
    /** Light's own carousel catalog (#61): empty when it mixes automatically or the catalog is unavailable. */
    val lightFeaturedItems: List<Meta> by derivedStateOf {
        val source = look.lightHome.heroCatalog
        val from = heroRows(source)
        if (source == null || from.none { HeroBanner.sourceKey(it) == source }) emptyList()
        else HeroBanner.items(from, source, ::homeTitleVisible)
    }
    private suspend fun loadFeaturedRows(sources: Set<String>, defs: List<CatalogDef>, onHome: Set<String>, revision: Long) {
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
    private val featuredRevision = java.util.concurrent.ConcurrentHashMap<String, Long>()
    init {
        // Dispatched, not immediate: it reads state declared further down this class.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            // The catalog list's identity changes on every refresh; comparing it by value would
            // compare every addon manifest.
            androidx.compose.runtime.snapshotFlow {
                Triple(listOfNotNull(look.heroCatalog, look.lightHome.heroCatalog).toSet(), System.identityHashCode(catalogs.allCatalogs).toLong(),
                    rows.mapNotNull { it.catalogKey }.toSet())
            }.collectLatest { (sources, revision, home) -> loadFeaturedRows(sources, catalogs.allCatalogs, home, revision) }
        }
    }

    internal var appsJob: Job? = null
    internal var appsLoadedAt: Long? = null
    internal val packagesChanged=object:android.content.BroadcastReceiver() {
        override fun onReceive(context:android.content.Context?,intent:android.content.Intent?) {
            if(appInventoryChanged(intent?.action,intent?.getBooleanExtra(android.content.Intent.EXTRA_REPLACING,false)==true))refreshApps(force=true)
        }
    }
    internal val localeChanged=object:android.content.BroadcastReceiver() {
        override fun onReceive(context:android.content.Context?,intent:android.content.Intent?) {
            if(intent?.action==android.content.Intent.ACTION_LOCALE_CHANGED)refreshApps(force=true)
        }
    }
    override fun onCleared() {
        runCatching {getApplication<Application>().unregisterReceiver(packagesChanged)}
        runCatching {getApplication<Application>().unregisterReceiver(localeChanged)}
    }

    // ---- addon catalogs: Home rows and Discover (state/CatalogsState.kt) ----
    val catalogs: CatalogsState = CatalogsState(
        prefs = prefs,
        scope = viewModelScope,
        client = client,
        addons = { activeAddons },
        profile = { profile },
        profileAllows = { profileAllows(it) },
        addonInfos = { loadAddonInfos() },
        addonTimeout = { addonTimeout },
        noteSlow = ::noteSlow,
        discover = { discover },
        onRows = {
            sections.migrateCatalogLayout()
            updateScreensaver()
            saveSnapshot()
        },
        onLiveAdded = { enableTab(NavTabs.LIVE) },
        restoreLiveDefault = { restoreLiveDefault },
        requestParallelism = { if (lowPowerDevice()) 2 else 4 },
    ).apply {filterItems=::filterKidsItems}
    val rows: List<CatalogRow> get() = if(!KidsContentPolicy.restricted(profile))catalogs.rows else catalogs.rows.mapNotNull {row->row.copy(items=row.items.filter(::contentAllowed)).takeIf {it.items.isNotEmpty()}}
    val liveRows: List<CatalogRow> get() = if(!liveAllowed)emptyList() else catalogs.liveRows
    val status: String? get() = catalogs.status
    val addonInfo: Map<String, AddonInfo> get() = catalogs.addonInfo
    val allCatalogs: List<CatalogDef> get() = catalogs.allCatalogs
    val catalogHome: Map<String, Boolean> get() = catalogs.catalogHome
    val catalogDiscoverOff: Set<String> get() = catalogs.catalogDiscoverOff
    val catalogLive: Map<String, Boolean> get() = catalogs.catalogLive
    fun isOnLive(c: CatalogDef) = catalogs.isOnLive(c)
    fun isOnHome(c: CatalogDef) = catalogs.isOnHome(c)
    fun isInDiscover(c: CatalogDef) = catalogs.isInDiscover(c)
    fun toggleCatalogLive(c: CatalogDef) = catalogs.toggleCatalogLive(c)
    fun toggleCatalogHome(c: CatalogDef) = catalogs.toggleCatalogHome(c)
    fun toggleCatalogDiscover(c: CatalogDef) = catalogs.toggleCatalogDiscover(c)
    fun moveCatalog(c: CatalogDef, delta: Int, toTop: Boolean = false) = catalogs.moveCatalog(c, delta, toTop)
    fun resetCatalogs() = catalogs.resetCatalogs()
    fun renameCatalog(key: String, name: String) {
        val names = app.ember.tv.data.CatalogNames.updated(prefs.catalogNames, key, name)
        prefs.catalogNames = names
        app.ember.tv.data.CatalogNames.load(names)
    }
    fun refreshRows(refetch: Boolean = true) = catalogs.refreshRows(refetch)
    fun canSeeAll(row: CatalogRow) = row.catalogKey?.startsWith("explore:") == true || catalogs.canSeeAll(row)
    fun seeAll(row: CatalogRow): Boolean {
        row.catalogKey?.takeIf { it.startsWith("explore:") }?.let { key ->
            if (profile.kids) return false
            explore.load(key.removePrefix("explore:"))
            return true
        }
        return catalogs.seeAll(row).also { if (it) explore.addonMode = true }
    }

    var homeHiddenTitles by mutableStateOf(prefs.homeHiddenTitles); private set
    /** Nothing hidden on this profile (the usual case): no title needs hashing (#57). */
    private val hidesHomeTitles by derivedStateOf {
        val prefix = HomeTitleVisibility.prefix(profiles.profileId)
        homeHiddenTitles.any { it.startsWith(prefix) }
    }
    internal fun homeTitleVisible(meta: Meta) = !hidesHomeTitles ||
        HomeTitleVisibility.key(profiles.profileId, meta) !in homeHiddenTitles
    val hiddenHomeTitleCount get() = homeHiddenTitles.count { it.startsWith(HomeTitleVisibility.prefix(profiles.profileId)) }
    fun restoreHomeTitles() {
        val prefix = HomeTitleVisibility.prefix(profiles.profileId)
        homeHiddenTitles = homeHiddenTitles.filterNot { it.startsWith(prefix) }.toSet()
        prefs.homeHiddenTitles = homeHiddenTitles
    }
    fun hideHomeTitle(meta: Meta) {
        val key = HomeTitleVisibility.key(profiles.profileId, meta)
        homeHiddenTitles = homeHiddenTitles + key; prefs.homeHiddenTitles = homeHiddenTitles
        offerUndo("Hidden from Home") { homeHiddenTitles = homeHiddenTitles - key; prefs.homeHiddenTitles = homeHiddenTitles }
    }
    data class LocalUndo(val id: Long, val profile: String, val message: String, val action: () -> Unit)
    var localUndo by mutableStateOf<LocalUndo?>(null); private set
    internal var undoRevision = 0L
    internal fun offerUndo(message: String, action: () -> Unit) {
        localUndo = LocalUndo(++undoRevision, profiles.profileId, message, action)
        notice = message
    }
    fun undoLocalChange() {
        val pending = localUndo ?: return
        localUndo = null
        if (pending.profile == profiles.profileId && pending.message == notice) { pending.action(); notice = "Change undone." }
    }
    fun hideChannelsWithUndo(keys: Set<String>, hidden: Boolean) {
        val before = iptv.library.hiddenChannels
        iptv.setChannelsHidden(keys, hidden)
        offerUndo(if (hidden) "Channels hidden" else "Channels restored") { iptv.restoreHiddenChannels(before) }
    }
    fun hideChannelWithUndo(channel: app.ember.tv.iptv.Channel) {
        iptv.setChannelHidden(channel.key, true)
        offerUndo("Channel hidden") { iptv.setChannelHidden(channel.key, false) }
    }

    fun openSourceSettings() = openSetupSettings("ADDONS")
    fun openServerSettings() = openSetupSettings("SERVERS")
    fun openTrackerSettings() = openSetupSettings("TRACKERS")
    fun openSettingsPage(page: String) = openSettingsGuarded { look.settingsDestination = page }
    internal fun openSetupSettings(page: String) = openSettingsPage(page)

    // ---- watchlist ----
    /** A short message shown at the bottom of the screen, then cleared. */
    var notice by mutableStateOf<String?>(null); internal set
    fun clearNotice() { notice = null; localUndo = null }

    internal val serverWatchlistItems: List<Meta> by derivedStateOf {
        serverRows.filter { it.catalogKey?.endsWith(":watchlist") == true }.flatMap { it.items }
    }
    internal val savedTitles: TitleIndex by derivedStateOf {
        LocalLibrary.version
        TitleIndex((watchlist + trackerWatchlist + serverWatchlistItems).filterNot { LocalLibrary.isListHidden("watchlist", it) })
    }
    private fun personalListIncludes(list: String, meta: Meta, fallback: String) = sections.sourceEnabled(
        profiles.profileId, list, app.ember.tv.data.PersonalListSources.source(meta, fallback))

    internal fun List<String>.syncedSuffix(meta: Meta): String {
        val pending = LocalLibrary.pending.firstOrNull { it.meta.sameTitle(meta) }?.targets.orEmpty()
        val done = if (isEmpty()) " on this device." else " and synced to ${joinToString(", ")}."
        return done + if (pending.isNotEmpty()) " Waiting to sync to ${pending.size} tracker(s)." else ""
    }

    internal fun Meta.toRef() = TitleRef(
        kind = when (type) {
            "movie" -> Kind.MOVIE
            "anime" -> Kind.ANIME
            else -> Kind.SHOW
        },
        tmdbId = tmdbId,
        imdbId = imdb,
        title = name,
        year = releaseInfo?.take(4)?.toIntOrNull(),
    )

    // ---- addons ----
    /** Returns an error message, or null when the addon was added. */
    fun addAddon(link: String): String? {
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
    fun renameAddon(url: String, name: String) {
        val n = name.trim()
        val map = prefs.addonNames.toMutableMap().apply { if (n.isEmpty()) remove(AddonAccess.key(url)) else put(AddonAccess.key(url), n) }
        prefs.addonNames = map
        AddonClient.renames = map
        discover.reset()
        refreshRows(refetch = false)
    }

    fun removeAddon(url: String) {
        ensureAddons()
        if (!prefs.saveAddons(addons - url)) { notice = "Encrypted storage is unavailable. The add-on was kept."; return }
        if (AddonAccess.key(url) in prefs.addonNames) renameAddon(url, "")
        AddonClient.cancel(url); client.forget(url); collections.invalidatePages()
        addons = addons - url
        pausedAddons = pausedAddons - AddonAccess.key(url)
        prefs.pausedAddons = pausedAddons; AddonAccess.paused = pausedAddons
        discover.reset(); refreshRows()
    }

    // ---- look ----
    fun updateHeroOn(on: Boolean) {
        if (sectionOn(HOME_HERO) != on) sections.toggleSection(HOME_HERO)
    }

    // ---- top menu ----
    var nav by mutableStateOf(prefs.nav); private set

    internal fun enableTab(id: String) {
        val enabled = NavTabs.enable(nav, id)
        if (enabled != nav) {
            nav = enabled
            prefs.nav = nav
        }
    }

    /** Tabs shown in the top menu, in order. */
    val visibleTabs: List<String>
        get() = nav.filter { it.second }.map { it.first }
            .filter { it != NavTabs.LIVE || featureOn(app.ember.tv.data.Features.LIVE) }
            .filter { it != NavTabs.LIVE || liveAllowed }
            .filter { personalOn || (it != NavTabs.LIBRARY && it != NavTabs.WATCHLIST) }

    fun toggleTab(id: String) {
        if (NavTabs.locked(id)) return
        if (id == NavTabs.LIVE) restoreLiveDefault = false
        nav = nav.map { if (it.first == id) it.first to !it.second else it }
        prefs.nav = nav
    }

    fun moveTab(id: String, delta: Int) {
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

    fun resetTabs() {
        restoreLiveDefault = false
        nav = NavTabs.ALL.map { it.id to it.shownByDefault }
        prefs.nav = nav
    }

    fun showNotice(message: String) { notice = message }

    /** True when a For You row is an addon catalog that Discover can show in full. */
    internal var searchPreferencesRevision by mutableIntStateOf(0)
    val recentSearchesEnabled get() = searchPreferencesRevision.let { prefs.recentSearchesEnabled(profiles.profileId) }
    val recentSearches get() = searchPreferencesRevision.let { prefs.recentSearches(profiles.profileId) }
    fun setRecentSearchesEnabled(enabled: Boolean) { prefs.setRecentSearchesEnabled(profiles.profileId, enabled); searchPreferencesRevision++ }
    fun clearRecentSearches() { prefs.clearRecentSearches(profiles.profileId); searchPreferencesRevision++ }
    fun submitSearch(query: String) {
        prefs.recordRecentSearch(profiles.profileId, query); searchPreferencesRevision++
        search.setQuery(query, immediate = true)
    }

    // ---- Discover and Search ----
    val discover: DiscoverState = DiscoverState(viewModelScope, client) {
        catalogs.ordered(loadAddonInfos().flatMap { client.catalogsOf(it) }).filter { isInDiscover(it) }
    }.apply {filterItems=::filterKidsItems}
    val search: SearchState = SearchState(
        viewModelScope, client,
        apps = { if (look.uiStyle == "light") appAccess.page(false) else visibleApps },
        saved = { if (personalOn) combinedWatchlist else emptyList() },
        searchable = { loadAddonInfos().flatMap { client.catalogsOf(it) }.filter { it.searchable && profileAllows(it) } },
    ).apply {
        filterItems=::filterKidsItems
        providers = { activeAddons.mapIndexedNotNull { index, url ->
            val cached = client.cachedManifest(url)
            if (cached != null && client.catalogsOf(cached).none { it.searchable && profileAllows(it) }) return@mapIndexedNotNull null
            SearchProvider("manifest:$url", cached?.name ?: "Addon ${index + 1}") {
                val info = kotlinx.coroutines.withTimeout(addonTimeout * 1000L) { client.manifest(url) }
                client.catalogsOf(info).filter { it.searchable && profileAllows(it) }
            }
        } + if (look.uiStyle == "light" && !profiles.profile.kids &&
                activeAddons.none { it.trimEnd('/').removeSuffix("/manifest.json") == "https://v3-cinemeta.strem.io" }) {
            listOf(SearchProvider("light:titles", "Movies and shows") {
                val info = kotlinx.coroutines.withTimeout(10_000) { client.manifest("https://v3-cinemeta.strem.io/manifest.json") }
                client.catalogsOf(info).filter { it.searchable && profileAllows(it) }
            })
        } else emptyList() }
    }

    // ---- Library: what you are part-way through, and what you watched recently ----
    var libraryHistory by mutableStateOf<List<Meta>>(emptyList()); internal set

    /** In progress on trackers and on media servers. */
    val libraryInProgress: List<Meta>
        get() = (continueItems + serverRows.filter { it.inProgress }.flatMap { it.items }).distinctBy { it.mediaKey() }

    /** Library is your media servers now; history loads with the trackers (refreshTrackers). */
    fun openLibrary() = Unit

    // ---- streaming services: "Where to watch" (state/WhereToWatchState.kt) ----
    val watch: WhereToWatchState = WhereToWatchState(prefs, viewModelScope, apps = { apps }) {
        title.details?.let { title.details = it.copy(offers = emptyList()) }
    }

    // ---- player, metadata and source settings (state/PlayerSettingsState.kt, MetaSettingsState.kt, SourceFormatState.kt) ----
    val playerSettings = PlayerSettingsState(prefs)
    val metadata = MetaSettingsState(prefs)
    /** Collections: Home rows of folders (state/CollectionsState.kt). */
    val collections = CollectionsState(
        getApplication<Application>(), prefs, viewModelScope, client,
        catalogs = { loadAddonInfos().flatMap { client.catalogsOf(it) } },
        onChanged = {},
        sourceAllowed = { s -> collectionSourceAllowed(s) },
        explorePage = { source, skip -> collectionExplore.page(source, skip) },
        cacheContext = { "${profiles.profileId}:${watch.watchRegion}:$keysRev" },
        profileId = { profiles.profileId },
    )
    internal fun collectionSourceAllowed(s: app.ember.tv.data.CollectionSource): Boolean = if(KidsContentPolicy.restricted(profile))false else when (s.provider) {
        "ember" -> !profiles.profile.kids && s.ownerProfile == profiles.profileId
        "addon" -> if (profiles.profile.kids) allCatalogs.any { addonAvailable(it.addon.url) && app.ember.tv.data.Collections.matches(it, s) && profileAllows(it) }
            else s.addonKey?.let { id -> activeAddons.any { AddonAccess.key(it) == id } } ?: activeAddons.isNotEmpty()
        else -> !profiles.profile.kids
    }
    fun refreshCollection() { collectionExplore.clear(); collections.refreshFolder() }
    val collectionCatalogs get() = allCatalogs.filter { addonAvailable(it.addon.url) && profileAllows(it) && it.addon.manifestId != null }
    val collectionSearches get() = prefs.explorePresets(profiles.profileId)
    fun addonLabel(url: String): String = (addonInfo[url] ?: client.cachedManifest(url))?.name ?: "Add-on ${addons.indexOf(url) + 1}"
    val sourceFormat = SourceFormatState(getApplication<Application>(), prefs, viewModelScope) { details }

    // ---- IPTV (Live TV page, Settings > Live TV) ----
    /** Created the first time Live TV is used, so it costs nothing at start when unused. */
    val iptv by lazy {
        IptvController(getApplication<Application>(), viewModelScope,
            onSourceAdded = { enableTab(NavTabs.LIVE) })
    }
    val iptvSetup = IptvSetupState(prefs, viewModelScope) { iptv }

    // ---- API keys entered on the TV ----
    /** Bumped when a key changes, so Settings redraws. */
    var keysRev by mutableIntStateOf(0); internal set
    var keysMessage by mutableStateOf<String?>(null); private set

    fun saveApiKey(id: String, value: String) {
        viewModelScope.launch {saveApiKeyAndWait(id,value)}
    }

    internal suspend fun saveApiKeyAndWait(id:String,value:String):String? {
        try {withContext(Dispatchers.IO) { ApiKeys.set(id, value) }}
        catch(error:kotlinx.coroutines.CancellationException) {throw error}
        catch(_:Exception) {return "The key could not be saved to encrypted storage. Previous settings were kept.".also {keysMessage=it}}
        keysRev++
        trackerRev++
        keysMessage = if (value.isBlank()) {
            "Removed. " + if (ApiKeys.builtIn(id).isNotBlank()) "Using the key built into the app." else "This service is off until you add a key."
        } else {
            "Saved."
        }
        if (id == "RPDB_API_KEY") Posters.keyRev++
        watch.keyChanged(id)
        if (id == "TMDB_API_KEY") explore.reset()
        smartRowsLoadedAt = 0L
        refreshTrackers()
        return null
    }

    // ---- private set (About: tap "0.1 First light" ten times) ----
    internal var privateFails = 0
    internal var privateLockedUntil = 0L
    var privateBusy by mutableStateOf(false); internal set

    // ---- backup and restore (state/BackupState.kt) ----
    val backup = BackupState(app, viewModelScope)

    // Kept last on purpose: Kotlin runs init blocks in source order, so every
    // state property above must exist before these calls touch it.

    // ---- phone setup (Settings > Phone setup; phone/PhoneSetupController.kt) ----
    internal val phone = PhoneSetupController(this)
    val phoneLog get() = phone.phoneLog
    fun clearPhoneLog() = phone.clearPhoneLog()
    internal val pendingContent get() = phone.pendingContent
    val approvingContent get() = phone.approvingContent
    fun clearPendingContent() = phone.clearPendingContent()
    fun approveContent() = phone.approveContent()
    fun phoneHandler(access: app.ember.tv.phone.PhoneSetupAccess): app.ember.tv.phone.PhoneSetup.Handler = phone.handler(access)

    // ---- profiles (state/ProfilesState.kt) ----
    val profiles: ProfilesState = ProfilesState(
        prefs,
        catalogs = { allCatalogs },
        apps = { apps },
        withPin = { message, go -> pin.withPin(message, go) },
        onNotice = { notice = it },
        onChanged = {
            homeFeatureAction=null;pendingAppLaunch=null
            personalHomes.switchTo(profiles.profileId,profiles.all.map {it.id}.toSet())
            look.reloadHome();sections.reloadHome()
            hidden=prefs.hidden;appsPageOff=prefs.appsPageOff;homeAppRows=prefs.homeAppRows
            apps=AppRepository.sorted(allApps,prefs.order)
            LocalLibrary.selectScope(LocalActivity.scope(profiles.profile))
            serverUserJob?.cancel()
            settingsAccess.revoke()
            pin.pinPrompt=null
            FocusMemory.pending = null
            FocusMemory.state.focused = null
            homeShortcuts.reload()
            libraryShortcutPending=false
            invalidateServerLibraries()
            serverRefreshJob?.cancel()
            serverRows=emptyList()
            viewModelScope.launch {restoreServerHome();refreshServers(force=true)}
            search.reset()
            closeDetails()
            playing.dismissChooser()
            resumeAsk = null
            collections.closeFolder(); collectionExplore.clear()
            discover.reset()
            explore.reset()
            widgets.reload()
            updateScreensaver()
            refreshRows(refetch = false)
            saveSnapshot()
        },
    ).apply {onRemoved=personalHomes::removeProfile}
    init {LocalLibrary.selectScope(LocalActivity.scope(profiles.profile))}
    internal val collectionExplore by lazy { CollectionExplore(exploreRepository, { profiles.profileId }, { !profiles.profile.kids }, { watch.watchRegion }) }
    internal val cacheGeneration=ConfigurationGeneration.current(getApplication<Application>().filesDir)
    val exploreRepository = ExploreRepository(java.io.File(getApplication<Application>().cacheDir, "discover/${cacheGeneration.ifEmpty {"legacy"}}"))
    val explore: ExploreState = ExploreState(viewModelScope, exploreRepository, prefs, { profiles.profileId },
        { !profiles.profile.kids }, { watch.watchRegion }, { sections.reloadLayout() })
    // ---- Home widgets (#161, state/WidgetsState.kt) ----
    // Declared after profiles: it reads the active profile's widgets as it is created.
    val widgets = WidgetsState(prefs, viewModelScope, { profiles.profileId }, { !profiles.profile.kids },
        page = { source -> collections.firstPage(source) }, onChanged = { sections.reloadLayout(); saveSnapshot() })
    fun refreshWidgets(force: Boolean = false) = widgets.refresh(force) { sections.sectionOn(it) }
    /** Deleting a widget also drops its place in the Home layout. */
    fun deleteWidget(id: String) { if (widgets.remove(id)) refreshWidgets() }
    internal fun widgetItems(w: HomeWidget): List<Meta> = if (w.source.provider == HomeWidgets.LIST) {
        if (!personalOn) emptyList() else when (w.source.catalogId) { "continue" -> inProgressItems; "recent" -> recentItems; else -> combinedWatchlist }.take(w.limit)
    } else widgets.items[w.id].orEmpty()

    val discoverSections: List<app.ember.tv.state.DiscoverSection> get() = app.ember.tv.data.DiscoverVisibility.visible(
        look.discoverSections,
        allCatalogs.any { addonAvailable(it.addon.url) && isInDiscover(it) } || discover.discoverCatalogs.isNotEmpty(),
        collections.onDiscover.isNotEmpty(), profiles.profile.kids)
    val profile: app.ember.tv.data.Profile get() = profiles.profile
    internal val personalOn: Boolean get() = profiles.personalOn
    internal fun profileAllows(c: CatalogDef): Boolean = profiles.allows(c)
    internal fun profileAllowsApp(pkg: String): Boolean = profiles.allowsApp(pkg)

    /** The "Up next" row goes right after Continue watching; the other extra rows keep their place. */
    val upNextRow: CatalogRow? by derivedStateOf { if (personalOn) smartRows.firstOrNull { it.catalogKey == "smart:${SmartRows.UP_NEXT}" } else null }
    val otherSmartRows: List<CatalogRow> by derivedStateOf { if (personalOn) smartRows.filter { it.catalogKey != "smart:${SmartRows.UP_NEXT}" } else emptyList() }

    // ---- PIN (state/PinState.kt) ----
    val pin = PinState(prefs) {notice=it}.apply {appLocks={launcherExtras.options.lockedApps.isNotEmpty()}}

    internal val settingsAccess=SettingsAccess({profile},{prefs.pinHash},{reason,go->pin.withPin(reason,go)},{notice=it})
    val settingsAllowed:Boolean get()=settingsAccess.allowed
    fun revokeSettingsAccess()=settingsAccess.revoke()
    /** Kids settings fail closed without a PIN, including quick settings and restored routes. */
    fun openSettingsGuarded(open: () -> Unit)=settingsAccess.open(open)

    // ---- resume or start over, mark as watched ----
    /** A play waiting for "Resume or Start over", with the label for Resume. */
    var resumeAsk by mutableStateOf<Pair<app.ember.tv.player.PlayRequest, String>?>(null)

    val primaryPlaybackLabel:String get() {
        if(playerSettings.playbackConfig.resume=="start")return "Play"
        val d=details?:return "Play"
        val ep=d.episode;val m=d.meta
        val key=m.playbackKey(ep)
        val fraction=ep?.progress ?: m.progress?.takeIf {ep==null || ep.season==m.season && ep.episode==m.episode}
        return app.ember.tv.data.ResumeAction.buttonLabel(app.ember.tv.player.PositionStore(getApplication<Application>(),LocalActivity.scope(profiles.profile)).get(key),fraction,m.runtimeMinutes?.toLong()?.times(60_000))
    }
    val detailsWatched:Boolean get() {
        val d=details?:return false
        val ep=d.episode;val m=d.meta
        return if(ep!=null)isEpisodeWatched(m,ep) else isWatched(m)
    }

    // ---- quick settings ----
    var quickPanel by mutableStateOf(false)
    /** The last title played, for "Back to …" (Settings > Player > Leaving the player). */
    var lastPlay by mutableStateOf<app.ember.tv.player.LastPlay?>(null)
    /** "Press ▶ to return to …" shows on Home for a few seconds after leaving a video part-way. */
    var returnHint by mutableStateOf(false)
    /** "Back to …" can be offered now (the title still has a saved position). */
    var returnReady by mutableStateOf(false)
    val sleep = SleepState(app, viewModelScope)

    // ---- updates (state/UpdatesState.kt) ----
    val updates = UpdatesState(app, prefs, viewModelScope)

    val homeShortcuts = HomeShortcuts(app,viewModelScope,{profiles.profileId},{notice=it})
    var libraryShortcutPending by mutableStateOf(false);private set
    fun consumeLibraryShortcut() {libraryShortcutPending=false}
    fun openHomeShortcut(value:app.ember.tv.data.HomeShortcut):String? {
        if(profile.kids || !personalOn || homeShortcuts.items.none {it.id==value.id})return null
        when(value.kind) {
            "server" -> {
                if(!featureOn(Features.SERVERS))return null
                val ref=app.ember.tv.mediaserver.ServerRef.parse(value.ref)?:return null
                if(ref.provider=="plex" && !plexConnected || ref.provider!="plex" && serverAccounts.none {it.key==ref.account}) {
                    notice="Reconnect this media server in Settings.";return null
                }
                val lib=serverLibraries.firstOrNull {it.ref==value.ref} ?: app.ember.tv.mediaserver.ServerLibrary(value.label,value.label,"",emptyList(),ref=value.ref)
                serverBrowser.open(lib,value.folder,value.label.takeIf {value.folder!=null})
                libraryShortcutPending=true
                return NavTabs.LIBRARY
            }
            "collection" -> {
                val c=collections.list.firstOrNull {it.id==value.ref}
                val f=c?.folders?.firstOrNull {it.id==value.folder}
                if(c!=null && f!=null)collections.openFolder(c,f) else notice="This collection folder is no longer available."
            }
            "network" -> {
                val context=getApplication<Application>()
                val intent=android.content.Intent(context,app.ember.tv.player.MediaFilesActivity::class.java)
                    .putExtra("network.profile",profiles.profileId).putExtra("network.account",value.ref).putExtra("network.path",value.folder.orEmpty()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
        return null
    }

    // ---- Home layout (state/HomeSectionsState.kt) ----
    val sections: HomeSectionsState = HomeSectionsState(
        prefs,
        onSmartRowsChanged = { viewModelScope.launch { refreshSmartRows(force = true) } },
        onChanged = { saveSnapshot() },
        catalogSections = {
            val definitions = allCatalogs.filter { it.homeable }
            val known = definitions.map { it.key }.toSet()
            definitions.map { c ->
                HomeSectionsState.SectionInfo(HomeSectionsState.catalogSection(c.key), c.label, "${c.addon.name} · ${c.type}") to isOnHome(c)
            } + rows.filter { it.catalogKey != null && it.catalogKey !in known }.map { row ->
                HomeSectionsState.SectionInfo(HomeSectionsState.catalogSection(row.catalogKey!!), row.displayTitle, row.addonName) to
                    (catalogHome[row.catalogKey] ?: !prefs.catalogHomeDefaultsOff)
            }
        },
        extraSections = { if (profile.kids) emptyList() else buildList {
            if(homeShortcuts.items.isNotEmpty())add(HomeSectionsState.SectionInfo("pinned-shortcuts","Your shortcuts","Pinned server libraries, collections and network folders.") to true)
            addAll(explore.saved.filter {it.home}.map {HomeSectionsState.SectionInfo(it.section,it.name,"Saved Discover · up to 20 matching titles") to true})
            // #161: your own widgets are sections like the rest, so they can be switched off and moved.
            addAll(widgets.list.map {w->HomeSectionsState.SectionInfo(HomeWidgets.section(w.id),w.title,
                "Widget · ${HomeWidgets.styleName(w.style)} · ${HomeWidgets.sourceLabel(w.source,allCatalogs)}") to true})
        } },
        onCatalogVisibilityChanged = { values ->
            catalogs.updateCatalogHomes(values.mapKeys { HomeSectionsState.catalogKey(it.key) })
        },
    )
    internal fun sectionOn(id: String) = sections.sectionOn(id)
    internal fun smartSection(id: String) = sections.smartSection(id)
    internal val layout get() = sections.layout
    val homeCatalogsWanted:Boolean get() {
        val catalogs=sections.layout.filter {HomeSectionsState.isCatalogSection(it.first)}
        return if(catalogs.isNotEmpty())catalogs.any {it.second} else !prefs.catalogHomeDefaultsOff && sectionOn(HOME_HERO)
    }
    val homeServersWanted:Boolean get()=personalOn && sectionOn(HOME_SERVERS)
    val homeTrackersWanted:Boolean get()=personalOn && sections.layout.any {it.second && (it.first in setOf(HOME_CONTINUE,HOME_RECENT,HOME_WATCHLIST,HOME_UPNEXT) || it.first.startsWith("smart:"))}

    /** One block of Home, in layout order. */
    sealed interface HomeBlock {
        val key: String
        data object Hero : HomeBlock { override val key = "hero" }
        data object Apps : HomeBlock { override val key = "apps" }
        /** Buttons opening In progress, Recently watched or the watchlist ([ids] are their sections). */
        data class Shortcuts(override val key: String, val ids: List<String>) : HomeBlock
        data class Pinned(val items:List<app.ember.tv.data.HomeShortcut>):HomeBlock {override val key="pinned-shortcuts"}
        data class Loading(override val key:String,val title:String):HomeBlock
        /** [widget] is set for one of your own widgets (#161); the Ember interface draws it in its style. */
        data class Row(override val key: String, val row: CatalogRow, val saved: Boolean = false, val widget: HomeWidget? = null) : HomeBlock
        /** A Collection: a row of folder tiles. */
        data class Collection(override val key: String, val collection: app.ember.tv.data.MediaCollection) : HomeBlock
    }

    val homeBlocks: List<HomeBlock> by derivedStateOf { buildList {
            val catalogsShown = mutableSetOf<String>()
            // Collections marked "pin to top" come first; the others go after your catalogs.
            val shownCollections = if (profile.kids) emptyList() else collections.onHome
            val (pinned, unpinned) = shownCollections.partition { it.pinToTop }
            fun addCollections(list: List<app.ember.tv.data.MediaCollection>) = list.forEach { add(HomeBlock.Collection("collection:${it.id}", it)) }
            for ((id, on) in layout) {
                if (!on) continue
                when (id) {
                    HOME_HERO -> if (heroItems.isNotEmpty()) add(HomeBlock.Hero)
                    HOME_APPS -> if (homeApps.isNotEmpty() || personalHomes.current.folders.any {f->f.packages.any(appAccess::canLaunch)}) add(HomeBlock.Apps)
                    "pinned-shortcuts" -> if(personalOn && homeShortcuts.items.isNotEmpty())add(HomeBlock.Pinned(homeShortcuts.items))
                    HOME_CONTINUE, HOME_RECENT, HOME_WATCHLIST -> if (personalOn) {
                        if (sections.listStyle(id) == "button") {
                            // Buttons next to each other share one line (joinShortcuts below).
                            add(HomeBlock.Shortcuts("shortcuts:$id", listOf(id)))
                        } else {
                            val (title, items) = when (id) {
                                HOME_CONTINUE -> "In progress" to inProgressItems
                                HOME_RECENT -> "Recently watched" to recentItems
                                else -> "Your watchlist" to combinedWatchlist
                            }
                            if (items.isNotEmpty()) add(HomeBlock.Row(id, CatalogRow("", title, items), saved = id == HOME_WATCHLIST))
                        }
                    }
                    HOME_UPNEXT -> upNextRow?.let { add(HomeBlock.Row("upnext", it)) }
                    HOME_SERVERS -> if (personalOn) serverRows.filterNot { it.catalogKey?.endsWith(":watchlist") == true }.forEach { r ->
                        val shown = if (r.inProgress) r.copy(items = r.items.filterNot { LocalLibrary.isListHidden("continue", it) }) else r
                        if (shown.items.isNotEmpty()) add(HomeBlock.Row(shown.catalogKey ?: "server:${shown.addonName}:${shown.title}", shown))
                    }
                    else -> if (HomeSectionsState.isCatalogSection(id)) {
                        val key = HomeSectionsState.catalogKey(id)
                        val row=rows.firstOrNull {it.catalogKey==key}
                        if(row!=null) {
                            catalogsShown+=key
                            add(HomeBlock.Row("addon:$key",row))
                        } else if(catalogs.loading && count {it is HomeBlock.Loading}<3) {
                            allCatalogs.firstOrNull {it.key==key}?.let {add(HomeBlock.Loading("addon:$key",it.label))}
                        }
                    } else if (HomeWidgets.isSection(id) && !profile.kids) {
                        widgets.list.firstOrNull { HomeWidgets.section(it.id) == id }?.let { w ->
                            val items = widgetItems(w)
                            if (items.isNotEmpty()) add(HomeBlock.Row(id, CatalogRow("", w.title, items, catalogKey = id), widget = w))
                            else if (w.id in widgets.loading && count { it is HomeBlock.Loading } < 3) add(HomeBlock.Loading(id, w.title))
                        }
                    } else if (id.startsWith("explore:") && !profile.kids) {
                        explore.homeRows.firstOrNull { it.catalogKey == id }?.let { add(HomeBlock.Row(id, it)) }
                    } else if (id.startsWith("smart:") && personalOn) {
                        val smartId = id.removePrefix("smart:")
                        otherSmartRows.filter { it.catalogKey == id || it.catalogKey?.startsWith("$id:") == true || (smartId == SmartRows.LISTS && it.catalogKey?.startsWith("smart:${SmartRows.LISTS}") == true) }
                            .forEach { r -> add(HomeBlock.Row(r.catalogKey ?: "$id:${r.addonName}:${r.title}", r)) }
                    }
                }
            }
            // A Kids profile's Home is its allowed catalogs, even with the section off.
            if (profile.kids) rows.filter { it.catalogKey !in catalogsShown }.forEachIndexed { i, r ->
                add(HomeBlock.Row("addon:${r.catalogKey ?: "$i:${r.addonName}:${r.title}"}", r))
            }
            // Pinned collections go right after the banner and apps when those lead, else first.
            val lead = indexOfFirst { it !is HomeBlock.Hero && it !is HomeBlock.Apps && it !is HomeBlock.Shortcuts }.let { if (it < 0) size else it }
            pinned.reversed().forEach { add(lead, HomeBlock.Collection("collection:${it.id}", it)) }
            addCollections(unpinned)
        }.mapNotNull { block ->
            if (block is HomeBlock.Row) block.copy(row = block.row.copy(items = block.row.items.filter(::homeTitleVisible))).takeIf { it.row.items.isNotEmpty() }
            else block
        }.let { joinShortcuts(it) }
    }

    // ---- apps on Home and on the Apps page ----
    var homeAppRows by mutableIntStateOf(prefs.homeAppRows); internal set
    var appsPageOff by mutableStateOf(prefs.appsPageOff); internal set

    /** Apps in the Home section: every app unless removed from Home (or not allowed for the profile). */
    val homeApps: List<AppEntry> get() = visibleApps.filter {personalHomes.folderFor(it.pkg)==null}

    // ---- background refresh (work/RefreshWorker.kt) ----
    var backgroundRefresh by mutableStateOf(prefs.backgroundRefresh); private set

    fun toggleBackgroundRefresh() {
        backgroundRefresh = !backgroundRefresh
        prefs.backgroundRefresh = backgroundRefresh
        app.ember.tv.work.RefreshWorker.schedule(getApplication<Application>())
    }

    // ---- slow addons ----
    var addonTimeout by mutableIntStateOf(prefs.addonTimeout); private set
    /** Addons that ran past the time limit last time, by name. */
    var slowAddons by mutableStateOf<Set<String>>(emptySet()); private set

    fun updateAddonTimeout(seconds: Int) {
        addonTimeout = seconds
        prefs.addonTimeout = seconds
    }

    internal fun noteSlow(name: String, slow: Boolean) {
        slowAddons = if (slow) slowAddons + name else slowAddons - name
    }

    // ---- instant Home ----
    internal var snapshotJob: kotlinx.coroutines.Job? = null

    init {
        if (!prefs.hasSavedNav) {
            val hasIptv = runCatching {
                Json.decodeFromString<List<IptvSource>>(
                    TokenStore(app).get("iptv.sources") ?: "[]",
                ).any { it.enabled }
            }.getOrDefault(false)
            val defaults = NavTabs.sourceDefaults(nav, saved = false, live = hasIptv, servers = servers.connected)
            if (defaults != nav) { nav = defaults; prefs.nav = defaults }
        }
        androidx.core.content.ContextCompat.registerReceiver(app,packagesChanged,
            android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_PACKAGE_ADDED);addAction(android.content.Intent.ACTION_PACKAGE_REMOVED);addAction(android.content.Intent.ACTION_PACKAGE_CHANGED);addDataScheme("package")
            },androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        androidx.core.content.ContextCompat.registerReceiver(app,localeChanged,
            android.content.IntentFilter(android.content.Intent.ACTION_LOCALE_CHANGED),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        homeShortcuts.reload()
        hub.disabled = trackersOff
        hub.quiet = trackersQuiet
        startupPresentation.preferencesRestored()
        refreshApps()
        viewModelScope.launch {
        val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { prefs.addons }
        if (!addonsLoaded) { addons = loaded; addonsLoaded = true }
        restoreServerHome()
        restoreSnapshot()
        // What the trackers said last time, until they answer (or when offline).
        LocalLibrary.awaitReady()
        if (featureOn(Features.TRACKERS)) {
            if (continueItems.isEmpty()) continueItems = LocalLibrary.trackerProgress
            trackerWatchlist = LocalLibrary.trackerWatchlist
            libraryHistory = LocalLibrary.trackerHistory
        }
        seedTestAddons()
        // Cache restoration precedes queued network work; the first frame gets priority.
        queueResumeRefresh()
        refreshQueue.submit("badges") { sourceFormat.loadBadges().join() }
        refreshQueue.submit("screensaver-art") { refreshCollections(); artCollectionsJob?.join() }
        refreshQueue.submit("photos") { refreshPhotos(); photosJob?.join() }
        refreshQueue.submit("services") { watch.refreshStreamingServices(); watch.awaitRefresh() }
        refreshQueue.submit("updates") { updates.checkForUpdate(manual = false); updates.awaitCheck() }
        cachedHomeLoaded = true
        if (uiPresented) refreshQueue.presented()
        // Visible Home data has its own bounded provider fan-out. A slow catalog must
        // not hold the optional queue and starve weather/trackers for minutes.
        refreshQueue.awaitPresentation()
        refreshRows()
        }
    }

    companion object {
        /** Joins buttons that end up next to each other into one line of buttons. */
        fun joinShortcuts(blocks: List<HomeBlock>): List<HomeBlock> {
            val out = ArrayList<HomeBlock>()
            for (b in blocks) {
                val last = out.lastOrNull()
                if (b is HomeBlock.Shortcuts && last is HomeBlock.Shortcuts) out[out.lastIndex] = last.copy(ids = last.ids + b.ids) else out += b
            }
            return out
        }

        const val HOME_HERO = "hero"
        const val HOME_APPS = "apps"
        const val HOME_CONTINUE = "continue"
        const val HOME_UPNEXT = "upnext"
        const val HOME_SERVERS = "servers"
        const val HOME_WATCHLIST = "watchlist"
        const val HOME_CATALOGS = "catalogs"
        /** A button on For You that opens the watchlist page. */
        const val HOME_WATCH_BUTTON = "watchbutton"
        /** Recently watched: Ember's own plays and your Trakt history. */
        const val HOME_RECENT = "recent"
    }

}
