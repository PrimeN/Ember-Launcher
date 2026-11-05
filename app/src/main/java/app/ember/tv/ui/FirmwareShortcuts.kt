package app.ember.tv.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.ember.tv.device.DeviceMenu
import app.ember.tv.device.DeviceMenuKind
import app.ember.tv.device.DeviceMenus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Discover once per opening, off the UI thread; device-event updates need not rescan packages. */
@Composable
internal fun rememberFirmwareShortcuts(): State<List<DeviceMenu>> {
    val context = LocalContext.current
    return produceState<List<DeviceMenu>>(emptyList(), context) {
        value = withContext(Dispatchers.IO) {
            DeviceMenus.detect(context).filter { it.kind in setOf(DeviceMenuKind.PICTURE, DeviceMenuKind.DEVICE) }
        }
    }
}
