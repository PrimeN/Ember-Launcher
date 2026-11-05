package app.ember.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import app.ember.tv.HomeViewModel
import app.ember.tv.data.LocalLibrary
import app.ember.tv.data.PersonalListSources

@Composable
internal fun PersonalListSettings(vm: HomeViewModel) {
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) }
    Section("Omni-channel list sources", "Choose sources separately for each list and Ember profile. Excluded sources keep their own data.") {
        ButtonRow(spacing = 8.dp) {
            PersonalListSources.lists.forEach { list -> Chip(list.second) { selected = list } }
        }
    }
    selected?.let { (list, label) ->
        MediaDialog("$label sources", { selected = null }) {
            Note("Applies to ${vm.profile.name} on Home and the full list page.")
            PersonalListSources.sources.forEach { (source, name) ->
                ToggleRow(name, null, vm.sections.sourceEnabled(vm.profiles.profileId, list, source)) {
                    vm.sections.toggleSource(vm.profiles.profileId, list, source)
                }
            }
            if (list != "recent") Chip("Restore hidden $label items") { LocalLibrary.restoreList(list) }
        }
    }
}
