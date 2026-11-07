package app.ember.tv.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.IconCacheVersion
import coil.ImageLoader
import coil.disk.DiskCache
import coil.imageLoader
import coil.intercept.Interceptor
import coil.request.ImageRequest
import coil.request.ImageResult
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Long-lived icon bytes, independent of still/GIF decoding. Icons are kept per image link; catalog
 * or collection edits do not discard them (#55). The disk budget evicts the least used, cache
 * headers refresh changed images, and Clear cache starts a new epoch.
 */
@android.annotation.SuppressLint("StaticFieldLeak") // Holds only the application context and application image loader.
@OptIn(coil.annotation.ExperimentalCoilApi::class)
internal object IconArtworkCache {
    private const val PARAMETER = "ember.icon.epoch"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private var app: Context? = null
    private var version: IconCacheVersion? = null
    @Volatile private var disk: DiskCache? = null
    @Volatile private var images: ImageLoader? = null
    @Volatile private var activeEpoch = "loading"
    var epoch by mutableStateOf("loading"); private set

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        scope.launch { open() }
    }

    private suspend fun open() = mutex.withLock {
        val context = app ?: return@withLock
        try {
            if (version == null) {
                version = IconCacheVersion(File(context.cacheDir, "icon-cache-version.json"))
                val available = runCatching { android.os.StatFs(context.cacheDir.path).availableBytes }.getOrDefault(0)
                val budget = if (available > 0) (available / 50).coerceIn(4L shl 20, 64L shl 20) else 16L shl 20
                disk = DiskCache.Builder().directory(File(context.cacheDir, "catalog-collection-icons")).maxSizeBytes(budget).build()
            }
            activeEpoch = version!!.epoch
            epoch = activeEpoch
        } catch (_: Exception) {
            // Cache failures must not prevent browsing. A new in-memory namespace cannot reuse stale files.
            activeEpoch = java.util.UUID.randomUUID().toString()
            epoch = activeEpoch
        } finally { ready.complete(Unit) }
    }

    private fun discard() {
        disk?.clear()
        images?.memoryCache?.clear()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            val rotated = runCatching { version?.rotate() }
            activeEpoch = version?.epoch ?: java.util.UUID.randomUUID().toString()
            discard()
            epoch = activeEpoch
            rotated.getOrThrow()
        }
    }
    fun size() = disk?.size ?: 0L
    fun trimMemory() { images?.memoryCache?.clear() }

    @Synchronized fun loader(context: Context): ImageLoader = images ?: context.imageLoader.newBuilder()
        .diskCache { disk }
        .memoryCache { coil.memory.MemoryCache.Builder(context).maxSizePercent(.05).build() }
        .respectCacheHeaders(true)
        .components { add(VersionInterceptor()) }
        .build().also { images = it }

    fun cached(request: ImageRequest) = request.parameters.value<String>(PARAMETER) != null
    fun remote(model: Any?): Boolean {
        val value = if (model is ImageRequest) model.data else model
        return value?.toString()?.let { it.startsWith("https://") || it.startsWith("http://") } == true
    }
    fun configure(builder: ImageRequest.Builder, model: Any, value: String): ImageRequest.Builder {
        val data = if (model is ImageRequest) model.data else model
        val key = IconCacheVersion.key(value, data.toString())
        // Let Coil include decode parameters (notably still/GIF frames) in its memory key.
        return builder.diskCacheKey(key).memoryCacheKey(null as coil.memory.MemoryCache.Key?)
            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
            .setParameter(PARAMETER, value, value)
    }

    private class VersionInterceptor : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            ready.await()
            val stamp = chain.request.parameters.value<String>(PARAMETER)
            if (stamp != activeEpoch) throw CancellationException("Icon configuration changed")
            var result: ImageResult? = null
            try {
                result = chain.proceed(chain.request)
                if (stamp != activeEpoch) throw CancellationException("Icon configuration changed")
                return result
            }
            finally {
                if (stamp != activeEpoch) withContext(NonCancellable + Dispatchers.IO) {
                    chain.request.diskCacheKey?.let { disk?.remove(it) }
                    (result as? coil.request.SuccessResult)?.memoryCacheKey?.let { images?.memoryCache?.remove(it) }
                }
            }
        }
    }
}
