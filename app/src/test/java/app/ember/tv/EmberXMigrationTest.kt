package app.ember.tv

import android.content.Context
import app.ember.tv.data.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class EmberXMigrationTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun oldCinemaSelectionMigratesOnceWithoutChangingOtherPreferences() {
        val context=PersistenceContext(temp.newFolder())
        val raw=context.getSharedPreferences("ember",Context.MODE_PRIVATE)
        raw.edit().putInt("accent3",6).putString("uiStyle","light").putString("menuPosition","side").putString("theme","day").commit()
        val prefs=Prefs(context)
        assertEquals(6,prefs.accent) // Even an early palette read performs migration.
        assertEquals(InterfaceStyles.EMBER_X,prefs.uiStyle)
        assertEquals("side",prefs.menuPosition);assertEquals("day",prefs.theme)
        prefs.uiStyle=InterfaceStyles.MODERN
        assertEquals(InterfaceStyles.MODERN,Prefs(context).uiStyle)
        assertEquals(6,Prefs(context).accent)
    }
    @Test fun selectingRedPaletteDoesNotSelectAnInterface() {
        val prefs=Prefs(PersistenceContext(temp.newFolder()))
        prefs.uiStyle=InterfaceStyles.LIGHT;prefs.accent=6
        assertEquals(InterfaceStyles.LIGHT,prefs.uiStyle)
        assertEquals(6,prefs.accent)
    }
    @Test fun oldBackupMigratesButNewBackupKeepsAnExplicitInterfaceChoice() {
        val old=PersistenceContext(temp.newFolder())
        old.getSharedPreferences("ember",Context.MODE_PRIVATE).edit().putInt("accent3",6).putString("uiStyle","light").commit()
        val target=PersistenceContext(temp.newFolder())
        Prefs(target).uiStyle=InterfaceStyles.MODERN // Restore replaces the old marker as well.
        BackupPersistence(target,FixtureSecrets()).restore(BackupPersistence(old,FixtureSecrets()).snapshot())
        val prefs=Prefs(target)
        assertEquals(InterfaceStyles.EMBER_X,prefs.uiStyle)
        prefs.uiStyle=InterfaceStyles.LIGHT
        val next=PersistenceContext(temp.newFolder())
        BackupPersistence(next,FixtureSecrets()).restore(BackupPersistence(target,FixtureSecrets()).snapshot())
        assertEquals(InterfaceStyles.LIGHT,Prefs(next).uiStyle)
        assertEquals(6,Prefs(next).accent)
    }
}
