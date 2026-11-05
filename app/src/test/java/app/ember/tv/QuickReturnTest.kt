package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.player.*
import app.ember.tv.tracker.Kind
import org.junit.Assert.*
import org.junit.Test

/** "Back to …" after leaving a video, and the Back confirmation setting. */
class QuickReturnTest {
    private val stream = StreamOption("Addon", "Source", "1080p", "https://example.com/video", null, null, "", "", "", "", "", "", null, null, true)
    private val req = PlayRequest(stream, "Film", Kind.MOVIE, "tt1", null, null, null, "tt1", null, emptyList(), startOver = true)
    private val last = LastPlay(req, "builtin", 1_000L)

    @Test fun offeredOnlyForAPartlyWatchedTitleInsideTheWindow() {
        assertTrue(QuickReturn.available(last, true, 2_000L, 60_000L to 600_000L))
        assertFalse(QuickReturn.available(last, false, 2_000L, 60_000L to 600_000L))
        assertFalse(QuickReturn.available(null, true, 2_000L, 60_000L to 600_000L))
        // Finished titles have no saved position; nothing played yet is not worth returning to.
        assertFalse(QuickReturn.available(last, true, 2_000L, null))
        assertFalse(QuickReturn.available(last, true, 2_000L, 0L to 600_000L))
        assertTrue(QuickReturn.available(last, true, 1_000L + QuickReturn.WINDOW_MS, 1L to 2L))
        assertFalse(QuickReturn.available(last, true, 1_001L + QuickReturn.WINDOW_MS, 1L to 2L))
        assertFalse(QuickReturn.available(last, true, 999L, 1L to 2L))
    }
    @Test fun returningResumesWithoutAskingAgain() {
        val back = QuickReturn.request(last)
        assertFalse(back.startOver);assertTrue(back.resumeDecided);assertEquals(req.resumeKey, back.resumeKey)
    }
    @Test fun backConfirmationIsOffAndReturnIsOnByDefault() {
        val c = PlaybackConfig()
        assertFalse(c.confirmExit);assertTrue(c.quickReturn);assertFalse(c.pip)
        assertEquals("leaving", app.ember.tv.ui.SettingDefinitions.matching("confirm exit").single().id)
        assertTrue("leaving" in app.ember.tv.ui.PLAYER_KEYS)
    }
    @Test fun returnKeyChoiceRoundTripsAndOffKeepsTheLastKey() {
        val c = PlaybackConfig()
        assertEquals("play", QuickReturn.key(c))
        val back = QuickReturn.choose(c, "back")
        assertEquals("back", QuickReturn.key(back));assertTrue(back.quickReturn)
        val off = QuickReturn.choose(back, "off")
        assertEquals("off", QuickReturn.key(off));assertEquals("back", off.returnKey)
        assertEquals("play", QuickReturn.key(c.copy(returnKey = "future")))
        assertEquals("Press Back twice to return to", QuickReturn.prompt("back"))
        assertEquals(listOf("play", "back", "off"), QuickReturn.keys.map { it.first })
    }
}
