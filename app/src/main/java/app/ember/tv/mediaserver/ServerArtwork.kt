package app.ember.tv.mediaserver

import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.Fetcher
import coil.fetch.FetchResult
import coil.fetch.DrawableResult
import coil.request.Options
import app.ember.tv.data.Net
import app.ember.tv.data.ArtworkCaches
import app.ember.tv.data.CacheManagement
import app.ember.tv.data.withResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import android.net.Uri

internal data class ServerImageBytes(val bytes: ByteArray, val mime: String?,val cacheable:Boolean=true)
internal class ArtworkAccessDenied:java.io.IOException("Server artwork access was revoked")

/** Reverse-proxy redirects are allowed only inside the authenticated server's origin. */
internal suspend fun fetchServerImage(client: OkHttpClient, url: String, headers: Map<String, String>): ServerImageBytes = coroutineScope {
    val origin = url.toHttpUrl()
    var target = origin
    val http = client.newBuilder().cache(null).followRedirects(false).followSslRedirects(false).build()
    repeat(6) {
        val request = Request.Builder().url(target).apply { headers.forEach { (k,v)->header(k,v) }; header("Accept","image/*") }.build()
        withResponse(http.newCall(request)) { response ->
            if(response.code in listOf(301,302,303,307,308)) {
                val next = target.resolve(response.header("Location") ?: error("Missing artwork redirect.")) ?: error("Invalid artwork redirect.")
                check(next.host==origin.host && next.port==origin.port && next.scheme==origin.scheme && next.username.isEmpty() && next.password.isEmpty()) { "Artwork redirected outside the server." }
                target=next
            } else {
                if(response.code==401 || response.code==403)throw ArtworkAccessDenied()
                check(response.isSuccessful) { "Server artwork unavailable." }
                val bytes = withContext(Dispatchers.IO) {
                    response.body.byteStream().use { input ->
                        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while(out.size()<=8*1024*1024) {val n=input.read(buffer,0,minOf(buffer.size,8*1024*1024+1-out.size()));if(n<0)break;out.write(buffer,0,n)}
                        out.toByteArray()
                    }
                }
                check(bytes.size<=8*1024*1024) { "Server artwork exceeds size limit." }
                return@coroutineScope ServerImageBytes(bytes,response.body.contentType()?.toString(),!response.cacheControl.noStore)
            }
        }
    }
    error("Too many artwork redirects.")
}

/** Resolves authorization before encrypted cache access; private bytes never enter Coil's disk cache. */
class ServerArtwork(private val key: String, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val (ref, path) = requireNotNull(ServerMetadata.artworkParts(key))
        CacheManagement.awaitReady()
        val realm=options.parameters.value<String>(ArtworkCaches.PRIVATE_SCOPE) ?: ArtworkCaches.realm(options.context,ref)
            ?: throw ArtworkAccessDenied()
        val valid:suspend ()->Boolean={ArtworkCaches.realm(options.context,ref)==realm}
        val load:suspend ()->ServerImageBytes={
            val (url,headers)=servers(options.context).artwork(ref,path)
            try {
                val response=fetchServerImage(Net.artworkClient(5,15),url,headers)
                if(!response.cacheable)ArtworkCaches.noStore(realm,key)
                response
            } catch(denied:ArtworkAccessDenied) {ArtworkCaches.refused(realm);throw denied}
        }
        privateRequests.withPermit {
            val store=ArtworkCaches.originals()
            var decoded:DrawableResult?=null
            val validate:(ServerImageBytes)->Unit={decoded=decodePrivateArtwork(it.bytes,options,DataSource.DISK)}
            val cached=if(store==null) {validate(load());false} else
                privateCache(store).get("$realm:$path",valid,validate,load).second
            if(!valid())throw CancellationException("Artwork account changed")
            ensureActive()
            if(!valid())throw CancellationException("Artwork account changed")
            requireNotNull(decoded).copy(dataSource=if(cached)DataSource.DISK else DataSource.NETWORK)
        }
    }

    companion object {
        private val privateRequests=kotlinx.coroutines.sync.Semaphore(2)
        private var cachedStore:app.ember.tv.data.ArtworkDiskCache?=null
        private var cachedImages:PrivateArtworkCache?=null
        @Synchronized private fun privateCache(store:app.ember.tv.data.ArtworkDiskCache):PrivateArtworkCache {
            if(cachedStore!==store) {cachedStore=store;cachedImages=PrivateArtworkCache(store)}
            return cachedImages!!
        }
        // One per process: accounts are read from storage on each call and Plex routes are shared
        // statically, so a fresh instance per poster only repeated the encrypted-store setup.
        @Volatile private var shared: MediaServers? = null
        private fun servers(context: android.content.Context): MediaServers =
            shared ?: synchronized(this) { shared ?: MediaServers(context.applicationContext).also { shared = it } }
    }
    class Factory : Fetcher.Factory<Uri> {
        // Coil maps String models to Uri before selecting a fetcher.
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            data.toString().let {key->if (ServerMetadata.artworkParts(key) != null) ServerArtwork(key, options) else null}
    }
}

/** Decode directly from memory: platform decoders must never spool private bytes to a plaintext file. */
internal fun decodePrivateArtwork(bytes:ByteArray,options:Options,source:DataSource):DrawableResult {
    var sampled=false
    val drawable=android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) {decoder,info,_->
        val width=info.size.width;val height=info.size.height
        require(width in 1..32768 && height in 1..32768) {"Invalid server artwork dimensions"}
        val targetWidth=(options.size.width as? coil.size.Dimension.Pixels)?.px ?: width
        val targetHeight=(options.size.height as? coil.size.Dimension.Pixels)?.px ?: height
        val scale=minOf(1.0,4096.0/maxOf(width,height),coil.decode.DecodeUtils.computeSizeMultiplier(width,height,targetWidth,targetHeight,options.scale))
        sampled=scale<1.0
        decoder.setTargetSize(maxOf(1,(width*scale).toInt()),maxOf(1,(height*scale).toInt()))
        decoder.allocator=if(options.config==android.graphics.Bitmap.Config.HARDWARE)android.graphics.ImageDecoder.ALLOCATOR_HARDWARE else android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        if(options.allowRgb565)decoder.memorySizePolicy=android.graphics.ImageDecoder.MEMORY_POLICY_LOW_RAM
        options.colorSpace?.let(decoder::setTargetColorSpace)
    }
    return DrawableResult(drawable,sampled,source)
}
