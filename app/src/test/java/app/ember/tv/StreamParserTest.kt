package app.ember.tv

import app.ember.tv.data.StreamParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Labels read from source names: what the title screen, the Play button and auto-play rely on. */
class StreamParserTest {
    @Test fun quality() {
        assertEquals("4K", StreamParser.quality("Dune.Part.Two.2024.2160p.UHD.BluRay"))
        assertEquals("FHD", StreamParser.quality("Movie 1080p WEB-DL"))
        assertEquals("HD", StreamParser.quality("Show S01E01 720p"))
        assertEquals("SD", StreamParser.quality("Old film 480p DVDRip"))
        assertEquals("", StreamParser.quality("No resolution here"))
    }

    @Test fun hdr() {
        assertEquals("DV + HDR10+", StreamParser.hdr("Film.2160p.DV.HDR10+.HEVC"))
        assertEquals("DV + HDR10", StreamParser.hdr("Film 2160p Dolby Vision HDR10"))
        assertEquals("HDR", StreamParser.hdr("Film 2160p HDR"))
        assertEquals("", StreamParser.hdr("Film 1080p"))
        // "DVD" is not Dolby Vision.
        assertEquals("", StreamParser.hdr("Film DVDRip"))
    }

    @Test fun codecAudioSource() {
        assertEquals("HEVC", StreamParser.codec("x265 10bit"))
        assertEquals("H.264", StreamParser.codec("H.264"))
        assertEquals("AV1", StreamParser.codec("AV1"))
        assertEquals("Atmos", StreamParser.audio("TrueHD 7.1 Atmos"))
        assertEquals("TrueHD", StreamParser.audio("TrueHD 7.1"))
        assertEquals("DD+", StreamParser.audio("DDP5.1"))
        assertEquals("Remux", StreamParser.source("BluRay REMUX"))
        assertEquals("WEB-DL", StreamParser.source("WEB-DL"))
    }

    @Test fun releaseGroup() {
        assertEquals("FLUX", StreamParser.group("Movie.2024.1080p.WEB-DL-FLUX"))
        assertEquals("YTS", StreamParser.group("Movie 2024 [YTS]"))
    }

    @Test fun parseStream() {
        val j = Json.parseToJsonElement(
            """{"name":"Torrentio\n4K","title":"Dune.2024.2160p.DV.HDR10.TrueHD.Atmos-GRP\n💾 58.2 GB 👤 120","url":"https://example.com/v.mkv"}""",
        )
        val s = StreamParser.parse("Torrentio", j)
        assertNotNull(s)
        s!!
        assertEquals("4K", s.quality)
        assertTrue(s.hdr.contains("DV"))
        assertEquals("Atmos", s.audio)
        assertEquals(58.2, s.sizeGb ?: 0.0, 0.01)
        assertEquals(120, s.seeders)
        assertTrue(s.isDirect)
    }

    @Test fun parseNeedsALink() {
        assertNull(StreamParser.parse("X", Json.parseToJsonElement("""{"name":"no link"}""")))
    }
}
