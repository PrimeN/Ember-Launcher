package app.ember.tv

import android.app.Application
import android.content.Intent
import app.ember.tv.data.*
import app.ember.tv.home.AppNames
import app.ember.tv.home.PersonalHome
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Widgets for things on this TV, your own app names, and automatic backups. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LauncherRefinementsTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test fun deviceWidgetsAreRowsThatStandAlone() {
        val source = CollectionSource(provider = HomeWidgets.DEVICE, catalogId = HomeWidgets.APPS_RECENT)
        val w = HomeWidgets.normalize(HomeWidget("w1", "Recent", style = HomeWidgets.HERO, source = source,
            more = listOf(CollectionSource(provider = "addon", catalogId = "top"))))
        assertEquals(HomeWidgets.ROW, w.style)
        assertTrue(w.more.isEmpty())
        assertTrue(HomeWidgets.isLocal(source))
        assertNull(HomeWidgets.sourceError(source))
        assertNotNull(HomeWidgets.sourceError(source.copy(catalogId = "nonsense")))
        assertEquals("Recently used apps", HomeWidgets.sourceLabel(source, emptyList()))
        assertEquals("Continue watching in your apps", HomeWidgets.sourceLabel(source.copy(catalogId = HomeWidgets.APPS_CONTINUE), emptyList()))
    }

    @Test fun recentAppsAreKeptPerProfileNewestFirst() {
        val recent = RecentApps(ctx)
        recent.record("main", "a.one"); recent.record("main", "b.two"); recent.record("main", "a.one")
        recent.record("kid", "c.three")
        assertEquals(listOf("a.one", "b.two"), recent.list("main"))
        assertEquals(listOf("c.three"), recent.list("kid"))
        repeat(30) { recent.record("main", "pkg.$it") }
        assertEquals(RecentApps.LIMIT, recent.list("main").size)
        assertEquals("pkg.29", recent.list("main").first())
        recent.forget("main", "pkg.29")
        assertFalse("pkg.29" in recent.list("main"))
        recent.record("main", "bad\nname")
        assertFalse(recent.list("main").any { '\n' in it })
        recent.removeProfile("kid")
        assertTrue(recent.list("kid").isEmpty())
    }

    @Test fun yourOwnAppNames() {
        assertEquals("My TV", AppNames.clean("  My\u0007   TV  "))
        assertEquals(AppNames.LENGTH, AppNames.clean("x".repeat(100)).length)
        val apps = listOf(AppEntry("a", "Alpha", Intent()), AppEntry("b", "Beta", Intent()))
        val named = AppNames.apply(apps, mapOf("b" to "Films"))
        assertEquals(listOf("Alpha", "Films"), named.map { it.label })
        assertSame(apps, AppNames.apply(apps, emptyMap()))
        // Saved homes with names restore and validate.
        app.ember.tv.home.HomeConfigValidation.homes(mapOf(Profile.MAIN to PersonalHome(names = mapOf("b" to "Films"))), setOf(Profile.MAIN))
    }

    @Test fun automaticBackupsFollowTheScheduleAndKeepTheNewest() {
        val day = 86_400_000L
        assertFalse(AutoBackup.due("off", 0, 10 * day))
        assertTrue(AutoBackup.due("weekly", 0, 10 * day))
        assertFalse(AutoBackup.due("weekly", 9 * day, 10 * day))
        assertTrue(AutoBackup.due("weekly", 2 * day, 10 * day))
        assertTrue(AutoBackup.due("daily", 8 * day, 10 * day))
        assertTrue("a clock that went back counts as due", AutoBackup.due("daily", 20 * day, 10 * day))
        assertEquals("weekly", AutoBackup.mode(ctx))

        val dir = File(ctx.filesDir, AutoBackup.DIR).apply { mkdirs() }
        listOf(3L, 1L, 2L).forEach { File(dir, "auto-$it.bin").writeText("x") }
        File(dir, "other.txt").writeText("x")
        assertEquals(listOf(3L, 2L, 1L), AutoBackup.list(ctx).map { it.time })
        val bad = AutoBackup.list(ctx).first()
        AutoBackup.cipher = { object : app.ember.tv.tracker.SecretCipher {
            override fun encrypt(name: String, value: ByteArray) = value
            override fun decrypt(name: String, blob: ByteArray): ByteArray = error("wrong key")
        } }
        try {
            val error = runCatching { AutoBackup.read(bad) }.exceptionOrNull()
            assertTrue(error is Backup.BackupException)
        } finally { AutoBackup.cipher = { app.ember.tv.tracker.KeystoreSecretCipher("ember_auto_backup_v1") } }
    }
}
