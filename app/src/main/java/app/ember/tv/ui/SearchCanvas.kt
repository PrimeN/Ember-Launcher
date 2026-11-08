package app.ember.tv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.R
import app.ember.tv.data.*

/** Local actions are typed results, so media filters never count hidden settings/input matches. */
class SearchShortcut(val id: String, val title: String, val group: String, val open: () -> Unit)

internal data class SearchCanvasState(
    val profileId: String = "", val query: String = "", val rows: List<CatalogRow> = emptyList(),
    val saved: List<Meta> = emptyList(), val apps: List<AppEntry> = emptyList(),
    val shortcuts: List<SearchShortcut> = emptyList(), val searching: Boolean = false,
    val note: String? = null, val unavailable: Boolean = false, val recent: List<String> = emptyList(),
)

internal class SearchCanvasActions(
    val query: (String) -> Unit, val submit: (String) -> Unit, val voice: () -> Unit,
    val clearRecent: () -> Unit, val open: (Meta) -> Unit, val launch: (AppEntry) -> Unit,
    val appOptions: (AppEntry) -> Unit, val sources: () -> Unit, val retry: () -> Unit,
    val isSaved: (Meta) -> Boolean = { false },
)

/** A single virtualized canvas: source labels live with results rather than taking whole rows. */
@Composable
internal fun SearchCanvas(state: SearchCanvasState, actions: SearchCanvasActions,
    modifier: Modifier = Modifier, autoFocus: Boolean = true, navigationUp: FocusRequester? = null) {
    key(state.profileId) {
        var filter by rememberSaveable { mutableStateOf(SearchFilter.ALL) }
        var combine by rememberSaveable { mutableStateOf(false) }
        var filtersOpen by remember { mutableStateOf(false) }
        var restoreFilters by remember { mutableStateOf(false) }
        val field = remember { FocusRequester() }
        val filters = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val results = remember(state.rows, state.saved, filter, combine) {
            SearchPresentation.results(state.rows, state.saved, filter, combine)
        }
        val apps = if (filter in listOf(SearchFilter.ALL, SearchFilter.APPS)) state.apps else emptyList()
        val shortcuts = if (filter == SearchFilter.ALL) state.shortcuts else emptyList()
        val count = results.size + apps.size + shortcuts.size
        val anyMatches = state.rows.any { it.items.isNotEmpty() } || state.saved.isNotEmpty() ||
            state.apps.isNotEmpty() || state.shortcuts.isNotEmpty()
        val typed = state.query.trim().length >= 2
        fun closeFilters() { restoreFilters = true; filtersOpen = false }
        LaunchedEffect(autoFocus) { if (autoFocus) runCatching { field.requestFocus() } }
        LaunchedEffect(filtersOpen) {
            if (!filtersOpen && restoreFilters) { restoreFilters = false; runCatching { filters.requestFocus() } }
        }
        if (filtersOpen) MediaDialog(stringResource(R.string.search_filters), ::closeFilters) {
            SearchFilter.entries.forEach { choice ->
                Chip(choice.label, selected = filter == choice, maxLines = 1) { filter = choice; closeFilters() }
            }
            SettingToggle(stringResource(R.string.search_combine), null, combine) { combine = !combine }
        }
        CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides Fg) {
            BoxWithConstraints(modifier.fillMaxSize().testTag("search_canvas")) {
                val fontScale = LocalDensity.current.fontScale
                val compact = maxWidth < 620.dp || fontScale > 1.25f
                Column(Modifier.fillMaxSize().padding(horizontal = if (maxWidth < 620.dp) 24.dp else TvTokens.Space.Page.dp,
                    vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth().focusGroup().onPreviewKeyEvent { event ->
                        if (navigationUp != null && event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                            keyboard?.hide()
                            runCatching { navigationUp.requestFocus() }.getOrDefault(false)
                        } else false
                    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvTextField(state.query, actions.query, Modifier.weight(1f).testTag("search_field"),
                            placeholder = stringResource(R.string.search_placeholder), fontSize = TvTokens.Type.Navigation.sp,
                            imeAction = ImeAction.Search, onSubmit = { actions.submit(state.query) },
                            focusRequester = field, startEditing = false, glass = true)
                        if (state.query.isNotEmpty()) SearchIcon(stringResource(R.string.ui_clear), Icons.Outlined.Close,
                            Modifier.testTag("search_clear")) { field.requestFocus(); actions.query("") }
                        SearchIcon(stringResource(R.string.ui_voice), Icons.Outlined.Mic,
                            Modifier.testTag("search_voice"), actions.voice)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LazyRow(Modifier.weight(1f).focusGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(horizontal = 3.dp, vertical = 3.dp)) {
                            val choices = if (compact) listOf(filter) else listOf(SearchFilter.ALL, SearchFilter.MOVIES,
                                SearchFilter.SHOWS, SearchFilter.APPS).let { if (filter == SearchFilter.WATCHLIST) it + filter else it }
                            items(choices, key = { it.name }) { choice ->
                                Chip(choice.label, selected = filter == choice,
                                    modifier = Modifier.testTag("search_filter_${choice.name}"), maxLines = 1) {
                                    if (compact) filtersOpen = true else filter = choice
                                }
                            }
                        }
                        SearchFiltersButton(combine || filter == SearchFilter.WATCHLIST,
                            Modifier.focusRequester(filters).testTag("search_filters")) { filtersOpen = true }
                    }
                    if (typed || count > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.search_results), fontSize = TvTokens.Type.Label.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (state.searching) stringResource(R.string.search_searching)
                            else pluralStringResource(R.plurals.search_result_count, count, count),
                            fontSize = TvTokens.Type.Caption.sp, color = Mute,
                            modifier = Modifier.testTag("search_count").semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    // A new query/filter starts at the top; arriving providers keep the current viewport.
                    key(state.query.trim(), filter) {
                        val grid = rememberLazyGridState()
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            val columns = LocalRenderOptions.current.columns(maxWidth.value, fontScale)
                            LazyVerticalGrid(GridCells.Fixed(columns), state = grid, modifier = Modifier.fillMaxSize().testTag("search_grid"),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (state.query.isBlank() && state.recent.isNotEmpty()) item(key = "recent", span = { GridItemSpan(maxLineSpan) }) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text(stringResource(R.string.search_recent), fontSize = TvTokens.Type.Label.sp)
                                            SearchIcon(stringResource(R.string.search_clear_recent), Icons.Outlined.Close) {
                                                field.requestFocus(); actions.clearRecent()
                                            }
                                        }
                                        ButtonRow { state.recent.forEach { recent -> Chip(recent, maxLines = 1) { actions.submit(recent) } } }
                                    }
                                }
                                if (apps.isNotEmpty()) item(key = "apps", span = { GridItemSpan(maxLineSpan) }) {
                                    SearchApps(apps, actions)
                                }
                                if (shortcuts.isNotEmpty()) item(key = "shortcuts", span = { GridItemSpan(maxLineSpan) }) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        shortcuts.groupBy { it.group }.forEach { (group, items) ->
                                            Text(group, fontSize = TvTokens.Type.Label.sp, fontWeight = FontWeight.SemiBold)
                                            ButtonRow { items.forEach { shortcut -> Chip(shortcut.title, maxLines = 1, onClick = shortcut.open) } }
                                        }
                                    }
                                }
                                gridItems(results, key = { it.key }, contentType = { "media" }) { result ->
                                    SearchMediaCard(result, result.saved || actions.isSaved(result.meta), actions.open)
                                }
                                if (count == 0 && !state.searching && typed) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                                    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        if (filter != SearchFilter.ALL) {
                                            Text(stringResource(R.string.search_no_filtered_matches, filter.label), color = Mute)
                                            Chip(stringResource(R.string.search_show_all), maxLines = 1) { filter = SearchFilter.ALL }
                                        } else if (state.unavailable && !anyMatches) {
                                            RecoveryPanel(stringResource(R.string.search_unavailable), state.note.orEmpty(),
                                                stringResource(R.string.search_sources), actions.sources)
                                            Chip(stringResource(R.string.search_retry), maxLines = 1, onClick = actions.retry)
                                        } else Text(state.note ?: stringResource(R.string.search_no_matches), color = Mute)
                                    }
                                }
                                if (!typed && count == 0) item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                                    Text(stringResource(R.string.ui_type_at_least_two_letters_or), color = Mute,
                                        fontSize = TvTokens.Type.Compact.sp, modifier = Modifier.padding(vertical = 12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchMediaCard(result: SearchResult, saved: Boolean, open: (Meta) -> Unit) {
    BoxWithConstraints(Modifier.testTag("search_result_${result.key}")) {
        val cardWidth = maxWidth / result.meta.cardWidthScale
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // Wide/square provider art keeps its shape while respecting the grid's cell boundary.
            TitleCard(result.meta, saved, width = cardWidth) { open(result.meta) }
            if (tileLabelShown(TileLabels.MEDIA)) Text(
                listOfNotNull(result.meta.releaseInfo?.take(4)?.takeIf { it.all(Char::isDigit) && it.length == 4 },
                    result.sources.joinToString(" · ").takeIf { it.isNotBlank() }).joinToString(" · "),
                color = Mute, fontSize = TvTokens.Type.Caption.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchApps(apps: List<AppEntry>, actions: SearchCanvasActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ui_apps), fontSize = TvTokens.Type.Label.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)) {
            items(apps, key = { it.pkg }) { app ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    AppTile(app, width = 120.dp, onClick = { actions.launch(app) }, onLongClick = { actions.appOptions(app) })
                    if (tileLabelShown(TileLabels.APPS)) Text(app.label, Modifier.width(120.dp), fontSize = TvTokens.Type.Caption.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SearchIcon(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Focusable(click, modifier.size(44.dp).onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label },
        radius = TvTokens.Shape.Pill, container = Color.Transparent, focusedContainer = PillColor,
        focusedContent = OnPillColor, outline = false, focusedScale = TvTokens.Focus.Control) {
        Box(Modifier.fillMaxSize().glassSurface(RoundedCornerShape(TvTokens.Shape.Pill.dp), focused = focused), contentAlignment = Alignment.Center) {
            MaterialGlyph(icon, if (focused) OnPillColor else Fg, 20.dp)
        }
    }
}

@Composable
private fun SearchFiltersButton(active: Boolean, modifier: Modifier = Modifier, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Focusable(click, modifier.onFocusChanged { focused = it.isFocused }.semantics { selected = active },
        radius = TvTokens.Shape.Pill, container = if (active) MaterialTheme.colorScheme.primary.copy(alpha = .30f) else Color.Transparent,
        focusedContainer = PillColor, focusedContent = OnPillColor, outline = false, focusedScale = TvTokens.Focus.None) {
        Row(Modifier.glassSurface(RoundedCornerShape(TvTokens.Shape.Pill.dp), focused = focused)
            .padding(horizontal = 14.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            MaterialGlyph(Icons.Outlined.Tune, if (focused) OnPillColor else Fg, 18.dp)
            Text(stringResource(R.string.search_filters_button), fontSize = TvTokens.Type.Label.sp, maxLines = 1)
        }
    }
}
