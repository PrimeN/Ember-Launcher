package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R
import androidx.compose.runtime.Composable
import app.ember.tv.HomeViewModel
import app.ember.tv.state.DiscoverSection
import androidx.tv.material3.Text
import androidx.compose.ui.unit.sp

@Composable
internal fun DiscoverSettings(vm: HomeViewModel) {
    Section(stringResource(R.string.st_discover_sections), stringResource(R.string.st_explore_is_shown_by_default_catalogs)) {
        // #404: the three sections as cards, like API keys: a green tick when shown, a red cross when not. OK switches it.
        val tiles = DiscoverSection.entries.map { section ->
            val on = vm.look.discoverSections[section.name] ?: when (section) {
                DiscoverSection.EXPLORE -> true
                DiscoverSection.SPARK -> app.ember.tv.tracker.Tmdb.available
                DiscoverSection.CATALOGS -> section in vm.discoverSections
                DiscoverSection.COLLECTIONS -> vm.collections.onDiscover.isNotEmpty()
            }
            val line = when (section) {
                DiscoverSection.EXPLORE -> "Movies and shows · needs TMDB"
                DiscoverSection.SPARK -> "Picks from what you watch · needs TMDB"
                DiscoverSection.CATALOGS -> "Your addons' catalogs"
                DiscoverSection.COLLECTIONS -> "Collections you pick"
            }
            ServiceTileInfo(section.name, section.name.lowercase().replaceFirstChar { it.uppercase() },
                if (on) ServiceMark.OK else ServiceMark.PROBLEM, if (on) "Shown" else "Hidden", line, tagStrong = on)
        }
        ServiceGrid(tiles) { t -> vm.look.updateDiscoverSection(t.id, t.mark != ServiceMark.OK) }
        Chip(stringResource(R.string.st_restore_automatic_defaults)) { vm.look.resetDiscoverSections() }
    }
    // #437: Spark's settings: your trackers' history, keeping everything, refreshing on its own, Home rows.
    Section("Spark", "Picks from what you watch, in Discover and as Home rows.", id = "spark") {
        SparkHistoryPanel(vm)
        SettingToggle("Keep everything", "Also keeps each title's full details from Trakt on this TV: story, genres, running time, age rating, rating, network and more. " +
            "Spark then knows the kind of every title you've watched without asking TMDB. Uses more room, about 1 MB for a big library.", vm.spark.keepEverything) {
            vm.spark.updateKeepEverything(!vm.spark.keepEverything)
        }
        Choice(listOf("off" to "Only when I ask", "daily" to "Daily", "weekly" to "Weekly"), vm.spark.autoRefresh, "Bring my history in again") { vm.spark.updateAutoRefresh(it) }
        Text("Spark's rows can also be Home rows: Create widget › Your lists › Spark.", color = Mute, fontSize = 13.sp)
    }
    Section(stringResource(R.string.st_ai_assisted_discovery), stringResource(R.string.st_optional_describe_what_to_watch_then), id = "ai-discovery") {
        SettingToggle(stringResource(R.string.st_ai_assisted_discovery), stringResource(R.string.st_off_by_default_manual_filters_and), vm.look.aiDiscoveryEnabled) {
            vm.look.updateAiDiscoveryEnabled(!vm.look.aiDiscoveryEnabled)
        }
        Chip(stringResource(R.string.settings_keys)) { vm.openSettingsPage("KEYS") }
    }
}
