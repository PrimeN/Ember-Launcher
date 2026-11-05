package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.player.*
import app.ember.tv.state.WhereToWatchState
import app.ember.tv.streaming.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable

class StreamingOffersAndLaunchOwnershipTest {
    @get:Rule val temp=TemporaryFolder()
    private fun json(value:String)=Json.parseToJsonElement(value)
    private fun offer(expiry:Long?=null)=Offer(1,"Service","sub",null,"HD",null,null,expiresAt=expiry)
    private val watchOffers="""[{"source_id":8,"name":"Netflix","type":"sub","format":"HD"}]"""
    private val tmdbOffers="""{"results":{"CA":{"flatrate":[{"provider_id":8,"provider_name":"Netflix"}],"link":"https://example.test/title"}}}"""

    @Test fun bothOlderProvidersSkipNetworkWithoutTheirKeys()=runBlocking {
        var calls=0
        val get:suspend(String,Map<String,String>)->JsonElement={_,_->calls++;error("must not call")}
        val watch=WatchmodeClient(key={""},get=get);val tmdb=TmdbWatchClient(key={""},get=get)
        assertTrue(watch.services("CA").isEmpty());assertTrue(watch.offers("tt1234567","CA").isEmpty())
        assertTrue(tmdb.services("CA").isEmpty());assertTrue(tmdb.offers(true,1,null,"CA").isEmpty())
        assertFalse(watch.available);assertFalse(tmdb.available);assertEquals(0,calls)
    }
    @Test fun watchmodeUsesEncodedKeysNoStoreAndCanonicalRegion()=runBlocking {
        var calls=0
        val client=WatchmodeClient(key={"fixture +&"},get={url,headers->
            calls++;val parsed=url.toHttpUrl()
            assertEquals("fixture +&",parsed.queryParameter("apiKey"));assertEquals("CA",parsed.queryParameter("regions"))
            assertEquals("no-store",headers["Cache-Control"]);assertEquals("/v1/title/tt1234567/sources/",parsed.encodedPath)
            json(watchOffers)
        })
        assertEquals(1,client.offers("tt1234567","ca").size)
        client.offers("tt1234567","CA");assertEquals(1,calls)
    }
    @Test fun watchmodeCacheExpiresAndDoesNotSurviveCredentialChange()=runBlocking {
        var now=100L;var key="fixture-one";var calls=0
        val client=WatchmodeClient(key={key},clock={now},get={_,_->calls++;json(watchOffers)})
        client.offers("tt1234567","CA");client.offers("tt1234567","CA");assertEquals(1,calls)
        key="fixture-two";client.offers("tt1234567","CA");assertEquals(2,calls)
        now+=6*60*60_000L;client.offers("tt1234567","CA");assertEquals(3,calls)
        key="";assertTrue(client.offers("tt1234567","CA").isEmpty());assertEquals(3,calls)
    }
    @Test fun malformedWatchmodeResponseIsNotPermanentlyCachedAsNoOffers()=runBlocking {
        var calls=0
        val client=WatchmodeClient(key={"fixture"},get={_,_->if(calls++==0)json("{}") else json(watchOffers)})
        assertTrue(runCatching {client.offers("tt1234567","CA")}.isFailure)
        assertEquals(1,client.offers("tt1234567","CA").size);assertEquals(2,calls)
    }
    @Test fun watchmodeRejectsInvalidTitleAndRegionBeforeSendingARequest()=runBlocking {
        val client=WatchmodeClient(key={"fixture"},get={_,_->error("must not call")})
        assertTrue(client.offers("../secret","CA").isEmpty())
        assertTrue(runCatching {client.offers("tt1234567","../US")}.isFailure)
    }
    @Test fun changedWatchmodeKeyCannotPublishOldOffers()=runBlocking {
        var key="fixture-one"
        val client=WatchmodeClient(key={key},get={_,_->key="fixture-two";json(watchOffers)})
        assertTrue(runCatching {client.offers("tt1234567","CA")}.isFailure)
    }
    @Test fun tmdbCachesImdbResolutionAsPartOfTheOfferLookup()=runBlocking {
        var calls=0
        val client=TmdbWatchClient(key={"fixture +&"},get={url,headers->
            calls++;val parsed=url.toHttpUrl()
            assertEquals("fixture +&",parsed.queryParameter("api_key"));assertEquals("no-store",headers["Cache-Control"])
            if(parsed.encodedPath.contains("/find/"))json("""{"movie_results":[{"id":1}]}""") else json(tmdbOffers)
        })
        assertEquals("Subscription",client.offers(true,null,"tt1234567","ca").single().label)
        client.offers(true,null,"tt1234567","CA");assertEquals(2,calls)
    }
    @Test fun tmdbCacheSeparatesCredentialRegionAndMovieFromTv()=runBlocking {
        var key="fixture-one";var calls=0
        val client=TmdbWatchClient(key={key},get={_,_->calls++;json(tmdbOffers)})
        client.offers(true,1,null,"CA");client.offers(true,1,null,"CA");assertEquals(1,calls)
        client.offers(false,1,null,"CA");client.offers(true,1,null,"US");assertEquals(3,calls)
        key="fixture-two";client.offers(true,1,null,"CA");assertEquals(4,calls)
    }
    @Test fun tmdbRejectsMalformedFindAndOffersButAcceptsLegitimateEmptyAvailability()=runBlocking {
        var response=json("{}")
        val client=TmdbWatchClient(key={"fixture"},get={_,_->response})
        assertTrue(runCatching {client.offers(true,null,"tt1234567","CA")}.isFailure)
        assertTrue(runCatching {client.offers(true,1,null,"CA")}.isFailure)
        response=json("""{"results":{}}""")
        assertTrue(client.offers(true,1,null,"CA").isEmpty())
        response=json("""{"movie_results":[]}""")
        assertTrue(client.offers(true,null,"tt1234567","CA").isEmpty())
    }
    @Test fun changedTmdbKeyStopsBeforeAFollowupLookup()=runBlocking {
        var key="fixture-one";var calls=0
        val client=TmdbWatchClient(key={key},get={_,_->calls++;key="fixture-two";json("""{"movie_results":[{"id":1}]}""")})
        assertTrue(runCatching {client.offers(true,null,"tt1234567","CA")}.isFailure);assertEquals(1,calls)
    }
    @Test fun offerCacheIsBoundedAndRetainsRecentEntries()=runBlocking {
        val cache=OfferCache({100L},capacity=2);var calls=0
        suspend fun get(id:String)=cache.get(id,{true}) {calls++;listOf(offer())}
        get("a");get("b");get("a");get("c");assertEquals(3,calls)
        get("a");assertEquals(3,calls);get("b");assertEquals(4,calls)
    }
    @Test fun offerCacheRejectsFutureTimestampsAfterAClockRollback()=runBlocking {
        var now=200L;var calls=0;val cache=OfferCache({now})
        cache.get("title",{true}){calls++;listOf(offer())};now=100L
        cache.get("title",{true}){calls++;listOf(offer())};assertEquals(2,calls)
    }
    @Test fun simultaneousOfferLookupsAreCoalesced()=runBlocking {
        val cache=OfferCache({100L});val pending=CompletableDeferred<List<Offer>>();var calls=0
        val first=async(start=CoroutineStart.UNDISPATCHED){cache.get("title",{true}){calls++;pending.await()}}
        val second=async(start=CoroutineStart.UNDISPATCHED){cache.get("title",{true}){calls++;listOf(offer())}}
        pending.complete(listOf(offer()));first.await();second.await();assertEquals(1,calls)
    }
    @Test fun cancelledProviderWorkCannotPopulateTheSharedCache()=runBlocking {
        val cache=OfferCache({100L});val pending=CompletableDeferred<List<Offer>>();var calls=0
        val first=launch(start=CoroutineStart.UNDISPATCHED){cache.get("title",{true}){calls++;withContext(NonCancellable){pending.await()}};fail("cancelled result published")}
        first.cancel();pending.complete(listOf(offer()));first.join()
        cache.get("title",{true}){calls++;listOf(offer())};assertEquals(2,calls)
    }
    @Test fun offerExpirationInvalidatesTheCacheBeforeItsNormalTtl()=runBlocking {
        var now=100L;val cache=OfferCache({now});var calls=0
        cache.get("title",{true}){calls++;listOf(offer(1000L))};now=999L
        cache.get("title",{true}){calls++;emptyList()};assertEquals(1,calls)
        now=1000L;cache.get("title",{true}){calls++;emptyList()};assertEquals(2,calls)
    }
    @Test fun streamingAvailabilitySkipsExpiredOffersAndRetainsExpiry() {
        val data=json("""{"imdbId":"tt1234567","streamingOptions":{"ca":[
            {"service":{"id":"old","name":"Expired"},"type":"free","expiresOn":1,"link":"https://example.test/old"},
            {"service":{"id":"new","name":"Current"},"type":"free","expiresOn":3,"link":"https://example.test/new"}]}}""")
        val offers=StreamingAvailabilityClient.parseOffers(data,"CA","tt1234567",2000L)
        assertEquals("Current",offers.single().name);assertEquals(3000L,offers.single().expiresAt)
    }
    @Test fun paidChannelOptionsStillAutoEnableWithParentAppsAndHonorManualOff() {
        val prefs=Prefs(PersistenceContext(temp.newFolder()));prefs.watchProvider="streaming"
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val service=StreamingService(1,"Starz on Prime Video","addon",externalId="prime:starz")
        val apps=listOf(AppEntry("com.amazon.amazonvideo.livingroom","Prime Video",android.content.Intent(),android.graphics.drawable.ColorDrawable()))
        val state=WhereToWatchState(prefs,scope,{apps},{})
        try {
            assertTrue(state.isServiceOn(service));state.toggleService(service);assertFalse(state.isServiceOn(service))
            assertTrue("streaming:prime:starz" in prefs.servicesOff)
            assertFalse(WhereToWatchState(prefs,scope,{apps},{}).isServiceOn(service))
            state.toggleService(service);assertTrue(state.isServiceOn(service))
        } finally {scope.cancel()}
    }
    @Test fun futureDatedSavedServicesAreRefreshed()=runBlocking {
        val prefs=Prefs(PersistenceContext(temp.newFolder()));prefs.watchProvider="streaming"
        prefs.servicesSource="streaming:${prefs.watchRegion}";prefs.servicesJson="[]";prefs.servicesFetchedAt=10_000L
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);var calls=0
        val state=WhereToWatchState(prefs,scope,{emptyList()},{})
        state.clock={100L};state.providerReady={true};state.serviceLoader={_,_->calls++;emptyList()}
        try {state.refreshStreamingServices();state.awaitRefresh();assertEquals(1,calls);assertEquals(100L,prefs.servicesFetchedAt)} finally {scope.cancel()}
    }
    @Test fun malformedDiscoverVisibilityBackupIsRejectedBeforeAnyMutation() {
        val context=PersistenceContext(temp.newFolder());val prefs=Prefs(context);prefs.discoverSections=mapOf("EXPLORE" to false)
        val persistence=BackupPersistence(context,FixtureSecrets());val before=persistence.snapshot()
        val values=before["prefs.ember"]!!.jsonObject
        val invalid=JsonObject(before+("prefs.ember" to JsonObject(values+("discoverSections" to buildJsonObject{put("t","b");put("v",true)}))))
        assertTrue(runCatching {persistence.restore(invalid)}.exceptionOrNull() is Backup.BackupException)
        assertEquals(before,persistence.snapshot())
    }
    @Test fun validDiscoverAndArtworkChoicesRoundTripTogether() {
        val context=PersistenceContext(temp.newFolder());val prefs=Prefs(context)
        prefs.discoverSections=mapOf("EXPLORE" to false,"COLLECTIONS" to true);prefs.heroArtworkMode="fanart";prefs.heroCatalog="test:catalog"
        val payload=BackupPersistence(context,FixtureSecrets()).snapshot();val target=PersistenceContext(temp.newFolder())
        BackupPersistence(target,FixtureSecrets()).restore(payload)
        assertEquals(prefs.discoverSections,Prefs(target).discoverSections);assertEquals(prefs.heroArtworkMode,Prefs(target).heroArtworkMode)
        assertEquals(prefs.heroCatalog,Prefs(target).heroCatalog)
    }
    @Test fun failedPlaybackLaunchReleasesItsQueueAndNetworkResources() {
        var id:String?=null;var closed=0
        assertTrue(runCatching {MediaQueues.launch(listOf(QueueMedia("content://fixture","Fixture")),listOf(Closeable{closed++})) {
            id=it;assertNotNull(MediaQueues.get(it));error("fixture launch failure")
        }}.isFailure)
        assertNotNull(id);assertNull(MediaQueues.get(id));assertEquals(1,closed)
    }
    @Test fun successfulPlaybackLaunchTransfersOwnershipUntilThePlayerReleasesIt() {
        var id:String?=null;var closed=0
        MediaQueues.launch(listOf(QueueMedia("content://fixture","Fixture")),listOf(Closeable{closed++})){id=it}
        assertNotNull(MediaQueues.get(id));assertEquals(0,closed)
        MediaQueues.release(id);MediaQueues.release(id);assertEquals(1,closed)
    }
}
