package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R
import androidx.compose.runtime.Composable
import app.ember.tv.HomeViewModel
import app.ember.tv.state.DiscoverSection

@Composable
internal fun DiscoverSettings(vm: HomeViewModel) {
    Section(stringResource(R.string.st_discover_sections), stringResource(R.string.st_explore_is_shown_by_default_catalogs)) {
        DiscoverSection.entries.forEach { section ->
            val on = vm.look.discoverSections[section.name] ?: when (section) {
                DiscoverSection.EXPLORE -> true
                DiscoverSection.CATALOGS -> section in vm.discoverSections
                DiscoverSection.COLLECTIONS -> vm.collections.onDiscover.isNotEmpty()
            }
            val note = when (section) {
                DiscoverSection.EXPLORE -> "Movie and show discovery. Requires a TMDB key; unavailable to Kids profiles."
                DiscoverSection.CATALOGS -> "Installed, enabled catalogs that can be browsed in Discover."
                DiscoverSection.COLLECTIONS -> "Collections selected for Discover in Settings > Home > Widgets & collections."
            }
            SettingToggle(section.name.lowercase().replaceFirstChar { it.uppercase() }, note, on) { vm.look.updateDiscoverSection(section.name, !on) }
        }
        Chip(stringResource(R.string.st_restore_automatic_defaults)) { vm.look.resetDiscoverSections() }
    }
    Section(stringResource(R.string.st_ai_assisted_discovery), stringResource(R.string.st_optional_describe_what_to_watch_then), id = "ai-discovery") {
        SettingToggle(stringResource(R.string.st_ai_assisted_discovery), stringResource(R.string.st_off_by_default_manual_filters_and), vm.look.aiDiscoveryEnabled) {
            vm.look.updateAiDiscoveryEnabled(!vm.look.aiDiscoveryEnabled)
        }
        Chip(stringResource(R.string.settings_keys)) { vm.openSettingsPage("KEYS") }
    }
}
