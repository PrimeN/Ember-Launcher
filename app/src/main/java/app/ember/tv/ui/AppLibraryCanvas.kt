package app.ember.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.ember.tv.R
import app.ember.tv.data.*

/** The input is already profile-authorized; showing hidden apps never expands profile permission. */
@Composable
internal fun AppLibraryCanvas(permitted: List<AppEntry>, hidden: Set<String>, profile: String,
    onLaunch: (AppEntry) -> Unit, onOptions: (AppEntry) -> Unit, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    key(profile) {
        var query by rememberSaveable { mutableStateOf("") }
        var sort by rememberSaveable { mutableStateOf(AppLibrarySort.CUSTOM) }
        var showHidden by rememberSaveable { mutableStateOf(false) }
        var sorting by remember { mutableStateOf(false) }
        var more by remember { mutableStateOf(false) }
        val field = remember { FocusRequester() }; val sortFocus = remember { FocusRequester() }; val moreFocus = remember { FocusRequester() }
        val visible = remember(permitted, hidden, showHidden) { permitted.filter { showHidden || it.pkg !in hidden } }
        val apps = remember(visible, query, sort) { AppLibraryPresentation.items(visible, query, sort) }
        val hiddenCount = remember(permitted, hidden) { permitted.distinctBy { it.pkg }.count { it.pkg in hidden } }
        // #121: an app that leaves the grid while focused (hidden, uninstalled) hands focus to the app
        // that takes its place, instead of leaving nothing focused.
        val tiles = remember { mutableMapOf<String, FocusRequester>() }
        var focusedPkg by remember { mutableStateOf<String?>(null) }
        var focusedIndex by remember { mutableIntStateOf(0) }
        val latestApps by rememberUpdatedState(apps)
        LaunchedEffect(apps) {
            val pkg = focusedPkg ?: return@LaunchedEffect
            if (apps.any { it.pkg == pkg }) return@LaunchedEffect
            focusedPkg = null
            withFrameNanos { }
            val next = apps.getOrNull(focusedIndex.coerceAtMost(apps.lastIndex))
            if (next == null || !runCatching { tiles[next.pkg]?.requestFocus() == true }.getOrDefault(false))
                runCatching { field.requestFocus() }
        }
        CompositionLocalProvider(LocalContentColor provides Fg) {
            BoxWithConstraints(modifier.fillMaxSize().testTag("app_library")) {
                val pageInset = if (maxWidth < 620.dp) 24.dp else TvTokens.Space.Page.dp
                Column(Modifier.fillMaxSize().padding(horizontal = pageInset, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TvTextField(query, { query = it }, Modifier.weight(1f).testTag("app_library_search"),
                            placeholder = stringResource(R.string.apps_find), fontSize = TvTokens.Type.Navigation.sp,
                            focusRequester = field, glass = true, onSubmit = {})
                        if (query.isNotEmpty()) GlassControl(stringResource(R.string.ui_clear), Icons.Outlined.Close,
                            Modifier.testTag("app_library_clear"), iconOnly = true) { field.requestFocus(); query = "" }
                        GlassControl(sort.label, Icons.AutoMirrored.Outlined.Sort,
                            Modifier.widthIn(max = 200.dp).focusRequester(sortFocus).testTag("app_library_sort"), dropdown = true) { sorting = true }
                        GlassControl(stringResource(R.string.apps_options), Icons.Outlined.MoreHoriz,
                            Modifier.focusRequester(moreFocus).testTag("app_library_more"), iconOnly = true) { more = true }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (query.isBlank()) stringResource(R.string.ui_your_apps) else stringResource(R.string.search_results),
                            fontSize = TvTokens.Type.Label.sp, fontWeight = FontWeight.SemiBold)
                        Text(pluralStringResource(R.plurals.apps_count, apps.size, apps.size), color = Mute,
                            fontSize = TvTokens.Type.Caption.sp, modifier = Modifier.testTag("app_library_count"))
                    }
                    if (apps.isEmpty()) Column(Modifier.weight(1f).padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(if (query.isBlank()) R.string.apps_none else R.string.apps_no_matches), color = Mute)
                        if (query.isNotEmpty()) Chip(stringResource(R.string.apps_clear_search), maxLines = 1) { field.requestFocus(); query = "" }
                    } else key(query.trim(), sort, showHidden) {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            val columns = LocalRenderOptions.current.columns(maxWidth.value, LocalDensity.current.fontScale)
                            LazyVerticalGrid(GridCells.Fixed(columns), modifier = Modifier.fillMaxSize().testTag("app_library_grid")
                                .onFocusChanged { state ->
                                    // Leaving the grid on purpose (the toolbar, a page) forgets the tile.
                                    if (!state.hasFocus && latestApps.any { it.pkg == focusedPkg }) focusedPkg = null
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                itemsIndexed(apps, key = { _, app -> app.pkg }, contentType = { _, _ -> "app" }) { index, app ->
                                    val tile = remember(app.pkg) { FocusRequester() }
                                    DisposableEffect(app.pkg) {
                                        tiles[app.pkg] = tile
                                        onDispose { if (tiles[app.pkg] === tile) tiles.remove(app.pkg) }
                                    }
                                    BoxWithConstraints(Modifier.testTag("app_library_${app.pkg}")) {
                                        val width = maxWidth
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            AppTile(app, dim = app.pkg in hidden, width = width,
                                                onClick = { onLaunch(app) }, onLongClick = { onOptions(app) },
                                                modifier = Modifier.focusRequester(tile).onFocusChanged {
                                                    if (it.isFocused) { focusedPkg = app.pkg; focusedIndex = index }
                                                })
                                            if (tileLabelShown(TileLabels.APPS)) Text(app.label, fontSize = TvTokens.Type.Compact.sp,
                                                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (sorting) OptionMenu(stringResource(R.string.apps_sort), AppLibrarySort.entries.map { choice ->
                MenuOption(choice.label, selected = choice == sort) { sort = choice }
            }) { sorting = false }
            if (more) OptionMenu(stringResource(R.string.apps_options), buildList {
                if (hiddenCount > 0) add(MenuOption(if (showHidden) stringResource(R.string.apps_hide_hidden)
                    else pluralStringResource(R.plurals.apps_show_hidden, hiddenCount, hiddenCount), selected = showHidden,
                    icon = if (showHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility) { showHidden = !showHidden })
                if (query.isNotEmpty() || sort != AppLibrarySort.CUSTOM || showHidden) add(MenuOption(stringResource(R.string.apps_reset_view)) {
                    query = ""; sort = AppLibrarySort.CUSTOM; showHidden = false
                })
                add(MenuOption(stringResource(R.string.apps_refresh), icon = Icons.Outlined.Refresh, onPick = onRefresh))
            }) { more = false }
        }
        RestoreToolbarFocus(sorting, sortFocus)
        RestoreToolbarFocus(more, moreFocus)
    }
}
