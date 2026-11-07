package app.ember.tv

import android.app.Application
import app.ember.tv.data.ApiKeys

/** Sets up what the launcher, the player and the screensaver all share. */
class EmberApp : Application(), coil.ImageLoaderFactory {
    private var initializedImages:coil.ImageLoader?=null
    override fun onCreate() {
        super.onCreate()
        // First, so a failure in anything below is recorded too.
        app.ember.tv.data.CrashLog.install(this)
        // Why the previous run ended when no exception was thrown: not responding, native crash,
        // low memory (#27). Read on the crash log's own thread.
        app.ember.tv.data.ExitHistory.record(this)
        // Open the sign-in vault (Keystore self-test, migration) off the main thread, so the first
        // screen usually finds it ready instead of waiting on the secure hardware (#30).
        val appContext = this
        Thread({ runCatching { app.ember.tv.tracker.TokenStore(appContext).get("ms.deviceId") } }, "ember-vault-warmup").start()
        app.ember.tv.data.CacheManagement.init(this)
        app.ember.tv.data.Net.init(this)
        app.ember.tv.ui.IconArtworkCache.init(this)
        ApiKeys.init(this)
        app.ember.tv.data.SourceFreshness.init(this)
        // The app icon follows the accent colour (also after a restore).
        app.ember.tv.data.AppIcon.init(this)
        // Queue the saved library read; disk IO/migration stays off the startup thread.
        app.ember.tv.data.LocalLibrary.init(this)
        val prefs = app.ember.tv.data.Prefs(this)
        runCatching { app.ember.tv.data.AppIcon.sync(prefs.accent) }
        app.ember.tv.data.AddonAccess.paused = prefs.pausedAddons
        app.ember.tv.data.AddonClient.renames = prefs.addonNames
        app.ember.tv.data.CatalogNames.load(prefs.catalogNames)
        // Home rows and the IPTV guide refresh every few hours (Settings > Features).
        runCatching { app.ember.tv.work.RefreshWorker.schedule(this) }
    }

    override fun onTrimMemory(level:Int) {
        super.onTrimMemory(level)
        app.ember.tv.ui.RuntimeBudgets.trim(level)
        if(app.ember.tv.ui.RuntimeBudgets.pressureLevel(level)) {
            app.ember.tv.data.CacheManagement.trimMemory()
            initializedImages?.memoryCache?.clear()
            app.ember.tv.ui.IconArtworkCache.trimMemory()
        }
    }

    /**
     * Images use the shared network stack. Posters and backdrops have no transparency, so they
     * are kept at half the memory (RGB 565); Coil already decodes each at the size it is shown.
     */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        // Coil keeps its own image cache, so the shared client's HTTP cache is left out here.
        .okHttpClient { app.ember.tv.data.Net.artworkClient() }
        .allowRgb565(true)
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(app.ember.tv.ui.ArtworkStorageBudget.disk(runCatching {android.os.StatFs(cacheDir.path).availableBytes}.getOrDefault(0))).build() }
        // Artwork retention is Ember's own (Settings > System > Cache Management, enforced by
        // ImageCacheRetention). Server cache headers only added a revalidation of every poster on
        // each launch, for hosts that send no-cache or a short max-age (#32). Private server
        // artwork never uses this disk cache, so this cannot store a no-store image.
        // Interceptors stay on the main dispatcher: Coil then serves memory hits in the same frame
        // (no placeholder flash while scrolling); the interceptor's per-image work is a short hash.
        .respectCacheHeaders(false)
        // Animated GIF covers (Collection folders); the platform decoder from Android 9.
        .components {
            add(app.ember.tv.ui.ImageCacheRetention())
            add(app.ember.tv.mediaserver.ServerArtwork.Factory())
            add(coil.decode.ImageDecoderDecoder.Factory())
        }
        .build().also {initializedImages=it}
}
