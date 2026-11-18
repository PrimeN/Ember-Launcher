package app.ember.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.R
import app.ember.tv.data.ApiKeys

/** One optional step: its name, whether it is done, and the settings page that does it. */
private data class SetupStep(val label: String, val done: Boolean, val page: SettingsTab)

@Composable
private fun setupSteps(vm: HomeViewModel): List<SetupStep> {
    val metadataReady = remember(vm.keysRev) { ApiKeys.get("TMDB_API_KEY").isNotBlank() }
    val trackerReady = remember(vm.trackerRev) { vm.hub.trackers.any { it.isConnected() } }
    val context = androidx.compose.ui.platform.LocalContext.current
    val emberHome = remember { runCatching { app.ember.tv.home.DefaultHome.currentHome(context) == context.packageName || app.ember.tv.home.DefaultHome.serviceOn(context) }.getOrDefault(false) }
    return listOf(
        SetupStep("Catalogs and playback addons", vm.addons.isNotEmpty(), SettingsTab.ADDONS),
        SetupStep("Metadata", metadataReady, SettingsTab.KEYS),
        SetupStep("Personal media servers", vm.plexConnected || vm.serverAccounts.isNotEmpty(), SettingsTab.SERVERS),
        SetupStep("Watch history accounts", trackerReady, SettingsTab.TRACKERS),
        SetupStep("Ember as Home", emberHome, SettingsTab.DEFAULT_HOME),
    )
}

@Composable
private fun SetupStepChips(vm: HomeViewModel) {
    setupSteps(vm).forEach { step ->
        Chip(step.label + " · " + stringResource(if (step.done) R.string.setup_configured else R.string.setup_optional_action)) { vm.openSettingsPage(step.page.name) }
    }
}

/** Optional setup, never an onboarding gate. Configured is not a claim of current network health. */
@Composable
internal fun SetupChecklist(vm:HomeViewModel) {
    var expanded by remember {mutableStateOf(false)}
    Section(stringResource(R.string.setup_checklist),stringResource(R.string.setup_optional),id="setup-checklist") {
        Chip(if(expanded)stringResource(R.string.setup_hide) else stringResource(R.string.setup_show)) {expanded=!expanded}
        if(expanded) {
            Text(stringResource(R.string.setup_apps_ready),fontSize=TvTokens.Type.CompactBody.sp)
            SetupStepChips(vm)
            Text(stringResource(R.string.setup_health_hint),color=Mute,fontSize=TvTokens.Type.Compact.sp)
            SwitchChip(stringResource(R.string.setup_home_shortcut),!vm.look.setupChecklistHidden) {vm.look.hideSetupChecklist(!vm.look.setupChecklistHidden)}
        }
    }
}

/** #335: on a new install (nothing connected yet) the steps themselves are on Home, not a link to them. */
@Composable
internal fun HomeSetupChecklist(vm: HomeViewModel) {
    Column(Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.setup_checklist), fontSize = TvTokens.Type.Section.sp)
        Text(stringResource(R.string.setup_optional), color = Mute, fontSize = TvTokens.Type.Compact.sp)
        ButtonRow { SetupStepChips(vm) }
        ButtonRow { Chip("Dismiss") { vm.look.hideSetupChecklist(true) } }
    }
}
