package app.ember.tv

import app.ember.tv.data.StartupGuard
import app.ember.tv.data.StartupGuard.State
import app.ember.tv.system.RecoveryLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** #442 recovery page. */
class RecoveryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun threeFailedStartsInARowOpenRecovery() {
        var s = State()
        s = StartupGuard.starting(s, 1_000); assertFalse(StartupGuard.shouldRecover(s, 1_000))
        s = StartupGuard.starting(s, 2_000); assertFalse(StartupGuard.shouldRecover(s, 2_000))
        s = StartupGuard.starting(s, 3_000); assertTrue(StartupGuard.shouldRecover(s, 3_500))
        // Long after, the old failures don't count.
        assertFalse(StartupGuard.shouldRecover(s, 3_000 + StartupGuard.WINDOW_MS + 1))
        assertEquals(1, StartupGuard.starting(s, 3_000 + StartupGuard.WINDOW_MS + 1).failures)
    }

    @Test fun releasePagesBecomeApiLookups() {
        assertEquals("https://api.github.com/repos/PrimeN/Ember-Launcher/releases/latest", RecoveryLinks.releaseApi("https://github.com/PrimeN/Ember-Launcher/releases/latest"))
        assertEquals("https://api.github.com/repos/PrimeN/Ember-Launcher/releases/tags/v1.4.1", RecoveryLinks.releaseApi("https://github.com/PrimeN/Ember-Launcher/releases/tag/v1.4.1"))
        assertEquals("https://api.github.com/repos/PrimeN/Ember-Launcher/releases/latest", RecoveryLinks.releaseApi("github.com/PrimeN/Ember-Launcher/releases".let { "https://$it" }))
        assertNull(RecoveryLinks.releaseApi("https://example.com/ember.apk"))
        val json = """{"assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt"},{"name":"ember-tv-1.4.1-1.apk","browser_download_url":"https://x/e.apk"}]}"""
        assertEquals("https://x/e.apk", RecoveryLinks.resolve("https://github.com/PrimeN/Ember-Launcher/releases/latest") { json })
        assertEquals("https://example.com/a.apk", RecoveryLinks.resolve("https://example.com/a.apk") { error("no lookup") })
        assertTrue(runCatching { RecoveryLinks.resolve("https://github.com/a/b/releases/latest") { """{"assets":[]}""" } }.isFailure)
    }

    @Test fun apksAreToldApartFromZipsHoldingOne() {
        fun zip(vararg names: String) = temp.newFile().also { f ->
            ZipOutputStream(f.outputStream()).use { z -> names.forEach { z.putNextEntry(ZipEntry(it)); z.write(1); z.closeEntry() } }
        }
        assertTrue(RecoveryLinks.isApk(zip("AndroidManifest.xml", "classes.dex")))
        assertFalse(RecoveryLinks.isApk(zip("ember-tv-1.4.1.apk")))
        assertFalse(RecoveryLinks.isApk(temp.newFile().also { it.writeText("not a zip") }))
    }

    @Test fun releasesAreListedNewestFirstAndComparedWithTheInstalledOne() {
        val json = """[
          {"tag_name":"v1.4.1","name":"v1.4.1 Squished bugs","draft":false,"published_at":"2026-11-25T03:00:00Z","assets":[{"name":"ember-tv-1.4.1-9.apk","browser_download_url":"https://x/141.apk"}]},
          {"tag_name":"v1.4.0","name":"v1.4.0 Meet Spark","draft":false,"published_at":"2026-11-24T03:00:00Z","assets":[{"name":"ember-tv-1.4.0-8.apk","browser_download_url":"https://x/140.apk"}]},
          {"tag_name":"v1.3.9","name":"Draft","draft":true,"assets":[{"name":"a.apk","browser_download_url":"https://x/d.apk"}]},
          {"tag_name":"v1.0.0","name":"No app","draft":false,"assets":[]}
        ]"""
        val list = RecoveryLinks.releases(json)
        assertEquals(listOf("v1.4.1", "v1.4.0"), list.map { it.tag })
        assertEquals("Squished bugs", list[0].title)
        assertEquals("1.4.1", list[0].version)
        assertEquals("2026-11-25", list[0].date)
        assertEquals("https://x/140.apk", list[1].apk)
        assertTrue(RecoveryLinks.releases("not json").isEmpty())

        assertTrue(RecoveryLinks.compare("1.4.0", "1.4.1") < 0)
        assertTrue(RecoveryLinks.compare("v1.10.0", "1.9.3") > 0)
        assertEquals(0, RecoveryLinks.compare("1.4", "1.4.0"))
        assertEquals(0, RecoveryLinks.compare("1.4.1", "1.4.1-beta"))
        assertEquals(0, RecoveryLinks.compare("1.4.1", ""))
    }

    @Test fun aBlackScreenIsToldFromADarkHome() {
        val black = IntArray(64 * 36) { 0xFF050505.toInt() }
        assertTrue(StartupGuard.looksBlack(black))
        val dark = black.copyOf().also { it[100] = 0xFFE8E0D8.toInt() } // one bit of text
        assertFalse(StartupGuard.looksBlack(dark))
        assertFalse(StartupGuard.looksBlack(IntArray(0)))
    }

    @Test fun theRemoteSequenceOpensRecovery() {
        val s = StartupGuard.SECRET
        assertTrue(StartupGuard.secretEntered(listOf(23, 4) + s))
        assertFalse(StartupGuard.secretEntered(s.dropLast(1)))
        assertFalse(StartupGuard.secretEntered(s.reversed()))
    }

    @Test fun releasesCompareByBuildNumberWhenKnown() {
        assertEquals(217744169L, app.ember.tv.system.RecoveryLinks.buildCode("https://github.com/x/y/releases/download/v1.4.1/ember-tv-1.4.1-217744169.apk"))
        assertEquals(217744169L, app.ember.tv.system.RecoveryLinks.buildCode("https://x/ember-tv-1.4.1-217744169-release.apk?raw=1"))
        assertEquals(null, app.ember.tv.system.RecoveryLinks.buildCode("https://x/other.apk"))
        val release = app.ember.tv.system.RecoveryLinks.ReleaseItem("v1.4.1", "", "1.4.1", "", "https://x/ember-tv-1.4.1-217744169.apk")
        // A newer 1.4.1 test build: the 1.4.1 release is older, as Android sees it.
        assertTrue(app.ember.tv.system.RecoveryLinks.age(release, "1.4.1", 217799277L) < 0)
        assertEquals(0, app.ember.tv.system.RecoveryLinks.age(release, "1.4.1", 217744169L))
        // Without build numbers, the version names decide.
        assertTrue(app.ember.tv.system.RecoveryLinks.age(release, "1.4.0", null) > 0)
    }
}
