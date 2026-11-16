package app.ember.tv

import android.app.Application
import app.ember.tv.player.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #309–#311: playback features brought over from Ember-Player. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaybackPortsTest {
    @Test fun nightListeningCompressesDecodedSoundInBothPlayers() {
        val off = AudioEffects()
        assertFalse(off.active)
        val night = AudioEffects(compression = "night")
        assertTrue(night.active)
        val filters = night.nativeFilters(2)
        assertTrue(filters.contains("acompressor="))
        assertTrue("make-up gain is taken off first", filters.startsWith("lavfi=[volume=-6.0000dB"))
        assertEquals(6.0, night.headroomDb(), 0.001)
        assertFalse(AudioEffects(compression = "loud").active)

        // A loud passage comes out quieter relative to a quiet one, and never as NaN.
        fun level(input: Float): Float {
            val dsp = AudioDsp(48_000, 2, night)
            val frame = FloatArray(2)
            repeat(48_000) { frame[0] = input; frame[1] = input; dsp.frame(frame) }
            return frame[0]
        }
        val quiet = level(0.02f); val loud = level(0.9f)
        assertTrue(quiet.isFinite() && loud.isFinite())
        assertTrue("dynamic range is reduced", loud / quiet < 0.9f / 0.02f)
        val bad = FloatArray(2) { Float.NaN }
        AudioDsp(48_000, 2, night).frame(bad)
        assertTrue(bad.all { it.isFinite() })
    }

    @Test fun customBufferingIsCappedByDeviceMemoryAndBuildsALoadControl() {
        val custom = PlaybackConfig(stream = StreamConfig(custom = true, forwardMiB = 512, rewindMiB = 128))
        val low = StreamConfig.plan(custom, heap = 512L * 1024 * 1024, low = true)
        assertTrue(low.forwardMiB + low.rewindMiB <= 24)
        val roomy = StreamConfig.plan(custom, heap = 2048L * 1024 * 1024, low = false)
        assertTrue(roomy.forwardMiB + roomy.rewindMiB <= 192)
        assertEquals("30", roomy.nativeOptions()["network-timeout"])
        // Extreme values still give a valid player buffer.
        val odd = PlaybackConfig(stream = StreamConfig(custom = true, aheadSeconds = 10, readAheadSeconds = 300, refillSeconds = 30))
        Playback.loadControl(odd, RuntimeEnvironment.getApplication())
        Playback.loadControl(PlaybackConfig(), RuntimeEnvironment.getApplication())
        val limits = StreamConfig(connectSeconds = 1, readSeconds = 9999).normalized()
        assertEquals(5, limits.connectSeconds); assertEquals(300, limits.readSeconds)
    }

    @Test fun compatibilityDecodingChoices() {
        fun choose(mode: String, manual: Boolean? = null, rule: String = "inherit") =
            NativeDecoderProfile.choose(mode, manual, rule, false, "off", false, false, true)
        assertEquals("mediacodec-copy", choose("copy").hardware)
        assertEquals("mediacodec", choose("direct").hardware)
        val light = choose("light")
        assertEquals("no", light.hardware); assertEquals("nonref", light.options["vd-lavc-skiploopfilter"])
        assertEquals("default", choose("software").options["vd-lavc-skiploopfilter"])
        assertNotEquals("no", choose("light", manual = false).hardware)
        assertEquals("no", choose("direct", rule = "software").hardware)
        assertEquals("auto", NativeDecoderProfile.normalized("nonsense"))
        assertTrue(NativeDecoderProfile.choose("auto", null, "inherit", false, "off", true, true, true).hi10p)
        assertFalse(NativeDecoderProfile.choose("auto", null, "inherit", false, "off", true, true, false).hi10p)
    }
}
