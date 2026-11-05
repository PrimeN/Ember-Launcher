package app.ember.tv.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ember.tv.data.AddonClient
import app.ember.tv.data.Features
import app.ember.tv.data.HomeSnapshot
import app.ember.tv.data.Prefs
import app.ember.tv.data.DevicePerformance
import app.ember.tv.data.AddonAccess
import app.ember.tv.iptv.IptvController
import app.ember.tv.iptv.refreshActiveController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit

/**
 * Background refresh (Settings > Features > Refresh in the background): every few hours, while
 * the TV is on a network, the saved Home rows are reloaded from your addons and the IPTV channels
 * and guide are updated, so Home and Live TV are current the moment you turn the TV on.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.backgroundRefresh) return Result.success()
        runCatching { refreshHomeRows(applicationContext, prefs) }
        if (Features.LIVE !in prefs.featuresOff) runCatching { refreshIptv(applicationContext) }
        return Result.success()
    }

    /** Reloads the catalogs behind the saved Home and Live TV rows (instant Home), each with a time limit. */
    private suspend fun refreshHomeRows(context: Context, prefs: Prefs) {
        val snap = HomeSnapshot.load(context) ?: return
        val urls = prefs.addons.filter { AddonAccess.key(it) !in prefs.pausedAddons }
        if (urls.isEmpty()) return
        val client = AddonClient()
        val limit = prefs.addonTimeout * 2000L
        val caps = DevicePerformance.capabilities(context)
        val low = DevicePerformance.resolve(prefs.devicePerformance, caps.lowRam, caps.heapMb, caps.totalBytes) == "low"
        val permits = Semaphore(if (low) 2 else 4)
        val infos = coroutineScope {
            urls.map { url -> async { permits.withPermit {
                withTimeoutOrNull(limit) { runCatching { client.manifest(url) }.getOrNull() }
            } } }.awaitAll().filterNotNull()
        }
        val defs = infos.flatMap { client.catalogsOf(it) }.associateBy { it.key }
        suspend fun reload(rows: List<HomeSnapshot.Row>) = coroutineScope {
            rows.map { r ->
                async {
                    val d = r.catalogKey?.let { defs[it] } ?: return@async r
                    permits.withPermit {
                        val items = withTimeoutOrNull(limit) { runCatching { client.browse(d) }.getOrNull() }
                        if (items.isNullOrEmpty()) r else r.copy(items = items)
                    }
                }
            }.awaitAll()
        }
        val fresh = snap.copy(rows = reload(snap.rows), live = reload(snap.live), savedAt = System.currentTimeMillis())
        HomeSnapshot.save(context, fresh)
    }

    /** Reloads IPTV channels and the guide into their caches. */
    private suspend fun refreshIptv(context: Context) {
        // Ember is open: refresh its own Live TV data (one copy in memory, the page updates too).
        val reused=withTimeoutOrNull(10*60_000L) {
            withContext(Dispatchers.Main) {
                refreshActiveController(IptvController.shared,{it.alive}) {app->
                    if(app.hasSources)app.refreshAndWait(force=true)
                }
            }
        }
        // A timeout keeps the existing retry budget; only an absent/closed owner needs fallback.
        if(reused!=false)return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val iptv = IptvController(context, scope, loadCache = false)
            if (!iptv.hasSources) return
            withTimeoutOrNull(10 * 60_000L) { iptv.refreshAndWait(force = true) }
        } finally {
            scope.cancel()
        }
    }

    companion object {
        private const val NAME = "ember-refresh"

        /** Schedules (or cancels) the refresh, following the setting. Called when Ember starts and when the setting changes. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!Prefs(context).backgroundRefresh) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(4, TimeUnit.HOURS)
                // Foreground startup already refreshes Home/Live. Avoid a second full refresh
                // immediately after the worker is first registered on a fresh installation.
                .setInitialDelay(4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
