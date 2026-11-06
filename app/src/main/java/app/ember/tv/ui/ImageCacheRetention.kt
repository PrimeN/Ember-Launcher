package app.ember.tv.ui

import app.ember.tv.data.CacheKind
import app.ember.tv.data.CacheManagement
import app.ember.tv.data.CachePolicy
import app.ember.tv.data.ArtworkCaches
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
        val privateRef=app.ember.tv.mediaserver.ServerMetadata.artworkParts(original.data.toString())?.first
        val realm=privateRef?.let {ArtworkCaches.realm(original.context,it)
            ?: throw app.ember.tv.mediaserver.ArtworkAccessDenied()}
        val epoch = CachePolicy.epoch(CacheKind.IMAGES)
        val ttl = CachePolicy.config.millis(CacheKind.IMAGES)
        val identity="${original.diskCacheKey ?: original.data}:${original.headers}"
        val key = CachePolicy.digest(if(realm==null)"$epoch:$identity" else "$epoch:$realm:$identity")
        val bucket = "${epoch}:${System.currentTimeMillis() / ttl}"
        val builder = original.newBuilder().diskCacheKey(key).setParameter("ember.image.age", bucket, bucket).setParameter("ember.image.source",key,key)
        original.memoryCacheKey?.let { builder.memoryCacheKey(coil.memory.MemoryCache.Key("${it.key}:$key:$bucket", it.extras)) }
        if(realm!=null) {
            builder.diskCachePolicy(coil.request.CachePolicy.DISABLED)
                .setParameter(ArtworkCaches.PRIVATE_SCOPE,realm,realm)
            if(!ArtworkCaches.persistenceAllowed(realm,original.data.toString()))builder.memoryCachePolicy(coil.request.CachePolicy.DISABLED)
        }
        if (original.headers["Cache-Control"] == null) builder.addHeader("Cache-Control", "max-age=${ttl / 1000}")
        var result: coil.request.ImageResult? = null
        var scopeValid=true
        try {
            val loaded=chain.proceed(builder.build()).also {result=it}
            scopeValid=privateRef==null || ArtworkCaches.realm(original.context,privateRef)==realm
            if(!scopeValid)throw kotlinx.coroutines.CancellationException("Artwork account changed")
            return loaded
        }
        finally {
            if (!scopeValid || epoch != CachePolicy.epoch(CacheKind.IMAGES) || realm!=null && !ArtworkCaches.persistenceAllowed(realm,original.data.toString())) withContext(NonCancellable + Dispatchers.IO) {
                val loader = if (IconArtworkCache.cached(original)) IconArtworkCache.loader(original.context) else original.context.imageLoader
                loader.diskCache?.remove(key)
                (result as? SuccessResult)?.memoryCacheKey?.let { loader.memoryCache?.remove(it) }
            }
        }
    }
}
