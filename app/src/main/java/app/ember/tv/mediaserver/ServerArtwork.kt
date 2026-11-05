package app.ember.tv.mediaserver

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.FetchResult
import coil.fetch.SourceResult
import coil.request.Options
import app.ember.tv.data.Net
import app.ember.tv.data.withResponse
import kotlinx.coroutines.*
import okhttp3.Request
import okio.Buffer
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class ServerImageBytes(val bytes: ByteArray, val mime: String?)

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
                check(response.isSuccessful) { "Server artwork unavailable." }
                val bytes = withContext(Dispatchers.IO) {
                    response.body.byteStream().use { input ->
                        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while(out.size()<=8*1024*1024) {val n=input.read(buffer,0,minOf(buffer.size,8*1024*1024+1-out.size()));if(n<0)break;out.write(buffer,0,n)}
                        out.toByteArray()
                    }
                }
                check(bytes.size<=8*1024*1024) { "Server artwork exceeds size limit." }
                return@coroutineScope ServerImageBytes(bytes,response.body.contentType()?.toString())
            }
        }
    }
    error("Too many artwork redirects.")
}

/** Resolves a credential-free image key at request time. Private responses never use disk cache. */
class ServerArtwork(private val key: String, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult = coroutineScope {
        val (ref, path) = requireNotNull(ServerMetadata.artworkParts(key))
        val (url, headers) = servers(options.context).artwork(ref, path)
        val result = fetchServerImage(Net.artworkClient(5,15),url,headers)
        SourceResult(ImageSource(Buffer().write(result.bytes), options.context.cacheDir), result.mime, DataSource.NETWORK)
    }
    companion object {
        // One per process: accounts are read from storage on each call and Plex routes are shared
        // statically, so a fresh instance per poster only repeated the encrypted-store setup.
        @Volatile private var shared: MediaServers? = null
        private fun servers(context: android.content.Context): MediaServers =
            shared ?: synchronized(this) { shared ?: MediaServers(context.applicationContext).also { shared = it } }
    }
    class Factory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (ServerMetadata.artworkParts(data) != null) ServerArtwork(data, options) else null
    }
}
