package app.ember.tv.ui

import app.ember.tv.isSaved

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.AppEntry
import app.ember.tv.data.Meta
import app.ember.tv.data.SearchPresentation
import app.ember.tv.data.SearchFilter

/**
 * Search titles in every addon that supports search, plus your apps and your watchlist.
 * Voice uses the TV's speech recognizer.
 */
@Composable
fun SearchScreen(vm: HomeViewModel, onOpen: (Meta) -> Unit, onLaunch: (AppEntry) -> Unit,
    onAppOptions: (AppEntry) -> Unit, autoFocus: Boolean = true, navigationUp: FocusRequester? = null,
    localMatches: Int = 0, localResults: (@Composable () -> Unit)? = null) {
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var filter by rememberSaveable(vm.profiles.profileId) { mutableStateOf(SearchFilter.ALL) }
    var filtersOpen by rememberSaveable(vm.profiles.profileId) { mutableStateOf(false) }
    var combine by rememberSaveable(vm.profiles.profileId) { mutableStateOf(false) }
    var voiceProfile by remember { mutableStateOf<String?>(null) }
    val groups = remember(vm.search.searchRows, filter, combine) { SearchPresentation.groups(vm.search.searchRows, filter, combine) }
    val saved = vm.search.searchSaved.filter { SearchPresentation.accepts(it, filter) }
    val showApps = filter == SearchFilter.ALL || filter == SearchFilter.APPS
    val count = groups.sumOf { it.items.size } + saved.size + (if (showApps) vm.search.searchApps.size else 0) + localMatches
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && voiceProfile == vm.profiles.profileId) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?.let { vm.submitSearch(it) }
        }
    }
    val startVoice = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search movies, shows and apps")
        try {
            voiceProfile = vm.profiles.profileId
            voice.launch(intent)
        } catch (e: ActivityNotFoundException) {
            vm.showNotice("Voice search is not available on this TV. Type instead.")
        }
    }

    // Opening Search by hovering/focusing its tab must leave focus on navigation.
    // Light Home has no bar, so it can place focus in the field (OK starts typing).
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { field.requestFocus() } }
    LaunchedEffect(vm.search.voicePending) {
        if (vm.search.voicePending) {
            vm.search.voiceHandled()
            startVoice()
        }
    }

    if (filtersOpen) MediaDialog("Search filters", { filtersOpen = false }) {
        SearchFilter.entries.forEach { choice -> Chip(choice.label, selected = choice == filter) { filter = choice; filtersOpen = false } }
        SettingToggle("Combine duplicates", null, combine) { combine = !combine }
    }
    val compactFilters = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f
    Column(Modifier.fillMaxSize().padding(horizontal = TvTokens.Space.Page.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvTextField(
                    vm.search.searchQuery,
                    { vm.search.setQuery(it) },
                    Modifier.weight(1f).onPreviewKeyEvent { event ->
                        if (navigationUp != null && event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                            keyboard?.hide()
                            runCatching { navigationUp.requestFocus(); true }.getOrDefault(false)
                        } else false
                    },
                    placeholder = "Search titles and apps",
                    fontSize = TvTokens.Type.Section.sp,
                    imeAction = ImeAction.Search,
                    onSubmit = { vm.submitSearch(vm.search.searchQuery) },
                    focusRequester = field,
                    startEditing = false,
                )
                Chip(stringResource(R.string.ui_voice)) { startVoice() }
                if (vm.search.searchQuery.isNotEmpty()) Chip(stringResource(R.string.ui_clear)) { vm.search.setQuery("") }
            }

        ButtonRow(spacing = 8.dp) {
            if (compactFilters) Chip("Filter: ${filter.label}") { filtersOpen = true }
            else {
                SearchFilter.entries.forEach { choice -> Chip(choice.label, selected = filter == choice) { filter = choice } }
                SwitchChip("Combine duplicates", combine) { combine = !combine }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("$count results" + if (vm.search.searching) " · Searching…" else "", color = Mute, fontSize = TvTokens.Type.Compact.sp)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (localResults != null) item(key = "local") { localResults() }
        if (vm.search.searchQuery.isBlank() && vm.recentSearches.isNotEmpty()) item(key = "recent") {
            Section("Recent searches", "This profile only") {
                ButtonRow { vm.recentSearches.forEach { query -> Chip(query) { vm.submitSearch(query) } } }
                Chip("Clear recent searches") { field.requestFocus(); vm.clearRecentSearches() }
            }
        }
        if (count == 0 && !vm.search.searching && vm.search.searchQuery.trim().length >= 2 && filter != SearchFilter.ALL) item(key = "filtered-empty") {
            Section("No ${filter.label.lowercase()} matches", "Try another filter or change your search.") {
                Chip("Show all results") { filter = SearchFilter.ALL }
            }
        }
        vm.search.searchNote?.let { note -> item(key = "note") {
            if (vm.search.sourceUnavailable && localMatches == 0) {
                RecoveryPanel("Search unavailable", note, "Source settings", vm::openSourceSettings)
                Chip("Retry search") { vm.search.setQuery(vm.search.searchQuery, immediate = true) }
            } else if (!vm.search.sourceUnavailable) Text(note, color = Mute, fontSize = TvTokens.Type.Compact.sp)
        } }

        if (showApps && vm.search.searchApps.isNotEmpty()) {
            item(key = "apps") {
                Section(stringResource(R.string.ui_apps)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp)) {
                        items(vm.search.searchApps, key = { it.pkg }) { app ->
                            AppTile(app, onClick = { onLaunch(app) }, onLongClick = { onAppOptions(app) })
                        }
                    }
                }
            }
        }
        if (saved.isNotEmpty()) {
            item(key = "saved") {
                Section(stringResource(R.string.ui_in_your_watchlist)) { TitleRow(saved, isSaved = { true }, onOpen = onOpen) }
            }
        }
        items(groups, key = { it.catalogKey ?: "${it.addonName}:${it.title}" }) { row ->
            Section(row.displayTitle, row.addonName) {
                TitleRow(row.items, isSaved = { vm.isSaved(it) }, onOpen = onOpen)
            }
        }
        if (vm.search.searchQuery.trim().length < 2) {
            item(key = "hint") {
                Text(
                    stringResource(R.string.ui_type_at_least_two_letters_or),
                    color = Mute,
                    fontSize = TvTokens.Type.Compact.sp,
                )
            }
        }
    }
    }
}
