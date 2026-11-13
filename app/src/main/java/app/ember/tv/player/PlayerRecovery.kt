package app.ember.tv.player

import androidx.core.view.isVisible
import androidx.compose.ui.graphics.toArgb
import app.ember.tv.iptv.*
import app.ember.tv.R
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.Tracks
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import app.ember.tv.data.Episode
import app.ember.tv.data.Prefs
import app.ember.tv.data.SubtitleOption
import app.ember.tv.mediaserver.MediaServers
import app.ember.tv.mediaserver.ReportState
import app.ember.tv.tracker.Kind
import app.ember.tv.tracker.ScrobbleState
import app.ember.tv.tracker.TrackedItem
import app.ember.tv.tracker.TrackerHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.Locale
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleDelayMilliseconds
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleSpeed

@OptIn(UnstableApi::class)
/** One retry owner for live streams: Media3 must not add hidden retries ahead of our budget. */
internal fun PlayerActivity.mediaSourceFactory(http: DefaultDataSource.Factory) = DefaultMediaSourceFactory(http,
    androidx.media3.extractor.DefaultExtractorsFactory().setTsExtractorFlags(androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
        .setTsExtractorTimestampSearchBytes(1500 * 188)).apply {
    if (live) setLoadErrorHandlingPolicy(object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo) = C.TIME_UNSET
        override fun getFallbackSelectionFor(
            options: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackOptions,
            info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo,
        ): androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackSelection? = null
    })
}

@OptIn(UnstableApi::class)
internal fun PlayerActivity.retryLive(e: PlaybackException): Boolean {
    val http = generateSequence<Throwable>(e) { it.cause }
        .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    val malformed = PlaybackErrors.malformedContainer(e.errorCode)
    val network = PlaybackErrors.liveNetwork(e.errorCode)
    if ((!network && !malformed) || !LiveRetryPolicy.retryable(http?.responseCode)) return false
    val url = currentUrl ?: return false
    val retryAfter = http?.headerFields?.entries?.firstOrNull { it.key.equals("Retry-After", true) }?.value?.firstOrNull()
    val attempt = liveBudget.next(android.os.SystemClock.elapsedRealtime()) ?: return false
    val pause = LiveRetryPolicy.delayMillis(attempt, LiveRetryPolicy.retryAfterSeconds(retryAfter))
    if (pause > 15_000) return false // Leave long provider cooldowns to a deliberate user retry.
    val retryUrl = if (malformed) PlaybackErrors.alternateLiveUrl(url) ?: return false else url
    val key = sessionChannel?.key
    val name = zapNames.getOrNull(zapIndex).orEmpty()
    lastRetryAt = android.os.SystemClock.elapsedRealtime()
    liveRetryJob?.cancel()
    liveRetryJob = lifecycleScope.launch {
        delay(pause)
        if (currentUrl != url || sessionChannel?.key != key ||
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@launch
        if (key != null && liveSession?.channels()?.none { it.key == key } != false) return@launch
        liveRetryJob = null
        start(retryUrl, name, emptyList(), null)
    }
    return true
}

@OptIn(UnstableApi::class)
internal fun PlayerActivity.describe(f: androidx.media3.common.Format?) = PlaybackErrors.describe(f)

@OptIn(UnstableApi::class)
/**
 * Decoding failed: try the HDR10 picture of a Dolby Vision file, another sound track, or a
 * software decoder (up to 1080p), resuming where it stopped. False when nothing is left to try.
 */
internal fun PlayerActivity.recover(e: PlaybackException): Boolean {
    if(playbackEngine?.compatibility!=null)return false
    val decoding = PlaybackErrors.decoding(e.errorCode)
    val p = player ?: return false
    val network = PlaybackErrors.connection(e.errorCode)
    if (network && ioRetries < 2) {
        ioRetries++
        lastRetryAt = android.os.SystemClock.elapsedRealtime()
        Toast.makeText(this, getString(R.string.pl_the_connection_dropped_trying_again), Toast.LENGTH_SHORT).show()
        val at = p.currentPosition
        handler.postDelayed({
            player?.let {
                it.seekTo(at)
                it.prepare()
                it.playWhenReady = true
            }
        }, 1_500L * ioRetries)
        return true
    }
    val outputFailure=PlaybackErrors.audioOutput(e.errorCode)
    if ((!decoding && !outputFailure) || recoveries >= 6) return false
    val format=(e as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat
    val audio=outputFailure || format?.sampleMimeType?.let(MimeTypes::isAudio)==true
    val message: String
    if(audio && decoderRecovery.pcmFallback()) {
        codecs.pcm=true
        val was=p.playWhenReady;val at=p.currentPosition
        p.stop();p.seekTo(at);p.playWhenReady=was
        message="Trying PCM audio output"
    } else {
        val height=format?.height?.takeIf { it>0 } ?: p.videoFormat?.height ?: 2160
        val hdr=(format ?: p.videoFormat)?.colorInfo?.colorTransfer.let { it==C.COLOR_TRANSFER_ST2084 || it==C.COLOR_TRANSFER_HLG }
        val next=decoderRecovery.next(audio,if(audio)config.ffmpeg!="off" else config.ffmpegVideo!="off",audio || height<=1080 && !hdr) ?: return false
        val mode=if(next=="ffmpeg")DecoderMode.FFMPEG else DecoderMode.SOFTWARE
        if(audio)decoderManager.selectAudioDecoder(mode) else { decoderManager.selectVideoDecoder(mode);codecs.ffmpegVideo=mode==DecoderMode.FFMPEG }
        message=if(audio)"Trying another audio decoder" else "Trying another video decoder"
    }
    recoveries++
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    val at = p.currentPosition
    p.seekTo(at)
    p.prepare()
    p.playWhenReady = true
    return true
}

@OptIn(UnstableApi::class)
/**
 * Switches video to Nextlib's FFmpeg decoder: opens its gate and turns the TV's video decoders
 * off in the track selector, which makes it choose the video track again.
 */
internal fun PlayerActivity.useFfmpegVideo(p: ExoPlayer) {
    codecs.ffmpegVideo=true
    decoderManager.selectVideoDecoder(DecoderMode.FFMPEG)
}
