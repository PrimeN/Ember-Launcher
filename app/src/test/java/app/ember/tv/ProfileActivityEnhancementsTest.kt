package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.state.*
import app.ember.tv.player.*
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class ProfileActivityEnhancementsTest {
    @get:Rule val temp=TemporaryFolder()
    private val a=Profile("a","A",activityMode=LocalActivity.LOCAL)
    private val b=a.copy(id="b",name="B")
    private val movie=Meta(id="tt1",type="movie",name="Film")
    @Before fun reset()=LocalLibrary.resetForTest()
    @After fun cleanup()=LocalLibrary.resetForTest()
    @Test fun oldProfileJsonRetainsSharedActivityWithoutReassigningData() {
        val old=Json.decodeFromString<Profile>("""{"id":"old","name":"Old"}""")
        assertEquals(LocalActivity.SHARED,LocalActivity.scope(old));assertFalse(old.kidsSetupRequired)
    }
    @Test fun localAndHouseholdScopesCannotCollide() {
        assertNotEquals(LocalActivity.scope(a),LocalActivity.scope(b))
        assertNotEquals(LocalActivity.SHARED,LocalActivity.scope(a.copy(id="shared")))
        assertEquals("",LocalActivity.prefix(LocalActivity.SHARED))
        assertNotEquals(LocalActivity.prefix("local:a:b"),LocalActivity.prefix("local:a"))
    }
    @Test fun positionsPreserveLegacyKeysButNeverLeakIntoPrivateProfiles() {
        val c=PersistenceContext(temp.newFolder())
        val shared=PositionStore(c);shared.put("tt1",100000,1000000)
        val first=PositionStore(c,LocalActivity.scope(a));val second=PositionStore(c,LocalActivity.scope(b))
        assertEquals(100000L,shared.get("tt1")!!.first);assertNull(first.get("tt1"))
        first.put("tt1",200000,1000000);second.put("tt1",300000,1000000)
        assertEquals(200000L,first.get("tt1")!!.first);assertEquals(300000L,second.get("tt1")!!.first)
        first.clear("tt1");assertNull(first.get("tt1"));assertNotNull(second.get("tt1"));assertNotNull(shared.get("tt1"))
    }
    @Test fun trackMemoryAndScopeSpecificClearAreIndependent() {
        val c=PersistenceContext(temp.newFolder());val shared=TrackMemory(c)
        val first=TrackMemory(c,LocalActivity.scope(a));val second=TrackMemory(c,LocalActivity.scope(b))
        shared.putAudio("show","en");first.putAudio("show","ja");second.putSubtitles("show","off")
        assertEquals("ja",first.get("show")!!.audio);assertEquals("off",second.get("show")!!.subtitles)
        shared.clear();assertNull(shared.get("show"));assertEquals("ja",first.get("show")!!.audio)
        first.clear();assertNull(first.get("show"));assertNotNull(second.get("show"))
    }
    @Test fun localHistorySeparatesPlaybackAndRetainsHouseholdEntries() {
        LocalLibrary.started("tt1",movie);LocalLibrary.progress("tt1",.1f)
        LocalLibrary.selectScope(LocalActivity.scope(a));assertTrue(LocalLibrary.recent.isEmpty())
        LocalLibrary.started("tt1",movie);LocalLibrary.progress("tt1",.4f)
        LocalLibrary.selectScope(LocalActivity.scope(b));assertTrue(LocalLibrary.inProgress.isEmpty())
        LocalLibrary.started("tt1",movie);LocalLibrary.progress("tt1",.7f)
        LocalLibrary.selectScope(LocalActivity.scope(a));assertEquals(.4f,LocalLibrary.inProgress.single().progress!!,0f)
        LocalLibrary.selectScope(LocalActivity.SHARED);assertEquals(.1f,LocalLibrary.inProgress.single().progress!!,0f)
    }
    @Test fun latePlayerWritesUseOriginalScopeAfterProfileSwitch() {
        val owner=LocalActivity.scope(a);LocalLibrary.started("tt1",movie,owner)
        LocalLibrary.selectScope(LocalActivity.scope(b))
        LocalLibrary.progress("tt1",.5f,scope=owner);assertTrue(LocalLibrary.recent.isEmpty())
        LocalLibrary.selectScope(owner);assertEquals(.5f,LocalLibrary.inProgress.single().progress!!,0f)
    }
    @Test fun hiddenRecentAndCompletionAreProfileLocal() {
        for(p in listOf(a,b))LocalLibrary.started("tt1",movie,LocalActivity.scope(p))
        LocalLibrary.selectScope(LocalActivity.scope(a));LocalLibrary.hideRecent(movie)
        assertTrue(LocalLibrary.recent.isEmpty())
        LocalLibrary.selectScope(LocalActivity.scope(b));assertEquals(1,LocalLibrary.recent.size);assertFalse(LocalLibrary.isHidden(movie))
        LocalLibrary.finished("tt1");assertTrue(LocalLibrary.isFinished("tt1"))
        LocalLibrary.selectScope(LocalActivity.scope(a));assertFalse(LocalLibrary.isFinished("tt1"))
    }
    @Test fun localActivityRoundTripsThroughExistingBackupAndRestart()=runBlocking {
        val from=PersistenceContext(temp.newFolder());val f=java.io.File(from.filesDir,"library.json")
        LocalLibrary.initFile(f){"{}"};LocalLibrary.awaitReady()
        LocalLibrary.started("tt1",movie);LocalLibrary.started("tt1",movie,LocalActivity.scope(a))
        LocalLibrary.progress("tt1",.6f,scope=LocalActivity.scope(a))
        LocalLibrary.snapshotFiles {Unit}
        val backup=BackupPersistence(from,FixtureSecrets()).snapshot()
        LocalLibrary.resetForTest()
        val to=PersistenceContext(temp.newFolder());BackupPersistence(to,FixtureSecrets()).restore(backup)
        LocalLibrary.initFile(java.io.File(to.filesDir,"library.json"));LocalLibrary.awaitReady()
        LocalLibrary.selectScope(LocalActivity.scope(a));assertEquals(.6f,LocalLibrary.inProgress.single().progress!!,0f)
        LocalLibrary.selectScope(LocalActivity.SHARED);assertEquals(1,LocalLibrary.recent.size)
    }
    @Test fun parentSetupIsRequestedBeforeAdultHandsOverUnprotectedChildProfile() {
        val c=PersistenceContext(temp.newFolder());val prefs=Prefs(c)
        prefs.profiles=listOf(Profile("adult","Adult"),Profile("kid","Kid",kids=true,kidsSetupRequired=true))
        prefs.currentProfile="adult"
        val s=ProfilesState(prefs,{emptyList()},{emptyList()},{_,go->go()},{},{})
        s.switchProfile("kid");assertEquals("adult",s.profileId);assertEquals("kid",s.setupRequested)
    }
    @Test fun newProfilesHaveLocalActivityAndParentSetupDoesNotActivateBeforePinExists() {
        val prefs=Prefs(PersistenceContext(temp.newFolder()))
        val s=ProfilesState(prefs,{emptyList()},{emptyList()},{_,go->go()},{},{})
        val adult=s.addProfile(false);assertEquals(LocalActivity.LOCAL,s.all.first {it.id==adult}.activityMode)
        val child=Profile("kid","Kid",kids=true)
        assertFalse(s.completeKidsSetup(child));assertFalse(s.all.any {it.id==child.id})
        prefs.pinHash=Pin.hash("1234",prefs.pinSalt)
        assertTrue(s.completeKidsSetup(child));assertTrue(s.all.first {it.id=="kid"}.kidsSetupComplete)
        assertFalse(s.all.first {it.id=="kid"}.kidsSetupRequired)
    }
    @Test fun testingReturnPinUsesLockoutButDoesNotRunUnrelatedPendingAction() {
        val prefs=Prefs(PersistenceContext(temp.newFolder()));val pin=PinState(prefs,{})
        pin.nowMillis={0L};pin.setPin("1234");var ran=false
        pin.withPin("Existing action"){ran=true}
        assertTrue(pin.verifyPin("1234"));assertFalse(ran);assertNotNull(pin.pinPrompt)
        assertTrue(pin.enterPin("1234"));assertTrue(ran)
    }
    @Test fun newPreferencesAndScopedPlaybackStoresRoundTripTogether() {
        val from=PersistenceContext(temp.newFolder());val prefs=Prefs(from)
        prefs.profiles=listOf(a,b);prefs.currentProfile=a.id
        prefs.setupChecklistHidden=true;prefs.navigationGlassMethod="cached"
        prefs.devicePerformance="custom";prefs.customPerformance=EffectPresets.options.first().effects
        PositionStore(from,LocalActivity.scope(a)).put("tt1",123000,900000)
        TrackMemory(from,LocalActivity.scope(a)).putSubtitles("tt1","ja")
        from.getSharedPreferences("performance-runtime",0).edit().putBoolean("slow",true).commit()
        val payload=BackupPersistence(from,FixtureSecrets()).snapshot()
        assertFalse(payload.containsKey("prefs.performance-runtime"))
        val to=PersistenceContext(temp.newFolder());BackupPersistence(to,FixtureSecrets()).restore(payload)
        val restored=Prefs(to)
        assertTrue(restored.setupChecklistHidden);assertEquals("cached",restored.navigationGlassMethod)
        assertEquals(a,restored.profiles.first());assertEquals(prefs.customPerformance,restored.customPerformance)
        assertEquals(123000L,PositionStore(to,LocalActivity.scope(a)).get("tt1")!!.first)
        assertNull(PositionStore(to,LocalActivity.scope(b)).get("tt1"))
        assertEquals("ja",TrackMemory(to,LocalActivity.scope(a)).get("tt1")!!.subtitles)
        assertNull(TrackMemory(to).get("tt1"))
    }
    @Test fun unknownFrostMethodIsNormalizedAndPersistedAsSafeDefault() {
        val prefs=Prefs(PersistenceContext(temp.newFolder()));prefs.navigationGlassMethod="future"
        assertEquals("live",prefs.navigationGlassMethod)
    }

}
