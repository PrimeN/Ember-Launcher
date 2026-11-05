package app.ember.tv.ui

import android.content.*
import android.hardware.display.DisplayManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.*
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

internal data class QuickPanelSnapshot(val info: QuickActions.Info, val inputs: List<app.ember.tv.device.DeviceInput>)

/** Device events refresh the snapshot; minute ticks update the clock without four-second polling. */
@Composable
internal fun rememberQuickPanelSnapshot(): State<QuickPanelSnapshot?> {
    val context = LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    return produceState<QuickPanelSnapshot?>(null, context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            callbackFlow {
                fun refresh() { trySend(Unit) }
                val handler = Handler(Looper.getMainLooper())
                val network = context.getSystemService(ConnectivityManager::class.java)
                val displays = context.getSystemService(DisplayManager::class.java)
                val audio = context.getSystemService(AudioManager::class.java)
                val tv = context.getSystemService(TvInputManager::class.java)
                val netCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(n: Network) = refresh()
                    override fun onLost(n: Network) = refresh()
                    override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = refresh()
                    override fun onLinkPropertiesChanged(n: Network, p: LinkProperties) = refresh()
                }
                val displayCallback = object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(id: Int) = refresh()
                    override fun onDisplayRemoved(id: Int) = refresh()
                    override fun onDisplayChanged(id: Int) = refresh()
                }
                val audioCallback = object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) = refresh()
                    override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) = refresh()
                }
                val tvCallback = object : TvInputManager.TvInputCallback() {
                    override fun onInputAdded(id: String) = refresh()
                    override fun onInputRemoved(id: String) = refresh()
                    override fun onInputUpdated(id: String) = refresh()
                    override fun onTvInputInfoUpdated(input: TvInputInfo) = refresh()
                    override fun onInputStateChanged(id: String, state: Int) = refresh()
                }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) = refresh()
                }
                val netRegistered = runCatching { network.registerDefaultNetworkCallback(netCallback) }.isSuccess
                val displayRegistered = runCatching { displays.registerDisplayListener(displayCallback, handler) }.isSuccess
                val audioRegistered = runCatching { audio.registerAudioDeviceCallback(audioCallback, handler) }.isSuccess
                val tvRegistered = tv != null && runCatching { tv.registerCallback(tvCallback, handler) }.isSuccess
                val receiverRegistered = runCatching {
                    ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                        addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED)
                        addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_LOCALE_CHANGED)
                        addAction(Intent.ACTION_DEVICE_STORAGE_LOW); addAction(Intent.ACTION_DEVICE_STORAGE_OK)
                    }, ContextCompat.RECEIVER_NOT_EXPORTED)
                }.isSuccess
                refresh()
                awaitClose {
                    if (netRegistered) runCatching { network.unregisterNetworkCallback(netCallback) }
                    if (displayRegistered) runCatching { displays.unregisterDisplayListener(displayCallback) }
                    if (audioRegistered) runCatching { audio.unregisterAudioDeviceCallback(audioCallback) }
                    if (tvRegistered) runCatching { tv.unregisterCallback(tvCallback) }
                    if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
                }
            }.conflate().collectLatest {
                value = withContext(Dispatchers.Default) { QuickPanelSnapshot(QuickActions.info(context), app.ember.tv.device.DeviceInputs.list(context)) }
            }
        }
    }
}
