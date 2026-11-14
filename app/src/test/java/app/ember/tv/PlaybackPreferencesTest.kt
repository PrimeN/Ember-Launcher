package app.ember.tv

import app.ember.tv.player.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PlaybackPreferencesTest {
    @Test fun olderSettingsKeepPreciseSeekingAndUnprocessedAudio() {
        val config=Json.decodeFromString<PlaybackConfig>("{}")
        assertEquals("precise",config.seekMode)
        assertFalse(config.audioEffects.active)
        assertEquals("inherit",config.decoderRules.preference("https://host/movie.mkv"))
        assertNull(config.bluetoothDelayMs)
        assertEquals(PlayerShortcuts.defaults,config.quickShortcuts)
    }
    @Test fun allNewPreferencesSurviveSerialization() {
        val config=PlaybackConfig(decoderRules=DecoderRules("hardware","software",mapOf("video/hevc" to "auto"),mapOf("avi" to "software")),bluetoothDelayMs=-200,seekMode="fast",audioEffects=AudioEffects("dialogue",4,"mono"),quickShortcuts=listOf("delay","audio","effects"))
        assertEquals(config,Json.decodeFromString<PlaybackConfig>(Json.encodeToString(config)))
    }
    @Test fun codecRulesTakePriorityOverExtensionAndSourceDefaults() {
        val rules=DecoderRules("hardware","software",mapOf("video/hevc" to "auto"),mapOf("mkv" to "hardware"))
        assertEquals("auto",rules.preference("https://host/MOVIE.MKV?token=opaque","video/hevc"))
        assertEquals("hardware",rules.preference("https://host/MOVIE.MKV?token=opaque","video/avc"))
        assertEquals("software",rules.preference("https://host/stream?name=movie.mkv","video/avc"))
        assertEquals("hardware",rules.preference("content://files/42"))
        assertEquals("hardware",rules.preference("https://host/stream","video/avc","Movie.MKV"))
        assertEquals("hardware",rules.preference("https://host/Movie.MKV","video/avc","Title without a filename"))
    }
    @Test fun futureAndInheritedRulesFallBackWithoutForcingAnUnknownDecoder() {
        val rules=DecoderRules("future","software",mapOf("video/hevc" to "inherit"),mapOf("mkv" to "future"))
        assertEquals("software",rules.preference("https://host/movie.mkv","video/hevc"))
        assertEquals("inherit",rules.preference("file:///movie.mp4"))
    }
    @Test fun bluetoothOverrideDoesNotOverwriteOtherOutputs() {
        val config=PlaybackConfig(audioDelayMs=-50,bluetoothDelayMs=250)
        assertEquals(-50,config.outputDelay(false));assertEquals(250,config.outputDelay(true))
        assertEquals(-50,config.copy(bluetoothDelayMs=null).outputDelay(true))
        assertEquals(10000,config.copy(bluetoothDelayMs=Int.MAX_VALUE).outputDelay(true))
    }
    @Test fun navigationCanUseKeyframesWhileResumeRemainsExact() {
        assertEquals("absolute+keyframes",SeekPreference.flags("fast",false))
        assertEquals("absolute+exact",SeekPreference.flags("fast",true))
        assertEquals("absolute+exact",SeekPreference.flags("future",false))
    }
    @Test fun shortcutsPreserveTheUsersOrderAndDiscardUnknownOrRepeatedEntries() {
        assertEquals(listOf("delay","speed","audio"),PlayerShortcuts.normalized(listOf("delay","future","speed","delay","audio")))
        assertEquals(emptyList<String>(),PlayerShortcuts.normalized(emptyList()))
        assertEquals(8,PlayerShortcuts.normalized(PlayerShortcuts.choices.map {it.first}).size)
    }
    @Test fun channelFiltersKeepSurroundChannelsAndUnknownEffectsAreNeutral() {
        assertEquals("lavfi=[pan=6c|c0=c1|c1=c0|c2=c2|c3=c3|c4=c4|c5=c5]",AudioEffects(channels="swap").nativeFilters(6))
        assertEquals("",AudioEffects(channels="swap").nativeFilters(1))
        assertEquals("",AudioEffects("future",-100,"future").nativeFilters())
        assertFalse(AudioEffects("future",-100,"future").active)
    }
    @Test fun optionalFiltersWaitForTrackIdentityAndNeverForceBitstreamAudioToDecode() {
        assertFalse(AudioProcessingPolicy.allowed(null,true,false,false))
        assertFalse(AudioProcessingPolicy.allowed("audio/ac3",true,false,false))
        assertFalse(AudioProcessingPolicy.allowed("audio/ac3",false,true,true))
        assertTrue(AudioProcessingPolicy.allowed("audio/ac3",false,false,false))
        assertTrue(AudioProcessingPolicy.allowed("audio/ac3",true,false,true))
        assertTrue(AudioProcessingPolicy.allowed("audio/mp4a-latm",true,false,false))
    }

}
