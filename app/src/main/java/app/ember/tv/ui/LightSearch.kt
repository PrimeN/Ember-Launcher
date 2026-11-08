package app.ember.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.ember.tv.HomeViewModel
import app.ember.tv.data.AppEntry
import app.ember.tv.device.DeviceInputs

/** Local results appear as you type; network title search remains debounced and cancellable. */
@Composable
internal fun LightSearchScreen(vm: HomeViewModel, onLaunch: (AppEntry) -> Unit, onAppOptions: (AppEntry) -> Unit) {
    val context = LocalContext.current
    val query = vm.search.searchQuery.trim()
    val inputs = rememberLightInputs(0)
    val settings = remember(query) {
        val light = SettingDefinition("wallpaper", SettingsTab.APPEARANCE, "Ember Light Home", "Light wallpaper carousel dock six columns more apps idle motion background aurora ocean", app.ember.tv.data.LightLayout.backgrounds)
        val words = query.lowercase(java.util.Locale.ROOT).split(Regex("\\s+")).filter { it.length >= 2 }
        (SettingDefinitions.matching(query) + if (words.isNotEmpty() && words.all { it in light.searchText }) listOf(light) else emptyList())
            .plus(if(words.isNotEmpty())SETTINGS_INDEX.filter {(_,text)->words.all {it in text.lowercase(java.util.Locale.ROOT)}}.map {(tab,_)->SettingDefinition("page",tab,tab.label,"")} else emptyList())
            .distinctBy { it.title }.take(6)
    }
    val matchedInputs = remember(inputs, query) { if (query.isBlank()) emptyList() else inputs.filter {
        val connector = when (it.type) { android.media.tv.TvInputInfo.TYPE_HDMI -> "HDMI"; android.media.tv.TvInputInfo.TYPE_TUNER -> "TV"; else -> "AV" }
        it.label.contains(query, ignoreCase = true) || connector.contains(query, true) || query.equals("inputs", true)
    }.take(6) }
    val apps = if (query.length == 1) vm.appAccess.page(false).filter { it.label.contains(query, true) }.take(12) else emptyList()
    val shortcuts = settings.map { setting ->
        SearchShortcut("setting:${setting.tab.name}:${setting.id}", setting.title, "Settings") {
            vm.openSettingsPage(setting.tab.name)
        }
    } + matchedInputs.map { input ->
        SearchShortcut("input:${input.id}", input.label, "Inputs") {
            vm.openSettingsGuarded { DeviceInputs.open(context, input.id, includeTuners = true) }
        }
    }
    SearchScreen(vm, onOpen = vm::openDetails, onLaunch, onAppOptions,
        localApps = apps, localShortcuts = shortcuts)
}
