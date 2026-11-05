package app.ember.tv.ui

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.R
import app.ember.tv.data.ApiKeys

/** Optional setup, never an onboarding gate. Configured is not a claim of current network health. */
@Composable
internal fun SetupChecklist(vm:HomeViewModel) {
    var expanded by remember {mutableStateOf(false)}
    val metadataReady=remember(vm.keysRev){ApiKeys.get("TMDB_API_KEY").isNotBlank()}
    val trackerReady=remember(vm.trackerRev){vm.hub.trackers.any {it.isConnected()}}
    Section(stringResource(R.string.setup_checklist),stringResource(R.string.setup_optional),id="setup-checklist") {
        Chip(if(expanded)stringResource(R.string.setup_hide) else stringResource(R.string.setup_show)) {expanded=!expanded}
        if(expanded) {
            Text(stringResource(R.string.setup_apps_ready),fontSize=TvTokens.Type.CompactBody.sp)
            listOf(
                Triple("Metadata",metadataReady,"KEYS"),
                Triple("Catalogs and playback add-ons",vm.addons.isNotEmpty(),"ADDONS"),
                Triple("Personal media servers",vm.plexConnected || vm.serverAccounts.isNotEmpty(),"SERVERS"),
                Triple("Watch history accounts",trackerReady,"TRACKERS")
            ).forEach {(label,configured,page)->
                Chip(label+" · "+stringResource(if(configured)R.string.setup_configured else R.string.setup_optional_action)) {vm.openSettingsPage(page)}
            }
            Text(stringResource(R.string.setup_health_hint),color=Mute,fontSize=TvTokens.Type.Compact.sp)
            SwitchChip(stringResource(R.string.setup_home_shortcut),!vm.look.setupChecklistHidden) {vm.look.hideSetupChecklist(!vm.look.setupChecklistHidden)}
        }
    }
}
