package app.ember.tv

import app.ember.tv.data.Badges
import app.ember.tv.data.StreamInfo
import app.ember.tv.data.StreamOption
import app.ember.tv.state.HomeSectionsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BadgesAndLayoutTest {
    // The built-in Detailed pack, as shipped (unit tests run in the app module's folder).
    private val pack = File("src/main/assets/badges/modern.json").readText()

    private fun source(quality: String?, resolution: String?, visual: List<String>, audio: List<String>, channels: List<String>, name: String = "Film 2160p") =
        StreamOption(
            addonName = "Addon", name = name, title = "", url = "https://example.com/a.mkv", infoHash = null, fileIdx = null,
            quality = "4K", hdr = "", codec = "", audio = "", source = "", releaseGroup = "", sizeGb = null, seeders = null, cached = true,
            info = StreamInfo(
                addonName = "Addon", name = name, description = "", filename = null, title = null, year = null, seasonEpisode = emptyList(),
                resolution = resolution, quality = quality, encode = null, visualTags = visual, audioTags = audio, audioChannels = channels,
                languages = emptyList(), sizeBytes = null, seeders = null, indexer = null, releaseGroup = null, edition = null, network = null,
                upscaled = false, remastered = false, uncensored = false, repack = false, unrated = false, proxied = false, message = null,
                service = null, cached = true, type = "debrid",
            ),
        )

    @Test
    fun detailedBadgesFromParsedDetails() {
        // No markers in the addon's text: Ember writes them from what it read of the source.
        val names = Badges.namesFor(pack, source("BluRay REMUX", "2160p", listOf("DV", "HDR10"), listOf("TrueHD", "Atmos"), listOf("7.1")))
        // The combined visual badge takes precedence over separate DV/audio chips.
        assertEquals(listOf("Remux", "4K", "Atmos+DV", "7.1"), names)
    }

    @Test
    fun specificTagsWin() {
        // HDR10+ alone (not also HDR10), DTS-HD MA alone (not also DTS), DD+ alone (not also DD).
        val a = Badges.namesFor(pack, source("BluRay", "2160p", listOf("HDR10+"), listOf("DTS-HD MA"), listOf("5.1")))
        assertEquals(listOf("BluRay", "4K", "HDR10+", "DTS-HD MA", "5.1"), a)
        val b = Badges.namesFor(pack, source("WEB-DL", "1080p", listOf("SDR"), listOf("DD+"), listOf("5.1")))
        assertEquals(listOf("WebDL", "1080p", "SDR", "DD+", "5.1"), b)
    }

    @Test
    fun markersHiddenFromText() {
        assertEquals("Film", Badges.clean("Film⁣​‍⁣"))
        // Emoji keep their zero-width joiner.
        val family = "👨‍👩"
        assertEquals(family, Badges.clean(family))
    }

    @Test
    fun newSectionGoesToItsDefaultPlace() {
        val known = listOf("hero", "apps", "watchbutton", "continue", "catalogs")
        val saved = listOf("apps" to true, "hero" to true, "continue" to false, "catalogs" to true)
        val merged = HomeSectionsState.merge(known, saved, setOf("hero", "apps", "watchbutton", "catalogs"))
        // The user's order stays; the new button goes right after apps, switched on.
        assertEquals(listOf("apps" to true, "watchbutton" to true, "hero" to true, "continue" to false, "catalogs" to true), merged)
    }

    @Test
    fun freshLayoutIsTheDefault() {
        val known = listOf("hero", "apps", "watchbutton", "continue", "catalogs")
        val merged = HomeSectionsState.merge(known, emptyList(), setOf("hero", "apps", "watchbutton", "catalogs"))
        assertEquals(known, merged.map { it.first })
        assertTrue(merged.first { it.first == "continue" }.second.not())
    }
}
