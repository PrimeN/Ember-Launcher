package app.ember.tv

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import app.ember.tv.player.Playback
import app.ember.tv.player.PlaybackConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #380: the user's audio output setting decides what Standard may send encoded; Media3 checks the receiver. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioOutputPolicyTest {
    private fun audio(mime: String) = Format.Builder().setSampleMimeType(mime).build()
    private val pcm = audio(MimeTypes.AUDIO_RAW)
    private val ac3 = audio(MimeTypes.AUDIO_AC3)
    private val eac3 = audio(MimeTypes.AUDIO_E_AC3)
    private val truehd = audio(MimeTypes.AUDIO_TRUEHD)

    @Test fun pcmOnlyDecodesEverything() {
        val p = Playback.AudioOutputPolicy(PlaybackConfig(passthrough = false), Playback.CodecChoice())
        assertTrue(p.permits(pcm)); assertFalse(p.permits(ac3)); assertFalse(p.permits(truehd))
    }

    @Test fun manualPassesOnlyTheChosenFormats() {
        val p = Playback.AudioOutputPolicy(PlaybackConfig(passthrough = true, passthroughMode = "manual", formats = setOf("ac3")), Playback.CodecChoice())
        assertTrue(p.permits(ac3)); assertFalse(p.permits(eac3)); assertFalse(p.permits(truehd)); assertTrue(p.permits(pcm))
    }

    @Test fun automaticLeavesItToTheReceiver() {
        val p = Playback.AudioOutputPolicy(PlaybackConfig(passthrough = true, passthroughMode = "auto"), Playback.CodecChoice())
        assertTrue(p.permits(ac3)); assertTrue(p.permits(truehd))
    }

    @Test fun temporaryPcmFallbackDecodesEverything() {
        val choice = Playback.CodecChoice().apply { pcm = true }
        val p = Playback.AudioOutputPolicy(PlaybackConfig(passthrough = true, passthroughMode = "auto"), choice)
        assertTrue(p.permits(pcm)); assertFalse(p.permits(ac3))
    }
}
