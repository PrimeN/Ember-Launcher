package app.ember.tv.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.ember.tv.HomeViewModel
import app.ember.tv.R
import app.ember.tv.device.DeviceMenu
import app.ember.tv.device.DeviceMenuKind
import app.ember.tv.device.DeviceMenus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** System destination for accessible firmware engineering menus; refreshed after returning from one. */
@Composable
internal fun EngineeringMenusSettings(vm: HomeViewModel) {
    if (!vm.settingsAllowed) return
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { revision++; onPauseOrDispose {} }
    val menus by produceState<List<DeviceMenu>?>(null, context, revision) {
        value = withContext(Dispatchers.IO) { DeviceMenus.detect(context).filter { it.kind == DeviceMenuKind.ENGINEERING } }
    }
    Section(stringResource(R.string.device_engineering), stringResource(R.string.engineering_intro), id = "engineering-menus") {
        if (menus == null) Note(stringResource(R.string.device_detecting))
        else if (menus.orEmpty().isEmpty()) Note(stringResource(R.string.engineering_none))
        ButtonRow {
            menus.orEmpty().forEach { menu ->
                Chip(menu.title, modifier = Modifier.widthIn(max = 360.dp)) {
                    vm.openSettingsGuarded { DeviceMenus.open(context, menu) }
                }
            }
        }
    }
}
