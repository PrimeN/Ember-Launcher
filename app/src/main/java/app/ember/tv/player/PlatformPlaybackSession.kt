package app.ember.tv.player

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player

/** Android transport state for TV remotes, receiver controls and system media surfaces. */
class PlatformPlaybackSession(context: Context, private val player: Player, private val stop: () -> Unit, private val next: () -> Unit) : Player.Listener {
    private val session = MediaSession(context, "Ember")
    private var canNext = false
    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { player.play() }
            override fun onPause() { player.pause() }
            override fun onSeekTo(pos: Long) { player.seekTo(pos.coerceAtLeast(0)) }
            override fun onStop() { stop() }
            override fun onSkipToNext() { if (canNext) next() }
            override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) { /* Only the current authorized item is exposed. */ }
        }, Handler(Looper.getMainLooper()))
        session.isActive = true
        player.addListener(this); update()
    }
    fun nextAvailable(value: Boolean) { canNext = value; update() }
    override fun onEvents(player: Player, events: Player.Events) { update() }
    private fun update() {
        val state = when {
            player.playbackState == Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
            player.playbackState == Player.STATE_BUFFERING -> PlaybackState.STATE_BUFFERING
            player.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,player.mediaMetadata.title?.toString())
            .putLong(MediaMetadata.METADATA_KEY_DURATION,player.duration.coerceAtLeast(0)).build())
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO or (if (canNext) PlaybackState.ACTION_SKIP_TO_NEXT else 0L)
        session.setPlaybackState(PlaybackState.Builder().setActions(actions).setState(state,player.currentPosition,if(player.isPlaying) player.playbackParameters.speed else 0f).build())
    }
    fun release() { player.removeListener(this); session.isActive = false; session.release() }
}
