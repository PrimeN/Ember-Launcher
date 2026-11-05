package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.mediaserver.*
import app.ember.tv.player.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch

class LauncherEnhancementsTest {
    @Test fun pinnedLibraryOpensWithoutEnablingItsTabAndStillHonorsProfileAccess() {
        val visible=listOf(NavTabs.SEARCH,NavTabs.HOME,NavTabs.APPS)
        assertEquals(NavTabs.LIBRARY,app.ember.tv.ui.resolveNavigationPage(NavTabs.LIBRARY,visible,true))
        assertEquals(NavTabs.HOME,app.ember.tv.ui.navigationTabForPage(NavTabs.LIBRARY,visible))
        assertEquals(NavTabs.HOME,app.ember.tv.ui.resolveNavigationPage(NavTabs.LIBRARY,visible,false))
    }
    @Test fun primaryResumeUsesSameThresholdAndExactEpisodeProgressAsPlayback() {
        assertEquals("Resume from 2:00",ResumeAction.label(120_000L to 3_600_000L,null))
        assertEquals("Resume at 40%",ResumeAction.label(null,0.4f))
        assertNull(ResumeAction.label(29_000L to 3_600_000L,0.01f))
        assertNull(ResumeAction.label(3_590_000L to 3_600_000L,1f))
        assertNull(ResumeAction.label(null,Float.NaN))
    }
    @Test fun shortcutsUseStableIdentityAcrossRenameAndRejectTraversalAndTransport() {
        val lib=HomeShortcut("server","Movies","plex|account|library:1")
        assertTrue(lib.valid());assertEquals(lib.id,lib.copy(label="Family movies").id)
        assertFalse(lib.copy(ref="https://example.test/?token=private").valid())
        assertTrue(HomeShortcut("network","Movies","saved-account","movies/animation").valid())
        assertFalse(HomeShortcut("network","Movies","saved-account","../private").valid())
        assertFalse(HomeShortcut("network","Movies","saved-account","movies\\..\\private").valid())
        assertFalse(HomeShortcut("unknown","Movies","x").valid())
    }
    @Test fun restoredServerMetadataHasIdentityButNoOldSignedPlaybackOrArtworkToken() {
        val raw=Meta(id="plex:account:1",type="movie",name="Movie",serverRef="plex|account|1",playUrl="https://example.test/movie?token=private",
            poster="https://example.test/library/metadata/1/thumb?X-Plex-Token=private")
        val safe=ServerMetadata.safe(raw)
        assertNull(safe.playUrl);assertEquals(raw.serverRef,safe.serverRef)
        val art=ServerMetadata.artworkParts(safe.poster!!)
        assertNotNull(art);assertFalse(art!!.second.contains("private"))
    }
    @Test fun encryptedHomeCacheIsProfileAndAccountScopedAndDoesNotRenewStaleRows() {
        val stored=mutableMapOf<String,String>();var time=1_000L
        val cache=ServerHomeCache({stored[it]},{key,value->stored[key]=value;true},{time})
        cache.save("adult","account1",mapOf("plex" to listOf(ServerRow("Continue","Plex",listOf(Meta(id="plex:a:1",serverRef="plex|a|1",playUrl="https://private.test/?token=old"))))),time)
        val restored=cache.load("adult","account1")!!
        assertNull(restored.providers.getValue("plex").single().items.single().playUrl)
        assertNull(cache.load("kids","account1"));assertNull(cache.load("adult","account2"))
        time+=8*24*60*60*1000L
        assertNull(cache.load("adult","account1"))
    }
    @Test fun sourceStatusDistinguishesOfflineAccessAndSuccessfulEmptyLibrary() {
        val health=SourceHealth(readSaved={null},saveSuccess={_,_->})
        health.saved("plex:rows","Plex",100)
        health.start("plex:rows","Plex")
        health.finish("plex:rows",false,true,200,SourceFailure.from(ServerHttpException(401)))
        assertEquals(SourceFailure.ACCESS,health.entries.getValue("plex:rows").failure)
        assertTrue(health.entries.getValue("plex:rows").cached)
        assertEquals(100L,health.entries.getValue("plex:rows").lastSuccess)
        assertEquals(SourceFailure.OFFLINE,SourceFailure.from(java.net.ConnectException("private address")))
        health.start("plex:rows","Plex");health.finish("plex:rows",true,false,300,empty=true)
        val empty=health.entries.getValue("plex:rows")
        assertTrue(empty.empty);assertFalse(empty.failed);assertNull(empty.failure);assertFalse(empty.cached)
    }
    @Test fun manualConnectionSampleIsBoundedAndAlwaysClosesTheStream() {
        var closed=false;var tick=0L
        val input=object:ByteArrayInputStream(ByteArray(4096)) {override fun close(){closed=true;super.close()}}
        val result=ConnectionProbe.measure({input},limit=1024,now={tick.also {tick+=100_000_000L}})
        assertEquals(1024L,result.bytes);assertTrue(closed);assertTrue(result.averageMbps>0)
    }
    @Test fun cancellingBlockedProbeClosesTheNetworkReadExactlyOnce() = kotlinx.coroutines.runBlocking {
        val opened=kotlinx.coroutines.CompletableDeferred<Unit>()
        val release=java.util.concurrent.CountDownLatch(1)
        val closes=java.util.concurrent.atomic.AtomicInteger()
        val remote=object:RemoteMedia {
            override val length=4096L
            override fun open(offset:Long)=object:java.io.InputStream() {
                override fun read():Int=error("bulk read expected")
                override fun read(b:ByteArray,off:Int,len:Int):Int {
                    opened.complete(Unit)
                    release.await(5,java.util.concurrent.TimeUnit.SECONDS)
                    throw java.io.IOException("closed")
                }
                override fun close(){closes.incrementAndGet();release.countDown()}
            }
        }
        val task=launch {ConnectionProbe.run(remote)}
        kotlinx.coroutines.withTimeout(2000){opened.await()}
        task.cancel()
        kotlinx.coroutines.withTimeout(2000){task.join()}
        assertEquals(1,closes.get())
    }
    @Test fun cancelledConnectionSampleClosesItsStream() {
        var closed=false;var checks=0
        val input=object:ByteArrayInputStream(ByteArray(4096)) {override fun close(){closed=true}}
        assertThrows(CancellationException::class.java) {
            ConnectionProbe.measure({input},check={if(++checks==1)throw CancellationException()})
        }
        assertTrue(closed)
    }
}
