package app.ember.tv

import app.ember.tv.data.Meta
import app.ember.tv.data.PosterRibbons
import app.ember.tv.data.PosterRibbons.Kind
import app.ember.tv.iptv.Channel
import app.ember.tv.player.DeviceTuning
import app.ember.tv.player.FileLength
import app.ember.tv.player.PlaybackConfig
import app.ember.tv.ui.LiveGuideState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #431–#434. */
class TuningRibbonsGuideTest {
    @Test fun tuningFollowsMemoryAndRefreshRates() {
        val small = DeviceTuning.plan(DeviceTuning.Facts(1500, listOf(60f), emptyList(), false), PlaybackConfig())
        assertEquals("normal", small.config.buffer); assertEquals("off", small.config.frameRate)
        val film = DeviceTuning.plan(DeviceTuning.Facts(4000, listOf(23.976f, 24f, 50f, 60f), listOf("DTS"), true), PlaybackConfig())
        assertEquals("huge", film.config.buffer); assertEquals("always", film.config.frameRate)
        val noFilm = DeviceTuning.plan(DeviceTuning.Facts(2500, listOf(50f, 60f), emptyList(), false), PlaybackConfig())
        assertEquals("large", noFilm.config.buffer); assertEquals("seamless", noFilm.config.frameRate)
        assertEquals(3, film.lines.size)
    }

    @Test fun fileLengthCatchesSamplesNotCuts() {
        assertTrue(FileLength.suspicious(2 * 60_000L, 120, episode = false))
        assertFalse(FileLength.suspicious(118 * 60_000L, 120, episode = false))
        assertFalse(FileLength.suspicious(160 * 60_000L, 120, episode = false))
        assertTrue(FileLength.suspicious(200 * 60_000L, 120, episode = false))
        assertFalse(FileLength.suspicious(22 * 60_000L, 45, episode = true))
        assertTrue(FileLength.suspicious(60_000L, 45, episode = true))
        assertFalse(FileLength.suspicious(60_000L, null, episode = false))
    }

    @Test fun ribbonsComeFromTheTitleAlone() {
        val m = Meta(id = "tt1", type = "movie", name = "M", releaseInfo = "2026", ageRating = "PG-13", genres = listOf("Comedy"), runtime = "136 min")
        assertEquals(listOf("New", "PG-13"), PosterRibbons.of(m, PosterRibbons.DEFAULT, 2026))
        assertEquals(listOf("Comedy", "2026"), PosterRibbons.of(m, setOf(Kind.GENRE, Kind.YEAR), 2026))
        assertEquals(listOf("2h 16m"), PosterRibbons.of(m, setOf(Kind.RUNTIME), 2026))
        assertEquals(listOf("Movie"), PosterRibbons.of(m.copy(releaseInfo = "2019", ageRating = null), PosterRibbons.DEFAULT, 2026))
        assertEquals(setOf(Kind.AGE, Kind.YEAR), PosterRibbons.parse(PosterRibbons.write(setOf(Kind.YEAR, Kind.AGE))))
    }

    @Test fun guideMovesAndWraps() {
        val chans = (1..5).map { Channel("k$it", "s", "C$it", "http://x/$it") }
        val g = LiveGuideState(chans, 0, "k1", 0)
        assertEquals(4, g.moved(-1).selected)
        assertEquals(3, g.moved(8).selected)
    }
}
