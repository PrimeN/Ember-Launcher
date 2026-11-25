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
}
