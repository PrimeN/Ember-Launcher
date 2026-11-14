package app.ember.tv.player

import android.content.Context
import android.media.MediaRouter

/** Uses the selected audio route, never the mere presence of a paired Bluetooth device. */
internal class OutputRouteMonitor(context: Context, private val changed: (Boolean) -> Unit) : AutoCloseable {
    private val router = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
    var bluetooth = false; private set
    private var initialized = false
    private val callback = object : MediaRouter.SimpleCallback() {
        override fun onRouteSelected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) = refresh()
        override fun onRouteUnselected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) = refresh()
    }
    init { router.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, callback); refresh() }
    internal fun refresh() {
        val selected = router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)?.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH
        if (initialized && selected == bluetooth) return
        initialized = true
        bluetooth = selected
        changed(bluetooth)
    }
    override fun close() { router.removeCallback(callback) }
}

internal fun PlaybackConfig.outputDelay(bluetooth: Boolean): Int =
    (if (bluetooth) bluetoothDelayMs ?: audioDelayMs else audioDelayMs).coerceIn(-10_000, 10_000)
