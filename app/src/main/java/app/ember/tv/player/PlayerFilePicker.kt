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
internal fun PlayerActivity.pickFile(which:String) {
    pickerWasPlaying=player?.playWhenReady==true;pickingSidecar=true;player?.pause()
    try {when(which){"sub"->subtitleFilePicker.launch(arrayOf("*/*"));"audio"->audioFilePicker.launch(arrayOf("audio/*","application/octet-stream"));else->queueFilePicker.launch(arrayOf("video/*","audio/*","application/octet-stream"))}}
    catch(_:android.content.ActivityNotFoundException){pickingSidecar=false;player?.playWhenReady=pickerWasPlaying;Toast.makeText(this,"No file picker is installed on this TV.",Toast.LENGTH_LONG).show()}
}

@OptIn(UnstableApi::class)
// ---- subtitles ----
internal fun PlayerActivity.addonSubtitlesFromIntent(): List<SubtitleOption> {
    val urls = intent.getStringArrayExtra(PlayRequest.EXTRA_SUB_URLS) ?: return emptyList()
    val langs = intent.getStringArrayExtra(PlayRequest.EXTRA_SUB_LANGS) ?: emptyArray()
    return urls.mapIndexed { i, u -> SubtitleOption("", langs.getOrNull(i) ?: "", u) }
}
