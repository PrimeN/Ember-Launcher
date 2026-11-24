package app.ember.tv

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import app.ember.tv.data.ShowEpisodes
import app.ember.tv.player.Playback
import app.ember.tv.player.PlaybackConfig
import app.ember.tv.tracker.ParentsGuide
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #421–#426. */
class AudioAndHomeExtrasTest {
    private fun audio(mime: String, channels: Int = 6) = Format.Builder().setSampleMimeType(mime).setChannelCount(channels).build()

    @Test fun soundbarPrefersTracksArcCarries() {
        val truehd = audio(MimeTypes.AUDIO_TRUEHD, 8)
        val ac3 = audio(MimeTypes.AUDIO_AC3)
        assertTrue(Playback.audioScore(truehd) > Playback.audioScore(ac3))
        assertTrue(Playback.audioScore(truehd, soundbar = true) < Playback.audioScore(ac3, soundbar = true))
        assertTrue(Playback.audioScore(audio(MimeTypes.AUDIO_DTS_HD), soundbar = true) > Playback.audioScore(ac3, soundbar = true))
    }

    @Test fun soundbarDefaultsAreUnchanged() {
        val c = PlaybackConfig()
        assertEquals("auto", c.passthroughMode)
        assertTrue(c.keepSurround)
    }

    @Test fun episodesLeftCountsAiredEpisodesAfterTheCurrentOne() {
        val show = ShowEpisodes(mapOf(1 to 10, 2 to 8, 3 to 10), lastAired = 3 to 4)
        assertEquals(6 + 8 + 4, show.leftAfter(1, 4))
        assertEquals(4, show.leftAfter(3, 0))
        assertEquals(0, show.leftAfter(3, 4))
        assertNull(ShowEpisodes(mapOf(1 to 10), null).leftAfter(1, 1))
    }

    @Test fun parentsGuideKeepsCategoriesWhereASeverityBeatsNone() {
        val json = Json.parseToJsonElement("""[
            {"category":"VIOLENCE","severityBreakdowns":[{"severityLevel":"none","voteCount":3},{"severityLevel":"moderate","voteCount":40},{"severityLevel":"severe","voteCount":12}]},
            {"category":"PROFANITY","severityBreakdowns":[{"severityLevel":"none","voteCount":50},{"severityLevel":"mild","voteCount":10}]},
            {"category":"SEXUAL_CONTENT","severityBreakdowns":[{"severityLevel":"severe","voteCount":9}]},
            {"category":"SOMETHING_ELSE","severityBreakdowns":[{"severityLevel":"severe","voteCount":9}]}
        ]""") as JsonArray
        val guide = ParentsGuide.parse(json.toList())
        assertEquals(listOf("Sex & nudity" to "Severe", "Violence" to "Moderate"), guide.map { it.category to it.severity })
        assertFalse(guide.any { it.category == "Profanity" })
    }
}
