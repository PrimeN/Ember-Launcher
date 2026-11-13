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
internal fun PlayerActivity.createPlayer(savedInstanceState: Bundle?) {
    PlayerActivity.current?.get()?.takeIf { it !== this }?.finish()
    PlayerActivity.current = java.lang.ref.WeakReference(this)
    startOver = intent.getBooleanExtra(PlayRequest.EXTRA_START_OVER, false)
    queueId=intent.getStringExtra("ember.fileQueue");fileQueue=MediaQueues.get(queueId)
    if(queueId!=null&&fileQueue==null){Toast.makeText(this,"Reopen your media files to restore this queue.",Toast.LENGTH_LONG).show();finish();return}

    trailer = intent.getBooleanExtra(PlayerActivity.EXTRA_TRAILER, false) || intent.getBooleanExtra(PlayerActivity.EXTRA_LIVE, false)
    live = intent.getBooleanExtra(PlayerActivity.EXTRA_LIVE, false)
    zapUrls = intent.getStringArrayExtra(PlayerActivity.EXTRA_ZAP_URLS) ?: emptyArray()
    zapNames = intent.getStringArrayExtra(PlayerActivity.EXTRA_ZAP_NAMES) ?: emptyArray()
    zapAgents = intent.getStringArrayExtra(PlayerActivity.EXTRA_ZAP_AGENTS) ?: emptyArray()
    zapReferrers = intent.getStringArrayExtra(PlayerActivity.EXTRA_ZAP_REFERRERS) ?: emptyArray()
    zapIndex = intent.getIntExtra(PlayerActivity.EXTRA_ZAP_INDEX, 0)
    liveSessionId = intent.getStringExtra(PlayerActivity.EXTRA_LIVE_SESSION)
    if (liveSessionId != null) {
        liveSession = LiveSessions.get(liveSessionId!!)
        if (!syncSession()) {
            Toast.makeText(this, "Open Live TV to choose an available channel.", Toast.LENGTH_LONG).show()
            finish(); return
        }
    }
    applyChannelHeaders(zapIndex)
    if (liveSession == null) applyStreamHeaders(PlayRequest.headers(intent))
    audioUrl = intent.getStringExtra(PlayerActivity.EXTRA_AUDIO_URL)
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
            if(subsInitialized){playbackEngine?.compatibility?.subtitleSpeed(subs.engine.speed);playbackEngine?.compatibility?.subtitleDelay(subs.engine.offsetMs)}
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
                if (subsInitialized) subs.onReady()
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
                    this@createPlayer,
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
                this@createPlayer,
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
            Toast.makeText(this@createPlayer, "Server unavailable. Reopen the title to choose another version.", Toast.LENGTH_LONG).show()
            finish(); return@launch
        }
        start(transport.parts.first().url, title, transport.parts.first().subtitles, intent.getStringExtra(PlayRequest.EXTRA_RELEASE))
        findNextServerEpisode()
    } else start(url.orEmpty(), title, addonSubtitlesFromIntent(), intent.getStringExtra(PlayRequest.EXTRA_RELEASE))
    view.requestFocus()
    handler.postDelayed(saver, 5_000)
}
