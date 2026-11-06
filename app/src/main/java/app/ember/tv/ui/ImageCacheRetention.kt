package app.ember.tv.ui

import app.ember.tv.data.CacheKind
import app.ember.tv.data.CacheManagement
import app.ember.tv.data.CachePolicy
import coil.imageLoader
import coil.intercept.Interceptor
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Stable disk namespaces survive restarts; age buckets bound decoded-image reuse in memory. */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
internal class ImageCacheRetention : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): coil.request.ImageResult {
        val original = chain.request
        if (!CacheManagement.initialized || !(IconArtworkCache.remote(original) || original.data.toString().startsWith("ember-art://"))) return chain.proceed(original)
        CacheManagement.awaitReady()
        val epoch = CachePolicy.epoch(CacheKind.IMAGES)
        val ttl = CachePolicy.config.millis(CacheKind.IMAGES)
        val key = CachePolicy.digest("$epoch:${original.diskCacheKey ?: original.data}:${original.headers}")
        val bucket = "${epoch}:${System.currentTimeMillis() / ttl}"
        val builder = original.newBuilder().diskCacheKey(key).setParameter("ember.image.age", bucket, bucket).setParameter("ember.image.source",key,key)
        original.memoryCacheKey?.let { builder.memoryCacheKey(coil.memory.MemoryCache.Key("${it.key}:$key:$bucket", it.extras)) }
        if (original.headers["Cache-Control"] == null) builder.addHeader("Cache-Control", "max-age=${ttl / 1000}")
        var result: coil.request.ImageResult? = null
        try { return chain.proceed(builder.build()).also { result = it } }
        finally {
            if (epoch != CachePolicy.epoch(CacheKind.IMAGES)) withContext(NonCancellable + Dispatchers.IO) {
                val loader = if (IconArtworkCache.cached(original)) IconArtworkCache.loader(original.context) else original.context.imageLoader
                loader.diskCache?.remove(key)
                (result as? SuccessResult)?.memoryCacheKey?.let { loader.memoryCache?.remove(it) }
            }
        }
    }
}
