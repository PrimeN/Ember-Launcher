package app.ember.tv.ui

import app.ember.tv.data.TileLabels

import app.ember.tv.isSaved

import androidx.compose.ui.res.stringResource
import app.ember.tv.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PushPin
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.Meta
import app.ember.tv.data.MediaCollection
import app.ember.tv.data.CollectionFolder
import app.ember.tv.data.CollectionMotion
import app.ember.tv.data.CollectionMediaKind
import coil.compose.AsyncImage
import androidx.compose.material.icons.outlined.BookmarkBorder as BookmarkBorderIcon
import androidx.compose.material.icons.outlined.History as HistoryIconImport
import androidx.compose.material.icons.outlined.PlayCircleOutline as PlayCircleIconImport

/** A Collection on Home: its title, then a row of folder tiles. */
@Composable
fun CollectionRow(c: MediaCollection, animations: Boolean = false, onMove: ((CollectionFolder, Int) -> Unit)? = null,
    /** Deletes the folder, or the whole collection when the folder is null (#157). */
    onDelete: ((CollectionFolder?) -> Unit)? = null, onOpen: (CollectionFolder) -> Unit) {
    // Hold OK on a folder to move it (#69), e.g. the ones used most to the front.
    var moving by remember(c.id) { mutableStateOf<CollectionFolder?>(null) }
    var deleting by remember(c.id) { mutableStateOf<Pair<Boolean, CollectionFolder>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(c.title, fontSize = TvTokens.Type.Section.sp, fontWeight = FontWeight.Medium, color = Fg, modifier = Modifier.padding(start = 4.dp))
        // Sized like the other rows: wide folders 5¼ across, poster folders like poster cards.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = CardSizes.width(maxWidth, CardSizes.WIDE, 16.dp, 4.dp)
            val poster = CardSizes.width(maxWidth, CardSizes.POSTER, 16.dp, 4.dp)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
            ) {
                items(c.folders, key = { it.id }) { f -> FolderTile(f, tileSize(f, wide, poster), glow = c.focusGlowEnabled != false, animations = animations, owner = c.id, backdrop = c.backdropImageUrl,
                    onLongClick = onMove?.let { { moving = f } }) { onOpen(f) } }
            }
        }
    }
    moving?.let { f ->
        val index = c.folders.indexOfFirst { it.id == f.id }
        val last = c.folders.lastIndex
        if (index < 0 || onMove == null) { moving = null; return@let }
        OptionMenu(f.title.ifBlank { "Folder" }, buildList {
            if (index > 0) { add(MenuOption("To the front") { onMove(f, -index) }); add(MenuOption("Left") { onMove(f, -1) }) }
            if (index < last) { add(MenuOption("Right") { onMove(f, 1) }); add(MenuOption("To the end") { onMove(f, last - index) }) }
            if (onDelete != null) {
                add(MenuOption("Delete this folder", icon = Icons.Outlined.Delete) { deleting = false to f })
                add(MenuOption("Delete the whole collection", icon = Icons.Outlined.Delete) { deleting = true to f })
            }
        }) { moving = null }
    }
    deleting?.let { (whole, f) ->
        if (whole) ConfirmDestructive("Delete ${c.title.ifBlank { "this collection" }}?", CollectionDeletion.collectionMessage(c.folders.size), { deleting = null }) { onDelete?.invoke(null) }
        else ConfirmDestructive("Delete ${f.title.ifBlank { "this folder" }}?", CollectionDeletion.folderMessage(c.folders.size == 1), { deleting = null }) { onDelete?.invoke(f) }
    }
}

private fun tileSize(f: CollectionFolder, wide: Dp, poster: Dp): Pair<Dp, Dp> = when (f.shape) {
    "wide" -> wide to wide * 0.5625f
    "poster" -> poster to poster * 1.5f
    else -> poster * 1.25f to poster * 1.25f
}

/**
 * A folder tile: its cover, swapped for the animated cover while focused (when the folder has
 * one), and the name underneath unless the folder hides it.
 */
@Composable
private fun FolderTile(f: CollectionFolder, size: Pair<Dp, Dp>, glow: Boolean, animations: Boolean, owner: String, backdrop: String?, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val background = (f.heroBackdropUrl ?: backdrop ?: f.coverImageUrl)?.takeUnless { CollectionMotion.knownKind(it) == CollectionMediaKind.VIDEO }
    val focusBackground = rememberFocusTile(FocusTileKind.COLLECTION, "$owner/${f.id}", background)
    val (w, h) = size
    val motion = CollectionMotion.allowed(animations, focused, collectionMotionActive(), LocalReduceMotion.current)
    Column(Modifier.width(w), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Focusable(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.width(w).height(h).focusBackdrop(focusBackground).onFocusChanged { focused = it.isFocused || it.hasFocus },
            container = PanelHi,
            outline = glow,
            card=true,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val animated = f.focusGifUrl?.takeIf { motion && f.focusGifEnabled != false && it.isNotBlank() }
                val cover = f.coverImageUrl?.takeIf { it.isNotBlank() }
                var coverFailed by remember(cover, animations) { mutableStateOf(false) }
                when {
                    cover != null && !coverFailed && CollectionMotion.knownKind(cover) != CollectionMediaKind.VIDEO ->
                        CollectionImage(cover, f.title, Modifier.fillMaxSize(), animate = motion && f.focusGifEnabled != false && animated == null, onError = { coverFailed = true }, onDrawable = { focusBackground.loaded(it, cover) })
                    !f.coverEmoji.isNullOrBlank() -> Text(f.coverEmoji, fontSize = 48.sp)
                    else -> Text(f.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
                }
                val preview = animated ?: cover?.takeIf { motion && f.focusGifEnabled != false && CollectionMotion.knownKind(it) == CollectionMediaKind.VIDEO }
                CollectionMediaPreview(preview, motion, Modifier.fillMaxSize())
            }
        }
        if (!f.hideTitle && tileLabelShown(TileLabels.COLLECTIONS)) Text(f.title, fontSize = TvTokens.Type.Card.sp, color = if (focused) Fg else Mute, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
    }
}

/**
 * An open folder. Tabbed grid: a tab per source (and "All" first when the collection asks), with
 * a grid of titles. Rows (and "follow layout"): a row per source. Back closes it.
 */
@Composable
fun FolderScreen(vm: HomeViewModel, onOpenTitle: (Meta) -> Unit) {
    val (c, f) = vm.collections.open ?: return
    BackHandler { vm.collections.closeFolder() }
    val tabs = vm.collections.tabs
    val views = rememberSaveableStateHolder()
    var search by rememberSaveable(vm.profiles.profileId, c.id, f.id) { mutableStateOf("") }
    var tools by remember(vm.profiles.profileId, c.id, f.id) { mutableStateOf(false) }
    val toolsFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var contentFocused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    // The tile that opened the folder is gone, so focus moves into the page once it has content.
    val enter = remember { androidx.compose.ui.focus.FocusRequester() }
    val ready = tabs.isNotEmpty() && (tabs.any { it.items.isNotEmpty() } || tabs.none { it.loading })
    androidx.compose.runtime.LaunchedEffect(f.id, ready) {
        if (ready && FocusMemory.pending == null) {
            kotlinx.coroutines.delay(80)
            runCatching { enter.requestFocus() }
        }
    }
    val focusedTile = LocalFocusBackdrop.current?.tile != null
    val showBackdrop = vm.look.collectionFocusBackground == app.ember.tv.data.FocusBackground.ARTWORK && !focusedTile
    Box(Modifier.fillMaxSize().sceneEnter("folder:${c.id}/${f.id}").background(if (focusedTile) Color.Transparent else Ink)) {
        val backdrop = f.heroBackdropUrl ?: c.backdropImageUrl
        if (showBackdrop && !backdrop.isNullOrBlank()) {
            CollectionImage(backdrop, null, Modifier.fillMaxSize(), animate = vm.collections.heroPreviews && !vm.profiles.profile.kids && contentFocused && !tools && f.heroVideoUrl.isNullOrBlank(), alpha = .25f)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.6f to Ink)))
        }
        CollectionHeroPreview(f.heroVideoUrl, showBackdrop && vm.collections.heroPreviews && !vm.profiles.profile.kids && contentFocused && !tools && vm.details == null, Modifier.fillMaxSize())
        if (showBackdrop && !f.heroVideoUrl.isNullOrBlank()) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .75f to Ink)))
        Column(
            Modifier.fillMaxSize().onFocusChanged { contentFocused = it.hasFocus }.padding(start = 48.dp, end = 48.dp, top = 12.dp)
                .focusRequester(enter).focusGroup(),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.title, fontSize = 12.sp, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!f.titleLogoUrl.isNullOrBlank()) CollectionImage(f.titleLogoUrl, f.title,
                        contentScale = ContentScale.Fit, modifier = Modifier.height(36.dp).width(280.dp))
                    else Text(f.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!vm.profiles.profile.kids) {
                    val shortcut = app.ember.tv.data.HomeShortcut("collection", f.title, c.id, f.id)
                    val pinned = vm.homeShortcuts.contains(shortcut)
                    ToolbarIcon(if (pinned) "Unpin from Home" else "Pin to Home", Icons.Outlined.PushPin, selected = pinned) { vm.homeShortcuts.toggle(shortcut) }
                }
                ToolbarIcon("Folder options and search", Icons.Outlined.MoreHoriz, Modifier.focusRequester(toolsFocus)) { tools = true }
            }
            val grid = c.viewMode.uppercase() == "TABBED_GRID"
            if (tabs.isEmpty()) {
                LoadingCards(wide = false)
            } else if (grid) {
                val withAll = c.showAllTab && tabs.size > 1
                val keys = vm.collections.tabKeys(c, f)
                var selectedKey by rememberSaveable(vm.profiles.profileId, c.id, f.id) { mutableStateOf(keys.getOrNull(vm.collections.savedTab(c, f))) }
                val tab = keys.indexOf(selectedKey).coerceAtLeast(0)
                LaunchedEffect(keys, selectedKey) { if (selectedKey !in keys) selectedKey = keys.firstOrNull() }
                fun pick(t: Int) {
                    selectedKey = keys.getOrNull(t)
                    vm.collections.saveTab(c, f, t)
                    vm.collections.prioritize(t - if (withAll) 1 else 0)
                }
                LazyRow(contentPadding = PaddingValues(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (withAll) item { Chip(stringResource(R.string.ui_all), selected = tab == 0) { pick(0) } }
                    itemsIndexed(tabs, key = { i, _ -> i }) { i, t -> Chip(t.label.take(45), selected = tab == i + if (withAll) 1 else 0) { pick(i + if (withAll) 1 else 0) } }
                }
                val index = tab - if (withAll) 1 else 0
                val current = tabs.getOrNull(index)
                val items = (if (withAll && tab == 0) vm.collections.allItems else current?.items.orEmpty()).filter { search.isBlank() || it.name.contains(search.trim(), true) }
                when {
                    items.isNotEmpty() -> {
                        val all = withAll && tab == 0
                        val more = if (all) tabs.any { !it.end } else current?.end == false
                        val busy = if (all) tabs.any { it.loading } else current?.loading == true
                        val failed = if (all) tabs.any { it.failed } else current?.failed == true
                        views.SaveableStateProvider("${vm.profiles.profileId}/${c.id}/${f.id}/${keys.getOrNull(tab)}") {
                        val gridState = rememberLazyGridState()
                        LaunchedEffect(search) { if (search.isNotBlank()) gridState.scrollToItem(0) }
                        LaunchedEffect(gridState, vm.collections.autoPage, items.size, busy, more, search) {
                            if (vm.collections.autoPage && !busy && more && search.isBlank()) snapshotFlow {
                                gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                            }.distinctUntilChanged().collect { last ->
                                if (last >= (items.size - 8).coerceAtLeast(0)) vm.collections.loadMore(if (all) -1 else index)
                            }
                        }
                        TitleGrid(
                            items, isSaved = { vm.isSaved(it) }, onOpen = onOpenTitle, modifier = Modifier.fillMaxSize(), state = gridState,
                            footer = if (!more) null else {
                                { PageFooter(vm.collections.autoPage, busy, failed) { vm.collections.loadMore(if (all) -1 else index) } }
                            },
                        )
                        }
                    }
                    withAll && tab == 0 && tabs.any { it.loading } -> LoadingCards(wide = false)
                    withAll && tab == 0 && tabs.any { it.failed } -> RecoveryPanel("Collection sources unavailable",
                        "Could not load some sources. Retry to check again.", "Retry sources", { vm.collections.loadMore(-1) })
                    withAll && tab == 0 -> Text("Nothing here.", color = Mute, fontSize = TvTokens.Type.Card.sp)
                    current?.loading == true -> LoadingCards(wide = false)
                    current?.failed == true -> RecoveryPanel("Collection source unavailable", current.error.orEmpty(), "Retry", { vm.collections.loadMore(index) })
                    else -> Text(current?.error ?: "Nothing here.", color = Mute, fontSize = TvTokens.Type.Card.sp)
                }
                val all = withAll && tab == 0
                val more = if (all) tabs.any { !it.end } else current?.end == false
                val busy = if (all) tabs.any { it.loading } else current?.loading == true
                val failed = if (all) tabs.any { it.failed } else current?.failed == true
                if (items.isEmpty() && more && !busy && !failed) Chip("Load more") { vm.collections.loadMore(if (all) -1 else index) }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(tabs, key = { i, t -> "$i:${t.label}" }) { i, t ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(t.label, fontSize = TvTokens.Type.Section.sp, fontWeight = FontWeight.Medium, color = Fg, modifier = Modifier.padding(start = 4.dp))
                            val matches = t.items.filter { search.isBlank() || it.name.contains(search.trim(), true) }
                            when {
                                // Near the end of a row, its next page loads on its own (#109).
                                matches.isNotEmpty() -> TitleRow(matches, isSaved = { vm.isSaved(it) }, onFocused = { m ->
                                    if (vm.collections.autoPage && search.isBlank() && !t.loading && !t.end && !t.failed && nearEnd(matches.indexOf(m), matches.size, 4)) vm.collections.loadMore(i)
                                }, onOpen = onOpenTitle)
                                t.loading -> LoadingCards(wide = false)
                                else -> Text(if (search.isNotBlank() && t.items.isNotEmpty()) "No loaded titles match this search." else t.error ?: "Nothing here.", color = Mute, fontSize = 13.sp, modifier = Modifier.padding(4.dp))
                            }
                            if (!t.loading && !t.end && (t.failed || !vm.collections.autoPage)) Chip(if (t.failed) "Retry" else "Load more") { vm.collections.loadMore(i) }
                        }
                    }
                }
            }
        }
    }
    RestoreToolbarFocus(tools, toolsFocus)
    if (tools) MediaDialog("Folder options", { tools = false }, resizeForKeyboard = true) {
        Text("${tabs.sumOf { it.items.size }} titles loaded" + (if (tabs.any { it.failed }) " · ${tabs.count { it.failed }} unavailable sources" else ""), color = Mute, fontSize = 12.sp)
        SwitchChip("Animated collections", vm.collections.heroPreviews) { vm.collections.enableHeroPreviews(!vm.collections.heroPreviews) }
        Text("Muted videos and GIFs start after focus settles and stop when you leave. Off or reduced motion keeps static covers.", color = Mute, fontSize = 12.sp)
        TvTextField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = "Search loaded titles")
        if (c.viewMode.uppercase() == "TABBED_GRID") SwitchChip("Load more as I scroll", vm.collections.autoPage) { vm.collections.updateAutoPage(!vm.collections.autoPage) }
        Chip("Refresh sources") { tools = false; vm.refreshCollection() }
        Text("Search filters the titles already loaded. Load more or refresh to find additional titles.", color = Mute, fontSize = 13.sp)
    }

}

/** Settings > Stremio > Collections: import Collections, choose which show on Home, reorder, remove. */
@Composable
internal fun CollectionsSettings(vm: HomeViewModel) = CollectionManager(vm)

/** For You's buttons for In progress, Recently watched and the watchlist (Settings > Home layout: as a button). */
@Composable
fun ListButtons(vm: HomeViewModel, ids: List<String>, onOpen: (String) -> Unit) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ids.forEach { id ->
            when (id) {
                HomeViewModel.HOME_CONTINUE -> ListButton(androidx.compose.material.icons.Icons.Outlined.PlayCircleIconImport, "In progress", countText(vm.inProgressItems.size, "Nothing part-way through")) { onOpen(app.ember.tv.data.NavTabs.PROGRESS) }
                HomeViewModel.HOME_RECENT -> ListButton(androidx.compose.material.icons.Icons.Outlined.HistoryIconImport, "Recently watched", countText(vm.recentItems.size, "Nothing yet")) { onOpen(app.ember.tv.data.NavTabs.RECENT) }
                else -> ListButton(androidx.compose.material.icons.Icons.Outlined.BookmarkBorderIcon, "Your watchlist", countText(vm.combinedWatchlist.size, "Nothing saved yet")) { onOpen(app.ember.tv.data.NavTabs.WATCHLIST) }
            }
        }
    }
}

private fun countText(n: Int, none: String) = if (n == 0) none else "$n title" + if (n == 1) "" else "s"

@Composable
private fun ListButton(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Focusable(
        onClick = onClick,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp).width(300.dp).heightIn(min=76.dp).onFocusChanged { focused = it.isFocused || it.hasFocus },
        container = PanelHi,
    ) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().heightIn(min=76.dp).padding(horizontal = 18.dp,vertical=12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MaterialGlyph(icon, if (focused) Fg else MaterialTheme.colorScheme.primary, 26.dp)
            Column {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Fg)
                Text(subtitle, fontSize = 13.sp, color = Mute)
            }
        }
    }
}

/** For You's watchlist button (kept for older callers): opens the watchlist page. */
@Composable
fun WatchlistButton(count: Int, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Focusable(
        onClick = onClick,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp).width(300.dp).heightIn(min=76.dp).onFocusChanged { focused = it.isFocused || it.hasFocus },
        container = PanelHi,
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min=76.dp).padding(horizontal = 18.dp,vertical=12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MaterialGlyph(androidx.compose.material.icons.Icons.Outlined.BookmarkBorderIcon, if (focused) Fg else MaterialTheme.colorScheme.primary, 26.dp)
            Column {
                Text(stringResource(R.string.ui_your_watchlist), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Fg)
                Text(if (count == 0) "Nothing saved yet" else "$count title" + if (count == 1) "" else "s", fontSize = 13.sp, color = Mute)
            }
        }
    }
}
