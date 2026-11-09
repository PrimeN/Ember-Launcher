package app.ember.tv.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.Prefs
import app.ember.tv.DetailsUi
import app.ember.tv.data.StreamOption

/** Settings > Subtitles, Player and Play button, and how sources are listed. Split out of HomeViewModel. */
class PlayerSettingsState(private val prefs: Prefs) {
    // ---- subtitles (Settings > Subtitles) ----
    var subtitleConfig by mutableStateOf(prefs.subtitleConfig); private set

    fun updateSubtitleConfig(change: (app.ember.tv.data.SubtitleConfig) -> app.ember.tv.data.SubtitleConfig) {
        subtitleConfig = change(subtitleConfig)
        prefs.subtitleConfig = subtitleConfig
    }

    // ---- built-in player engine (Settings > Player) ----
    var playbackConfig by mutableStateOf(prefs.playbackConfig); private set

    var playRules by mutableStateOf(prefs.playRules); private set

    init {
        // #145: the beta "Auto select" button could switch the Play button off by accident; turn it
        // back on once. Turning it off in Settings afterwards sticks.
        if (!prefs.playButtonRestored) {
            if (!playRules.enabled) { playRules = playRules.copy(enabled = true); prefs.playRules = playRules }
            prefs.playButtonRestored = true
        }
    }

    fun updatePlayRules(change: (app.ember.tv.data.PlayRules) -> app.ember.tv.data.PlayRules) {
        playRules = change(playRules)
        prefs.playRules = playRules
    }

    /** The source the title screen's Play button plays (Settings > Play button), or null. */
    fun bestSource(d: DetailsUi): StreamOption? =
        if (!playRules.enabled) null else app.ember.tv.data.PlayPicker.pick(d.streams, d.serverStreams, playRules)

    fun updatePlaybackConfig(change: (app.ember.tv.player.PlaybackConfig) -> app.ember.tv.player.PlaybackConfig) {
        playbackConfig = change(playbackConfig)
        prefs.playbackConfig = playbackConfig
    }

    var sourcesCombined by mutableStateOf(prefs.sourcesCombined); private set

    fun updateSourcesCombined(on: Boolean) {
        sourcesCombined = on
        prefs.sourcesCombined = on
    }
}
