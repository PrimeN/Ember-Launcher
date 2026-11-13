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
/** Loads a source into the player and starts it. Used for the first episode and every next one. */
internal fun PlayerActivity.start(url: String, title: String, addonSubs: List<SubtitleOption>, release: String?) {
    val p = player ?: return
    hdr10PlusMetadata = false
    liveRetryJob?.cancel(); liveRetryJob = null
    handler.removeCallbacks(commitNumber); numberDigits = ""; numberToast?.cancel()
    repeatRange.clear()
    playbackEngine?.configure(EngineSource(
        urls=serverPlayback?.parts?.map {it.url} ?: listOf(url),title=title,key=resumeKey,
        durations=serverPlayback?.parts?.map {it.durationMs} ?: listOf(null),durationMs=serverPlayback?.durationMs,
        audio=audioUrl,headers=sourceHeaders,live=live,
        audioLanguages=listOfNotNull(showKey()?.let(memory::get)?.audio,java.util.Locale.getDefault().language).distinct(),
        subtitleLanguages=app.ember.tv.data.Subtitles.wanted(prefs.subtitleConfig)))
    serverPartIndex = 0
    transcodeStopQueued = false
    currentUrl = url
    triedAsHls = false
    showLiveBanner()
    if (subsInitialized && !trailer) {
        subs.startSearch(
            url,
            app.ember.tv.data.Subtitles.Query(
                imdb = item.imdbId,
                movie = item.kind == Kind.MOVIE,
                season = item.season,
                episode = item.episode,
                release = release ?: title,
                hash = sourceHash?.first,
                size = sourceHash?.second,
            ),
            addonSubs,
        )
    }
    decoderRecovery.reset();codecs.pcm=false
    decoderManager.selectVideoDecoder(userVideoMode ?: if(config.ffmpegVideo=="always")DecoderMode.FFMPEG else DecoderMode.AUTO)
    decoderManager.selectAudioDecoder(userAudioMode ?: DecoderMode.AUTO)
    codecs.ffmpegVideo=decoderManager.videoMode==DecoderMode.FFMPEG
    codecs.preferSoftware=false
    audioPicked = false
    modeApplied = false
    dropped = 0
    loadSpeed = 0
    recoveries = 0
    ioRetries = 0
    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
    val media = MediaItem.Builder()
        .setUri(url)
        // Live links often have no extension; tell the player when it is HLS or DASH.
        .apply { PlaybackErrors.mimeHint(url)?.let(::setMimeType) }
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .build()
    val audio = audioUrl
    if (audio != null) {
        // Video and audio come as two streams; play them together.
        val factory = mediaSourceFactory(DefaultDataSource.Factory(this, httpFactory))
        val audioItem = MediaItem.fromUri(audio)
        p.setMediaSource(MergingMediaSource(factory.createMediaSource(media), factory.createMediaSource(audioItem)))
    } else if (serverPlayback?.parts?.size?.let { it > 1 } == true) {
        val factory = mediaSourceFactory(DefaultDataSource.Factory(this, httpFactory))
        val concat = androidx.media3.exoplayer.source.ConcatenatingMediaSource2.Builder().setMediaSourceFactory(factory)
            .setMediaItem(MediaItem.Builder().setMediaId(resumeKey).setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build())
        serverPlayback!!.parts.forEachIndexed { index, part ->
            val partItem = MediaItem.Builder().setUri(part.url).setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
            concat.add(partItem, serverPlayback!!.placeholderDuration(index))
        }
        p.setMediaSource(concat.build())
    } else {
        p.setMediaItem(media)
    }
    p.prepare()
    p.playWhenReady = true
    loadMarkers()
}

@OptIn(UnstableApi::class)
/**
 * "Stop watching?" with Keep watching focused. The video keeps playing behind it; Back again
 * (or Stop) leaves, so a double press still exits quickly.
 */
internal fun PlayerActivity.askToLeave() {
    if (leaveDialog?.isShowing == true) return
    val p = player ?: return
    val name = controls.title.ifBlank { if (itemInitialized) item.title else "" }
    val where = playbackDuration(p).takeIf { it > 0 }?.let { "${time(p.currentPosition)} of ${time(it)}" }
    leaveDialog = android.app.AlertDialog.Builder(this)
        .setTitle("Stop watching?")
        .setMessage(listOfNotNull(name.takeIf { it.isNotBlank() }, where).joinToString(" · ").ifBlank { null })
        .setPositiveButton("Keep watching", null)
        .setNegativeButton("Stop") { _, _ -> endPlayback() }
        .setOnKeyListener { d, code, e ->
            if (code != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            if (e.action == KeyEvent.ACTION_UP) { d.dismiss(); endPlayback() }
            true
        }
        .create().also { d ->
            d.setOnShowListener { runCatching { d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).requestFocus() } }
            d.setOnDismissListener { if (leaveDialog === d) leaveDialog = null }
            d.show()
        }
}

@OptIn(UnstableApi::class)
/** Saves where you are, reports the stop, releases the player and (normally) closes the screen. */
internal fun PlayerActivity.endPlayback(finishActivity: Boolean = true) {
    handler.removeCallbacks(saver)
    handler.removeCallbacks(tick)
    handler.removeCallbacks(infoUpdater)
    handler.removeCallbacks(hideInfo)
    handler.removeCallbacks(subtitleTicker)
    handler.removeCallbacks(markerTicker)
    handler.removeCallbacks(controlsTicker)
    handler.removeCallbacks(hideControls)
    handler.removeCallbacks(statsTicker)
    loudness?.let { runCatching { it.release() } }
    loudness = null
    val p = player ?: return
    rememberTracks()
    if (!ended) {
        savePosition()
        // Trakt and Simkl store a stop under 80% as "in progress", which feeds Continue watching.
        // Skip it when nothing really played, e.g. the source failed to open.
        val done = progress()
        if (done > 0.01f) {
            scrobble(ScrobbleState.STOP, done)
        }
        // Close the server session even before the local history threshold.
        serverReport(ReportState.STOP)
    }
    nextServerJob?.cancel()
    platformSession?.release(); platformSession = null
    decoderManager.detach()
    p.release()
    player = null
    if (finishActivity) finish()
}

@OptIn(UnstableApi::class)
// ---- resume and progress ----
internal fun PlayerActivity.resume(p: ExoPlayer): Boolean {
    if (trailer || startOver) return true
    if (playbackEngine?.compatibility==null && serverPlayback?.takeIf { it.parts.size > 1 }?.parts?.any { (it.durationMs ?: 0L) <= 0L } == true) return false
    val saved = positions.get(resumeKey)
    val duration = playbackDuration(p).takeIf {it>0}
    if(duration==null) {
        if(playbackEngine?.compatibility!=null && saved!=null && saved.first>30_000 && saved.first<saved.second*0.95){p.seekTo(saved.first);return true}
        return false
    }
    when {
        saved != null && saved.first > 30_000 && saved.first < duration * 0.95 -> p.seekTo(saved.first)
        resumeFraction in 0.02f..0.95f -> p.seekTo((duration * resumeFraction).toLong())
    }
    return true
}

@OptIn(UnstableApi::class)
internal fun PlayerActivity.playbackDuration(p: ExoPlayer): Long {
    val transport = serverPlayback
    if(playbackEngine?.compatibility!=null)return p.duration.takeIf {it>0}?:transport?.durationMs?:0L
    if (transport != null && transport.parts.size > 1 && transport.parts.any { (it.durationMs ?: 0L) <= 0L }) return transport.durationMs ?: 0L
    return p.duration.takeIf { it > 0 } ?: transport?.durationMs ?: 0L
}

@OptIn(UnstableApi::class)
internal fun PlayerActivity.progress(): Float {
    val p = player ?: return 0f
    val d = playbackDuration(p)
    return if (d > 0) (p.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
}

@OptIn(UnstableApi::class)
internal fun PlayerActivity.savePosition() {
    if (trailer) return
    val p = player ?: return
    if (ended) return
    val d = playbackDuration(p)
    if (d <= 0) return
    val pos = p.currentPosition
    if (pos >= d * 0.95) positions.clear(resumeKey) else positions.put(resumeKey, pos, d)
    // In progress / Recently watched on For You, kept on the TV.
    app.ember.tv.data.LocalLibrary.progress(resumeKey, pos.toFloat() / d, d,localActivityScope)
    if(pos.toFloat()/d>=.92f)recordWatched()
}

@OptIn(UnstableApi::class)
internal fun PlayerActivity.contentPlaybackAllowed():Boolean {
    if(watchProfileId!=prefs.currentProfile)return false
    val active=prefs.profiles.firstOrNull {it.id==watchProfileId} ?: return false
    val content=app.ember.tv.data.Meta(type=intent.getStringExtra(PlayRequest.EXTRA_CONTENT_TYPE) ?: if(item.kind==Kind.MOVIE)"movie" else "series",
        genres=intent.getStringArrayExtra(PlayRequest.EXTRA_GENRES)?.toList(),ageRating=intent.getStringExtra(PlayRequest.EXTRA_AGE_RATING),
        adult=intent.getBooleanExtra(PlayRequest.EXTRA_ADULT,false))
    return app.ember.tv.data.KidsContentPolicy.playbackAllowed(active,watchProfileId,content,live)
}

@OptIn(UnstableApi::class)
internal fun PlayerActivity.recordWatched() {
    if(live || trailer || !recordedWatched.add(resumeKey))return
    val meta=app.ember.tv.data.LocalLibrary.metaFor(resumeKey,localActivityScope) ?: app.ember.tv.data.Meta(id=item.imdbId ?: resumeKey,
        type=if(item.kind==Kind.MOVIE)"movie" else "series",name=seriesName,imdbId=item.imdbId,tmdbId=item.tmdbId,
        season=item.season,episode=item.episode,serverRef=serverRef,serverSeriesRef=serverPlayback?.seriesRef)
    app.ember.tv.data.WatchedStore.record(applicationContext,watchProfileId,meta)
}
