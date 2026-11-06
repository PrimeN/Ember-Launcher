package app.ember.tv.state

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ember.tv.data.AddonClient
import app.ember.tv.data.AddonInfo
import app.ember.tv.data.ApiKeys
import app.ember.tv.data.AppEntry
import app.ember.tv.data.AppRepository
import app.ember.tv.data.Backup
import app.ember.tv.data.CatalogDef
import app.ember.tv.data.CatalogRow
import app.ember.tv.data.Credits
import app.ember.tv.data.Episode
import app.ember.tv.data.Features
import app.ember.tv.data.LocalLibrary
import app.ember.tv.data.Meta
import app.ember.tv.data.MetaConfig
import app.ember.tv.data.MetaEnhance
import app.ember.tv.data.MetaExtra
import app.ember.tv.data.NavTabs
import app.ember.tv.data.Person
import app.ember.tv.data.PosterConfig
import app.ember.tv.data.Posters
import app.ember.tv.data.Prefs
import app.ember.tv.data.RatingSource
import app.ember.tv.data.Ratings
import app.ember.tv.data.StreamFormatDefaults
import app.ember.tv.data.StreamFormatter
import app.ember.tv.data.StreamOption
import app.ember.tv.data.StreamParser
import app.ember.tv.data.SubtitleOption
import app.ember.tv.iptv.IptvController
import app.ember.tv.mediaserver.FoundServer
import app.ember.tv.mediaserver.LanDiscovery
import app.ember.tv.mediaserver.MediaServers
import app.ember.tv.mediaserver.ServerQuery
import app.ember.tv.player.PlayRequest
import app.ember.tv.player.PlayerApp
import app.ember.tv.player.PlayerApps
import app.ember.tv.screensaver.ArtSources
import app.ember.tv.screensaver.Photos
import app.ember.tv.screensaver.ScreensaverFeed
import app.ember.tv.state.BackupState
import app.ember.tv.state.CollectionsState
import app.ember.tv.state.DiscoverState
import app.ember.tv.state.HomeSectionsState
import app.ember.tv.state.IptvSetupState
import app.ember.tv.state.LookState
import app.ember.tv.state.MetaSettingsState
import app.ember.tv.state.PinState
import app.ember.tv.state.PlayerSettingsState
import app.ember.tv.state.PlayingState
import app.ember.tv.state.ProfilesState
import app.ember.tv.state.SearchState
import app.ember.tv.state.SleepState
import app.ember.tv.state.SourceFormatState
import app.ember.tv.state.UpdatesState
import app.ember.tv.state.WeatherState
import app.ember.tv.state.WhereToWatchState
import app.ember.tv.streaming.Offer
import app.ember.tv.streaming.ServiceApps
import app.ember.tv.streaming.StreamingService
import app.ember.tv.streaming.TmdbWatch
import app.ember.tv.streaming.Watchmode
import app.ember.tv.tracker.Kind
import app.ember.tv.tracker.LinkSession
import app.ember.tv.tracker.SmartRows
import app.ember.tv.tracker.TitleRef
import app.ember.tv.tracker.Tmdb
import app.ember.tv.tracker.TrackedItem
import app.ember.tv.tracker.TrackerHub
import app.ember.tv.tracker.arr
import app.ember.tv.tracker.list
import app.ember.tv.tracker.str
import app.ember.tv.ui.FocusMemory
import app.ember.tv.ui.Ui
import app.ember.tv.weather.Place
import app.ember.tv.weather.Weather
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.CoroutineScope

/**
 * Addon catalogs (split out of HomeViewModel): every catalog your addons offer, which show on
 * Home, in Discover and on Live, their order, and the first page of each Home row. HomeViewModel
 * forwards rows, allCatalogs, refreshRows and the rest to it.
 */
class CatalogsState(
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val client: AddonClient,
    private val addons: () -> List<String>,
    private val profile: () -> app.ember.tv.data.Profile,
    private val profileAllows: (CatalogDef) -> Boolean,
    private val addonInfos: suspend () -> List<AddonInfo>,
    private val addonTimeout: () -> Int,
    private val noteSlow: (String, Boolean) -> Unit,
    private val discover: () -> DiscoverState,
    /** Rows changed: refresh the screensaver's pictures and the saved snapshot. */
    private val onRows: () -> Unit,
    private val onLiveAdded: () -> Unit = {},
    private val restoreLiveDefault: () -> Boolean = { false },
    private val requestParallelism: () -> Int = { 4 },
) {
    // Wait for a newly installed addon's manifest before deciding whether it adds Live.
    private val pendingAddons = mutableSetOf<String>()
    fun addonAdded(url: String) { pendingAddons += url }
    var rows by mutableStateOf<List<CatalogRow>>(emptyList())
    internal var filterItems: suspend (List<Meta>) -> List<Meta> = {it}
    val health = app.ember.tv.data.SourceHealth("catalogs")
    var revision: Long = 0; private set
    var loading by mutableStateOf(false); private set
    var status by mutableStateOf<String?>(null)
    /** Manifests of the added addons, keyed by link. Used to show what each addon offers. */
    var addonInfo by mutableStateOf<Map<String, AddonInfo>>(emptyMap())
    /** Every catalog of every addon, in the user's order. */
    var allCatalogs by mutableStateOf<List<CatalogDef>>(emptyList())
    internal fun updateIconConfiguration() = app.ember.tv.ui.IconArtworkCache.configuration("catalogs",
        listOf(allCatalogs, prefs.catalogNames, catalogHome, catalogDiscoverOff, catalogLive).toString())
    var catalogHome by mutableStateOf(prefs.catalogHome)
    var catalogDiscoverOff by mutableStateOf(prefs.catalogDiscoverOff)
    /** Catalogs on Home unless switched off: the first six usable ones of each addon, as before. */
    private var defaultHomeKeys: Set<String> = emptySet()
    /** First page of each Home catalog, so reordering or switching rows needs no new requests. */
    private val rowCache = HashMap<String, List<Meta>>()
    private val rowTimes = HashMap<String,Long>()
    private val rowEpochs = HashMap<String,String>()
    private fun rememberRow(key: String, items: List<Meta>, epoch: String) {
        if (epoch != app.ember.tv.data.CachePolicy.epoch(app.ember.tv.data.CacheKind.CATALOGS)) return
        rowCache[key] = items; rowTimes[key] = System.currentTimeMillis()
        rowEpochs[key] = epoch
    }
    private var rowsJob: Job? = null

    var catalogLive by mutableStateOf(prefs.catalogLive)
    /** Catalog rows on the Live TV page. */
    var liveRows by mutableStateOf<List<CatalogRow>>(emptyList())

    /** Stremio's live types: TV channels and events. Other catalogs are off unless switched on. */
    private val liveTypes = setOf("tv", "channel", "channels", "events")

    fun isOnLive(c: CatalogDef): Boolean = c.homeable && profileAllows(c) && (catalogLive[c.key] ?: (c.type.lowercase() in liveTypes))

    fun toggleCatalogLive(c: CatalogDef) {
        if (!c.homeable) return
        catalogLive = catalogLive + (c.key to !isOnLive(c))
        prefs.catalogLive = catalogLive
        if (isOnLive(c)) onLiveAdded()
        refreshRows(refetch = false)
    }

    fun isOnHome(c: CatalogDef): Boolean {
        if (!c.homeable) return false
        // A Kids profile's Home is exactly the catalogs allowed for it, whatever the main Home shows.
        profile().catalogs?.takeIf { profile().kids }?.let { return c.key in it }
        return profileAllows(c) && (catalogHome[c.key] ?: (c.key in defaultHomeKeys && !prefs.catalogHomeDefaultsOff))
    }
    fun isInDiscover(c: CatalogDef): Boolean = c.browsable && profileAllows(c) && c.key !in catalogDiscoverOff

    /** Saved order first; catalogs never moved keep their addon order after them. */
    fun ordered(defs: List<CatalogDef>): List<CatalogDef> {
        val pos = prefs.catalogOrder.withIndex().associate { it.value to it.index }
        val (known, unknown) = defs.partition { it.key in pos }
        return known.sortedBy { pos[it.key] } + unknown
    }

    /** Loads catalog lists and Home rows. [refetch] false reuses rows already loaded (after a reorder or switch). */
    fun retry(source: String) {
        if (source in addons()) refreshRows(refetch = true, only = source)
    }

    fun refreshRows(refetch: Boolean = true, only: String? = null) {
        revision++
        rowsJob?.cancel()
        health.cancelLoading()
        rows = app.ember.tv.data.visibleCatalogRows(rows, profile(), addons(), catalogHome, prefs.catalogHomeDefaultsOff)
        liveRows = app.ember.tv.data.visibleCatalogRows(liveRows, profile(), addons(), catalogLive, live = true)
        rowsJob = scope.launch {
            loading = true
            val urls = addons()
            health.retain(urls.toSet())
            val targets = if (only == null) urls else urls.filter { it == only }
            targets.forEach { url -> health.start(url, addonInfo[url]?.name ?: "Addon ${urls.indexOf(url) + 1}") }
            try {
                if (urls.isEmpty()) {
                    rows = emptyList(); liveRows = emptyList(); allCatalogs = emptyList()
                    updateIconConfiguration()
                    addonInfo = emptyMap(); rowCache.clear(); rowTimes.clear(); rowEpochs.clear(); pendingAddons.clear(); status = null
                    onRows()
                    return@launch
                }
                val freshInfos = if (only == null) addonInfos() else listOfNotNull(
                    kotlinx.coroutines.withTimeoutOrNull(addonTimeout() * 1000L) {
                        runCatching { client.manifest(only) }.getOrNull()
                    })
                ensureActive()
                val freshUrls = freshInfos.map { it.url }.toSet()
                val infos = freshInfos + addonInfo.values.filter { it.url in urls && it.url !in freshUrls }
                val defs = infos.flatMap { client.catalogsOf(it) }
                defaultHomeKeys = defs.filter { it.homeable }.groupBy { it.addon.url }.values.flatMap { it.take(6) }.map { it.key }.toSet()
                allCatalogs = ordered(defs)
                updateIconConfiguration()
                if (allCatalogs.any { (it.addon.url in pendingAddons || restoreLiveDefault()) && isOnLive(it) }) onLiveAdded()
                pendingAddons.removeAll(infos.map { it.url }.toSet())
                addonInfo = infos.associateBy { it.url }
                val wanted = allCatalogs.filter { isOnHome(it) }
                val wantedLive = allCatalogs.filter { isOnLive(it) }
                val fetching = (wanted + wantedLive).distinctBy { it.key }.filter {
                    (only == null || it.addon.url == only) && (refetch || it.key !in rowCache || rowEpochs[it.key] != app.ember.tv.data.CachePolicy.epoch(app.ember.tv.data.CacheKind.CATALOGS) ||
                        !app.ember.tv.data.CachePolicy.fresh(app.ember.tv.data.CacheKind.CATALOGS,rowTimes[it.key] ?: 0))
                }
                val failedUrls = targets.filter { it !in freshUrls }.toMutableSet()
                val failures=mutableMapOf<String,app.ember.tv.data.SourceFailure>()
                val permits = Semaphore(requestParallelism().coerceIn(1, 4))
                val shownPages = HashMap<String,List<Meta>>()
                val cacheEpoch = app.ember.tv.data.CachePolicy.epoch(app.ember.tv.data.CacheKind.CATALOGS)
                coroutineScope {
                    fetching.map { d -> async { permits.withPermit {
                        val result = kotlinx.coroutines.withTimeoutOrNull(addonTimeout() * 1000L) {
                            runCatching { client.browse(d, refresh = refetch) }
                        }
                        ensureActive()
                        noteSlow(d.addon.name, result == null)
                        result?.onSuccess { fresh ->
                            rememberRow(d.key, fresh, cacheEpoch)
                            val shown=filterItems(fresh)
                            ensureActive()
                            shownPages[d.key]=shown
                            fun updated(old: List<CatalogRow>, wanted: List<CatalogDef>): List<CatalogRow> {
                                if (wanted.none { it.key == d.key }) return old
                                val incoming = old.filterNot { it.catalogKey == d.key } +
                                    if (shown.isEmpty()) emptyList() else listOf(CatalogRow(d.addon.name, d.originalLabel, shown, catalogKey = d.key))
                                val positions = wanted.mapIndexed { index, def -> def.key to index }.toMap()
                                return incoming.sortedBy { positions[it.catalogKey] ?: Int.MAX_VALUE }
                            }
                            rows = updated(rows, wanted); liveRows = updated(liveRows, wantedLive)
                        }
                        d to (result ?: Result.failure(java.net.SocketTimeoutException("Source timed out")))
                    } } }.awaitAll()
                }.forEach { (def, result) ->
                    result.onSuccess { rememberRow(def.key, it, cacheEpoch) }.onFailure { error->
                        failedUrls += def.addon.url
                        failures[def.addon.url]=app.ember.tv.data.SourceFailure.from(error)
                    }
                }
                ensureActive()
                val before = (rows + liveRows).filter { it.catalogKey != null }.associate { it.catalogKey!! to it.items }
                // A successful empty response replaces old content; failure alone retains it.
                fun itemsFor(d: CatalogDef) = if (rowCache.containsKey(d.key)) rowCache[d.key] else before[d.key]
                val knownKeys = defs.map { it.key }.toSet()
                rowCache.keys.removeAll { key -> urls.none { app.ember.tv.data.CatalogIdentity.belongs(key, it) } || key !in knownKeys && freshUrls.any { app.ember.tv.data.CatalogIdentity.belongs(key, it) } }
                rowTimes.keys.retainAll(rowCache.keys); rowEpochs.keys.retainAll(rowCache.keys)
                fun offline(old: List<CatalogRow>) = old.filter { row ->
                    row.catalogKey !in knownKeys && freshUrls.none { app.ember.tv.data.CatalogIdentity.belongs(row.catalogKey, it) }
                }
                rows = wanted.mapNotNull { d -> itemsFor(d)?.takeIf { it.isNotEmpty() }?.let {
                    (shownPages[d.key] ?: filterItems(it)).takeIf {items->items.isNotEmpty()}?.let {items->CatalogRow(d.addon.name, d.originalLabel, items, catalogKey = d.key)}
                } } + offline(rows)
                liveRows = wantedLive.mapNotNull { d -> itemsFor(d)?.takeIf { it.isNotEmpty() }?.let {
                    (shownPages[d.key] ?: filterItems(it)).takeIf {items->items.isNotEmpty()}?.let {items->CatalogRow(d.addon.name, d.originalLabel, items, catalogKey = d.key)}
                } } + offline(liveRows)
                targets.forEach { url ->
                    health.rename(url, addonInfo[url]?.name ?: "Addon ${urls.indexOf(url) + 1}")
                    val cached = (rows + liveRows).any { app.ember.tv.data.CatalogIdentity.belongs(it.catalogKey, url) }
                    health.finish(url, url !in failedUrls, cached = url in failedUrls && cached, failure=failures[url])
                }
                status = if (health.entries.values.any { it.failed }) "Some sources are unavailable. Saved rows remain available." else null
                onRows()
            } finally {
                if (rowsJob == coroutineContext[Job]) {
                    loading = false
                    targets.filter { health.entries[it]?.loading == true }.forEach { health.finish(it, false, rows.isNotEmpty()) }
                }
            }
        }
    }

    fun toggleCatalogHome(c: CatalogDef) {
        if (!c.homeable) return
        updateCatalogHomes(mapOf(c.key to !isOnHome(c)))
    }

    fun updateCatalogHomes(values: Map<String, Boolean>) {
        catalogHome = catalogHome + values
        prefs.catalogHome = catalogHome
        refreshRows(refetch = false)
    }

    fun toggleCatalogDiscover(c: CatalogDef) {
        catalogDiscoverOff = if (c.key in catalogDiscoverOff) catalogDiscoverOff - c.key else catalogDiscoverOff + c.key
        prefs.catalogDiscoverOff = catalogDiscoverOff
        discover().reset() // Discover reloads its list next time it opens
    }

    /** Moves a catalog up (negative) or down; [toTop] puts it first. Applies to Home and Discover. */
    fun moveCatalog(c: CatalogDef, delta: Int, toTop: Boolean = false) {
        val list = allCatalogs.toMutableList()
        val i = list.indexOfFirst { it.key == c.key }
        if (i < 0) return
        val j = if (toTop) 0 else (i + delta).coerceIn(0, list.lastIndex)
        if (i == j) return
        list.add(j, list.removeAt(i))
        allCatalogs = list
        updateIconConfiguration()
        prefs.catalogOrder = list.map { it.key }
        discover().reset()
        refreshRows(refetch = false)
    }

    fun resetCatalogs() {
        prefs.catalogOrder = emptyList()
        prefs.catalogHomeDefaultsOff = false
        catalogHome = emptyMap()
        prefs.catalogHome = catalogHome
        catalogLive = emptyMap()
        prefs.catalogLive = catalogLive
        catalogDiscoverOff = emptySet()
        prefs.catalogDiscoverOff = catalogDiscoverOff
        discover().reset()
        refreshRows(refetch = false)
    }


    fun canSeeAll(row: CatalogRow): Boolean =
        row.catalogKey?.takeUnless { it.startsWith("smart:") }?.let { k -> allCatalogs.any { it.key == k } } == true

    /** "See all" on a For You row: that catalog in Discover. Returns false when it is not an addon catalog. */
    fun seeAll(row: CatalogRow): Boolean {
        val key = row.catalogKey ?: return false
        val def = allCatalogs.firstOrNull { it.key == key } ?: return false
        discover().show(def)
        return true
    }

}
