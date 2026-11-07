package app.ember.tv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil.imageLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File

/** Manages disposable caches only. Saved libraries, credentials, downloads and playback files are excluded. */
@android.annotation.SuppressLint("StaticFieldLeak")
@OptIn(coil.annotation.ExperimentalCoilApi::class)
object CacheManagement {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private var application: Context? = null
    private var responses: ResponseCache? = null
    var configuration by mutableStateOf(CacheConfig()); private set
    val initialized get() = application != null
    private fun root(context: Context) = File(context.cacheDir, "managed-responses")
    private fun state(context: Context) = File(context.cacheDir, "cache-management-state.json")

    fun init(context: Context) {
        if (application != null) return
        application = context.applicationContext
        ArtworkCaches.init(context)
        configuration = Prefs(context).cacheConfig
        CachePolicy.config = configuration
        scope.launch {
            gate.withLock {
                try {
                    val saved = runCatching { Json.parseToJsonElement(state(context).readText()).jsonObject }.getOrNull()
                    CachePolicy.epochs = CacheKind.entries.associateWith { kind ->
                        saved?.get(kind.name)?.jsonPrimitive?.contentOrNull?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
                            ?: java.util.UUID.randomUUID().toString()
                    }
                    responses = ResponseCache(root(context), null)
                    retireCacheKeys(context)
                    saveState(context)
                } catch (_: Exception) {
                    // Cache storage is optional; a full disk or unavailable key must not block browsing.
                } finally { ready.complete(Unit) }
            }
        }
    }
    /**
     * Caches are no longer encrypted by the app (#28). Removes the keys that sealed them; entries
     * in the old format read as misses and are replaced. Sign-in keys are untouched.
     */
    private fun retireCacheKeys(context: Context) {
        runCatching { context.deleteSharedPreferences("ember_cache_keys") }
        runCatching {
            val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (alias in listOf("ember_cache_v1", "ember_artwork_cache_v1")) if (store.containsAlias(alias)) store.deleteEntry(alias)
        }
    }
    private fun saveState(context: Context) = AtomicFile.write(state(context), buildJsonObject {
        CachePolicy.epochs.forEach { (kind, value) -> put(kind.name, value) }
    }.toString()).getOrThrow()
    suspend fun awaitReady() { if (initialized) ready.await() }

    suspend fun json(kind: CacheKind, identity: String, refresh: Boolean = false, load: suspend () -> String): String = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext load()
        ready.await()
        responses?.get(kind, identity, refresh, cacheable = { value -> runCatching {
            val parsed = Json.parseToJsonElement(value)
            (parsed is JsonArray || parsed is JsonObject &&
                (parsed["success"] as? JsonPrimitive)?.booleanOrNull != false &&
                (parsed["error"] == null || parsed["error"] is JsonNull ||
                    (parsed["error"] as? JsonPrimitive)?.let { it.booleanOrNull == false || it.content.isBlank() } == true))
        }.getOrDefault(false) }, load = load) ?: load()
    }

    suspend fun update(value: CacheConfig) = edit { value }
    suspend fun update(kind: CacheKind, amount: Int) = edit { old -> when(kind) {
        CacheKind.IMAGES -> old.copy(imageDays=amount)
        CacheKind.METADATA -> old.copy(metadataHours=amount)
        CacheKind.CATALOGS -> old.copy(catalogHours=amount)
        CacheKind.NETWORK -> old.copy(networkHours=amount)
    } }
    private suspend fun edit(change: (CacheConfig) -> CacheConfig) {
        val context = application ?: return
        ready.await()
        withContext(NonCancellable + Dispatchers.IO) { gate.withLock {
            val value = change(configuration)
            require(value.valid())
            val changed = CacheKind.entries.filterTo(mutableSetOf()) { configuration.millis(it) != value.millis(it) }
            Prefs(context).cacheConfig = value
            CachePolicy.config = value
            try { if (changed.isNotEmpty()) clearLocked(context, changed) }
            finally { configuration = value }
        } }
    }
    suspend fun clear(kinds: Set<CacheKind> = CacheKind.entries.toSet()) {
        val context = application ?: return
        ready.await()
        withContext(NonCancellable + Dispatchers.IO) { gate.withLock { clearLocked(context, kinds) } }
    }
    private suspend fun clearLocked(context: Context, kinds: Set<CacheKind>) {
        CachePolicy.epochs = CachePolicy.epochs.mapValues { (kind, old) -> if (kind in kinds) java.util.UUID.randomUUID().toString() else old }
        // Clearing must still free space when a full disk prevents writing the namespace first.
        val persisted = runCatching { saveState(context) }.isSuccess
        var failed = false
        suspend fun attempt(action: suspend () -> Unit) {
            try { action() } catch (_: Exception) { failed = true }
        }
        for (kind in kinds.filter { it == CacheKind.METADATA || it == CacheKind.CATALOGS }) attempt {
            val cache = responses
            if (cache != null) cache.clear(setOf(kind))
            else check(CacheFiles.delete(File(root(context),kind.directory)))
        }
        if (CacheKind.IMAGES in kinds) {
            attempt { ArtworkCaches.clear() }
            attempt { app.ember.tv.ui.IconArtworkCache.clear() }
            attempt { context.imageLoader.diskCache?.clear(); context.imageLoader.memoryCache?.clear(); app.ember.tv.ui.AppArtwork.clear(); app.ember.tv.ui.FrostCache.clear() }
            attempt { check(CacheFiles.delete(File(context.cacheDir,"hero-art-v1"))) }
            ArtworkCaches.imageClearCompleted()
        }
        if (CacheKind.METADATA in kinds) attempt { check(CacheFiles.delete(File(context.cacheDir,"discover"))) }
        if (CacheKind.CATALOGS in kinds) attempt { HomeSnapshot.clear(context) }
        if (CacheKind.NETWORK in kinds) attempt { Net.base.cache?.evictAll() }
        if (!persisted) attempt { saveState(context) }
        if (failed) error("Some cached data could not be cleared.")
    }
    fun trimMemory() { responses?.trimMemory() }
    fun preferencesRestored(context: Context) {
        if (!initialized) return
        scope.launch {
            ready.await()
            gate.withLock {
                val value = Prefs(context).cacheConfig
                CachePolicy.config = value; configuration = value
                runCatching { clearLocked(context, CacheKind.entries.toSet()) }
            }
        }
    }
    data class Usage(val categories: Map<CacheKind, Long>) { val total get() = categories.values.sum() }
    suspend fun usage(context: Context): Usage = withContext(Dispatchers.IO) {
        awaitReady()
        gate.withLock { Usage(CacheKind.entries.associateWith { kind -> when (kind) {
            CacheKind.IMAGES -> (context.imageLoader.diskCache?.size ?: 0L) + app.ember.tv.ui.IconArtworkCache.size() + size(File(context.cacheDir,"hero-art-v1")) + ArtworkCaches.size(context)
            CacheKind.METADATA -> size(File(root(context), kind.directory)) + size(File(context.cacheDir, "discover"))
            CacheKind.CATALOGS -> size(File(root(context), kind.directory)) + size(File(context.filesDir, "home-snapshot.json"))
            CacheKind.NETWORK -> Net.base.cache?.size() ?: 0L
        } }) }
    }
    private fun size(file: File) = CacheFiles.size(file)
}
