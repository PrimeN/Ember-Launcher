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

/**
 * Full-screen player built on Media3 ExoPlayer.
 * Reports progress to trackers, remembers where you stopped, and can play the next episode.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    internal var player: ExoPlayer? = null
    internal var playbackEngine: PlaybackEngine? = null
    internal lateinit var view: PlayerView
    internal lateinit var hub: TrackerHub
    internal lateinit var positions: PositionStore
    internal lateinit var prefs: Prefs
    internal lateinit var item: TrackedItem

    internal var resumeKey = ""
    internal var resumeFraction = -1f
    internal var resumed = false
    internal var ended = false
    /** True after START was sent and before PAUSE or STOP. */
    internal var reportedPlaying = false

    // Auto-play state
    internal var seriesName = ""
    internal var prevAddon: String? = null
    internal var prevQuality: String? = null
    /** The playing source's binge group and file hash, from its addon (#74). */
    internal var prevBingeGroup: String? = null
    internal var sourceHash: Pair<String, Long?>? = null
    internal val nextEpisodes = ArrayList<Episode>()

    // Media server titles report progress back to Plex, Jellyfin or Emby.
    internal var serverRef: String? = null
    internal var serverPlayback: app.ember.tv.mediaserver.ServerPlayback? = null
    internal var serverLifecycle = app.ember.tv.mediaserver.ServerReportLifecycle()
    internal var serverReportTail: Job? = null
    internal var nextServerJob: Job? = null
    internal val metadataLoads by lazy { PlaybackMetadataLoads(lifecycleScope) }
    @Volatile internal var hdr10PlusMetadata = false
    internal var transcodeStopQueued = false
    internal var serverRecoveryShowing = false
    internal var serverPartIndex = 0
    internal var platformSession: PlatformPlaybackSession? = null
    internal var servers: MediaServers? = null

    // "Up next" card
    /** #225: the Up next card, drawn in Compose over the video. */
    internal lateinit var upNext: android.view.View
    internal val upNextUi = UpNextUi()
    /** Back hid the card during the credits (#227); it stays hidden until playback leaves them. */
    internal var upNextDismissed = false
    internal var secondsLeft = 0

    // Debrid and addon links often redirect, sometimes from http to https. High-bitrate
    // sources can be slow to start, so the timeouts are generous.
    internal val httpFactory by lazy {
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setUserAgent("EmberTV/0.9")
    }

    /** Trailers play without saving positions or reporting to trackers and servers. */
    internal var queueId:String?=null
    internal var fileQueue:MediaQueue?=null
    internal val repeatRange=RepeatRange()
    internal var pickingSidecar=false
    internal var pickerWasPlaying=false
    internal val subtitleFilePicker=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) {uris ->
        pickingSidecar=false
        // A result can arrive after the activity was recreated, before playback set up subtitles.
        if(uris.isNotEmpty()&&::subs.isInitialized)lifecycleScope.launch {
            try {
                val bitmap=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){MediaFiles.bitmap(this@PlayerActivity,uris)}
                if(bitmap!=null) {
                    val native=playbackEngine?.compatibility ?: if(playbackEngine?.switch()==true)playbackEngine?.compatibility else null
                    if(native==null){(listOf(bitmap.first)+bitmap.second).forEach {it.delete()};error("Compatibility unavailable")}
                    native.externalFile(bitmap.first,bitmap.second);subs.selectManualNative()
                } else {
                    val (text,format)=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){MediaFiles.subtitle(this@PlayerActivity,uris.first())}
                    if(!subs.loadManual(text,format))error("Unreadable subtitle")
                }
            } catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(_:Exception){Toast.makeText(this@PlayerActivity,"Could not read subtitles. For VobSub, select both IDX and SUB files.",Toast.LENGTH_LONG).show()}
            finally {player?.playWhenReady=pickerWasPlaying;changed()}
        } else player?.playWhenReady=pickerWasPlaying
    }
    internal val audioFilePicker=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) {uri ->
        pickingSidecar=false
        if(uri!=null){
            val native=playbackEngine?.compatibility ?: if(playbackEngine?.switch()==true)playbackEngine?.compatibility else null
            if(native!=null)native.additionalAudio(uri.toString()) else Toast.makeText(this,"Could not start compatibility playback.",Toast.LENGTH_LONG).show()
        }
        player?.playWhenReady=pickerWasPlaying;changed()
    }
    internal val queueFilePicker=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) {uris ->
        pickingSidecar=false
        fileQueue?.add(uris.take(200).map {MediaFiles.retain(this,it);QueueMedia(it.toString(),MediaFiles.name(this,it))})
        player?.playWhenReady=pickerWasPlaying;changed()
    }
    internal fun pickFile(which:String) {
        pickerWasPlaying=player?.playWhenReady==true;pickingSidecar=true;player?.pause()
        try {when(which){"sub"->subtitleFilePicker.launch(arrayOf("*/*"));"audio"->audioFilePicker.launch(arrayOf("audio/*","application/octet-stream"));else->queueFilePicker.launch(arrayOf("video/*","audio/*","application/octet-stream"))}}
        catch(_:android.content.ActivityNotFoundException){pickingSidecar=false;player?.playWhenReady=pickerWasPlaying;Toast.makeText(this,"No file picker is installed on this TV.",Toast.LENGTH_LONG).show()}
    }
    internal var trailer = false
    /** A separate audio stream to play with the video (YouTube serves high quality as two streams). */
    internal var audioUrl: String? = null

    // ---- Live TV (IPTV) ----
    internal var live = false
    internal var zapUrls: Array<String> = emptyArray()
    internal var zapNames: Array<String> = emptyArray()
    internal var zapAgents: Array<String> = emptyArray()
    internal var zapReferrers: Array<String> = emptyArray()
    internal var zapIndex = 0
    internal var liveSessionId: String? = null
    internal var liveSession: LiveSession? = null
    internal var sessionChannel: Channel? = null
    internal val liveBanner = androidx.compose.runtime.mutableStateOf<LiveBanner?>(null)
    internal val hideLiveBanner = Runnable { liveBanner.value = null }

    /** Some providers only answer a particular player name or referrer. */
    internal var sourceHeaders:Map<String,String> = mapOf("User-Agent" to "EmberTV/0.9")

    internal var numberDigits = ""
    internal var numberToast: Toast? = null
    internal val commitNumber = Runnable {
        numberToast?.cancel()
        val entered = numberDigits; numberDigits = ""
        val matches = LiveRemote.matches(liveSession?.channels().orEmpty(), entered)
        when (matches.size) {
            0 -> Toast.makeText(this, "No channel $entered in this list", Toast.LENGTH_SHORT).show()
            1 -> tuneChannel(matches.single().key)
            else -> android.app.AlertDialog.Builder(this).setTitle("Choose channel $entered")
                .setItems(matches.map { it.name + " · " + liveSession?.sourceLabel(it).orEmpty() }.toTypedArray()) { _, index -> tuneChannel(matches[index].key) }
                .setNegativeButton("Cancel", null).show()
        }
    }

    companion object {
        internal val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        /** The player on screen (or in picture-in-picture); a new one closes it, so only one plays. */
        internal var current: java.lang.ref.WeakReference<PlayerActivity>? = null
        const val EXTRA_LIVE_SESSION = "ember.liveSession"
        const val EXTRA_LIVE = "ember.live"
        const val EXTRA_ZAP_URLS = "ember.zapUrls"
        const val EXTRA_ZAP_NAMES = "ember.zapNames"
        const val EXTRA_ZAP_AGENTS = "ember.zapAgents"
        const val EXTRA_ZAP_REFERRERS = "ember.zapReferrers"
        const val EXTRA_ZAP_INDEX = "ember.zapIndex"

        /** Plays a live channel; channel up and down move through [urls]. */
        fun liveIntent(
            context: android.content.Context,
            urls: List<String>,
            names: List<String>,
            agents: List<String>,
            referrers: List<String>,
            index: Int,
        ) = android.content.Intent(context, PlayerActivity::class.java)
            .putExtra(PlayRequest.EXTRA_URL, urls[index])
            .putExtra(PlayRequest.EXTRA_TITLE, names[index])
            .putExtra(EXTRA_LIVE, true)
            .putExtra(EXTRA_ZAP_URLS, urls.toTypedArray())
            .putExtra(EXTRA_ZAP_NAMES, names.toTypedArray())
            .putExtra(EXTRA_ZAP_AGENTS, agents.toTypedArray())
            .putExtra(EXTRA_ZAP_REFERRERS, referrers.toTypedArray())
            .putExtra(EXTRA_ZAP_INDEX, index)
        const val EXTRA_TRAILER = "ember.trailer"
        const val EXTRA_AUDIO_URL = "ember.audioUrl"

        /** Plays a trailer: [videoUrl] with an optional separate [audioUrl]. */
        fun trailerIntent(context: android.content.Context, videoUrl: String, audioUrl: String?, title: String) =
            android.content.Intent(context, PlayerActivity::class.java)
                .putExtra(PlayRequest.EXTRA_URL, videoUrl)
                .putExtra(PlayRequest.EXTRA_TITLE, title)
                .putExtra(EXTRA_TRAILER, true)
                .putExtra(EXTRA_AUDIO_URL, audioUrl)
    }

    // Playback engine (Settings > Player)
    internal lateinit var config: PlaybackConfig
    internal var audioPicked = false
    internal var modeApplied = false
    internal var videoDecoder: String? = null
    internal var audioDecoder: String? = null
    internal lateinit var info: TextView
    /** Info and subtitle panel (Info, Menu or Guide). */
    internal lateinit var panel: android.widget.ScrollView
    internal lateinit var subStatus: TextView
    internal lateinit var subList: LinearLayout
    internal lateinit var timingLabel: TextView
    internal lateinit var subs: SubtitleController
    internal val subtitleTicker = object : Runnable {
        override fun run() {
            val p = player
            if(p!=null && p.isPlaying)repeatRange.destination(p.currentPosition)?.let(p::seekTo)
            if (p != null && ::subs.isInitialized) {val prefix=if(subs.wholeFileTiming)0L else (playbackEngine?.compatibility?.partOffset ?: serverPlayback?.offset(serverPartIndex))?:0L;subs.engine.render((p.currentPosition-prefix).coerceAtLeast(0))}
            handler.postDelayed(this, 100)
        }
    }

    internal val handler = Handler(Looper.getMainLooper())

    // ---- resume, per-show tracks, picture-in-picture ----
    internal var startOver = false
    internal lateinit var memory: TrackMemory
    /** The user picked a subtitle in the panel, so it is remembered for the show. */
    internal var subsTouched = false
    internal var baseSubCfg: app.ember.tv.data.SubtitleConfig? = null

    /** Show or local title identity for remembered audio/subtitle choices. */
    internal fun showKey(): String? {
        if(fileQueue!=null&&config.rememberTracks)return resumeKey
        return if (trailer || !config.rememberTracks || !::item.isInitialized || item.kind == Kind.MOVIE) null
        else (item.imdbId ?: seriesName).takeIf { it.isNotBlank() }
    }

    /** The subtitle settings with this show's remembered choice applied. */
    internal fun subConfigFor(base: app.ember.tv.data.SubtitleConfig): app.ember.tv.data.SubtitleConfig {
        val mem = showKey()?.let { memory.get(it) }?.subtitles ?: return base
        return if (mem == "off") base.copy(autoSelect = "off")
        else base.copy(autoSelect = "always", languages = listOf(mem) + base.languages.filter { it != mem })
    }

    /** Saves the audio language (always) and the subtitle choice (when picked by hand) for the show. */
    internal fun rememberTracks() {
        val key = showKey() ?: return
        val p = player ?: return
        p.audioFormat?.language?.let { app.ember.tv.data.Subtitles.normalise(it) }
            ?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { memory.putAudio(key, it) }
        if (!subsTouched || !::subs.isInitialized) return
        when (val sel = subs.selection) {
            is SubtitleController.Selection.Manual -> Unit
            is SubtitleController.Selection.Off -> memory.putSubtitles(key, "off")
            is SubtitleController.Selection.External -> memory.putSubtitles(key, sel.candidate.lang)
            is SubtitleController.Selection.Embedded -> p.currentTracks.groups.getOrNull(sel.group)
                ?.getTrackFormat(sel.track)?.language?.let { app.ember.tv.data.Subtitles.normalise(it) }
                ?.takeIf { it.isNotBlank() && it != "und" }?.let { memory.putSubtitles(key, it) }
        }
    }

    /** Picture-in-picture when Home is pressed during playback (Settings > Player). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if(pickingSidecar)return
        // Home (or another app) chosen by the user: the player closes when it stops (unless picture-in-picture).
        userLeft = true
        val p = player ?: return
        if (!config.pip || !p.isPlaying || !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val size = p.videoSize
        val ratio = if (size.width > 0 && size.height > 0) android.util.Rational(size.width, size.height) else android.util.Rational(16, 9)
        val safe = if (ratio.toFloat() in 0.42f..2.38f) ratio else android.util.Rational(16, 9)
        runCatching { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(safe).build()) }.onSuccess { userLeft = false }
    }

    // ---- paused when the screensaver starts: stay open, so the video is still there afterwards ----
    internal var userLeft = false
    internal var keptAlive = false
    /** A paused player kept in the background closes after half an hour (the TV may be off by then). */
    internal val closeKept = Runnable { endPlayback() }

    override fun onStart() {
        super.onStart()
        if(::prefs.isInitialized && !contentPlaybackAllowed()){endPlayback();finish();return}
        userLeft = false
        if (!keptAlive) return
        keptAlive = false
        handler.removeCallbacks(closeKept)
        handler.post(subtitleTicker)
        handler.post(markerTicker)
        handler.postDelayed(saver, 5_000)
        if (advanced) {
            handler.post(controlsTicker)
            handler.post(statsTicker)
            showControls()
        }
    }

    override fun onDestroy() {
        // A dialog still showing would leak its window when the player closes (#77).
        leaveDialog?.let { runCatching { it.dismiss() } }; leaveDialog = null
        getSharedPreferences("ember",MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(contentProfileListener)
        handler.removeCallbacks(closeKept)
        handler.removeCallbacks(hideLiveBanner)
        // Closed by the system while kept in the background.
        if (player != null) endPlayback(finishActivity = false)
        if (!isChangingConfigurations) {LiveSessions.release(liveSessionId);MediaQueues.release(queueId)}
        super.onDestroy()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!::view.isInitialized) return
        view.useController = !isInPictureInPictureMode && !advanced
        if (isInPictureInPictureMode) {
            controls.visible = false
            closeMenu()
            view.hideController()
            if (::panel.isInitialized) hidePanel()
            showSkip(null)
        }
    }

    // ---- advanced controls (Settings > Player > Player look) ----
    internal val controls = ControlsState()
    internal var advanced = false
    internal var playerInterface = PlayerInterfaces.MODERN
    internal val playerLayoutStyle: String
        get() = if (live || trailer) prefs.uiStyle else PlayerInterfaces.layoutStyle(playerInterface)
    internal val hideControls = Runnable {
        // Paused, or with a list open, the controls stay.
        if (player?.isPlaying == true && controls.sheet == null) controls.visible = false else scheduleHide()
    }
    internal val controlsTicker = object : Runnable {
        override fun run() {
            updateControls()
            handler.postDelayed(this, 500)
        }
    }

    internal fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, 5_000)
    }

    internal fun showControls() {
        if (!advanced || controls.menu != null) return
        updateControls()
        if (!controls.visible) {
            // Sound and Subtitles lists (every design) show the current choices without opening a menu.
            controls.audioTracks = audioTracks(); updateSubtitleSheet()
            controls.visible = true
            controls.shownAt = android.os.SystemClock.elapsedRealtime()
        }
        scheduleHide()
    }

    internal fun updateControls() {
        val p = player ?: return
        controls.playing = p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING)
        controls.buffering = p.playbackState == Player.STATE_BUFFERING
        controls.playbackSpeed = p.playbackParameters.speed
        controls.position = p.currentPosition
        controls.duration = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        controls.buffered = p.bufferedPosition
    }

    internal fun refreshControlsTitle() {
        controls.title = item.title
        controls.seasonEpisode = if (item.season != null && item.episode != null) "S${item.season}:E${item.episode}" else null
        controls.nextEpisodes = nextEpisodes.toList()
        controls.fileQueueLabel=fileQueue?.let {"${it.index+1} / ${it.items.size}"}
        controls.fileQueueNext=fileQueue?.let {it.index<it.items.lastIndex||it.repeat=="all"}==true
    }

    /** Sound tracks for the Sound list: language, format and channels, with the one playing marked. */
    internal fun audioTracks(): List<Pair<String, Boolean>> {
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

    internal fun pickAudioTrack(index: Int) {
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

    internal val controlActions by lazy {
        ControlsActions(
            playPause = { player?.let { it.playWhenReady = !it.playWhenReady }; showControls() },
            seekBy = { d -> player?.let { p -> p.seekTo((p.currentPosition + d).coerceIn(0L, (p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))) }; showControls() },
            skip = { doSkip() },
            openMenu = { which, tab -> openMenu(which, tab) },
            options = { which -> optionRows(which) },
            next = {
                if(fileQueue==null)Toast.makeText(this, getString(R.string.pl_finding_a_source_for_the_next), Toast.LENGTH_SHORT).show()
                playNext()
            },
            playEpisode = { i ->
                repeat(i.coerceAtMost(nextEpisodes.size - 1)) { nextEpisodes.removeAt(0) }
                Toast.makeText(this, getString(R.string.pl_finding_a_source), Toast.LENGTH_SHORT).show()
                playNext()
            },
            touched = { scheduleHide() },
            queue = {showFileQueue()},
            close = {finish()},
            pickAudio = { i ->
                pickAudioTrack(i)
                controls.audioTracks = controls.audioTracks.mapIndexed { index, (label, _) -> label to (index == i) }
                handler.postDelayed({ controls.audioTracks = audioTracks(); changed() }, 400)
                showControls()
            },
            pickSubtitle = { i ->
                subtitleActions.getOrNull(i)?.let { subsTouched = true; it() }
                updateSubtitleSheet()
                showControls()
            },
        )
    }

    // ---- player menus (advanced look): the small menu, the Options page and playback stats ----
    internal var speed = 1f
    internal var boostLevel = 0
    internal var loudness: android.media.audiofx.LoudnessEnhancer? = null
    internal var aspect = 0
    internal var chapters: List<app.ember.tv.mediaserver.Chapter> = emptyList()
    /** Network estimate and speed of the latest loads, in bits per second. */
    internal var bandwidth = 0L
    internal var loadSpeed = 0L
    internal var dropped = 0

    internal val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    /** Volume boost steps in millibels (+3, +6, +9, +12 dB). */
    internal val boosts = listOf(0, 300, 600, 900, 1200)
    internal val aspects by lazy { listOf(getString(R.string.pl_auto) to 0f, "16:9" to 16f / 9, "4:3" to 4f / 3, "1.85:1" to 1.85f, "2.00:1" to 2f, "2.39:1" to 2.39f) }
    internal val zooms by lazy { listOf("fit" to getString(R.string.pl_fit), "crop" to getString(R.string.pl_fill_crop), "stretch" to getString(R.string.pl_stretch)) }
    internal val huds by lazy { listOf("off" to getString(R.string.ui_off), "simple" to getString(R.string.pl_simple), "full" to getString(R.string.pl_full)) }
    internal val subSizes by lazy { listOf("small" to getString(R.string.pl_small), "normal" to getString(R.string.pl_normal), "large" to getString(R.string.pl_large), "xlarge" to getString(R.string.pl_extra_large)) }
    internal val subPositions by lazy { listOf("low" to getString(R.string.pl_low), "normal" to getString(R.string.pl_normal), "high" to getString(R.string.pl_high)) }
    internal val subLooks by lazy { listOf("outline" to getString(R.string.pl_outline), "shadow" to getString(R.string.pl_shadow), "box" to getString(R.string.pl_box)) }
    internal val delays = (-10..10).map { it * 50 }
    internal val subShifts = (-20..20).map { it * 250L }

    /** The address being played, for reading MKV chapters. */
    internal var currentUrl: String? = null
    /** The current link was retried as HLS after ExoPlayer could not read it as a file. */
    internal var triedAsHls = false

    internal val statsTicker = object : Runnable {
        override fun run() {
            updateStats()
            if (config.hud != "off") handler.postDelayed(this, 1000)
        }
    }

    /** Keys while the advanced controls are in use; null lets the key through. */
    @android.annotation.SuppressLint("RestrictedApi") // super.dispatchKeyEvent, as in dispatchKeyEvent below
    internal fun advancedKey(e: KeyEvent): Boolean? {
        val p = player ?: return null
        val down = e.action == KeyEvent.ACTION_DOWN
        val up = e.action == KeyEvent.ACTION_UP
        when (e.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down && e.repeatCount == 0) {
                    p.playWhenReady = when (e.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> true
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> false
                        else -> !p.playWhenReady
                    }
                }
                if (up) showControls()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (down) controlActions.seekBy(if (e.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) 30_000 else -10_000)
                return true
            }
        }
        // A menu is open: Back closes its choices, then the menu; other keys go to the menu.
        if (controls.menu != null) {
            if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                if (up) {
                    if (controls.menuRow != null) controls.menuRow = null else closeMenu()
                }
                return true
            }
            return super.dispatchKeyEvent(e)
        }
        if (!controls.visible) {
            return when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // Hidden controls: Left and Right jump back 10 s or ahead 10 s and show where you are.
                    if (down) controlActions.seekBy(if (e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000 else -10_000)
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // OK while "Skip intro" shows skips; anything else brings up the controls.
                    val ok = e.keyCode != KeyEvent.KEYCODE_DPAD_UP && e.keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                    if (up && ok && skipTarget != null) doSkip() else if (up) showControls()
                    true
                }
                else -> null
            }
        }
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (up) {
                if (controls.sheet != null) controls.sheet = null else controls.visible = false
            }
            return true
        }
        scheduleHide()
        return super.dispatchKeyEvent(e)
    }

    /** One retry owner for live streams: Media3 must not add hidden retries ahead of our budget. */
    internal fun mediaSourceFactory(http: DefaultDataSource.Factory) = DefaultMediaSourceFactory(http,
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

    // ---- Live TV: bounded retries for transient provider failures ----
    internal val liveBudget = LiveRetryBudget()
    internal val liveRetries get() = liveBudget.attempts
    internal var liveRetryJob: kotlinx.coroutines.Job? = null
    internal var lastRetryAt = 0L

    internal fun retryLive(e: PlaybackException): Boolean {
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

    // ---- decoders: retry with another one when decoding fails ----
    internal val codecs = Playback.CodecChoice()
    internal val decoderManager = DecoderManager()
    internal val decoderRecovery = DecoderRecovery()
    internal var userVideoMode: DecoderMode? = null
    internal var userAudioMode: DecoderMode? = null
    internal var recoveries = 0
    /** Network hiccups (a dropped connection, a link that stalls) are retried a couple of times. */
    internal var ioRetries = 0

    internal fun describe(f: androidx.media3.common.Format?) = PlaybackErrors.describe(f)

    /**
     * Decoding failed: try the HDR10 picture of a Dolby Vision file, another sound track, or a
     * software decoder (up to 1080p), resuming where it stopped. False when nothing is left to try.
     */
    internal fun recover(e: PlaybackException): Boolean {
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

    /**
     * Switches video to Nextlib's FFmpeg decoder: opens its gate and turns the TV's video decoders
     * off in the track selector, which makes it choose the video track again.
     */
    internal fun useFfmpegVideo(p: ExoPlayer) {
        codecs.ffmpegVideo=true
        decoderManager.selectVideoDecoder(DecoderMode.FFMPEG)
    }

    // ---- sound timing and skip intro ----
    internal val audioDelay = Playback.AudioDelay()
    internal lateinit var soundLabel: TextView
    internal lateinit var skipButton: TextView
    internal var markers: List<app.ember.tv.mediaserver.Marker> = emptyList()
    internal var markersFromIntroDb = false
    internal val introDbClient = IntroDbClient()
    /** Markers already skipped automatically, so seeking back into one does not skip it again. */
    internal val autoSkipped = HashSet<app.ember.tv.mediaserver.Marker>()
    internal var skipTarget: app.ember.tv.mediaserver.Marker? = null
    internal val markerTicker = object : Runnable {
        override fun run() {
            checkMarkers()
            handler.postDelayed(this, 500)
        }
    }
    internal val infoUpdater = object : Runnable {
        override fun run() {
            if (!::panel.isInitialized || panel.visibility != View.VISIBLE) return
            info.text = infoText()
            handler.postDelayed(this, 1000)
        }
    }
    internal val hideInfo = Runnable { hidePanel() }
    internal val sleepAtStart = app.ember.tv.data.SleepTimer.fired
    internal val saver = object : Runnable {
        override fun run() {
            // Quick settings > Sleep timer ran out: stop (position and progress are saved on the way out).
            if (app.ember.tv.data.SleepTimer.fired != sleepAtStart) {
                finish()
                return
            }
            savePosition()
            if (player?.isPlaying == true) serverReport(ReportState.PROGRESS)
            handler.postDelayed(this, 10_000)
        }
    }
    internal val tick = object : Runnable {
        override fun run() {
            if (secondsLeft <= 0) {
                playNext()
                return
            }
            upNextUi.seconds = secondsLeft
            secondsLeft--
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current?.get()?.takeIf { it !== this }?.finish()
        current = java.lang.ref.WeakReference(this)
        startOver = intent.getBooleanExtra(PlayRequest.EXTRA_START_OVER, false)
        queueId=intent.getStringExtra("ember.fileQueue");fileQueue=MediaQueues.get(queueId)
        if(queueId!=null&&fileQueue==null){Toast.makeText(this,"Reopen your media files to restore this queue.",Toast.LENGTH_LONG).show();finish();return}

        trailer = intent.getBooleanExtra(EXTRA_TRAILER, false) || intent.getBooleanExtra(EXTRA_LIVE, false)
        live = intent.getBooleanExtra(EXTRA_LIVE, false)
        zapUrls = intent.getStringArrayExtra(EXTRA_ZAP_URLS) ?: emptyArray()
        zapNames = intent.getStringArrayExtra(EXTRA_ZAP_NAMES) ?: emptyArray()
        zapAgents = intent.getStringArrayExtra(EXTRA_ZAP_AGENTS) ?: emptyArray()
        zapReferrers = intent.getStringArrayExtra(EXTRA_ZAP_REFERRERS) ?: emptyArray()
        zapIndex = intent.getIntExtra(EXTRA_ZAP_INDEX, 0)
        liveSessionId = intent.getStringExtra(EXTRA_LIVE_SESSION)
        if (liveSessionId != null) {
            liveSession = LiveSessions.get(liveSessionId!!)
            if (!syncSession()) {
                Toast.makeText(this, "Open Live TV to choose an available channel.", Toast.LENGTH_LONG).show()
                finish(); return
            }
        }
        applyChannelHeaders(zapIndex)
        if (liveSession == null) applyStreamHeaders(PlayRequest.headers(intent))
        audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL)
        val url = if (liveSession != null) zapUrls.getOrNull(zapIndex) else intent.getStringExtra(PlayRequest.EXTRA_URL)
        if (url.isNullOrBlank() && intent.getStringExtra(PlayRequest.EXTRA_SERVER_REF) == null) {
            finish()
            return
        }
        val title = if (liveSession != null) zapNames.getOrNull(zapIndex).orEmpty() else intent.getStringExtra(PlayRequest.EXTRA_TITLE).orEmpty()
        resumeKey = intent.getStringExtra(PlayRequest.EXTRA_RESUME_KEY) ?: url.orEmpty()
        resumeFraction = intent.getFloatExtra(PlayRequest.EXTRA_RESUME_FRACTION, -1f)
        item = TrackedItem(
            key = resumeKey,
            title = title,
            kind = runCatching { Kind.valueOf(intent.getStringExtra(PlayRequest.EXTRA_KIND) ?: "") }.getOrDefault(Kind.MOVIE),
            imdbId = intent.getStringExtra(PlayRequest.EXTRA_IMDB),
            tmdbId = intent.getLongExtra(PlayRequest.EXTRA_TMDB, -1L).takeIf { it > 0 },
            season = intent.getIntExtra(PlayRequest.EXTRA_SEASON, -1).takeIf { it >= 0 },
            episode = intent.getIntExtra(PlayRequest.EXTRA_EPISODE, -1).takeIf { it >= 0 },
        )
        seriesName = intent.getStringExtra(PlayRequest.EXTRA_SERIES_NAME) ?: title
        prevAddon = intent.getStringExtra(PlayRequest.EXTRA_PREV_ADDON)
        prevQuality = intent.getStringExtra(PlayRequest.EXTRA_PREV_QUALITY)
        prevBingeGroup = intent.getStringExtra(PlayRequest.EXTRA_BINGE_GROUP)
        sourceHash = intent.getStringExtra(PlayRequest.EXTRA_VIDEO_HASH)?.let { it to intent.getLongExtra(PlayRequest.EXTRA_VIDEO_SIZE, -1L).takeIf { s -> s > 0 } }
        readNextEpisodes()
        serverRef = intent.getStringExtra(PlayRequest.EXTRA_SERVER_REF)
        if (serverRef != null) servers = MediaServers.forPlayback(applicationContext)

        prefs = Prefs(this)
        watchProfileId=app.ember.tv.data.WatchedStore.playProfile(prefs.currentProfile,intent.getStringExtra(PlayRequest.EXTRA_PROFILE)) ?: run {finish();return}
        if(!contentPlaybackAllowed()){finish();return}
        getSharedPreferences("ember",MODE_PRIVATE).registerOnSharedPreferenceChangeListener(contentProfileListener)
        hub = TrackerHub(applicationContext).also { h ->
            // Trackers switched off in Settings > Features: no reports at all.
            // A Kids profile (or one without personal rows) never reports to your trackers.
            val profile = prefs.profiles.firstOrNull { it.id == prefs.currentProfile }
            val privateProfile = profile != null && (profile.kids || !profile.personal)
            // Hiding a tracker from Home no longer stops reports; "Report what I watch" does (#80).
            h.quiet = if (privateProfile || app.ember.tv.data.Features.TRACKERS in prefs.featuresOff) h.trackers.map { it.id }.toSet() else prefs.trackersQuiet
        }
        localActivityScope=app.ember.tv.data.LocalActivity.scope(this,watchProfileId)
        memory=TrackMemory(this,localActivityScope)
        positions = PositionStore(this,localActivityScope)

        view = PlayerView(this).apply {
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            controllerShowTimeoutMs = 4000
        }
        config = prefs.playbackConfig
        playerInterface = PlayerInterfaces.resolve(config, prefs.uiStyle)
        controls.design = playerInterface
        advanced = PlayerInterfaces.customControls(playerInterface, live, trailer)
        // The accent colour (Settings > Appearance) for the progress bar and menus, in both player looks.
        val accent = app.ember.tv.ui.emberColorScheme(prefs.accent, day = false, oled = false).primary
        controls.accent = accent
        accentArgb = android.graphics.Color.argb(255, (accent.red * 255).toInt(), (accent.green * 255).toInt(), (accent.blue * 255).toInt())
        view.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setPlayedColor(accentArgb)
            setScrubberColor(accentArgb)
        }
        styleNativeIcons(view)
        controls.localUri=url?.takeIf {fileQueue!=null&&it.startsWith("content://")}
        controls.details = PlayerDetails.get(resumeKey)
        refreshControlsTitle()
        setContentView(buildLayout())
        if (advanced) handler.post(controlsTicker)

        applyZoom()
        if (advanced) handler.post(statsTicker)
        val trackSelector = DefaultTrackSelector(this)
        trackSelector.setParameters(
            trackSelector.buildUponParameters().apply {
                setTunnelingEnabled(config.tunneling)
                // Allow the full channel count and the highest quality the hardware reports.
                setMaxAudioChannelCount(Int.MAX_VALUE)
            },
        )

        // Debrid and addon links often redirect, sometimes from http to https. High-bitrate
        // sources can be slow to start, so the timeouts are generous.
        val http = httpFactory
        audioDelay.us = if (trailer) 0 else config.audioDelayMs * 1000L
        // Dolby Vision only where the screen shows it; projectors and most TVs without it get the HDR10 picture.
        codecs.dvAsHevc = when (config.dolbyVision) {
            "hdr10" -> true
            "always" -> false
            else -> !Playback.screenHasDolbyVision(this)
        }
        val standard = ExoPlayer.Builder(this, Playback.Renderers(this, config, audioDelay, codecs, decoderManager))
            .setTrackSelector(trackSelector)
            .setLoadControl(Playback.loadControl(config, this))
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(mediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setVideoChangeFrameRateStrategy(
                if (config.frameRate == "seamless") C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
                else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF,
            )
            .build()

        playbackEngine=PlaybackEngine(this,standard,{config.copy(passthrough=config.passthrough&&!codecs.pcm)}) { compatible ->
            videoDecoder=if(compatible)"Compatibility engine" else null
            loudness?.release();loudness=null
            if(boostLevel>0)setBoost(boostLevel)
            if(compatible) {
                audioDecoder=null
                applyZoom();applyAspect()
                playbackEngine?.compatibility?.captionStyle(prefs.subtitleConfig)
                playbackEngine?.compatibility?.audioDelay(audioDelay.us/1000)
                if(::subs.isInitialized){playbackEngine?.compatibility?.subtitleSpeed(subs.engine.speed);playbackEngine?.compatibility?.subtitleDelay(subs.engine.offsetMs)}
                Toast.makeText(this,"Trying compatibility playback",Toast.LENGTH_SHORT).show()
            }
        }
        val p=playbackEngine!!.player
        decoderManager.attach(standard)
        if(config.ffmpegVideo=="always")useFfmpegVideo(p)
        // Renderer priority already implements "prefer software". Forcing FFmpeg here
        // disables MPEG-H and device-only formats such as AC-4 in Nextlib's controller.

        p.setVideoFrameMetadataListener { _, _, _, output ->
            if (!hdr10PlusMetadata && output?.containsKey("hdr10-plus-info") == true)
                hdr10PlusMetadata = runCatching { output.getByteBuffer("hdr10-plus-info")?.hasRemaining() == true }.getOrDefault(false)
        }
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                hdr10PlusMetadata = false
            }
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                videoDecoder = decoderName
            }

            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                audioDecoder = decoderName
            }

            override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
                bandwidth = bitrateEstimate
                if (totalLoadTimeMs > 0) loadSpeed = totalBytesLoaded * 8_000 / totalLoadTimeMs
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                dropped += droppedFrames
            }

            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                // Volume boost is tied to the sound session; follow it to the new one.
                loudness?.let { runCatching { it.release() } }
                loudness = null
                if (boostLevel > 0) setBoost(boostLevel)
            }

            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                // A new audio format may go straight to the receiver; the decoder callback says otherwise.
                audioDecoder = null
            }
        })

        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                // PlayerView sets the video's own shape; a forced one goes back on top.
                if (aspect != 0) handler.post { applyAspect() }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (config.engine == "auto" && playbackEngine?.compatibility == null && tracks.groups.any { group ->
                    group.type == C.TRACK_TYPE_VIDEO && (0 until group.length).any { group.isTrackSelected(it) && PlaybackFormats.nativeFirst(group.getTrackFormat(it).sampleMimeType, group.isTrackSupported(it, false)) }
                } && playbackEngine?.switch() == true) return
                if(config.engine=="auto" && prefs.subtitleConfig.authoredStyles && !prefs.subtitleConfig.systemStyle && playbackEngine?.compatibility==null && tracks.groups.any {it.type==C.TRACK_TYPE_TEXT && (0 until it.length).any { index -> it.isTrackSelected(index) && it.getTrackFormat(index).sampleMimeType==MimeTypes.TEXT_SSA }}) {
                    if(playbackEngine?.switch()==true)return
                }
                if (advanced) {
                    controls.audioTracks = audioTracks()
                    changed()
                }
                // Start on the best audio track once per title; after that the user's choice stands.
                if (!audioPicked && tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
                    audioPicked = true
                    Playback.bestAudio(tracks, config, showKey()?.let { memory.get(it)?.audio })?.let { o ->
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(o).build()
                    }
                }
                matchDisplayMode(p)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (live) liveBudget.playback(state == Player.STATE_READY, android.os.SystemClock.elapsedRealtime())
                // Playing again after a retry: a later drop (an hour into a film or a channel) gets its
                // own retries, but not if it keeps failing right after starting.
                if (state == Player.STATE_READY && android.os.SystemClock.elapsedRealtime() - lastRetryAt > 60_000L) {
                    ioRetries = 0
                }
                if (state == Player.STATE_READY && !resumed) {
                    resumed = resume(p)
                    loadFileChapters(p)
                    if (advanced) showControls()
                    if (config.infoOnStart) showInfo(8_000)
                    if (::subs.isInitialized) subs.onReady()
                }
                if (state == Player.STATE_ENDED && !ended) {
                    ended = true
                    reportedPlaying = false
                    scrobble(ScrobbleState.STOP, 1f)
                    serverReport(ReportState.STOP)
                    positions.clear(resumeKey)
                    if (!trailer) {app.ember.tv.data.LocalLibrary.finished(resumeKey,localActivityScope);recordWatched()}
                    onEpisodeEnded()
                }
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                serverPlayback?.takeIf { it.parts.size > 1 && it.parts.any { part -> (part.durationMs ?: 0L) <= 0L } }?.let { transport ->
                    if (timeline.periodCount == transport.parts.size) {
                        val durations = (0 until timeline.periodCount).map { timeline.getPeriod(it, androidx.media3.common.Timeline.Period()).durationMs }
                        transport.withProbedDurations(durations)?.let { serverPlayback = it }
                    }
                }
                if (p.playbackState == Player.STATE_READY && !resumed) resumed = resume(p)
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) serverReport(ReportState.PAUSE)
                else if (p.isPlaying) serverReport(ReportState.START)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                controls.playing = isPlaying
                // Paused by the user: show the controls (with the synopsis and cast).
                if (advanced && !isPlaying && !p.playWhenReady && p.playbackState == Player.STATE_READY) showControls()
                // Keep the screen on only while video plays, so a paused video lets the screensaver start.
                view.keepScreenOn = isPlaying
                // isPlaying also flips around buffering and seeks; report only real play and pause changes.
                if (isPlaying && !reportedPlaying) {
                    reportedPlaying = true
                    scrobble(ScrobbleState.START, progress())
                    serverReport(ReportState.START)
                } else if (!isPlaying && reportedPlaying && p.playbackState == Player.STATE_READY && !p.playWhenReady) {
                    reportedPlaying = false
                    scrobble(ScrobbleState.PAUSE, progress())
                    serverReport(ReportState.PAUSE)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (live) {
                    // A live stream that fell behind its window just jumps back to live.
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        p.seekToDefaultPosition()
                        p.prepare()
                        return
                    }
                    app.ember.tv.data.CrashLog.note("Live TV error ${error.errorCodeName} · channel ${zapNames.getOrNull(zapIndex).orEmpty()} · try $liveRetries", error)
                    if (retryLive(error)) return
                    val http = generateSequence<Throwable>(error) { it.cause }
                        .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                    Toast.makeText(
                        this@PlayerActivity,
                        listOfNotNull(
                            getString(R.string.pl_channel_unavailable, error.errorCodeName + (http?.let { ", HTTP $it" } ?: "")),
                            if (http == 401 || http == 403) getString(R.string.pl_channel_refused) else null,
                            if (http == 429) "Provider request limit reached. Wait before trying again." else null,
                            if (zapUrls.size > 1) getString(R.string.pl_channel_other) else null,
                        ).joinToString(" "),
                        Toast.LENGTH_LONG,
                    ).show()
                    if (zapUrls.size < 2) finish()
                    return
                }
                // An addon's live channel plays here too: one that fell behind its window jumps back to live.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                // A link with no extension that is not a file ExoPlayer knows: try it once as HLS.
                if (!triedAsHls && playbackEngine?.compatibility == null && serverPlayback == null && audioUrl == null &&
                    PlaybackErrors.mayBeHls(error.errorCode, currentUrl)) {
                    triedAsHls = true
                    app.ember.tv.data.CrashLog.note("Playback error ${error.errorCodeName} · trying the link as HLS", error)
                    p.currentMediaItem?.buildUpon()?.setMimeType(MimeTypes.APPLICATION_M3U8)?.build()?.let {
                        p.setMediaItem(it)
                        p.prepare()
                        p.playWhenReady = true
                        return
                    }
                }
                val what = describe((error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat)
                // Every playback error goes to Settings > About > Crash log, with the decoders in use.
                app.ember.tv.data.CrashLog.note(
                    "Playback error ${error.errorCodeName}" + (if (what.isNotBlank()) " ($what)" else "") +
                        " · video decoder ${videoDecoder ?: "none"} · audio decoder ${audioDecoder ?: "none"}" +
                        " · FFmpeg video ${codecs.ffmpegVideo} · retries $recoveries/$ioRetries · at ${player?.currentPosition ?: 0} ms",
                    error,
                )
                if (recover(error)) return
                if(playbackEngine?.recover(error.errorCode)==true)return
                if (serverPlayback != null) { showServerRecovery(); return }
                // The underlying cause, e.g. "FfmpegDecoderException: ..." or "EOFException".
                val cause = generateSequence(error.cause) { it.cause }.lastOrNull()?.let { c ->
                    (c.javaClass.simpleName + (c.message?.let { ": " + app.ember.tv.data.CrashLog.redact(it).take(80) } ?: ""))
                }
                Toast.makeText(
                    this@PlayerActivity,
                    getString(
                        R.string.pl_source_cannot_play,
                        error.errorCodeName + (if (what.isNotBlank()) ", $what" else "") + (if (cause != null) "; $cause" else ""),
                    ),
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        })

        view.player = p
        player = p
        platformSession = PlatformPlaybackSession(this, p, { endPlayback() }, { if (nextEpisodes.isNotEmpty() || fileQueue!=null) playNext() }).also { it.nextAvailable(nextEpisodes.isNotEmpty() || (fileQueue?.items?.size?:0)>1) }
        val subCfg = prefs.subtitleConfig
        view.subtitleView?.let { sv ->
            SubtitleEngine.style(sv, subCfg)
            baseSubCfg = subCfg
            subs = SubtitleController(this, { player }, SubtitleEngine(sv), subConfigFor(subCfg), {refreshSubtitlePanel();updateSubtitleSheet()}) { text,format,wholeFile ->
                if(format=="ass" && config.engine=="auto" && prefs.subtitleConfig.authoredStyles)playbackEngine?.switch()
                playbackEngine?.compatibility?.external(text,format,wholeFile)==true
            }
        }
        handler.post(subtitleTicker)
        handler.post(markerTicker)
        liveSession?.let { session -> lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                androidx.compose.runtime.snapshotFlow { session.channels().map { it.key } }.collect { keys ->
                    if (session.currentKey !in keys) endPlayback()
                }
            }
        } }
        if (serverRef != null) lifecycleScope.launch {
            val cached = app.ember.tv.mediaserver.ServerPlaybackRegistry.get(intent.getStringExtra(PlayRequest.EXTRA_SERVER_SESSION))
            val selected = intent.getStringExtra(PlayRequest.EXTRA_SERVER_SOURCE)
            val quality = app.ember.tv.mediaserver.ServerQuality.from(intent.getStringExtra(PlayRequest.EXTRA_SERVER_QUALITY))
            serverPlayback = cached?.takeIf { it.ref.value == serverRef && it.sourceId == selected && it.quality == quality }
                ?: runCatching {
                    if (selected != null) servers?.playback(serverRef!!, selected, quality)?.serverPlayback
                    else servers?.sourcesFor(serverRef!!)?.firstOrNull()?.serverPlayback
                }.getOrNull()
            val transport = serverPlayback
            if (transport == null || transport.parts.isEmpty()) {
                Toast.makeText(this@PlayerActivity, "Server unavailable. Reopen the title to choose another version.", Toast.LENGTH_LONG).show()
                finish(); return@launch
            }
            start(transport.parts.first().url, title, transport.parts.first().subtitles, intent.getStringExtra(PlayRequest.EXTRA_RELEASE))
            findNextServerEpisode()
        } else start(url.orEmpty(), title, addonSubtitlesFromIntent(), intent.getStringExtra(PlayRequest.EXTRA_RELEASE))
        view.requestFocus()
        handler.postDelayed(saver, 5_000)
    }

    /** Loads a source into the player and starts it. Used for the first episode and every next one. */
    internal fun start(url: String, title: String, addonSubs: List<SubtitleOption>, release: String?) {
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
        if (::subs.isInitialized && !trailer) {
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

    internal val panelVisible: Boolean get() = ::panel.isInitialized && panel.isVisible

    /** What each row of the advanced Subtitles sheet does, in order. */
    internal var subtitleActions: List<() -> Unit> = emptyList()

    /** While the "Up next" card is open, its buttons get the remote; otherwise the player controls do. */
    @android.annotation.SuppressLint("RestrictedApi") // super.dispatchKeyEvent is the normal Activity path
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(fileQueue!=null && event.keyCode in setOf(KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS)){
            if(event.action==KeyEvent.ACTION_UP)(if(event.keyCode==KeyEvent.KEYCODE_MEDIA_NEXT)fileQueue?.next() else fileQueue?.previous())?.let { playFile(it) }
            return true
        }
        val unobstructed = !(::panel.isInitialized && panel.isVisible) &&
            !(if (advanced) controls.visible else ::view.isInitialized && view.isControllerFullyVisible)
        if (live && liveSession != null && unobstructed) {
            if (numberDigits.isNotEmpty() && event.keyCode in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
                if (event.action == KeyEvent.ACTION_UP) {
                    handler.removeCallbacks(commitNumber)
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> commitNumber.run()
                        KeyEvent.KEYCODE_DEL -> {
                            numberDigits = numberDigits.dropLast(1)
                            numberToast?.cancel()
                            if (numberDigits.isNotEmpty()) handler.postDelayed(commitNumber, 1200)
                        }
                        else -> { numberDigits = ""; numberToast?.cancel() }
                    }
                }
                return true
            }
            val digit = LiveRemote.digit(event.keyCode)
            if (digit != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && numberDigits.length < 6) {
                    numberDigits += digit
                    numberToast?.cancel(); numberToast = Toast.makeText(this, "Channel $numberDigits", Toast.LENGTH_SHORT).also { it.show() }
                    handler.removeCallbacks(commitNumber); handler.postDelayed(commitNumber, 1200)
                }
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_LAST_CHANNEL || event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) liveSession?.previous()?.let { tuneChannel(it.key) }
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showRecentChannels()
                return true
            }
        }
        // Live TV: channel up and down (and Up/Down while the controls are hidden) change channel.
        if (live && zapUrls.size > 1 && !(::panel.isInitialized && panel.isVisible)) {
            val up = event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP || event.keyCode == KeyEvent.KEYCODE_PAGE_UP ||
                (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && !view.isControllerFullyVisible)
            val down = event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
                (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !view.isControllerFullyVisible)
            if (up || down) {
                if (event.action == KeyEvent.ACTION_UP) zap(if (up) 1 else -1)
                return true
            }
        }
        if (live && liveSession != null && event.keyCode == KeyEvent.KEYCODE_GUIDE) {
            if (event.action == KeyEvent.ACTION_UP) {
                liveSession?.openGuide()
                endPlayback()
            }
            return true
        }
        // Info/Menu retain playback diagnostics; Guide returns live sessions to the timeline.
        val menuIsOurs = event.keyCode == KeyEvent.KEYCODE_MENU && prefs.remoteSettingsKey == "ember"
        if (event.keyCode == KeyEvent.KEYCODE_INFO || menuIsOurs || event.keyCode == KeyEvent.KEYCODE_GUIDE) {
            if (event.action == KeyEvent.ACTION_UP && ::panel.isInitialized) {
                when {
                    panelVisible -> hidePanel()
                    // The advanced look: the small menu (Options page and stats are in it).
                    advanced && controls.menu != null -> closeMenu()
                    advanced -> openMenu("quick", null)
                    else -> showInfo()
                }
            }
            return true
        }
        if (panelVisible) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) hidePanel()
                return true
            }
            handler.removeCallbacks(hideInfo)
            return super.dispatchKeyEvent(event)
        }
        if (::upNext.isInitialized && upNext.isVisible) {
            // #227: during the credits, Back hides the card and keeps watching.
            if (event.keyCode == KeyEvent.KEYCODE_BACK && !ended && player != null) {
                if (event.action == KeyEvent.ACTION_UP) dismissUpNext()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (advanced) advancedKey(event)?.let { return it }
        // Settings > Player > Leaving the player: Back asks before the video closes.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && config.confirmExit && !trailer && player != null && !ended) {
            if (event.action == KeyEvent.ACTION_UP) askToLeave()
            return true
        }
        if (skipTarget != null && !(if (advanced) controls.visible else view.isControllerFullyVisible) &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            if (event.action == KeyEvent.ACTION_UP) doSkip()
            return true
        }
        return (::view.isInitialized && view.dispatchKeyEvent(event)) || super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        liveRetryJob?.cancel(); liveRetryJob = null
        handler.removeCallbacks(commitNumber); numberDigits = ""; numberToast?.cancel()
        handler.removeCallbacks(hideLiveBanner)
        liveBanner.value = null
        handler.removeCallbacks(saver)
        handler.removeCallbacks(tick)
        handler.removeCallbacks(infoUpdater)
        handler.removeCallbacks(hideInfo)
        handler.removeCallbacks(subtitleTicker)
        handler.removeCallbacks(markerTicker)
        handler.removeCallbacks(controlsTicker)
        handler.removeCallbacks(hideControls)
        handler.removeCallbacks(statsTicker)
        val p = player
        // Paused and still on screen when the screensaver (or another overlay) took over: keep the
        // player, so the video is there when you come back. Home, Back and a running video close it.
        val upNextShown = ::upNext.isInitialized && upNext.isVisible
        if (p != null && !userLeft && !isFinishing && !isChangingConfigurations && !p.playWhenReady && !ended && !upNextShown) {
            keptAlive = true
            savePosition()
            rememberTracks()
            handler.postDelayed(closeKept, 30 * 60_000L)
            return
        }
        endPlayback()
    }

    /**
     * "Stop watching?" with Keep watching focused. The video keeps playing behind it; Back again
     * (or Stop) leaves, so a double press still exits quickly.
     */
    internal fun askToLeave() {
        if (leaveDialog?.isShowing == true) return
        val p = player ?: return
        val name = controls.title.ifBlank { if (::item.isInitialized) item.title else "" }
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
    private var leaveDialog: android.app.AlertDialog? = null

    /** Saves where you are, reports the stop, releases the player and (normally) closes the screen. */
    internal fun endPlayback(finishActivity: Boolean = true) {
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

    /** The accent colour (Settings > Appearance) as a colour int, for the panel's buttons and the simple look's progress bar. */
    internal var accentArgb = 0xFFFF7A2F.toInt()

    // ---- resume and progress ----
    internal fun resume(p: ExoPlayer): Boolean {
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

    internal fun playbackDuration(p: ExoPlayer): Long {
        val transport = serverPlayback
        if(playbackEngine?.compatibility!=null)return p.duration.takeIf {it>0}?:transport?.durationMs?:0L
        if (transport != null && transport.parts.size > 1 && transport.parts.any { (it.durationMs ?: 0L) <= 0L }) return transport.durationMs ?: 0L
        return p.duration.takeIf { it > 0 } ?: transport?.durationMs ?: 0L
    }

    internal fun progress(): Float {
        val p = player ?: return 0f
        val d = playbackDuration(p)
        return if (d > 0) (p.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
    }

    internal fun savePosition() {
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

    internal var localActivityScope=app.ember.tv.data.LocalActivity.SHARED
    internal var watchProfileId=app.ember.tv.data.Profile.MAIN
    internal val contentProfileListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,key->
        if(key==null || key=="profiles" || key=="currentProfile")handler.post {
            if(::prefs.isInitialized && !contentPlaybackAllowed()){endPlayback();finish()}
        }
    }
    internal fun contentPlaybackAllowed():Boolean {
        if(watchProfileId!=prefs.currentProfile)return false
        val active=prefs.profiles.firstOrNull {it.id==watchProfileId} ?: return false
        val content=app.ember.tv.data.Meta(type=intent.getStringExtra(PlayRequest.EXTRA_CONTENT_TYPE) ?: if(item.kind==Kind.MOVIE)"movie" else "series",
            genres=intent.getStringArrayExtra(PlayRequest.EXTRA_GENRES)?.toList(),ageRating=intent.getStringExtra(PlayRequest.EXTRA_AGE_RATING),
            adult=intent.getBooleanExtra(PlayRequest.EXTRA_ADULT,false))
        return app.ember.tv.data.KidsContentPolicy.playbackAllowed(active,watchProfileId,content,live)
    }
    internal val recordedWatched=hashSetOf<String>()
    internal fun recordWatched() {
        if(live || trailer || !recordedWatched.add(resumeKey))return
        val meta=app.ember.tv.data.LocalLibrary.metaFor(resumeKey,localActivityScope) ?: app.ember.tv.data.Meta(id=item.imdbId ?: resumeKey,
            type=if(item.kind==Kind.MOVIE)"movie" else "series",name=seriesName,imdbId=item.imdbId,tmdbId=item.tmdbId,
            season=item.season,episode=item.episode,serverRef=serverRef,serverSeriesRef=serverPlayback?.seriesRef)
        app.ember.tv.data.WatchedStore.record(applicationContext,watchProfileId,meta)
    }

    // ---- subtitles ----
    internal fun addonSubtitlesFromIntent(): List<SubtitleOption> {
        val urls = intent.getStringArrayExtra(PlayRequest.EXTRA_SUB_URLS) ?: return emptyList()
        val langs = intent.getStringArrayExtra(PlayRequest.EXTRA_SUB_LANGS) ?: emptyArray()
        return urls.mapIndexed { i, u -> SubtitleOption("", langs.getOrNull(i) ?: "", u) }
    }

    // Lateinit checks for members split into extension files (isInitialized is file-scoped).
    internal val itemInitialized: Boolean get() = ::item.isInitialized
    internal val panelInitialized: Boolean get() = ::panel.isInitialized
    internal val skipButtonInitialized: Boolean get() = ::skipButton.isInitialized
    internal val upNextInitialized: Boolean get() = ::upNext.isInitialized
    internal val soundLabelInitialized: Boolean get() = ::soundLabel.isInitialized
    internal val subListInitialized: Boolean get() = ::subList.isInitialized
    internal val subsInitialized: Boolean get() = ::subs.isInitialized
}
