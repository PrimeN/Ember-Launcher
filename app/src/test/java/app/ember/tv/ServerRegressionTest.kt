package app.ember.tv

import app.ember.tv.mediaserver.*
import app.ember.tv.data.Meta
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ServerRegressionTest {
    private class Secrets : ServerSecrets {
        val values = mutableMapOf("ms.deviceId" to "fixture-device")
        var writable = true
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String): Boolean { if (!writable) return false; values[key]=value; return true }
        override fun remove(key: String) { values.remove(key) }
    }
    private fun http(handler: (Request) -> Pair<Int,String>) = ServerHttp(OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = handler(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).build()
    }.build())
    private fun accounts() = Accounts(Secrets().apply { values["plex.token"]="fixture-account-token" })

    @Test fun failedEncryptedWriteKeepsExistingSignIn() {
        val secrets=Secrets(); val accounts=Accounts(secrets)
        accounts.plexToken="old-fixture"; secrets.writable=false
        assertTrue(runCatching { accounts.plexToken="new-fixture" }.isFailure)
        assertEquals("old-fixture",accounts.plexToken)
        assertTrue(runCatching { accounts.saveServers(listOf(ServerAccount("a","jellyfin","Same","https://example.com","u","t"))) }.isFailure)
        assertTrue(accounts.servers().isEmpty())
    }
    @Test fun defaultProtocolDistinguishesPrivateAndRemoteAddresses() {
        for (address in listOf("10.0.0.2:8096","192.168.1.2","172.16.0.2","127.0.0.1","[::1]:8096","[fd12::1]:8096","tv.local:8096")) assertTrue(ServerAddress.normalize(address).startsWith("http://"))
        for (address in listOf("203.0.113.8:8920","172.32.0.2","example.com","[2001:db8::1]:8920")) assertTrue(ServerAddress.normalize(address).startsWith("https://"))
        assertEquals("http://example.com/base",ServerAddress.normalize("http://example.com/base/"))
        assertTrue(runCatching { ServerAddress.normalize("https://user:secret@example.com") }.isFailure)
    }
    @Test fun metadataRemovesPrivateUrlsWhilePreservingReplayIdentity() {
        val m=Meta(id="plex:s:42",type="movie",name="A",poster="https://example.com/library/metadata/42/thumb?X-Plex-Token=fixture&width=100",playUrl="https://example.com/video?X-Plex-Token=fixture")
        val safe=ServerMetadata.safe(m)
        assertNull(safe.playUrl); assertEquals("plex|s|42",safe.serverRef)
        assertFalse(safe.poster!!.contains("fixture"))
        val (ref,path)=ServerMetadata.artworkParts(safe.poster!!)!!
        assertEquals("plex|s|42",ref.value); assertEquals("/library/metadata/42/thumb?width=100",path)
        assertEquals(safe,ServerMetadata.safe(safe))
    }
    @Test fun nativeIdsDistinguishAccountsAndRejectMalformedReferences() {
        assertNotEquals(ServerRef("plex","a","42"),ServerRef("plex","b","42"))
        assertNull(ServerRef.parse("plex||42")); assertNull(ServerRef.parse("unknown|a|42"))
        assertEquals("plex|a|42",ServerRef.of(Meta("plexshow:a:42","series","Show"))!!.value)
        assertNull(ServerMetadata.artwork(ServerRef("plex","a","42"),"//other.example/private"))
    }
    @Test fun multipartResumeUsesTheCompleteMovie() {
        val playback=ServerPlayback(ServerRef("plex","s","1"),"version",listOf(ServerPart("p1","https://example.com/1",100_000),ServerPart("p2","https://example.com/2",200_000)),300_000)
        assertEquals(120_000L,playback.position(1,20_000)); assertEquals(1 to 20_000L,playback.seek(120_000))
        assertEquals(1 to 0L,playback.seek(100_000))
        assertNull(playback.copy(parts=listOf(playback.parts[0].copy(durationMs=null),playback.parts[1])).seek(120_000))
    }
    @Test fun earlyExitBalancesSessionAndResumeDoesNotDuplicateStart() {
        val lifecycle=ServerReportLifecycle()
        assertNull(lifecycle.event(ReportState.PROGRESS))
        assertEquals(ReportState.START,lifecycle.event(ReportState.START))
        assertEquals(ReportState.PAUSE,lifecycle.event(ReportState.PAUSE))
        assertEquals(ReportState.PROGRESS,lifecycle.event(ReportState.START))
        assertEquals(ReportState.STOP,lifecycle.event(ReportState.STOP))
        assertNull(lifecycle.event(ReportState.STOP))
    }
    @Test fun jellyfinOnlyAcknowledgesSuccessfulMutations() = runBlocking {
        val account=ServerAccount("a","jellyfin","Server","https://example.com","user","fixture-token")
        assertFalse(JellyfinClient(accounts(),http { 503 to "" }).markWatched(account,"item"))
        val methods=mutableListOf<String>()
        val good=JellyfinClient(accounts(),http { methods+=it.method; 204 to "" })
        assertTrue(good.markWatched(account,"item")); assertTrue(good.markWatched(account,"item",false))
        assertEquals(listOf("POST","DELETE"),methods)
    }
    @Test fun failuresCannotBeMisreadAsEmptySuccessfulResponses() = runBlocking {
        val error=runCatching { http { 503 to "private fixture body" }.ack("https://example.com/private",emptyMap()) }.exceptionOrNull()
        assertEquals(503,(error as ServerHttpException).status)
        assertFalse(error.message!!.contains("private"))
        http { 200 to "<ack/>" }.ack("https://example.com/private",emptyMap())
    }
    @Test fun authenticationCannotFollowCrossOriginRedirect() = runBlocking {
        var calls=0
        val client=ServerHttp(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("fixture")
                .header("Location","https://other.example/stolen").body("".toResponseBody()).build()
        }.build())
        assertTrue(runCatching { client.get("https://example.com/private",mapOf("X-Plex-Token" to "fixture")) }.isFailure)
        assertEquals(1,calls)
    }
    @Test fun plexReprobesFailedRouteAndRetainsEveryFilePart() = runBlocking {
        val id=UUID.randomUUID().toString(); val calls=mutableListOf<String>(); var failed=false
        val plex=PlexClient(accounts(),http { req ->
            calls+=req.url.host+req.url.encodedPath
            when {
                req.url.host=="plex.tv" -> 200 to """[{"provides":"server","clientIdentifier":"$id","name":"Same","accessToken":"fixture","connections":[{"uri":"https://lan.example","local":true},{"uri":"https://remote.example"}]}]"""
                req.url.encodedPath=="/identity" -> 200 to """{"MediaContainer":{"machineIdentifier":"$id"}}"""
                req.url.host=="lan.example" && failed -> 503 to ""
                req.url.encodedPath.contains("/metadata/") -> 200 to """{"MediaContainer":{"Metadata":[{"type":"movie","ratingKey":"42","title":"Movie","duration":300000,"Media":[{"id":"v1","Part":[{"id":"p1","key":"/video/1","duration":100000},{"id":"p2","key":"/video/2","duration":200000}]}]}]}}"""
                else -> 204 to ""
            }
        })
        plex.refreshConnections()
        assertEquals(listOf("p1","p2"),plex.sourcesFor(id,"42").single().serverPlayback!!.parts.map { it.id })
        failed=true; assertTrue(plex.markWatched(id,"42"))
        assertTrue(calls.contains("remote.example/:/scrobble")); plex.refreshConnections()
    }
    @Test fun plexDoesNotClaimWatchedWhenEveryRouteFails() = runBlocking {
        val id=UUID.randomUUID().toString()
        val plex=PlexClient(accounts(),http { req -> when {
            req.url.host=="plex.tv" -> 200 to """[{"provides":"server","clientIdentifier":"$id","connections":[{"uri":"https://example.com"}]}]"""
            req.url.encodedPath=="/identity" -> 200 to """<MediaContainer machineIdentifier="$id"/>"""
            else -> 503 to ""
        } })
        plex.refreshConnections(); assertFalse(plex.markWatched(id,"42")); plex.refreshConnections()
    }
    @Test fun serverSeriesWithoutExternalMetadataKeepEpisodeIdentity() = runBlocking {
        val account=ServerAccount("a","jellyfin","Same","https://example.com","u","fixture")
        val client=JellyfinClient(accounts(),http { req ->
            if(req.url.encodedPath.endsWith("/Episodes")) 200 to """{"Items":[{"Id":"ep","Type":"Episode","SeriesId":"show","SeriesName":"Native show","Name":"Pilot","ParentIndexNumber":1,"IndexNumber":1}],"TotalRecordCount":1}"""
            else 200 to """{"Id":"show","Type":"Series","Name":"Native show"}"""
        })
        assertEquals("jellyfin|a|show",client.item(account,"show").serverRef)
        val page=client.episodes(account,"show",1,0); val episode=ServerMetadata.episode(page.items.single())
        assertEquals("Pilot",episode.title); assertEquals("jellyfin|a|ep",episode.serverRef); assertEquals("jellyfin|a|show",episode.serverSeriesRef)
        assertTrue(page.end)
    }
}
