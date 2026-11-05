package app.ember.tv.state

import androidx.compose.runtime.mutableIntStateOf

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.Prefs
import app.ember.tv.player.PlayRequest
import app.ember.tv.player.PlayerApp
import app.ember.tv.player.PlayerApps

/** Which player plays a source, the "Play with..." chooser, and autoplay. Split out of HomeViewModel. */
class PlayingState(
    private val context: Context,
    private val prefs: Prefs,
) {
    /** Installed video players, refreshed with the app list. */
    var players by mutableStateOf<List<PlayerApp>>(emptyList()); private set
    var playerPref by mutableStateOf(prefs.player); private set
    var subtitlesOn by mutableStateOf(prefs.subtitlesOn); private set

    /** Set while the "Play with..." chooser is open. */
    var chooser by mutableStateOf<PlayRequest?>(null); private set

    fun refreshPlayers(requery: Boolean = true) {
        if (requery) {
            val found = try { PlayerApps.videoPlayers(context) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { app.ember.tv.data.CrashLog.note("Could not refresh installed video players", e); return }
            players = found
        }
        // Fall back to the built-in player if the chosen app was uninstalled.
        if (playerPref != Prefs.PLAYER_BUILTIN && playerPref != Prefs.PLAYER_ASK && players.none { it.pkg == playerPref }) {
            updatePlayerPref(Prefs.PLAYER_BUILTIN)
        }
    }

    fun updatePlayerPref(value: String) {
        playerPref = value
        prefs.player = value
    }

    fun updateSubtitlesOn(on: Boolean) {
        subtitlesOn = on
        prefs.subtitlesOn = on
    }

    var autoplay by mutableStateOf(prefs.autoplay); private set
    var countdown by mutableIntStateOf(prefs.countdown); private set

    fun updateAutoplay(on: Boolean) {
        autoplay = on
        prefs.autoplay = on
    }

    fun updateCountdown(seconds: Int) {
        countdown = seconds
        prefs.countdown = seconds
    }

    fun askPlayer(req: PlayRequest) { chooser = req }
    fun dismissChooser() { chooser = null }

    /** The app list was reloaded off the main thread, with the video players found. */
    fun setInstalled(list: List<PlayerApp>) {
        players = list
        refreshPlayers(requery = false)
    }
}
