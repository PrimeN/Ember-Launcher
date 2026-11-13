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

/** Everything Home shows, in order; read through [HomeViewModel.homeBlocks]. */
internal fun HomeViewModel.buildHomeBlocks(): List<HomeViewModel.HomeBlock> = buildList {
        val catalogsShown = mutableSetOf<String>()
        // Collections marked "pin to top" come first; the others go after your catalogs.
        val shownCollections = if (profile.kids) emptyList() else collections.onHome
        val (pinned, unpinned) = shownCollections.partition { it.pinToTop }
        fun addCollections(list: List<app.ember.tv.data.MediaCollection>) = list.forEach { add(HomeViewModel.HomeBlock.Collection("collection:${it.id}", it)) }
        for ((id, on) in layout) {
            if (!on) continue
            val before = size
            when (id) {
                HomeViewModel.HOME_HERO -> if (heroItems.isNotEmpty()) add(HomeViewModel.HomeBlock.Hero)
                HomeViewModel.HOME_APPS -> if (homeApps.isNotEmpty() || personalHomes.current.folders.any {f->f.packages.any(appAccess::canLaunch)}) add(HomeViewModel.HomeBlock.Apps)
                "pinned-shortcuts" -> if(personalOn && homeShortcuts.items.isNotEmpty())add(HomeViewModel.HomeBlock.Pinned(homeShortcuts.items))
                HomeViewModel.HOME_CONTINUE, HomeViewModel.HOME_RECENT, HomeViewModel.HOME_WATCHLIST -> if (personalOn) {
                    if (sections.listStyle(id) == "button") {
                        // Buttons next to each other share one line (HomeViewModel.joinShortcuts below).
                        add(HomeViewModel.HomeBlock.Shortcuts("shortcuts:$id", listOf(id)))
                    } else {
                        val (title, items) = when (id) {
                            HomeViewModel.HOME_CONTINUE -> "In progress" to inProgressItems
                            HomeViewModel.HOME_RECENT -> "Recently watched" to recentItems
                            else -> "Your watchlist" to combinedWatchlist
                        }
                        if (items.isNotEmpty()) add(HomeViewModel.HomeBlock.Row(id, CatalogRow("", title, items), saved = id == HomeViewModel.HOME_WATCHLIST))
                    }
                }
                HomeViewModel.HOME_UPNEXT -> upNextRow?.let { add(HomeViewModel.HomeBlock.Row("upnext", it)) }
                HomeViewModel.HOME_SERVERS -> if (personalOn) serverRows.filterNot { it.catalogKey?.endsWith(":watchlist") == true }.forEach { r ->
                    val shown = if (r.inProgress) r.copy(items = r.items.filterNot { LocalLibrary.isListHidden("continue", it) }) else r
                    if (shown.items.isNotEmpty()) add(HomeViewModel.HomeBlock.Row(shown.catalogKey ?: "server:${shown.addonName}:${shown.title}", shown))
                }
                else -> if (HomeSectionsState.isCatalogSection(id)) {
                    val key = HomeSectionsState.catalogKey(id)
                    val row=rows.firstOrNull {it.catalogKey==key}
                    if(row!=null) {
                        catalogsShown+=key
                        add(HomeViewModel.HomeBlock.Row("addon:$key",row))
                    } else if(catalogs.loading && count {it is HomeViewModel.HomeBlock.Loading}<3) {
                        allCatalogs.firstOrNull {it.key==key}?.let {add(HomeViewModel.HomeBlock.Loading("addon:$key",it.label))}
                    }
                } else if (HomeWidgets.isSection(id) && !profile.kids) {
                    widgets.list.firstOrNull { HomeWidgets.section(it.id) == id }?.let { w ->
                        val items = widgetItems(w)
                        if (items.isNotEmpty()) add(HomeViewModel.HomeBlock.Row(id, CatalogRow("", w.title, items, catalogKey = id), widget = w))
                        else if (w.id in widgets.loading && count { it is HomeViewModel.HomeBlock.Loading } < 3) add(HomeViewModel.HomeBlock.Loading(id, w.title, w))
                        else widgets.errors[w.id]?.let { add(HomeViewModel.HomeBlock.Notice(id, w.title, it)) }
                    }
                } else if (id.startsWith("explore:") && !profile.kids) {
                    explore.homeRows.firstOrNull { it.catalogKey == id }?.let { add(HomeViewModel.HomeBlock.Row(id, it)) }
                } else if (id.startsWith("smart:") && personalOn) {
                    val smartId = id.removePrefix("smart:")
                    otherSmartRows.filter { it.catalogKey == id || it.catalogKey?.startsWith("$id:") == true || (smartId == SmartRows.LISTS && it.catalogKey?.startsWith("smart:${SmartRows.LISTS}") == true) }
                        .forEach { r -> add(HomeViewModel.HomeBlock.Row(r.catalogKey ?: "$id:${r.addonName}:${r.title}", r)) }
                }
            }
            // #195: every row this section added takes the section's own card shape, when it has one.
            sections.shape(id)?.let { shape -> for (i in before until size) (this[i] as? HomeViewModel.HomeBlock.Row)?.let { this[i] = it.copy(shape = shape) } }
        }
        // A Kids profile's Home is its allowed catalogs, even with the section off.
        if (profile.kids) rows.filter { it.catalogKey !in catalogsShown }.forEachIndexed { i, r ->
            add(HomeViewModel.HomeBlock.Row("addon:${r.catalogKey ?: "$i:${r.addonName}:${r.title}"}", r))
        }
        // Pinned collections go right after the banner and apps when those lead, else first.
        val lead = indexOfFirst { it !is HomeViewModel.HomeBlock.Hero && it !is HomeViewModel.HomeBlock.Apps && it !is HomeViewModel.HomeBlock.Shortcuts }.let { if (it < 0) size else it }
        pinned.reversed().forEach { add(lead, HomeViewModel.HomeBlock.Collection("collection:${it.id}", it)) }
        addCollections(unpinned)
    }.mapNotNull { block ->
        if (block is HomeViewModel.HomeBlock.Row) block.copy(row = block.row.copy(items = block.row.items.filter(this::homeTitleVisible))).takeIf { it.row.items.isNotEmpty() }
        else block
    }.let { HomeViewModel.joinShortcuts(it) }
