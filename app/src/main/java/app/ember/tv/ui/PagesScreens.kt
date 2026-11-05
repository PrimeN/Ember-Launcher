package app.ember.tv.ui

import app.ember.tv.isSaved
import app.ember.tv.retryTracker

import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import app.ember.tv.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.Meta
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import coil.compose.AsyncImage

/** Page title and one line of explanation, shared by the Watchlist, Discover and Library pages. */
@Composable
private fun PageHeader(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 6.dp)) {
        Text(title, fontSize = 26.sp)
        Text(subtitle, color = Mute, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Everything saved, from this TV and from connected trackers, as one grid. */
@Composable
fun WatchlistScreen(vm: HomeViewModel, onOpen: (Meta) -> Unit) = ListScreen(vm, app.ember.tv.data.NavTabs.WATCHLIST, onOpen)

/** The page behind For You's buttons: your watchlist, In progress or Recently watched, as a grid. */
@Composable
fun ListScreen(vm: HomeViewModel, which: String, onOpen: (Meta) -> Unit) {
    val (title, items, note) = when (which) {
        app.ember.tv.data.NavTabs.PROGRESS -> Triple(
            "In progress", vm.inProgressItems,
            if (vm.inProgressItems.isEmpty()) "Nothing part-way through. What you start in Ember shows here, with or without a tracker."
            else "Part-way through: played in Ember, on your trackers and on your media servers.",
        )
        app.ember.tv.data.NavTabs.RECENT -> Triple(
            "Recently watched", vm.recentItems,
            if (vm.recentItems.isEmpty()) "Nothing yet. What you play in Ember shows here, and your Trakt history when Trakt is connected."
            else "Played in Ember, newest first, and your Trakt history.",
        )
        else -> Triple(
            "Watchlist", vm.combinedWatchlist,
            if (vm.combinedWatchlist.isEmpty()) "Nothing saved yet. Open any title and choose Add to watchlist."
            else "${vm.combinedWatchlist.size} saved, on this TV and on your connected trackers.",
        )
    }
    var options by remember { mutableStateOf<Meta?>(null) }
    val listName = when (which) {
        app.ember.tv.data.NavTabs.PROGRESS -> "In progress"
        app.ember.tv.data.NavTabs.RECENT -> "Recently watched"
        else -> "your watchlist"
    }
    Column(Modifier.fillMaxSize().padding(horizontal = TvTokens.Space.Page.dp, vertical = 12.dp)) {
        PageHeader(title, note + if (items.isNotEmpty()) " Hold OK on a title to remove it." else "")
        if (which == app.ember.tv.data.NavTabs.RECENT && items.isNotEmpty()) {
            ButtonRow(spacing = 10.dp) { Chip(stringResource(R.string.ui_clear_recently_watched)) { vm.clearRecent() } }
        }
        SourceStatusButton("Tracker status", vm.trackerHealth, vm::retryTracker)
        if (items.isEmpty() && vm.trackerHealth.none { it.loading }) RecoveryPanel("Your $title is empty", note, "List sources") { vm.openSettingsPage(SettingsTab.HOME_ROWS.name) }
        if (items.isEmpty() && vm.trackerHealth.any { it.loading }) LoadingCards()
        if (items.isNotEmpty()) {
            TitleGrid(items, isSaved = { vm.isSaved(it) }, onOpen = onOpen, modifier = Modifier.fillMaxWidth().weight(1f), onLongPress = { options = it })
        }
    }
    options?.let { m ->
        val labels=listOf(stringResource(R.string.ui_open),
            if(which==app.ember.tv.data.NavTabs.WATCHLIST)"Remove from watchlist" else "Remove from $listName")
        CompactActionMenu(m.name,labels,{options=null}) {first->
            MenuActionRow(labels[0],Modifier.focusRequester(first),Icons.AutoMirrored.Outlined.OpenInNew) {options=null;onOpen(m)}
            MenuActionRow(labels[1],icon=Icons.Outlined.RemoveCircleOutline,destructive=true) {options=null;vm.removeFromList(which,m)}
        }
    }
}

/** Browse any catalog from your addons, by genre, a page at a time. */
@Composable
internal fun AddonDiscoverScreen(vm: HomeViewModel, onOpen: (Meta) -> Unit) {
    val navigation = LocalNavigationFocus.current
    Column(Modifier.fillMaxSize().padding(horizontal = TvTokens.Space.Page.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        vm.discover.discoverNote?.let { note ->
            if(app.ember.tv.data.KidsContentPolicy.restricted(vm.profile) && !vm.discover.failed)Note(note)
            else RecoveryPanel(if (vm.discover.failed) "Catalog unavailable" else "No titles to show", note,
                if (vm.discover.failed) "Retry catalog" else "Source settings",
                if (vm.discover.failed) ({
                    runCatching { navigation?.tab(app.ember.tv.data.NavTabs.DISCOVER)?.requestFocus() }
                    vm.discover.retry()
                }) else vm::openSourceSettings)
        }
        if (vm.discover.discoverLoading && vm.discover.discoverItems.isEmpty()) LoadingCards()
        if(!vm.discover.discoverLoading && vm.discover.discoverItems.isEmpty() && !vm.discover.discoverEnd && vm.discover.discoverCatalog!=null && !vm.discover.failed) {
            Chip("Load next page"){vm.discover.loadMoreDiscover()}
        }

        if (vm.discover.discoverItems.isNotEmpty()) {
            TitleGrid(
                vm.discover.discoverItems,
                isSaved = { vm.isSaved(it) },
                onOpen = onOpen,
                modifier = Modifier.fillMaxWidth().weight(1f),
                footer = if (vm.discover.discoverEnd) null else {
                    {
                        Chip(if (vm.discover.discoverLoading) "Loading..." else "Load more") { vm.discover.loadMoreDiscover() }
                    }
                },
            )
        }
    }
}

/** Part-way through on trackers and media servers, and recently watched on Trakt. */
@Composable
fun LibraryScreen(vm: HomeViewModel, onOpen: (Meta) -> Unit) {
    ServerOverview(vm,onOpen)
}

// ---------------------------------------------------------------- Live TV channel and guide components

internal fun timeOf(ms: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(ms))

@Composable
internal fun ChannelRow(
    c: app.ember.tv.iptv.Channel,
    nowNext: Pair<app.ember.tv.iptv.Programme?, app.ember.tv.iptv.Programme?>,
    favourite: Boolean,
    clock: Long,
    onFocus: () -> Unit,
    onPlay: () -> Unit,
    onFavourite: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Focusable(
        onClick = onPlay,
        onLongClick = onFavourite,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = listOfNotNull(c.name, nowNext.first?.title, nowNext.first?.let { timeOf(it.start) + " to " + timeOf(it.stop) }).joinToString(", ")
            if (favourite) stateDescription = "Favourite"
            nowNext.first?.let { p -> progressBarRangeInfo = ProgressBarRangeInfo(((clock - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f), 0f..1f) }
        }.onFocusChanged { if (it.isFocused) onFocus() },
        radius = 12,
    ) {
        Row(Modifier.clearAndSetSemantics { }.padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.number?.toString() ?: "", color = Mute, fontSize = 13.sp, modifier = Modifier.width(36.dp))
            Box(Modifier.width(72.dp).height(44.dp).background(PanelHi, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                if (c.logo != null) {
                    AsyncImage(model = c.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
                } else {
                    Text(c.name.take(3).uppercase(), color = Mute, fontSize = 12.sp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text((if (favourite) "★ " else "") + c.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val (now, next) = nowNext
                if (now != null) {
                    Text(now.title, fontSize = 13.sp, color = Mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val f = ((clock - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth(0.6f).height(3.dp).background(PanelHi, RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(f).height(3.dp).background(accent, RoundedCornerShape(2.dp)))
                    }
                }
                next?.let { Text(stringResource(R.string.ui_next_programme, timeOf(it.start), it.title), fontSize = 12.sp, color = Mute, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}
