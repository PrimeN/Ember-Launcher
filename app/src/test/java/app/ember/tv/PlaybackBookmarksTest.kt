package app.ember.tv

import app.ember.tv.player.*
import app.ember.tv.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class PlaybackBookmarksTest {
    @get:Rule val temp=TemporaryFolder()
    private fun context()=PersistenceContext(temp.newFolder())
    @Test fun bookmarkCanBeReloadedRenamedRemovedAndIsolatedByActivityScope() {
        val c=context();val first=PlaybackBookmarks(c,"local:adult")
        assertTrue(first.add("title",1234,"A scene"))
        val loaded=PlaybackBookmarks(c,"local:adult").read("title").single()
        assertEquals(1234L,loaded.positionMs);assertEquals("A scene",loaded.name)
        assertTrue(PlaybackBookmarks(c,"local:kids").read("title").isEmpty())
        assertTrue(first.rename("title",loaded.id,"Changed"));assertEquals("Changed",first.read("title").single().name)
        assertTrue(first.remove("title",loaded.id));assertTrue(first.read("title").isEmpty())
    }
    @Test fun labelIsBoundedAndPositionsAreSortedWithoutStoringMediaAddresses() {
        val c=context();val store=PlaybackBookmarks(c,"shared")
        assertTrue(store.add("https://private.invalid/movie?token=private",9000,"Later\nscene"))
        assertTrue(store.add("https://private.invalid/movie?token=private",1000,"x".repeat(200)))
        val values=store.read("https://private.invalid/movie?token=private")
        assertEquals(listOf(1000L,9000L),values.map {it.positionMs});assertEquals(80,values.first().name.length)
        assertFalse(c.getSharedPreferences("bookmarks",0).all.toString().contains("private.invalid"))
        assertFalse(store.add("title",-1,"Invalid"))
    }
    @Test fun fullTitleLimitDoesNotDeleteExistingBookmarks() {
        val c=context();val store=PlaybackBookmarks(c,"shared")
        repeat(512){assertTrue(store.add("title$it",it.toLong(),"Saved"))}
        assertFalse(store.add("new-title",0,"Overflow"));assertEquals(1,store.read("title0").size)
        assertTrue(store.add("title0",100,"Another scene"))
        repeat(48){assertTrue(store.add("title0",it.toLong(),"Scene"))}
        assertFalse(store.add("title0",200,"Overflow"));assertEquals(50,store.read("title0").size)
    }
    @Test fun bookmarksParticipateInBackupAndRestoreButDecoderHintsDoNot() {
        val c=context();Prefs(c).theme="day"
        assertTrue(PlaybackBookmarks(c,"shared").add("title",500,"Opening"))
        val payload=BackupPersistence(c,FixtureSecrets()).snapshot()
        assertTrue(payload.containsKey("prefs.bookmarks"));assertFalse(payload.containsKey("prefs.decoder_health"))
        val target=context();BackupPersistence(target,FixtureSecrets()).restore(payload)
        assertEquals("Opening",PlaybackBookmarks(target,"shared").read("title").single().name)
    }
    @Test fun corruptedOrOversizedSavedDataIsSafelyIgnored() {
        assertTrue(PlaybackBookmarks.decode("broken").isEmpty())
        assertTrue(PlaybackBookmarks.decode("x".repeat(65537)).isEmpty())
    }
    @Test fun invalidBookmarkBackupIsRejectedBeforeReplacingSavedPreferences() {
        val c=context();Prefs(c).theme="day"
        val target=BackupPersistence(c,FixtureSecrets());val before=target.snapshot()
        val payload=JsonObject(before+("prefs.bookmarks" to buildJsonObject {
            put("title",buildJsonObject {put("t","s");put("v","invalid")})
        }))
        assertThrows(Backup.BackupException::class.java){target.restore(payload)}
        assertEquals(before,target.snapshot())
    }
    @Test fun olderBackupWithoutBookmarksRemainsRestorable() {
        val from=context();Prefs(from).theme="night"
        val payload=JsonObject(BackupPersistence(from,FixtureSecrets()).snapshot()-"prefs.bookmarks")
        val to=context();PlaybackBookmarks(to,"shared").add("old",0,"Saved")
        BackupPersistence(to,FixtureSecrets()).restore(payload)
        assertTrue(PlaybackBookmarks(to,"shared").read("old").isEmpty())
        assertEquals("night",Prefs(to).theme)
    }
}
