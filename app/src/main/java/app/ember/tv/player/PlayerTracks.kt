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
/** Saves the audio language (always) and the subtitle choice (when picked by hand) for the show. */
internal fun PlayerActivity.rememberTracks() {
    val key = showKey() ?: return
    val p = player ?: return
    p.audioFormat?.language?.let { app.ember.tv.data.Subtitles.normalise(it) }
        ?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { memory.putAudio(key, it) }
    if (!subsTouched || !subsInitialized) return
    when (val sel = subs.selection) {
        is SubtitleController.Selection.Manual -> Unit
        is SubtitleController.Selection.Off -> memory.putSubtitles(key, "off")
        is SubtitleController.Selection.External -> memory.putSubtitles(key, sel.candidate.lang)
        is SubtitleController.Selection.Embedded -> p.currentTracks.groups.getOrNull(sel.group)
            ?.getTrackFormat(sel.track)?.language?.let { app.ember.tv.data.Subtitles.normalise(it) }
            ?.takeIf { it.isNotBlank() && it != "und" }?.let { memory.putSubtitles(key, it) }
    }
}

@OptIn(UnstableApi::class)
/** Sound tracks for the Sound list: language, format and channels, with the one playing marked. */
internal fun PlayerActivity.audioTracks(): List<Pair<String, Boolean>> {
    val p = player ?: return emptyList()
    return p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { g ->
        (0 until g.length).filter { g.isTrackSupported(it, true) }.map { i ->
            val f = g.getTrackFormat(i)
            val lang = f.language?.let { Locale.forLanguageTag(it).displayLanguage.takeIf { d -> d.isNotBlank() } ?: it }
            val label = listOfNotNull(lang, f.label, Playback.audioName(f), if (f.channelCount > 0) "${f.channelCount} ch" else null)
                .distinct().joinToString(" · ").ifBlank { getString(R.string.pl_track_n, i + 1) }
            label to g.isTrackSelected(i)
        }
    }
}

@OptIn(UnstableApi::class)
internal fun PlayerActivity.pickAudioTrack(index: Int) {
    val p = player ?: return
    var n = 0
    for (g in p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }) {
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i, true)) continue
            if (n == index) {
                audioPicked = true
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i))
                    .build()
                return
            }
            n++
        }
    }
}
