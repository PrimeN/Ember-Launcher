package app.ember.tv.ui

import android.content.Context
import android.media.MediaRouter
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

internal object AudioOutputShortcut {
    /** Public Android 14 API; requires no privileged system-router permission. */
    fun openSystemPicker(context: Context): Boolean = Build.VERSION.SDK_INT >= 34 &&
        runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)
}

/** Older firmware can expose selectable routes through the public legacy router. */
@Suppress("DEPRECATION")
@Composable
internal fun AudioOutputDialog(close: () -> Unit) {
    val context = LocalContext.current
    val router = remember(context) { context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter }
    var revision by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    DisposableEffect(router) {
        val callback = object : MediaRouter.SimpleCallback() {
            override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) { revision++ }
            override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) { revision++ }
            override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) { revision++ }
            override fun onRouteSelected(r: MediaRouter, type: Int, route: MediaRouter.RouteInfo) { revision++ }
        }
        router?.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, callback)
        onDispose { router?.removeCallback(callback) }
    }
    @Suppress("UNUSED_VARIABLE") val observed = revision
    val routes = router?.let { r -> (0 until r.routeCount).map(r::getRouteAt)
        .filter { it.supportedTypes and MediaRouter.ROUTE_TYPE_LIVE_AUDIO != 0 } }.orEmpty()
    val selected = router?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
    var requested by remember { mutableStateOf<MediaRouter.RouteInfo?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(requested, attempt) {
        requested?.let { route ->
            delay(1000)
            if (router?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO) != route)
                note = "This TV did not allow the output change. Try Bluetooth or its sound settings."
            revision++
        }
    }
    CompactActionMenu("Audio output", routes.map { it.name.toString() } + listOf("Bluetooth and accessories", "Sound settings"), close) { first ->
        if (routes.isEmpty()) Note("This firmware does not expose selectable audio outputs to other apps.")
        routes.forEachIndexed { index, route ->
            MenuActionRow(route.name.toString(), selected = route == selected,icon=Icons.Outlined.Speaker,
                modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier)) {
                if (route.isEnabled) {
                    note = null
                    runCatching { router?.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, route); requested = route; attempt++ }
                        .onFailure { note = "This TV did not allow the output change." }
                } else note = "This output is not currently available."
            }
        }
        note?.let { Note(it) }
        MenuActionRow("Bluetooth and accessories", if (routes.isEmpty()) Modifier.focusRequester(first) else Modifier,Icons.Outlined.Bluetooth,divider=routes.isNotEmpty()) { QuickActions.open(context, Settings.ACTION_BLUETOOTH_SETTINGS) }
        MenuActionRow("Sound settings",icon=Icons.Outlined.Settings) { QuickActions.open(context, Settings.ACTION_SOUND_SETTINGS) }
    }
}
