package app.ember.tv

import app.ember.tv.data.AddonHealth
import app.ember.tv.data.AddonHealth.Outcome
import app.ember.tv.data.AddonHealth.Sample
import app.ember.tv.data.DeadLinks
import app.ember.tv.data.Meta
import app.ember.tv.data.SourceWarmup
import app.ember.tv.data.StreamOption
import app.ember.tv.player.DisplaySettle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #427–#430. */
class SourcesAndSettleTest {
    @Test fun displayWaitsForTheSoundLinkWithinLimits() {
        assertFalse(DisplaySettle.settled(1_000, 1))
        assertTrue(DisplaySettle.settled(2_100, 1))
        assertTrue(DisplaySettle.settled(2_100, null))
        assertFalse(DisplaySettle.settled(3_000, 0))
        assertTrue(DisplaySettle.settled(5_000, 0))
    }

    private fun src(url: String, cached: Boolean = true, debrid: Boolean = true) =
        StreamOption("A", "n", "t", url, null, null, "1080p", "", "", "", "", "", null, null, cached, debrid)

    @Test fun deadLinkMovesToTheNextReadySource() {
        val list = listOf(src("https://a"), src("https://b", cached = false), src("magnet:?xt=1"), src("https://c"), src("https://d"))
        assertEquals("https://c", DeadLinks.next(list, "https://a", setOf("https://a"))?.url)
        assertEquals("https://d", DeadLinks.next(list, "https://a", setOf("https://a", "https://c"))?.url)
        assertEquals("https://a", DeadLinks.next(list, "https://d", setOf("https://d"))?.url)
        assertNull(DeadLinks.next(listOf(src("https://a")), "https://a", setOf("https://a")))
    }

    @Test fun deadLinkReportIsTakenOnce() {
        DeadLinks.report("k", "https://a", false, now = 1_000)
        assertEquals("https://a", DeadLinks.take(now = 2_000)?.url)
        assertNull(DeadLinks.take(now = 2_000))
        DeadLinks.report("k", "https://b", false, now = 3_000)
        assertNull(DeadLinks.take(now = 3_000 + 61_000))
    }

    @Test fun warmupTargetsWhatAddonsAreAskedFor() {
        assertEquals("movie" to "tt1", SourceWarmup.target(Meta(id = "tt1", type = "movie", name = "M")))
        assertEquals("series" to "tt2:1:3", SourceWarmup.target(Meta(id = "tt2", type = "series", name = "S", season = 1, episode = 3)))
        assertNull(SourceWarmup.target(Meta(id = "x", type = "series", name = "S")))
    }

    @Test fun healthLevels() {
        assertNull(AddonHealth.level(emptyList()))
        assertEquals(AddonHealth.Level.OK, AddonHealth.level(listOf(Sample(Outcome.OK, 900))))
        assertEquals(AddonHealth.Level.SLOW, AddonHealth.level(listOf(Sample(Outcome.OK, 9_000))))
        assertEquals(AddonHealth.Level.SLOW, AddonHealth.level(listOf(Sample(Outcome.OK, 900), Sample(Outcome.FAILED, 300))))
        assertEquals(AddonHealth.Level.DOWN, AddonHealth.level(listOf(Sample(Outcome.TIMEOUT, 9_000), Sample(Outcome.FAILED, 300))))
        assertEquals(AddonHealth.Level.OK, AddonHealth.level(listOf(Sample(Outcome.FAILED, 300), Sample(Outcome.OK, 800), Sample(Outcome.OK, 700))))
    }
}
