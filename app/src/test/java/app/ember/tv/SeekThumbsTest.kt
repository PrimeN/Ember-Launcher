package app.ember.tv

import app.ember.tv.player.StreamSeekThumbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #420: which streams get thumbnails, and the strip's three times. */
class SeekThumbsTest {
    @Test fun onlyPlainFilesOverHttp() {
        assertTrue(StreamSeekThumbs.supported("https://cdn.example/file.mkv", false))
        assertTrue(StreamSeekThumbs.supported("https://rd.example/dl/ABC?token=1", false))
        assertFalse(StreamSeekThumbs.supported("https://cdn.example/master.m3u8", false))
        assertFalse(StreamSeekThumbs.supported("https://cdn.example/x.mpd?a=1", false))
        assertFalse(StreamSeekThumbs.supported("https://cdn.example/file.mkv", true))
        assertFalse(StreamSeekThumbs.supported("content://media/1", false))
        assertFalse(StreamSeekThumbs.supported(null, false))
    }

    @Test fun stripIsBeforeAtAfterOnTheGrid() {
        assertEquals(listOf(1_790_000L, 1_800_000L, 1_810_000L), StreamSeekThumbs.strip(1_799_000, 3_600_000))
        assertEquals(listOf(0L, 10_000L), StreamSeekThumbs.strip(2_000, 3_600_000))
        assertEquals(listOf(3_590_000L, 3_600_000L), StreamSeekThumbs.strip(3_599_000, 3_600_000))
    }
}
