package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.mediaserver.*
import app.ember.tv.ui.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ServerArtworkAndMenusTest {
    private val meta = Meta(id="jellyfin:a:item",type="movie",name="Movie",serverRef="jellyfin|a|item",background="native-banner",poster="native-poster",logo="native-logo")
    private val public = HeroArtwork(listOf("public-banner"),"public-logo",listOf("public-poster"))

    @Test fun nativeBannerAndLogoSurviveDecorationAndPrefetch() {
        assertEquals(meta, public.applyTo(meta))
        val missing = meta.copy(background=null,poster=null,logo=null)
        val filled = public.applyTo(missing)
        assertEquals("public-banner", filled.background)
        assertEquals("public-poster", filled.poster)
        assertEquals(meta.serverRef, filled.serverRef)
        assertEquals(meta.id, filled.id)
    }

    @Test fun backgroundPriorityIsServerThenPublicLandscapeThenPortrait() {
        assertEquals(listOf("native-banner" to true,"public-banner" to true,"native-poster" to false,"public-poster" to false),ServerImagePolicy.backgrounds(meta,public))
        assertEquals("public-banner",ServerImagePolicy.backgrounds(meta,public,setOf("native-banner")).first().first)
        assertEquals("native-poster",ServerImagePolicy.backgrounds(meta,HeroArtwork(),setOf("native-banner")).first().first)
    }

    @Test fun portraitsNeverUseBackdropUrlsAndRetainExplicitEnhancement() {
        assertEquals(listOf("native-poster","public-poster"),ServerImagePolicy.posters(meta,public,null))
        assertEquals(listOf("rated-poster","native-poster","public-poster"),ServerImagePolicy.posters(meta,public,"rated-poster"))
        assertEquals(listOf("public-poster"),ServerImagePolicy.posters(meta,public,null,setOf("native-poster")))
    }

    @Test fun aVerifiedImdbCanUseFreeArtworkWithoutApiKeys() {
        val identified=meta.copy(imdbId="tt1234567")
        assertTrue(ServerImagePolicy.backgrounds(identified,HeroArtwork(),setOf("native-banner")).first().first.contains("/background/large/tt1234567/img"))
        assertTrue(ServerImagePolicy.posters(identified,HeroArtwork(),null,setOf("native-poster")).single().contains("/poster/medium/tt1234567/img"))
        assertTrue(ServerImagePolicy.posters(meta.copy(imdbId="tt123/secret"),HeroArtwork(),null,setOf("native-poster")).isEmpty())
    }

    @Test fun successfulNativeDecodeStopsWithoutTryingPublicImages()=runBlocking {
        val calls=mutableListOf<String>()
        val result=loadFirstServerImage(ServerImagePolicy.backgrounds(meta,public)){url,_->calls+=url;"decoded"}
        assertEquals("native-banner",result!!.first.first)
        assertEquals(listOf("native-banner"),calls)
    }

    @Test fun failedNativeDecodeContinuesInTheSameJobAndCanUseLateFallbacks()=runBlocking {
        val calls=mutableListOf<String>()
        val result=loadFirstServerImage(ServerImagePolicy.backgrounds(meta,public)){url,_->calls+=url;if(url=="public-banner")"decoded" else null}
        assertEquals("public-banner",result!!.first.first)
        assertEquals(listOf("native-banner","public-banner"),calls)
        assertEquals("public-poster",loadFirstServerImage(ServerImagePolicy.backgrounds(meta.copy(background=null,poster=null),public)){url,_->if(url=="public-poster")"decoded" else null}!!.first.first)
    }

    @Test fun cancellationStopsBeforeTheNextFallback()=runBlocking {
        val calls=mutableListOf<String>()
        val job=launch {
            loadFirstServerImage<String>(ServerImagePolicy.backgrounds(meta,public)){url,_->calls+=url;currentCoroutineContext().cancel();null}
        }
        job.join()
        assertEquals(listOf("native-banner"),calls)
        assertTrue(job.isCancelled)
    }

    @Test fun tmdbPortraitsAreLanguageAndShapeFiltered() {
        val body=Json.parseToJsonElement("""{"images":{"posters":[{"file_path":"/wide.jpg","width":1920,"height":1080,"iso_639_1":"en"},{"file_path":"/french.jpg","width":600,"height":900,"iso_639_1":"fr"},{"file_path":"/english.jpg","width":600,"height":900,"iso_639_1":"en"}]}}""")
        val art=HeroArtworkSelection.tmdb(body,"en-US")
        assertTrue(art.posters.first().endsWith("/w500/english.jpg"))
        assertFalse(art.posters.any {it.endsWith("wide.jpg")})
        assertFalse(art.empty)
    }

    @Test fun fanartPortraitsAndOldSerializedArtworkRemainCompatible() {
        val body=Json.parseToJsonElement("""{"movieposter":[{"url":"https://assets.fanart.tv/portrait.jpg","lang":"en","likes":"10"}]}""")
        assertEquals(listOf("https://assets.fanart.tv/portrait.jpg"),HeroArtworkSelection.fanart(body,true,"en").posters)
        assertTrue(Json.decodeFromString<HeroArtwork>("""{"backgrounds":["b"],"logo":"l"}""").posters.isEmpty())
    }

    @Test fun compactMenusGrowWithTextAndClampToSmallWindows() {
        val short=MenuSizing.actionMenuWidth(110.dp,60.dp,800.dp)
        val long=MenuSizing.actionMenuWidth(240.dp,60.dp,800.dp)
        assertTrue(short<200.dp)
        assertTrue(long>short)
        assertEquals(180.dp,MenuSizing.actionMenuWidth(900.dp,900.dp,180.dp))
        assertEquals(800.dp,MenuSizing.actionMenuWidth(900.dp,900.dp,800.dp))
        assertEquals(0.dp,MenuSizing.actionMenuWidth(110.dp,60.dp,(-1).dp))
    }

    @Test fun separateChannelAndAppMenusBothConsumeOnlyTheirOpeningRelease() {
        repeat(2) {
            val guard=OpeningPressGuard()
            assertTrue(guard.consume(android.view.KeyEvent.KEYCODE_DPAD_CENTER,true,2))
            assertTrue(guard.consume(android.view.KeyEvent.KEYCODE_DPAD_CENTER,false,0))
            assertFalse(guard.consume(android.view.KeyEvent.KEYCODE_DPAD_CENTER,true,0))
            assertFalse(guard.consume(android.view.KeyEvent.KEYCODE_DPAD_CENTER,false,0))
        }
    }

    @Test fun uninstallTargetsAreOnlyPackageUrisAndExcludeThisLauncher() {
        assertEquals("package:com.example.player",AppManagement.packageUri("com.example.player","app.ember.tv"))
        for(pkg in listOf("app.ember.tv","package:com.example","https://example.test","com.example?other=pkg","com.example/other",""))assertNull(AppManagement.packageUri(pkg,"app.ember.tv"))
    }

    private suspend fun plexMeta(item:String):Meta {
        val values=java.util.concurrent.ConcurrentHashMap<String,String>()
        val accounts=Accounts(object:ServerSecrets {
            override fun read(key:String)=values[key]
            override fun write(key:String,value:String):Boolean {values[key]=value;return true}
            override fun remove(key:String){values.remove(key)}
        })
        accounts.plexToken="fixture-token"
        val client=OkHttpClient.Builder().addInterceptor {chain->
            val body=when(chain.request().url.encodedPath) {
                "/api/v2/resources" -> """[{"clientIdentifier":"server","name":"Server","provides":"server","connections":[{"uri":"https://server.example","local":true}]}]"""
                "/identity" -> """{"MediaContainer":{"machineIdentifier":"server"}}"""
                else -> """{"MediaContainer":{"Metadata":[$item]}}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return PlexClient(accounts,ServerHttp(client)).item("plex|server|item")
    }

    @Test fun plexCanUseNativeImageRoutesEvenWhenListArtworkFieldsAreMissing()=runBlocking {
        val item=plexMeta("""{"ratingKey":"item","type":"movie","title":"Movie"}""")
        assertEquals("/library/metadata/item/art",ServerMetadata.artworkParts(item.background!!)!!.second)
        assertEquals("/library/metadata/item/thumb",ServerMetadata.artworkParts(item.poster!!)!!.second)
        assertFalse(item.background!!.contains("fixture-token"))
    }

    @Test fun plexEpisodePortraitAndBackdropBelongToTheSeriesWithoutChangingPlaybackId()=runBlocking {
        val item=plexMeta("""{"ratingKey":"item","type":"episode","title":"Pilot","grandparentTitle":"Show","grandparentRatingKey":"series","art":"","thumb":"/episode.jpg","grandparentThumb":""}""")
        assertEquals("/library/metadata/series/art",ServerMetadata.artworkParts(item.background!!)!!.second)
        assertEquals("/library/metadata/series/thumb",ServerMetadata.artworkParts(item.poster!!)!!.second)
        assertEquals("item",ServerRef.of(item)!!.item)
    }
    @Test fun plexShowCompletionRequiresAllLeavesRatherThanOneViewedEpisode()=runBlocking {
        assertEquals(false,plexMeta("""{"ratingKey":"item","type":"show","title":"Show","leafCount":10,"viewedLeafCount":4,"viewCount":4}""").watched)
        assertEquals(true,plexMeta("""{"ratingKey":"item","type":"show","title":"Show","leafCount":10,"viewedLeafCount":10}""").watched)
        assertNull(plexMeta("""{"ratingKey":"item","type":"show","title":"Show","viewCount":4}""").watched)
        assertEquals(true,plexMeta("""{"ratingKey":"item","type":"movie","title":"Movie","viewCount":1}""").watched)
    }

    private fun response(request:Request,code:Int,location:String?=null,bytes:ByteArray=byteArrayOf(1,2,3)) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .body(bytes.toResponseBody("image/jpeg".toMediaType())).apply {if(location!=null)header("Location",location)}.build()

    @Test fun artworkFollowsSameOriginRedirectsWithImageAcceptAndAuthentication()=runBlocking {
        val calls=mutableListOf<Request>()
        val client=OkHttpClient.Builder().addInterceptor {chain->
            calls+=chain.request()
            response(chain.request(),if(calls.size==1)302 else 200,if(calls.size==1)"/actual.jpg" else null)
        }.build()
        val result=fetchServerImage(client,"https://server.example/image",mapOf("X-Emby-Token" to "fixture-token","Accept" to "application/json"))
        assertArrayEquals(byteArrayOf(1,2,3),result.bytes)
        assertEquals(2,calls.size)
        assertTrue(calls.all {it.header("Accept")=="image/*" && it.header("X-Emby-Token")=="fixture-token"})
    }

    @Test fun artworkNeverSendsCredentialsAcrossOriginsOrHttpsDowngrades()=runBlocking {
        for(location in listOf("https://other.example/image","http://server.example/image","https://server.example:444/image","https://user:password@server.example/image")) {
            var calls=0
            val client=OkHttpClient.Builder().addInterceptor {chain->calls++;response(chain.request(),302,location)}.build()
            val failure=runCatching {fetchServerImage(client,"https://server.example/image",mapOf("Authorization" to "fixture-token"))}.exceptionOrNull()
            assertNotNull(failure)
            assertEquals(1,calls)
            assertFalse(failure!!.message.orEmpty().contains("fixture-token"))
        }
    }

    @Test fun failedOrOversizedNativeArtworkCanFallBackWithoutCachingPrivateBytes()=runBlocking {
        for((code,size) in listOf(404 to 0,200 to (8*1024*1024+1))) {
            val client=OkHttpClient.Builder().addInterceptor {chain->response(chain.request(),code,bytes=ByteArray(size))}.build()
            assertTrue(runCatching {fetchServerImage(client,"https://server.example/image",emptyMap())}.isFailure)
        }
    }
}
