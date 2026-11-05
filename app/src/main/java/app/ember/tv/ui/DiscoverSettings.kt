package app.ember.tv.ui

import androidx.compose.runtime.Composable
import app.ember.tv.HomeViewModel
import app.ember.tv.state.DiscoverSection

@Composable
internal fun DiscoverSettings(vm: HomeViewModel) {
    Section("Discover sections", "Explore is shown by default. Catalogs and Collections appear when content is available. A manual choice stays saved until you restore automatic defaults.") {
        DiscoverSection.entries.forEach { section ->
            val on = vm.look.discoverSections[section.name] ?: when (section) {
                DiscoverSection.EXPLORE -> true
                DiscoverSection.CATALOGS -> section in vm.discoverSections
                DiscoverSection.COLLECTIONS -> vm.collections.onDiscover.isNotEmpty()
            }
            val note = when (section) {
                DiscoverSection.EXPLORE -> "Movie and show discovery. Requires a TMDB key; unavailable to Kids profiles."
                DiscoverSection.CATALOGS -> "Installed, enabled catalogs that can be browsed in Discover."
                DiscoverSection.COLLECTIONS -> "Collections selected for Discover in Stremio settings."
            }
            SettingToggle(section.name.lowercase().replaceFirstChar { it.uppercase() }, note, on) { vm.look.updateDiscoverSection(section.name, !on) }
        }
        Chip("Restore automatic defaults") { vm.look.resetDiscoverSections() }
    }
    Section("AI-assisted discovery", "Optional: describe what to watch, then review and apply ordinary Discover filters. Only your typed description is sent to Mistral; no profile or watch history. Requires Mistral and TMDB keys; provider charges may apply.", id = "ai-discovery") {
        SettingToggle("AI-assisted discovery", "Off by default. Manual filters and saved searches remain available.", vm.look.aiDiscoveryEnabled) {
            vm.look.updateAiDiscoveryEnabled(!vm.look.aiDiscoveryEnabled)
        }
        Chip("API keys") { vm.openSettingsPage("KEYS") }
    }
}
