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
        assertEquals("Settings › Content › Addons & catalogs", SettingsTab.ADDONS.path)
        assertEquals("Settings › Look › Theme & navbar", SettingsTab.APPEARANCE.path)
        assertEquals("Settings › Home › Screensaver & weather", SettingsTab.SCREENSAVER.path)
        assertEquals("Settings › Content › Metadata & where to watch", SettingsTab.STREAMING.path)
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

    /** #325: a full release date reads as a date in the TV's language, and the year is not repeated beside it. */
    @Test fun releaseDatesReadAsDates() {
        val previous = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.CANADA)
        try {
            assertEquals("2026", app.ember.tv.data.DisplayDates.release("2026"))
            assertNotEquals("2026-01-01", app.ember.tv.data.DisplayDates.release("2026-01-01T00:00:00.000Z"))
            assertTrue(app.ember.tv.data.DisplayDates.release("2026-01-01")!!.contains("2026"))
            assertEquals("2026", app.ember.tv.data.DisplayDates.releaseYear("2026-01-01"))
            assertEquals(listOf("Series", app.ember.tv.data.DisplayDates.release("2026-01-01"), "108 min"),
                app.ember.tv.ui.TitleInfoLine.parts("series", "2026-01-01", null, "108 min", listOf("2026")))
        } finally { java.util.Locale.setDefault(previous) }
    }

    /** A server's Continue watching and Recently added can share an episode; the shelf shows it once, so its key is unique. */
    @Test fun lightShelfShowsEachTitleOnce() {
        fun card(id: String) = app.ember.tv.light.ShelfCard(id, id, "", null, null)
        val cards = app.ember.tv.ui.LightShelfCards.flatten(listOf(
            "Continue watching" to listOf(card("a"), card("b")),
            "Recently added" to listOf(card("b"), card("c")),
            "On deck" to listOf(card("a")),
        ))
        assertEquals(listOf("a", "b", "c"), cards.map { it.second.id })
        assertEquals(listOf("Continue watching", null, "Recently added"), cards.map { it.first })
    }

    /** A source template asking for something impossible (a negative length) is shown as written, never a crash. */
    @Test fun anImpossibleTemplateIsShownAsWritten() {
        val fields = app.ember.tv.data.StreamFormatter.Fields { _, _ -> "Remux" }
        val template = "{stream.title::truncate(-1)}"
        assertEquals(template, app.ember.tv.data.StreamFormatter.render(template, fields))
        assertEquals("Rem…", app.ember.tv.data.StreamFormatter.render("{stream.title::truncate(3)}", fields))
    }

    /** A server link with a stray "%" keeps working, and its key is still removed. */
    @Test fun aServerPathWithAStrayPercentIsCleaned() {
        assertEquals("/Videos/1/Subtitles/0/Stream.srt?x=%zz",
            app.ember.tv.mediaserver.ServerMetadata.cleanPath("/Videos/1/Subtitles/0/Stream.srt?api_key=abc&x=%zz"))
    }
}
