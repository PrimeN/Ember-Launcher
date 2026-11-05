package app.ember.tv

import app.ember.tv.data.CrashLog
import app.ember.tv.data.Quality
import app.ember.tv.data.Updater
import app.ember.tv.ui.cardArt
import app.ember.tv.ui.sharpArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmallLogicTest {
    @Test fun versionNames() {
        assertTrue(Updater.newerName("0.9.5.1", "0.9.5"))
        assertTrue(Updater.newerName("0.9.5.2", "0.9.5.1"))
        assertFalse(Updater.newerName("0.9.5", "0.9.5.1"))
        assertTrue(Updater.newerName("0.10.0", "0.9.2"))
        assertTrue(Updater.newerName("1.0", "0.9.9"))
        assertFalse(Updater.newerName("0.9.0", "0.9.0"))
        assertFalse(Updater.newerName("0.8.9", "0.9"))
    }

    @Test fun crashLogHidesKeys() {
        val r = CrashLog.redact("HTTP 404 for https://torrentio.strem.fun/realdebrid=SECRET123/stream.json and token=abc")
        assertFalse(r.contains("SECRET123"))
        assertFalse(r.contains("abc"))
        assertTrue(r.contains("torrentio.strem.fun"))
    }

    @Test fun qualityRank() {
        assertTrue(Quality.rank("4K") > Quality.rank("FHD"))
        assertTrue(Quality.rank("HD") > Quality.rank("SD"))
        assertEquals(0, Quality.rank(""))
    }

    @Test fun imageSizes() {
        assertEquals("https://image.tmdb.org/t/p/w342/a.jpg", cardArt("https://image.tmdb.org/t/p/original/a.jpg", wide = false))
        assertEquals("https://image.tmdb.org/t/p/w780/b.jpg", cardArt("https://image.tmdb.org/t/p/w1280/b.jpg", wide = true))
        assertEquals("https://images.metahub.space/background/large/tt1/img", sharpArt("https://images.metahub.space/background/medium/tt1/img"))
    }
}
