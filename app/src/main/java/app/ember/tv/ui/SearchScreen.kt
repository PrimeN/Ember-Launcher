package app.ember.tv.ui

import app.ember.tv.submitSearch

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import app.ember.tv.HomeViewModel
import app.ember.tv.data.AppEntry
import app.ember.tv.data.Meta
import app.ember.tv.isSaved

/** Search delivery stays independent of the grid, including cancellable network and voice work. */
@Composable
fun SearchScreen(vm: HomeViewModel, onOpen: (Meta) -> Unit, onLaunch: (AppEntry) -> Unit,
    onAppOptions: (AppEntry) -> Unit, autoFocus: Boolean = true, navigationUp: FocusRequester? = null,
    localApps: List<AppEntry> = emptyList(), localShortcuts: List<SearchShortcut> = emptyList()) {
    var voiceProfile by remember { mutableStateOf<String?>(null) }
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
        } catch (_: ActivityNotFoundException) {
            vm.showNotice("Voice search is not available on this TV. Type instead.")
        }
    }
    LaunchedEffect(vm.search.voicePending) {
        if (vm.search.voicePending) {
            vm.search.voiceHandled()
            startVoice()
        }
    }
    SearchCanvas(
        state = SearchCanvasState(
            profileId = vm.profiles.profileId, query = vm.search.searchQuery,
            rows = vm.search.searchRows, saved = vm.search.searchSaved,
            apps = (localApps + vm.search.searchApps).distinctBy { it.pkg },
            shortcuts = localShortcuts, searching = vm.search.searching,
            note = vm.search.searchNote, unavailable = vm.search.sourceUnavailable,
            recent = vm.recentSearches,
        ),
        actions = SearchCanvasActions(
            query = vm.search::setQuery, submit = vm::submitSearch, voice = startVoice,
            clearRecent = vm::clearRecentSearches, open = onOpen, launch = onLaunch,
            appOptions = onAppOptions, sources = vm::openSourceSettings,
            retry = { vm.search.setQuery(vm.search.searchQuery, immediate = true) },
            isSaved = vm::isSaved,
        ),
        autoFocus = autoFocus, navigationUp = navigationUp,
    )
}
