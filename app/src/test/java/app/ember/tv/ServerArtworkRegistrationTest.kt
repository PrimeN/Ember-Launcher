package app.ember.tv

import android.app.Application
import android.net.Uri
import app.ember.tv.mediaserver.ServerArtwork
import app.ember.tv.mediaserver.ServerMetadata
import app.ember.tv.mediaserver.ServerRef
import coil.ImageLoader
import coil.request.Options
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ServerArtworkRegistrationTest {
    @Test fun disconnectedArtworkRejectsCacheAccessWithoutCancellingFallbackLoading()=kotlinx.coroutines.runBlocking {
        val context:Application=RuntimeEnvironment.getApplication()
        app.ember.tv.data.CacheManagement.init(context)
        val key=ServerMetadata.artwork(ServerRef("jellyfin","missing-account","movie"),"/Items/movie/Images/Backdrop")!!
        val image=coil.request.ImageRequest.Builder(context).data(key).build()
        val chain=object:coil.intercept.Interceptor.Chain {
            override val request=image
            override val size=coil.size.Size(160,90)
            override fun withRequest(request:coil.request.ImageRequest)=error("Unexpected request replacement")
            override fun withSize(size:coil.size.Size)=error("Unexpected size replacement")
            override suspend fun proceed(request:coil.request.ImageRequest):coil.request.ImageResult=error("Disconnected artwork must not reach caches or network")
        }
        val failure=runCatching {app.ember.tv.ui.ImageCacheRetention().intercept(chain)}.exceptionOrNull()
        assertTrue(failure is app.ember.tv.mediaserver.ArtworkAccessDenied)
        assertFalse(failure is kotlinx.coroutines.CancellationException)
    }
    @Test fun serverArtworkSurvivesTheRealImageLoadersStringToUriMapping() {
        val context:Application=RuntimeEnvironment.getApplication()
        val loader=ImageLoader.Builder(context).components {add(ServerArtwork.Factory())}.build()
        try {
            val options=Options(context)
            val key=ServerMetadata.artwork(ServerRef("jellyfin","account","movie"),"/Items/movie/Images/Backdrop")!!
            val mapped=loader.components.map(key,options)
            assertTrue("The real loader maps image strings before choosing a fetcher",mapped is Uri)
            val fetcher=loader.components.newFetcher(mapped,options,loader)?.first
            assertTrue("Authenticated artwork must retain its server fetcher after mapping",fetcher is ServerArtwork)
            val publicImage=loader.components.map("https://example.org/poster.jpg",options)
            assertFalse(loader.components.newFetcher(publicImage,options,loader)?.first is ServerArtwork)
        } finally {loader.shutdown()}
    }
}
