package app.ember.tv

import android.view.KeyEvent
import app.ember.tv.data.*
import app.ember.tv.mediaserver.*
import app.ember.tv.player.PlayerMenuLayout
import app.ember.tv.ui.OpeningPressGuard
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class PlayerLibraryRefreshTest {
    @get:Rule val temp=TemporaryFolder()
    private fun meta(id:String)=Meta(id=id,type="movie",name=id,background="https://example.test/$id.jpg")
    private val rows=listOf(CatalogRow("Server","Continue",listOf(meta("one")),inProgress=true,catalogKey="server:resume"),
        CatalogRow("Server","Recently added",listOf(meta("two")),catalogKey="server:recent"))
    private val libraries=listOf(ServerLibrary("Server","Movies","movies",listOf(meta("three")),ref="plex|account|library:movie:1"))
    @Test fun openingLongPressReleaseCannotActivateOrCloseTheNewMenu() {
        val guard=OpeningPressGuard()
        assertTrue(guard.consume(KeyEvent.KEYCODE_DPAD_CENTER,true,3))
        assertTrue(guard.consume(KeyEvent.KEYCODE_DPAD_CENTER,false,0))
        assertFalse(guard.consume(KeyEvent.KEYCODE_DPAD_CENTER,true,0))
        assertFalse(guard.consume(KeyEvent.KEYCODE_DPAD_CENTER,false,0))
    }
    @Test fun aFreshPressWorksWithoutNeedingAnExtraClick() {
        val guard=OpeningPressGuard()
        assertFalse(guard.consume(KeyEvent.KEYCODE_DPAD_DOWN,true,0))
        assertFalse(guard.consume(KeyEvent.KEYCODE_ENTER,true,0))
        assertFalse(guard.consume(KeyEvent.KEYCODE_ENTER,false,0))
    }
    @Test fun libraryGroupsAndIndividualSectionsHideIndependently() {
        val sections=ServerPresentation.sections(rows,libraries)
        assertEquals(3,sections.size)
        assertEquals(listOf("recent","movies"),sections.filter {it.visible(setOf("group:continue"))}.map {it.group})
        assertEquals(2,sections.filter {it.visible(setOf(sections.last().id))}.size)
        assertTrue(sections.last().visible(setOf("group:shows")))
        assertFalse(sections.last().visible(setOf("group:movies")))
    }
    @Test fun heroSourceUsesTheSelectedVisibleListAndFallsBackWhenRemoved() {
        val sections=ServerPresentation.sections(rows,libraries)
        assertEquals(listOf("three"),ServerPresentation.hero(sections,emptySet(),sections.last().id).map {it.id})
        assertEquals(setOf("one","two"),ServerPresentation.hero(sections,setOf("group:movies"),sections.last().id).map {it.id}.toSet())
        assertEquals(3,ServerPresentation.hero(sections,emptySet(),"removed").size)
    }
    @Test fun libraryHeroIsDistinctAndBoundedToTenItems() {
        val items=(1..20).map {meta("$it")}
        val sections=ServerPresentation.sections(listOf(CatalogRow("S","Titles",items+items,catalogKey="one")),emptyList())
        assertEquals(10,ServerPresentation.hero(sections,emptySet(),null).size)
        assertTrue(ServerPresentation.hero(sections,setOf(sections.single().id),null).isEmpty())
    }
    @Test fun heroAndSectionPreferencesRoundTripThroughBackup() {
        val source=PersistenceContext(temp.newFolder());val prefs=Prefs(source)
        assertFalse(prefs.serverHeroOn);assertNull(prefs.serverHeroSource);assertTrue(prefs.serverSectionsOff.isEmpty())
        prefs.serverHeroOn=true;prefs.serverHeroSource="row:server:recent";prefs.serverSectionsOff=setOf("group:movies")
        val target=PersistenceContext(temp.newFolder())
        BackupPersistence(target,FixtureSecrets()).restore(BackupPersistence(source,FixtureSecrets()).snapshot())
        assertTrue(Prefs(target).serverHeroOn);assertEquals(prefs.serverHeroSource,Prefs(target).serverHeroSource)
        assertEquals(prefs.serverSectionsOff,Prefs(target).serverSectionsOff)
    }
    @Test fun firstServerSyncRetriesTransientFailureAndStopsAfterSuccess()=runBlocking {
        var attempts=0;val pauses=mutableListOf<Long>()
        initialServerSync(true,load={attempts++;listOf(SourceStatus("server:rows","Server",failed=attempts==1,failure=SourceFailure.UNAVAILABLE))},pause={pauses+=it})
        assertEquals(2,attempts);assertEquals(listOf(500L),pauses)
    }
    @Test fun firstSyncDoesNotRetryAuthenticationFailureOrSpinForever()=runBlocking {
        var attempts=0
        initialServerSync(true,load={attempts++;listOf(SourceStatus("s","S",failed=true,failure=SourceFailure.ACCESS))},pause={fail("must not retry access denial")})
        assertEquals(1,attempts);attempts=0
        initialServerSync(true,load={attempts++;listOf(SourceStatus("s","S",failed=true,failure=SourceFailure.OFFLINE))},pause={})
        assertEquals(3,attempts)
    }
    @Test fun manualSyncDoesNotRunTheSignInRetryLoop()=runBlocking {
        var attempts=0
        initialServerSync(false,load={attempts++;listOf(SourceStatus("s","S",failed=true))},pause={fail("not an initial sync")})
        assertEquals(1,attempts)
    }
    @Test fun cancellingInitialSyncPreventsLaterRetryAttempts()=runBlocking {
        var attempts=0;val waiting=CompletableDeferred<Unit>()
        val job=launch(start=CoroutineStart.UNDISPATCHED) {
            initialServerSync(true,load={attempts++;listOf(SourceStatus("s","S",failed=true))},pause={waiting.complete(Unit);awaitCancellation()})
        }
        waiting.await();job.cancelAndJoin();assertEquals(1,attempts)
    }
    @Test fun playerCategoriesHaveOneHomeForEachOptionGroupAndCompactArea() {
        assertEquals(listOf("playback","video","audio","subtitles"),PlayerMenuLayout.categories.map {it.first})
        assertEquals(4,PlayerMenuLayout.categories.distinctBy {it.first}.size)
        val panelAreaRatio=PlayerMenuLayout.PANEL_WIDTH*PlayerMenuLayout.ROW_HEIGHT/(600.0*60.0)
        assertTrue(panelAreaRatio in .25.. .50)
    }
    private suspend fun serverMeta(body:String):Meta {
        val values=java.util.concurrent.ConcurrentHashMap<String,String>()
        val accounts=Accounts(object:ServerSecrets {
            override fun read(key:String)=values[key]
            override fun write(key:String,value:String):Boolean {values[key]=value;return true}
            override fun remove(key:String){values.remove(key)}
        })
        val client=OkHttpClient.Builder().addInterceptor {chain->Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("Fixture").body(body.toResponseBody("application/json".toMediaType())).build()}.build()
        return JellyfinClient(accounts,ServerHttp(client)).item(ServerAccount("account","jellyfin","Server","https://example.test","user","fixture-token"),"item")
    }
    @Test fun serverMovieRetainsNativeBackdropWithoutListImageTagsAndUsesAdvertisedLogo()=runBlocking {
        val meta=serverMeta("""{"Id":"item","Type":"Movie","Name":"Movie","ImageTags":{"Logo":"fixture"}}""")
        assertEquals("/Items/item/Images/Backdrop?maxWidth=1280",ServerMetadata.artworkParts(meta.background!!)!!.second)
        assertTrue(ServerMetadata.artworkParts(meta.logo!!)!!.second.contains("/Images/Logo"))
        assertFalse(meta.background!!.contains("fixture-token"));assertFalse(meta.logo!!.contains("fixture-token"))
    }
    @Test fun serverEpisodeUsesItsSeriesBackdropAndLogoWhileKeepingEpisodeIdentity()=runBlocking {
        val meta=serverMeta("""{"Id":"episode","Type":"Episode","Name":"Pilot","SeriesName":"Show","SeriesId":"series","ParentBackdropItemId":"series","ParentLogoItemId":"series"}""")
        assertEquals("episode",ServerRef.of(meta)!!.item)
        assertTrue(ServerMetadata.artworkParts(meta.background!!)!!.second.contains("/Items/series/"))
        assertTrue(ServerMetadata.artworkParts(meta.logo!!)!!.second.contains("/Items/series/"))
    }
    @Test fun serverUsesAnAdvertisedBannerWhenNoBackdropIsAdvertised()=runBlocking {
        val meta=serverMeta("""{"Id":"item","Type":"Movie","Name":"Movie","ImageTags":{"Banner":"tag"}}""")
        assertEquals("/Items/item/Images/Banner?maxWidth=1280&tag=tag",ServerMetadata.artworkParts(meta.background!!)!!.second)
        assertEquals("item",ServerRef.of(meta)!!.item)
    }
    @Test fun inheritedPrimaryImagesKeepTheOriginalPlaybackReference()=runBlocking {
        val meta=serverMeta("""{"Id":"item","Type":"Movie","Name":"Movie","ParentPrimaryImageItemId":"parent"}""")
        assertTrue(ServerMetadata.artworkParts(meta.poster!!)!!.second.contains("/Items/parent/Images/Primary"))
        assertEquals("item",ServerRef.of(meta)!!.item)
    }
    @Test fun nativePlayedFlagsReachEpisodePresentation()=runBlocking {
        val movie=serverMeta("""{"Id":"item","Type":"Movie","Name":"Movie","UserData":{"Played":true}}""")
        assertEquals(true,movie.watched)
        val episode=serverMeta("""{"Id":"episode","Type":"Episode","Name":"Pilot","SeriesName":"Show","SeriesId":"series","UserData":{"Played":true}}""")
        assertEquals(true,ServerMetadata.episode(episode).watched)
    }
}
