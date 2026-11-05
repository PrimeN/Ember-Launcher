package app.ember.tv

import app.ember.tv.data.Meta
import app.ember.tv.mediaserver.*
import app.ember.tv.player.PlaybackBuffer
import app.ember.tv.state.ServerBrowserState
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ServerEnhancementsTest {
    private val library=ServerLibrary("Same", "Movies", "movies", emptyList(), "jellyfin|account|library:movies:lib")
    private fun title(id: String)=Meta("jellyfin:account:$id","movie",id,serverRef="jellyfin|account|$id")
    private class Secrets : ServerSecrets {
        override fun read(key: String) = if(key=="ms.deviceId") "fixture-device" else null
        override fun write(key: String,value: String)=true
        override fun remove(key: String) {}
    }
    private fun client(handler: (Request)->Pair<Int,String>): JellyfinClient {
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val (code,body)=handler(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).build()
        }.build()
        return JellyfinClient(Accounts(Secrets()),ServerHttp(http))
    }
    private val account=ServerAccount("account","jellyfin","Same","https://example.com","u","fixture-token")
    private suspend fun idle(state: ServerBrowserState) { while(state.loading) yield() }

    @Test(timeout=5000) fun browseContinuesBeyondSixtyAndStopsAtTheServerTotal() = runBlocking {
        val offsets=mutableListOf<Int>()
        val state=ServerBrowserState(this,{ _,_,offset -> offsets+=offset; ServerPage((offset until minOf(offset+60,125)).map { title("$it") },minOf(offset+60,125),offset+60>=125,125) }, { emptyList() })
        state.open(library); idle(state); state.more(); idle(state); state.more(); idle(state); state.more()
        assertEquals(listOf(0,60,120),offsets); assertEquals(125,state.items.size); assertEquals(125,state.total); assertTrue(state.end)
    }
    @Test(timeout=5000) fun lateCancelledSearchCannotReplaceNewResultsOrLoadingState() = runBlocking {
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val state=ServerBrowserState(this,{ _,query,_ ->
            if(query.query.isEmpty()) { entered.complete(Unit); withContext(NonCancellable) { release.await() }; ServerPage(listOf(title("old")),1,true) }
            else ServerPage(listOf(title(query.query)),1,true)
        },{emptyList()})
        state.open(library); entered.await()
        state.applyFilter(ServerFilter(query="new")); idle(state); release.complete(Unit); yield()
        assertEquals(listOf("new"),state.items.map { it.name }); assertFalse(state.loading); assertTrue(state.end)
    }
    @Test(timeout=5000) fun failedNextPageRetainsTitlesAndRetriesTheSameOffset() = runBlocking {
        val offsets=mutableListOf<Int>(); var fail=true
        val state=ServerBrowserState(this,{ _,_,offset ->
            offsets+=offset
            if(offset==1&&fail) throw ServerHttpException(503)
            ServerPage(listOf(title("$offset")),offset+1,offset==1,2)
        },{emptyList()})
        state.open(library); idle(state); state.more(); idle(state)
        assertEquals(1,state.items.size); assertNotNull(state.error)
        fail=false; state.retry(); idle(state)
        assertEquals(listOf(0,1,1),offsets); assertEquals(2,state.items.size); assertNull(state.error)
    }
    @Test(timeout=5000) fun repeatedPageCannotCreateAnEndlessPagingLoop() = runBlocking {
        var calls=0
        val state=ServerBrowserState(this,{ _,_,offset -> calls++; ServerPage(listOf(title("same")),offset+1,false) },{emptyList()})
        state.open(library); idle(state); state.more(); idle(state); state.more()
        assertEquals(2,calls); assertEquals(1,state.items.size); assertTrue(state.end)
    }
    @Test(timeout=5000) fun sortingAndDensityAreScopedToStableLibraryIdentity() = runBlocking {
        val values=mutableMapOf<String,String>()
        val state=ServerBrowserState(this,{_,_,_->ServerPage(emptyList(),0,true)},{emptyList()}, {k,d->values[k]?:d},{k,v->values[k]=v})
        state.open(library); idle(state); state.applyFilter(ServerFilter(sort="rating")); idle(state); state.updateDensity("dense")
        state.open(library.copy(ref="jellyfin|other|library:movies:lib")); idle(state)
        assertEquals("added",state.filter.sort); assertEquals("default",state.density)
        state.open(library); idle(state); assertEquals("rating",state.filter.sort); assertEquals("dense",state.density)
    }
    @Test(timeout=5000) fun nativeCollectionBackRestoresParentResultsAndPosition() = runBlocking {
        val state=ServerBrowserState(this,{_,query,_->ServerPage(listOf(title(query.parent?:"root")),1,true,1)},{emptyList()})
        state.open(library); idle(state)
        state.enterCollection(Meta("jellyfin:account:box","collection","Favorites",serverRef="jellyfin|account|box"),12,34); idle(state)
        assertEquals("box",state.filter.parent); assertEquals("Favorites",state.folderName)
        assertTrue(state.back()); assertEquals("root",state.items.single().name); assertEquals(12 to 34,state.restorePosition)
        assertFalse(state.back())
    }
    @Test fun buffersLeaveDecoderHeadroomAndRespondToMemoryPressure() {
        val mib=1024L*1024
        assertEquals(64*mib,PlaybackBuffer.bytes("huge",256*mib,false).toLong())
        assertEquals(192*mib,PlaybackBuffer.bytes("huge",2048*mib,false).toLong())
        assertEquals(24*mib,PlaybackBuffer.bytes("huge",1024*mib,true).toLong())
        assertEquals(64*mib,PlaybackBuffer.bytes("normal",1024*mib,false).toLong())
    }
    @Test fun disallowedTranscodingDoesNotOfferUnsupportedPresets() = runBlocking {
        val c=client { 200 to """{"Policy":{"EnableVideoPlaybackTranscoding":false}}""" }
        assertEquals(listOf(ServerQuality.ORIGINAL),c.qualities(account))
    }
    @Test fun negotiatedQualityKeepsSelectedSourceAndServerSession() = runBlocking {
        var requestBody=""
        val c=client { req -> when {
            req.url.encodedPath.endsWith("PlaybackInfo") -> { requestBody=req.body?.let { body -> okio.Buffer().also { body.writeTo(it) }.readUtf8() }.orEmpty(); 200 to """{"PlaySessionId":"session","MediaSources":[{"Id":"v2","TranscodingUrl":"/Videos/item/main.m3u8?api_key=stale"}]}""" }
            req.url.encodedPath.endsWith("/u") -> 200 to """{"Policy":{"EnableVideoPlaybackTranscoding":true}}"""
            else -> 200 to """{"Id":"item","Type":"Movie","Name":"Movie","RunTimeTicks":100000000,"MediaSources":[{"Id":"v2","MediaStreams":[]}]}"""
        } }
        val source=c.playback(account,"item","v2",ServerQuality.HD)
        assertEquals("v2",source.serverPlayback!!.sourceId); assertEquals("session",source.serverPlayback.sessionId)
        assertEquals("Transcode",source.serverPlayback.method); assertEquals(ServerQuality.HD,source.serverPlayback.quality)
        assertTrue(requestBody.contains("4000000")); assertTrue(source.url!!.contains("MaxWidth=1280")); assertFalse(source.url!!.contains("stale"))
    }
    @Test fun conversionCannotRedirectAnAccountTokenToAnotherOrigin() = runBlocking {
        val c=client { req -> when {
            req.url.encodedPath.endsWith("PlaybackInfo") -> 200 to """{"PlaySessionId":"s","MediaSources":[{"Id":"v","TranscodingUrl":"https://other.example/private"}]}"""
            req.url.encodedPath.endsWith("/u") -> 200 to """{"Policy":{"EnableVideoPlaybackTranscoding":true}}"""
            else -> 200 to """{"Id":"item","Type":"Movie","Name":"Movie","MediaSources":[{"Id":"v"}]}"""
        } }
        assertTrue(runCatching { c.playback(account,"item","v",ServerQuality.HD) }.isFailure)
    }
    @Test fun serverActionsUseNativeStateAndDoNotFakeMutationSuccess() = runBlocking {
        var fail=false
        val c=client { req -> if(req.method=="GET") 200 to """{"UserData":{"Played":false,"IsFavorite":true,"PlaybackPositionTicks":10000}}""" else (if(fail)503 else 204) to "" }
        val state=c.actions(account,"item"); assertFalse(state.watched); assertEquals(true,state.favorite); assertTrue(state.inResume)
        c.favorite(account,"item",false); fail=true
        assertTrue(runCatching { c.favorite(account,"item",true) }.isFailure)
        assertTrue(runCatching { c.hideResume(account,"item") }.isFailure)
    }
    @Test fun browseUsesTheServerCollectionEndpointAndRawCursor() = runBlocking {
        var path=""
        val c=client { req -> path=req.url.toString(); 200 to """{"Items":[{"Id":"box","Type":"BoxSet","Name":"Collection"}],"TotalRecordCount":1}""" }
        val result=c.browse(account,"library:movies:lib",ServerFilter(collections=true),0)
        assertTrue(path.contains("IncludeItemTypes=BoxSet")); assertEquals("collection",result.items.single().type); assertEquals(1,result.next)
    }
    @Test fun serverSidecarTracksRetainLanguageForcedAndSdhLabels() {
        val candidates=app.ember.tv.data.Subtitles.fromOptions(listOf(app.ember.tv.data.SubtitleOption("Server", "eng", "https://example.com/sub", forced=true, hearingImpaired=true, server=true)))
        assertEquals("Server",candidates.single().provider); assertEquals("en",candidates.single().lang)
        assertTrue(candidates.single().forced); assertTrue(candidates.single().hearingImpaired)
    }
    @Test fun legacyEmbyArtworkDoesNotKeepEncodedAuthenticationParameters() {
        val meta=title("1").copy(poster="https://example.com/Items/1/Images/Primary?X%2DEmby%2DToken=fixture-secret&quality=90")
        val safe=ServerMetadata.safe(meta)
        val path=ServerMetadata.artworkParts(safe.poster!!)!!.second
        assertEquals("/Items/1/Images/Primary?quality=90",path); assertFalse(path.contains("fixture-secret"))
    }

    @Test(timeout=8000) fun healthyProviderPublishesBeforeASlowSameNamedServerFinishes() = runBlocking {
        val release=java.util.concurrent.CountDownLatch(1)
        val secrets=object : ServerSecrets {
            override fun read(key:String):String? = when(key) {
                "ms.deviceId" -> "fixture-device"
                "ms.servers" -> """[{"key":"slow","kind":"jellyfin","name":"Same","url":"https://slow.example","userId":"u","token":"fixture"},{"key":"fast","kind":"jellyfin","name":"Same","url":"https://fast.example","userId":"u","token":"fixture"}]"""
                else -> null
            }
            override fun write(key:String,value:String)=true
            override fun remove(key:String) {}
        }
        val accounts=Accounts(secrets)
        val http=ServerHttp(OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            if(request.url.host=="slow.example") release.await(5,java.util.concurrent.TimeUnit.SECONDS)
            val body=if(request.url.encodedPath.endsWith("Resume")) """{"Items":[{"Id":"1","Type":"Movie","Name":"Movie"}]}""" else "[]"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()
        }.build())
        val servers=MediaServers(accounts,PlexClient(accounts,http),JellyfinClient(accounts,http))
        val published=CompletableDeferred<List<ServerRow>>()
        val all=async { servers.rows { rows -> if(rows.any { it.id.contains("fast") }) published.complete(rows) } }
        try {
            val early=withTimeout(3000) { published.await() }
            assertEquals(1,early.size); assertFalse(all.isCompleted)
        } finally { release.countDown() }
        val complete=all.await(); assertEquals(2,complete.size); assertEquals(2,complete.map { it.id }.distinct().size)
    }

    @Test fun splitFilesWithoutPartDurationsHaveFinitePlaceholdersAndUseProbedOffsets() {
        val transport=ServerPlayback(ServerRef("plex","server","item"),"version",listOf(ServerPart("1","https://example.com/1",100000),ServerPart("2","https://example.com/2")),300000)
        assertEquals(100000L,transport.placeholderDuration(0)); assertEquals(200000L,transport.placeholderDuration(1))
        assertEquals(60000L,transport.copy(durationMs=null).placeholderDuration(1))
        assertTrue(transport.copy(durationMs=50000).placeholderDuration(1)>0)
        assertNull(transport.withProbedDurations(listOf(100000,0)))
        val ready=transport.withProbedDurations(listOf(120000,180000))!!
        assertEquals(1 to 10000L,ready.seek(130000)); assertEquals(300000L,ready.durationMs)
    }

}
