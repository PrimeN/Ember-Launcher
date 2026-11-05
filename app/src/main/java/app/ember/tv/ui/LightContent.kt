package app.ember.tv.ui

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.ember.tv.HomeViewModel
import app.ember.tv.device.DeviceInput
import app.ember.tv.device.DeviceInputs
import app.ember.tv.light.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

@Composable
internal fun rememberLightSlides(vm: HomeViewModel, refresh: Int): List<LightSlide> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val options = vm.look.lightHome
    val desktop=vm.personalHomes.current
    val profile = vm.profiles.profileId
    val kids = vm.profiles.profile.kids
    val watch = options.watchNext && vm.profiles.personalOn
    val allowed = vm.appAccess.page(false).map { it.pkg }.toSet()
    var external by remember(profile) { mutableStateOf(emptyList<LightMedia>()) }
    LaunchedEffect(profile, allowed, watch, options.recommendations, kids, refresh, lifecycle, desktop.mutedApps,desktop.dismissed,desktop.hideShortClips,desktop.hideCredits) {
        external = emptyList()
        if(refresh>0)LightMediaProvider.invalidate()
        if (!kids && (watch || options.recommendations)) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            callbackFlow {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { LightMediaProvider.invalidate();trySend(Unit) } }
                runCatching {
                    context.contentResolver.registerContentObserver(LightMediaProvider.watchUri, true, observer)
                    context.contentResolver.registerContentObserver(LightMediaProvider.previewUri, true, observer)
                    context.contentResolver.registerContentObserver(LightMediaProvider.channelUri, true, observer)
                }
                trySend(Unit)
                awaitClose { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
            }.conflate().collectLatest {
                external = LightMediaProvider.read(context, allowed, watch, options.recommendations,desktop)
            }
        }
    }
    val own = if (vm.profiles.personalOn && options.watchNext) (
        (if (vm.sections.sectionOn(HomeViewModel.HOME_CONTINUE)) vm.inProgressItems else emptyList()) +
            (if (vm.sections.sectionOn(HomeViewModel.HOME_UPNEXT)) vm.upNextRow?.items.orEmpty() else emptyList())) else emptyList()
    return remember(own, external, vm.heroItems, options, kids, allowed, watch,desktop) {
        LightCarousel.compose(own, LightMediaProvider.personalized(LightMediaProvider.visible(external, allowed, kids, watch, options.recommendations),desktop),
            if (options.recommendations) vm.heroItems else emptyList())
    }
}

internal val LocalInternetStatus=staticCompositionLocalOf<Boolean?> {null}

/** Public connectivity callbacks, registered only while this screen is visible. */
@Composable
internal fun rememberLightInternet(): Boolean {
    LocalInternetStatus.current?.let {return it}
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var connected by remember { mutableStateOf(false) }
    LaunchedEffect(context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            callbackFlow {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                fun send() { trySend(runCatching { manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true }.getOrDefault(false)) }
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = send()
                    override fun onLost(network: Network) = send()
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = send()
                }
                val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
                send()
                awaitClose { if (registered) runCatching { manager.unregisterNetworkCallback(callback) } }
            }.conflate().collect { connected = it }
        }
    }
    return connected
}

@Composable
internal fun rememberLightInputs(refresh: Int): List<DeviceInput> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var inputs by remember { mutableStateOf(emptyList<DeviceInput>()) }
    LaunchedEffect(context, refresh, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            inputs = withContext(Dispatchers.IO) { DeviceInputs.list(context, includeTuners = true) }
        }
    }
    return inputs
}
