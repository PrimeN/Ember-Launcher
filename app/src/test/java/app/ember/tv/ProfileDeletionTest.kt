package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.player.*
import app.ember.tv.state.ProfilesState
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class ProfileDeletionTest {
    @get:Rule val temp=TemporaryFolder()
    @Before fun reset(){LocalLibrary.resetForTest();WatchedStore.restored()}
    @After fun cleanup(){LocalLibrary.resetForTest();WatchedStore.restored()}
    @Test fun deletionRemovesOwnedDataAndBackupsPreserveOtherProfilesAndSharedData() {
        val c=PersistenceContext(temp.newFolder());val p=Prefs(c)
        val retired=Profile("retired-person","Retired",activityMode=LocalActivity.LOCAL)
        val kept=Profile("kept-person","Kept",activityMode=LocalActivity.LOCAL)
        p.profiles=listOf(kept,retired);p.currentProfile=kept.id
        val film=Meta("tt1","movie","Film")
        for(person in listOf(kept,retired)) {
            p.setRecentSearchesEnabled(person.id,true);p.recordRecentSearch(person.id,"query-${person.id}")
            p.setHomeWidgets(person.id,"[]")
            PositionStore(c,LocalActivity.scope(person)).put("film",42000,100000)
            TrackMemory(c,LocalActivity.scope(person)).putAudio("show","fr")
            WatchedStore(c).mark(person.id,film,true)
            LocalLibrary.started("film",film,LocalActivity.scope(person))
        }
        PositionStore(c).put("shared",1000,100000)
        LocalLibrary.started("shared",film)
        ProfilesState(p,{emptyList()},{emptyList()},{_,go->go()},{},{ }).deleteProfile(retired.id)
        assertTrue(p.recentSearches(retired.id).isEmpty());assertFalse(p.recentSearchesEnabled(retired.id));assertNull(p.homeWidgets(retired.id))
        assertNull(PositionStore(c,LocalActivity.scope(retired)).get("film"));assertNull(TrackMemory(c,LocalActivity.scope(retired)).get("show"))
        assertTrue(WatchedStore(c).load(retired.id).yes.isEmpty())
        LocalLibrary.selectScope(LocalActivity.scope(retired));assertTrue(LocalLibrary.recent.isEmpty())
        LocalLibrary.started("late",film,LocalActivity.scope(retired));assertTrue(LocalLibrary.recent.isEmpty())
        WatchedStore(c).mark(retired.id,film,true);assertTrue(WatchedStore(c).load(retired.id).yes.isEmpty())
        assertNotNull(PositionStore(c,LocalActivity.scope(kept)).get("film"));assertNotNull(PositionStore(c).get("shared"))
        assertEquals(listOf("query-${kept.id}"),p.recentSearches(kept.id))
        assertFalse(BackupPersistence(c,FixtureSecrets()).snapshot().toString().contains("query-${retired.id}"))
        LocalLibrary.selectScope(LocalActivity.SHARED);assertEquals(1,LocalLibrary.recent.size)
    }
}
