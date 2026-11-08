package app.ember.tv

import android.app.Application
import androidx.lifecycle.viewModelScope
import app.ember.tv.data.AppRepository
import app.ember.tv.player.PlayerApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

// HomeViewModel: installed apps, their order and visibility, and refreshes when the launcher returns.
internal fun HomeViewModel.onLauncherResume() {
    refreshApps()
    homeShortcuts.reload()
    if (cachedHomeLoaded) queueResumeRefresh()
}

internal fun HomeViewModel.queueResumeRefresh() {
    refreshQueue.submit("trackers") { refreshTrackers(force=false); trackerRefreshJob?.join() }
    if (serverPlaybackDirty) {
        // Returning from playback is an explicit freshness request, even if an older
        // automatic server group is still completing.
        serverPlaybackDirty=false
        refreshServers(force=true)
    } else refreshQueue.submit("servers") {
        refreshServers()
        serverRefreshJob?.join()
    }
    refreshQueue.submit("weather") { weather.refreshWeather(); weather.awaitRefresh() }
}

internal fun HomeViewModel.refreshApps(force:Boolean=false) {
    if(appsJob?.isActive==true && !force)return
    if(!force && app.ember.tv.data.appDiscoveryFresh(appsLoadedAt,android.os.SystemClock.elapsedRealtime()))return
    appsJob?.cancel()
    // Loading every app's banner is slow on some TVs, so it runs off the main thread.
    val ctx = getApplication<Application>()
    appsJob = viewModelScope.launch {
        try {
            val loaded = withContext(Dispatchers.IO) {
                val work=kotlinx.coroutines.currentCoroutineContext()
                AppRepository.load(ctx) {work.ensureActive()}
            }
            ensureActive()
            appsLoadedAt=android.os.SystemClock.elapsedRealtime()
            allApps = loaded
            apps = AppRepository.sorted(loaded, prefs.order)
            val installedPlayers = withContext(Dispatchers.IO) {
                try {
                    val work=kotlinx.coroutines.currentCoroutineContext()
                    PlayerApps.videoPlayers(ctx) {work.ensureActive()}
                } catch(e:CancellationException) {throw e}
                  catch(e:Exception) {app.ember.tv.data.CrashLog.note("Could not refresh installed video players",e);null}
            }
            ensureActive()
            installedPlayers?.let(playing::setInstalled)
        } catch(e:CancellationException) {throw e}
          catch(e:Exception) {app.ember.tv.data.CrashLog.note("Could not refresh installed apps",e)}
    }
}

// ---- apps ----
internal fun HomeViewModel.toggleHidden(pkg: String) {
    hidden = if (pkg in hidden) hidden - pkg else hidden + pkg
    prefs.hidden = hidden
    val wasHidden = pkg !in hidden
    offerUndo(if (wasHidden) "App restored on Home" else "App hidden from Home") {
        hidden = if (wasHidden) hidden + pkg else hidden - pkg
        prefs.hidden = hidden
    }
}

internal fun HomeViewModel.setAppOrder(order: List<String>) {
    if(look.uiStyle==app.ember.tv.data.InterfaceStyles.LIGHT){setLightAppOrder(order);return}
    // Recheck the allowlist at save time, including profile/app changes during Move mode.
    val permitted = order.filter { appAccess.canLaunch(it) }
    val merged = app.ember.tv.data.AppOrder.merge(apps.map { it.pkg }, permitted)
    prefs.order = merged
    apps = AppRepository.sorted(allApps, merged)
}

internal fun HomeViewModel.moveApp(pkg: String, delta: Int) {
    if(look.uiStyle==app.ember.tv.data.InterfaceStyles.LIGHT) {
        val items=lightAppItems().map {it.pkg};val from=items.indexOf(pkg);val to=from+delta
        if(from>=0 && to in items.indices)setLightAppOrder(app.ember.tv.data.AppOrder.move(items,from,to))
        return
    }
    val order = apps.map { it.pkg }.toMutableList()
    val i = order.indexOf(pkg)
    if (i < 0) return
    // Step over hidden apps, so moving a visible app always changes what Home shows.
    var j = i + delta
    if (pkg !in hidden) while (j in order.indices && order[j] in hidden) j += delta
    if (j !in order.indices) return
    order.removeAt(i)
    order.add(j, pkg)
    prefs.order = order
    apps = AppRepository.sorted(allApps, order)
}

internal fun HomeViewModel.updateHomeAppRows(n: Int) {
    homeAppRows = n.coerceIn(1, 2)
    prefs.homeAppRows = homeAppRows
}

internal fun HomeViewModel.toggleAppsPage(pkg: String) {
    appsPageOff = if (pkg in appsPageOff) appsPageOff - pkg else appsPageOff + pkg
    prefs.appsPageOff = appsPageOff
}
