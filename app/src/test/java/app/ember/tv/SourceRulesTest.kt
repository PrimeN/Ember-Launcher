package app.ember.tv

import app.ember.tv.data.SourceKind
import app.ember.tv.data.SourceRules
import app.ember.tv.data.StreamParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #471: hiding sources by kind, picture, codec, size, words and addon. */
class SourceRulesTest {
    private fun stream(name: String, title: String, url: String = "https://cdn.example/f.mkv", addon: String = "Addon") =
        StreamParser.parse(addon, Json.parseToJsonElement("""{"url":"$url","name":"$name","title":"$title"}"""))!!

    private val uhd = stream("4K", "Film.2024.2160p.WEB-DL.DV.HEVC-GRP\n💾 22 GB")
    private val fhd = stream("1080p", "Film.2024.1080p.BluRay.x264-OTHER\n💾 8 GB")
    private val cam = stream("720p", "Film.2024.720p.HDCAM.x264")
    private val torrent = stream("1080p", "Film.2024.1080p.WEBRip", url = "magnet:?xt=urn:btih:abc")

    @Test fun nothingHiddenByDefault() {
        val (shown, hidden) = SourceRules().split(listOf(uhd, fhd, cam, torrent))
        assertEquals(4, shown.size); assertTrue(hidden.isEmpty())
        assertFalse(SourceRules().active)
    }

    @Test fun hidesUncachedAndCam() {
        assertEquals(SourceKind.UNCACHED, torrent.kind)
        val r = SourceRules(uncached = true, cam = true)
        assertEquals("Un-cached", r.reason(torrent))
        assertEquals("CAM", r.reason(cam))
        assertNull(r.reason(uhd))
        assertEquals(2, r.count)
    }

    @Test fun hidesPictureHdrCodecAndRelease() {
        assertEquals("4K", SourceRules(qualities = setOf("4K")).reason(uhd))
        assertEquals("DV", SourceRules(hdr = setOf("DV")).reason(uhd))
        assertEquals("SDR", SourceRules(hdr = setOf("SDR")).reason(fhd))
        assertEquals("H.264", SourceRules(codecs = setOf("H.264")).reason(fhd))
        assertEquals("BluRay", SourceRules(releases = setOf("BluRay")).reason(fhd))
        assertNull(SourceRules(codecs = setOf("AV1")).reason(fhd))
    }

    @Test fun wordsMatchWholeWordsAndGroups() {
        assertTrue(SourceRules.matches("hdcam", "Film.2024.720p.HDCAM.x264"))
        assertFalse(SourceRules.matches("TS", "DTS-HD MA 5.1 subs"))
        assertTrue(SourceRules.matches("dual audio", "Film 1080p Dual-Audio"))
        assertEquals("OTHER", SourceRules(words = listOf("OTHER")).reason(fhd))
        assertEquals(listOf("HDTS"), SourceRules.addWord(SourceRules.addWord(emptyList(), "HDTS"), "hdts"))
    }

    @Test fun hidesByAddonAndNeverHidesServers() {
        val r = SourceRules(addons = setOf("Addon"))
        assertEquals("Addon", r.reason(fhd))
        assertNull(r.reason(fhd.copy(serverRef = "plex:1")))
    }
}
