package app.ember.tv

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import app.ember.tv.player.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackRecoveryLifecycleTest {
    @Test fun delayedRetryCannotSeekOrResumeANewItem() {
        val activity=Robolectric.buildActivity(PlayerActivity::class.java).get()
        val player=ExoPlayer.Builder(activity).build();activity.player=player
        try {
            player.setMediaItem(MediaItem.fromUri("https://example.invalid/old.mp4"));player.seekTo(42000)
            assertTrue(activity.recover(PlaybackException("Connection lost",null,PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
            player.setMediaItem(MediaItem.fromUri("https://example.invalid/new.mp4"));player.seekTo(7000);player.playWhenReady=false
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1501))
            assertEquals(7000,player.currentPosition)
            assertFalse(player.playWhenReady)
        } finally {activity.player=null;player.release()}
    }
    @Test fun retryingTheSameItemPreservesAPauseMadeDuringTheDelay() {
        val activity=Robolectric.buildActivity(PlayerActivity::class.java).get()
        val player=ExoPlayer.Builder(activity).build();activity.player=player
        try {
            player.setMediaItem(MediaItem.fromUri("https://example.invalid/film.mp4"));player.seekTo(42000);player.playWhenReady=true
            assertTrue(activity.recover(PlaybackException("Connection lost",null,PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
            player.playWhenReady=false
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1501))
            assertEquals(42000,player.currentPosition);assertFalse(player.playWhenReady)
        } finally {activity.cancelRecovery();activity.player=null;player.release()}
    }

}
