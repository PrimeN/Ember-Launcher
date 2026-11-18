package app.ember.tv

import app.ember.tv.data.counted
import app.ember.tv.ui.AppChoices
import app.ember.tv.ui.SettingsTab
import app.ember.tv.ui.path
import org.junit.Assert.*
import org.junit.Test

/** Pre-release polish: directions, counts and wording. */
class ReleasePolishTest {
    /** #324: directions are written from the pages' own names; a merged page points at the page showing it. */
    @Test fun settingsDirectionsUseTheRealPages() {
        assertEquals("Settings › Sources › Addons & catalogs", SettingsTab.ADDONS.path)
        assertEquals("Settings › Look & feel › Appearance", SettingsTab.APPEARANCE.path)
        assertEquals("Settings › Home › Screensaver & extras", SettingsTab.SCREENSAVER.path)
        assertEquals("Settings › Sources › Artwork & metadata", SettingsTab.METADATA.path)
        assertEquals("Settings › System › Storage & diagnostics", SettingsTab.DEBUG.path)
    }

    /** #330: one of a thing is singular. */
    @Test fun countsUseTheRightForm() {
        assertEquals("1 channel", 1.counted("channel"))
        assertEquals("2 channels", 2.counted("channel"))
        assertEquals("0 titles", 0.counted("title"))
        assertEquals("1 new IPTV source", 1.counted("new IPTV source"))
        assertEquals("1 app", AppChoices.count(1, 1))
        assertEquals("All 3 apps", AppChoices.count(3, 3))
    }

    /** #323: every library the APK carries is credited, with its notice where the license asks for one. */
    @Test fun bundledLibrariesAreCredited() {
        val text = app.ember.tv.data.Legal.OPEN_SOURCE.flatMap { it.items }.joinToString("\n") { it.title + " " + it.text }
        for (name in listOf("Rhino", "jsoup", "Bouncy Castle", "SLF4J", "MBassador", "nanojson", "Protocol Buffers", "Tink", "Guava", "Okio", "asn-one"))
            assertTrue(name, text.contains(name))
        val notices = (java.io.File("src/main/assets/licenses/Bundled-MIT-BSD-NOTICES.txt").takeIf { it.exists() } ?: java.io.File("app/src/main/assets/licenses/Bundled-MIT-BSD-NOTICES.txt")).readText()
        for (holder in listOf("Jonathan Hedley", "Legion of the Bouncy Castle", "QOS.ch", "Benjamin Diedrichsen", "nanojson Authors", "Google Inc."))
            assertTrue(holder, notices.contains(holder))
    }
}
