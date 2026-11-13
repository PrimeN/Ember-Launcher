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

    /** The subtitle settings with this show's remembered choice applied. */
    internal fun subConfigFor(base: app.ember.tv.data.SubtitleConfig): app.ember.tv.data.SubtitleConfig {
        val mem = showKey()?.let { memory.get(it) }?.subtitles ?: return base
        return if (mem == "off") base.copy(autoSelect = "off")
        else base.copy(autoSelect = "always", languages = listOf(mem) + base.languages.filter { it != mem })
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

    // ---- Live TV: bounded retries for transient provider failures ----
    internal val liveBudget = LiveRetryBudget()
    internal val liveRetries get() = liveBudget.attempts
    internal var liveRetryJob: kotlinx.coroutines.Job? = null
    internal var lastRetryAt = 0L

    // ---- decoders: retry with another one when decoding fails ----
    internal val codecs = Playback.CodecChoice()
    internal val decoderManager = DecoderManager()
    internal val decoderRecovery = DecoderRecovery()
    internal var userVideoMode: DecoderMode? = null
    internal var userAudioMode: DecoderMode? = null
    internal var recoveries = 0
    /** Network hiccups (a dropped connection, a link that stalls) are retried a couple of times. */
    internal var ioRetries = 0

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
        createPlayer(savedInstanceState)
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

    internal var leaveDialog: android.app.AlertDialog? = null

    /** The accent colour (Settings > Appearance) as a colour int, for the panel's buttons and the simple look's progress bar. */
    internal var accentArgb = 0xFFFF7A2F.toInt()

    internal var localActivityScope=app.ember.tv.data.LocalActivity.SHARED
    internal var watchProfileId=app.ember.tv.data.Profile.MAIN
    internal val contentProfileListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,key->
        if(key==null || key=="profiles" || key=="currentProfile")handler.post {
            if(::prefs.isInitialized && !contentPlaybackAllowed()){endPlayback();finish()}
        }
    }
    internal val recordedWatched=hashSetOf<String>()
    // Lateinit checks for members split into extension files (isInitialized is file-scoped).
    internal val itemInitialized: Boolean get() = ::item.isInitialized
    internal val panelInitialized: Boolean get() = ::panel.isInitialized
    internal val skipButtonInitialized: Boolean get() = ::skipButton.isInitialized
    internal val upNextInitialized: Boolean get() = ::upNext.isInitialized
    internal val soundLabelInitialized: Boolean get() = ::soundLabel.isInitialized
    internal val subListInitialized: Boolean get() = ::subList.isInitialized
    internal val subsInitialized: Boolean get() = ::subs.isInitialized
    internal val viewInitialized: Boolean get() = ::view.isInitialized
    internal val hubInitialized: Boolean get() = ::hub.isInitialized
    internal val positionsInitialized: Boolean get() = ::positions.isInitialized
    internal val prefsInitialized: Boolean get() = ::prefs.isInitialized
    internal val configInitialized: Boolean get() = ::config.isInitialized
    internal val infoInitialized: Boolean get() = ::info.isInitialized
    internal val subStatusInitialized: Boolean get() = ::subStatus.isInitialized
    internal val timingLabelInitialized: Boolean get() = ::timingLabel.isInitialized
    internal val memoryInitialized: Boolean get() = ::memory.isInitialized
}
