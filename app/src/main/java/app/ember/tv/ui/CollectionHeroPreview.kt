package app.ember.tv.ui

import android.view.LayoutInflater
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.ember.tv.R
import app.ember.tv.data.CollectionMotion
import app.ember.tv.data.ExclusiveCollectionPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
internal fun CollectionHeroPreview(url: String?, enabled: Boolean, modifier: Modifier = Modifier) {
    CollectionMediaPreview(url, enabled, modifier, videoHint = true, alpha = .4f)
}

/** One muted preview decoder; errors retain the underlying cover instead of crashing Home. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun CollectionVideoPreview(url: String?, enabled: Boolean, modifier: Modifier = Modifier,
    alpha: Float = 1f, onFailure: () -> Unit = {}) {
    val context = LocalContext.current
    val low = LocalRenderOptions.current.lowPower
    val currentFailure by rememberUpdatedState(onFailure)
    val play = enabled && collectionMotionActive() && !LocalReduceMotion.current && CollectionMotion.remote(url)
    var ready by remember(url, play, low) { mutableStateOf(false) }
    val player by produceState<ExoPlayer?>(null, url, play, low) {
        value = null
        if (play) {
            delay(750)
            val instance = runCatching {
                val source = DefaultMediaSourceFactory(DefaultHttpDataSource.Factory().setConnectTimeoutMs(5000).setReadTimeoutMs(5000))
                ExoPlayer.Builder(context).setMediaSourceFactory(source)
                    .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1000, 5000, 500, 1000)
                        .setTargetBufferBytes(8 * 1024 * 1024).build()).build()
            }.getOrElse { currentFailure(); return@produceState }
            try {
                instance.volume = 0f
                instance.trackSelectionParameters = instance.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setForceLowestBitrate(true)
                    .setMaxVideoSize(if (low) 1280 else 1920, if (low) 720 else 1080)
                    .setMaxVideoBitrate(if (low) 1_500_000 else 4_000_000).build()
                instance.repeatMode = Player.REPEAT_MODE_ONE
                instance.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { ready = true }
                    override fun onPlayerError(error: PlaybackException) { ready = false; instance.stop(); currentFailure() }
                })
                CollectionPreviewOwner.claim(instance) // Release the old decoder before preparing this one.
                instance.setMediaItem(MediaItem.fromUri(url!!)); instance.prepare(); instance.playWhenReady = true
                value = instance
                awaitDispose { value = null; CollectionPreviewOwner.drop(instance) }
            } catch (e: CancellationException) { CollectionPreviewOwner.drop(instance); throw e }
            catch (_: Exception) { ready = false; value = null; CollectionPreviewOwner.drop(instance); currentFailure() }
        }
    }
    player?.let { instance -> AndroidView(modifier = modifier.alpha(if (ready) alpha else 0f),
        factory = { LayoutInflater.from(it).inflate(R.layout.collection_hero_player, android.widget.FrameLayout(it), false) as PlayerView },
        update = { it.player = instance }, onRelease = { it.player = null }) }
}

private val CollectionPreviewOwner = ExclusiveCollectionPreview<ExoPlayer> { runCatching { it.release() } }
