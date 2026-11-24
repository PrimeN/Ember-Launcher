package app.ember.tv

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import app.ember.tv.player.FrameCount
import app.ember.tv.player.PlaybackQuality
import app.ember.tv.player.PlaybackQualityWindow
import app.ember.tv.player.STABLE_PLAYBACK_MS
import app.ember.tv.player.SubtitleStyleProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #410 and #411, from Ember-Player. */
class PlayerPortNovemberTest {
    private val plain = "[V4+ Styles]\nStyle: Default,Arial,20,&H00FFFFFF\n[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello"
    private val sdr = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
    private val aac = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()

    @Test fun onlyStyledAssCounts() {
        assertFalse(SubtitleStyleProbe.styled(plain))
        assertTrue(SubtitleStyleProbe.styled(plain.replace("Hello", "{\\pos(10,20)}Hello")))
        assertTrue(SubtitleStyleProbe.styled(plain.replace("Default,Arial", "Sign,Arial")))
        assertTrue(SubtitleStyleProbe.styled(plain.replace("Default,Arial", "Default,Open Sans")))
    }

    @Test fun styledAssMovesOnlyWhenNothingIsLost() {
        assertTrue(SubtitleStyleProbe.routeToMpv(true, true, false, true, sdr, aac))
        assertFalse(SubtitleStyleProbe.routeToMpv(true, true, false, false, sdr, aac))
        assertFalse(SubtitleStyleProbe.routeToMpv(false, true, false, true, sdr, aac))
        val hdr = sdr.buildUpon().setColorInfo(ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build()).build()
        assertFalse(SubtitleStyleProbe.routeToMpv(true, true, false, true, hdr, aac))
        val dv = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build()
        assertFalse(SubtitleStyleProbe.routeToMpv(true, true, false, true, dv, aac))
        val ac4 = Format.Builder().setSampleMimeType("audio/ac4").build()
        assertFalse(SubtitleStyleProbe.routeToMpv(true, true, false, true, sdr, ac4))
    }

    @Test fun qualityIsMeasuredInACleanWindow() {
        assertTrue(PlaybackQuality(1000, 20).learnable(video = true))
        assertFalse(PlaybackQuality(1000, 40).learnable(video = true))
        assertFalse(PlaybackQuality(100, 0).learnable(video = true))
        assertFalse(PlaybackQuality().learnable(video = true))
        assertTrue(PlaybackQuality().learnable(video = false))
        val decoder = Any()
        val window = PlaybackQualityWindow(decoder, 0L, FrameCount(500, 300))
        assertNull(window.measure(decoder, STABLE_PLAYBACK_MS - 1, FrameCount(900, 300)))
        assertNull(window.measure(Any(), STABLE_PLAYBACK_MS, FrameCount(900, 300)))
        assertEquals(PlaybackQuality(400, 0), window.measure(decoder, STABLE_PLAYBACK_MS, FrameCount(900, 300)))
        assertNull(window.measure(decoder, STABLE_PLAYBACK_MS, FrameCount(400, 300)))
    }
}
