package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R
import androidx.compose.runtime.Composable
import app.ember.tv.HomeViewModel
import app.ember.tv.state.DiscoverSection

@Composable
internal fun DiscoverSettings(vm: HomeViewModel) {
    Section(stringResource(R.string.st_discover_sections), stringResource(R.string.st_explore_is_shown_by_default_catalogs)) {
        // #404: the three sections as cards, like API keys: a green tick when shown, a red cross when not. OK switches it.
        val tiles = DiscoverSection.entries.map { section ->
            val on = vm.look.discoverSections[section.name] ?: when (section) {
                DiscoverSection.EXPLORE -> true
                DiscoverSection.CATALOGS -> section in vm.discoverSections
                DiscoverSection.COLLECTIONS -> vm.collections.onDiscover.isNotEmpty()
            }
            val line = when (section) {
                DiscoverSection.EXPLORE -> "Movies and shows · needs TMDB"
                DiscoverSection.CATALOGS -> "Your addons' catalogs"
                DiscoverSection.COLLECTIONS -> "Collections you pick"
            }
            ServiceTileInfo(section.name, section.name.lowercase().replaceFirstChar { it.uppercase() },
                if (on) ServiceMark.OK else ServiceMark.PROBLEM, if (on) "Shown" else "Hidden", line, tagStrong = on)
        }
        ServiceGrid(tiles) { t -> vm.look.updateDiscoverSection(t.id, t.mark != ServiceMark.OK) }
        Chip(stringResource(R.string.st_restore_automatic_defaults)) { vm.look.resetDiscoverSections() }
    }
    Section(stringResource(R.string.st_ai_assisted_discovery), stringResource(R.string.st_optional_describe_what_to_watch_then), id = "ai-discovery") {
        SettingToggle(stringResource(R.string.st_ai_assisted_discovery), stringResource(R.string.st_off_by_default_manual_filters_and), vm.look.aiDiscoveryEnabled) {
            vm.look.updateAiDiscoveryEnabled(!vm.look.aiDiscoveryEnabled)
        }
        Chip(stringResource(R.string.settings_keys)) { vm.openSettingsPage("KEYS") }
    }
}
