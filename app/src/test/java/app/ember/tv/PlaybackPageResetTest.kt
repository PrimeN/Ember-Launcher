package app.ember.tv

import app.ember.tv.player.*
import app.ember.tv.ui.SettingsReset
import app.ember.tv.ui.SettingsTab
import org.junit.Assert.*
import org.junit.Test

class PlaybackPageResetTest {
    private fun custom()=PlaybackConfig(seekMode="fast",quickShortcuts=listOf("audio"),bluetoothDelayMs=450,
        decoderRules=DecoderRules(network="software"),rememberDecoderChoices=false,
        audioEffects=AudioEffects(preset="voice",bass=2,channels="mono"))
    @Test fun playerResetRestoresItsNewControlsAndPreservesAudioAndVideoPreferences() {
        val before=custom();val result=SettingsReset.playback(SettingsTab.PLAYER,before)!!
        assertEquals(PlaybackConfig().seekMode,result.seekMode)
        assertEquals(PlaybackConfig().quickShortcuts,result.quickShortcuts)
        assertEquals(before.bluetoothDelayMs,result.bluetoothDelayMs)
        assertEquals(before.decoderRules,result.decoderRules)
        assertEquals(before.audioEffects,result.audioEffects)
    }
    @Test fun audioVideoResetRestoresDecoderEffectsAndBluetoothControlsAndPreservesPlayerChoices() {
        val before=custom();val result=SettingsReset.playback(SettingsTab.AUDIO_VIDEO,before)!!;val defaults=PlaybackConfig()
        assertEquals(defaults.bluetoothDelayMs,result.bluetoothDelayMs)
        assertEquals(defaults.decoderRules,result.decoderRules)
        assertEquals(defaults.audioEffects,result.audioEffects)
        assertEquals(defaults.rememberDecoderChoices,result.rememberDecoderChoices)
        assertEquals(before.quickShortcuts,result.quickShortcuts);assertEquals(before.seekMode,result.seekMode)
    }
}
