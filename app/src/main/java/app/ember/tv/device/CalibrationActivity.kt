package app.ember.tv.device

import androidx.core.view.isVisible

import androidx.core.net.toUri

import android.content.Intent
import android.hardware.display.DisplayManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.ember.tv.R
import app.ember.tv.data.Prefs

/** Private calibration session. Video uses a normal SurfaceView and original files, never SDR UI pretending to be HDR. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CalibrationActivity : ComponentActivity() {
    private var surface: CalibrationSurface? = null
    private var player: ExoPlayer? = null
    private var videoView: PlayerView? = null
    private lateinit var overlay: TextView
    private var currentPattern = CalibrationPattern.SHARPNESS
    private var clipKind: CalibrationClip? = null
    private var sampleTitle: Int? = null
    private lateinit var profile: String
    private var profileStamp: app.ember.tv.data.Profile? = null
    private lateinit var resolution: String
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private var displayRegistered = false
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) {
            if (!::overlay.isInitialized || isFinishing || id != windowManager.defaultDisplay.displayId) return
            val clip = clipKind
            if (clip?.hdrType != null && clip.hdrType !in (windowManager.defaultDisplay.hdrCapabilities?.supportedHdrTypes ?: intArrayOf())) {
                fail(R.string.cal_display_unsupported); return
            }
            if (overlay.isVisible) showOverlay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        profile = intent.getStringExtra(EXTRA_PROFILE) ?: run { finish(); return }
        if (profile != prefs.currentProfile) { finish(); return }
        profileStamp = prefs.profiles.firstOrNull { it.id == profile }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val display = windowManager.defaultDisplay
        val now = display.mode
        fun android.view.Display.Mode.calibration() = CalibrationMode(modeId, physicalWidth, physicalHeight, refreshRate)
        val chosen = CalibrationMath.chooseMode(now.calibration(), display.supportedModes.map { it.calibration() })
        window.attributes = window.attributes.apply { preferredDisplayModeId = chosen.id }
        resolution = "${chosen.width} × ${chosen.height} · ${getString(R.string.cal_sdr)}"
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        clipKind = intent.getStringExtra(EXTRA_CLIP)?.let { name -> CalibrationClip.entries.firstOrNull { it.name == name } }
        val clip = clipKind
        val sampleId = intent.getStringExtra(EXTRA_SAMPLE)
        val sample = BundledCalibrationSamples.find(sampleId, clip)
        if (sampleId != null && sample == null) { fail(R.string.cal_clip_unavailable); return }
        sampleTitle = sample?.title
        if (clip == null) {
            currentPattern = (savedInstanceState?.getString(EXTRA_PATTERN) ?: intent.getStringExtra(EXTRA_PATTERN))
                ?.let { name -> CalibrationPattern.entries.firstOrNull { it.name == name } } ?: CalibrationPattern.SHARPNESS
            surface = CalibrationSurface(this, chosen.width, chosen.height,
                onSurfaceReady = { if (::overlay.isInitialized && overlay.isVisible) showOverlay() },
                onFailure = { fail(R.string.cal_surface_failed) }).also { it.pattern = currentPattern; root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        } else {
            // Do not request a different resolution/rate for encoded clips: Media3 follows the actual video cadence.
            window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
            val uri = sample?.let { "android.resource://$packageName/${it.resource}".toUri() }
                ?: CalibrationClips(this).get(clip)
            val supported = clip.hdrType == null || clip.hdrType in (display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf())
            if (uri == null || !supported) { fail(R.string.cal_clip_unavailable); return }
            val view = PlayerView(this).apply { useController = false; keepScreenOn = true }
            videoView = view
            root.addView(view, FrameLayout.LayoutParams(-1, -1))
            val renderers = DefaultRenderersFactory(this).setMediaCodecSelector { mime, secure, tunneled ->
                if (CalibrationMath.allowDecoder(clip, mime)) MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneled) else emptyList()
            }
            player = ExoPlayer.Builder(this, renderers).build().also { p ->
                view.player = p
                p.repeatMode = Player.REPEAT_MODE_ONE
                p.volume = 0f
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                p.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { fail(R.string.cal_clip_failed) }
                    override fun onTracksChanged(tracks: Tracks) = validateTracks(tracks, false)
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) validateTracks(p.currentTracks, true)
                    }
                    private fun validateTracks(tracks: Tracks, ready: Boolean) {
                        val format = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                            ?.let { group -> (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let(group::getTrackFormat) }
                        if (format == null) {
                            if (ready || tracks.groups.isNotEmpty()) fail(if (tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }) R.string.cal_clip_failed else R.string.cal_clip_wrong_format)
                            return
                        }
                        if (!CalibrationMath.clipMatches(clip, format.sampleMimeType, format.colorInfo?.colorTransfer)) {
                            fail(R.string.cal_clip_wrong_format); return
                        }
                        resolution = "${format.width} × ${format.height} · ${getString(clip.title)}"
                        showOverlay()
                    }
                })
                p.setMediaItem(MediaItem.fromUri(uri)); p.prepare()
            }
        }
        overlay = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0xB0000000.toInt())
            textSize = 15f; setPadding(24, 14, 24, 14)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -2))
        root.setOnClickListener { if (overlay.isVisible) { handler.removeCallbacks(hideOverlay); overlay.visibility = View.GONE } else showOverlay() }
        setContentView(root)
        displayRegistered = runCatching { getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler) }.isSuccess
        onBackPressedDispatcher.addCallback(this) { finish() }
        showOverlay()
    }
    private fun showOverlay() {
        if (!::overlay.isInitialized || isFinishing) return
        if (clipKind == null && surface != null) {
            val mode = windowManager.defaultDisplay.mode
            val frame = surface!!.holder.surfaceFrame
            resolution = getString(R.string.cal_output, frame.width(), frame.height(), mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
        }
        overlay.text = if (clipKind != null) (sampleTitle?.let { "${getString(it)}\n" }.orEmpty() + "$resolution\n${getString(R.string.cal_clip_controls)}")
            else "${getString(currentPattern.label)} · $resolution\n${getString(currentPattern.instructions)}\n${getString(R.string.cal_controls)}"
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay); handler.postDelayed(hideOverlay, 6000)
    }
    private fun fail(message: Int) { if (isFinishing) return; player?.pause(); Toast.makeText(this, message, Toast.LENGTH_LONG).show(); finish() }
    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        val prefs = Prefs(this)
        if (prefs.currentProfile != profile || prefs.profiles.firstOrNull { it.id == profile } != profileStamp) { finish(); return }
        surface?.start(); player?.play()
    }
    override fun onPause() { surface?.stop(); player?.pause(); super.onPause() }
    override fun onDestroy() {
        if (displayRegistered) runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        handler.removeCallbacksAndMessages(null); surface?.stop()
        videoView?.player = null; player?.release(); player = null
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString(EXTRA_PATTERN, currentPattern.name); super.onSaveInstanceState(outState) }
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val move = when (event.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        val info = event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO)
        if (move != 0 || info) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                if (move != 0 && clipKind == null) { currentPattern = currentPattern.neighbour(move); surface?.pattern = currentPattern; showOverlay() }
                else if (info) {
                    handler.removeCallbacks(hideOverlay)
                    if (overlay.isVisible) overlay.visibility = View.GONE else showOverlay()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    companion object {
        private const val EXTRA_PROFILE = "ember.calibration.profile"
        const val EXTRA_PATTERN = "ember.calibration.pattern"
        const val EXTRA_CLIP = "ember.calibration.clip"
        private const val EXTRA_SAMPLE = "ember.calibration.sample"
        internal fun pattern(context: android.content.Context, pattern: CalibrationPattern) = Intent(context, CalibrationActivity::class.java).putExtra(EXTRA_PATTERN, pattern.name).putExtra(EXTRA_PROFILE, Prefs(context).currentProfile)
        internal fun clip(context: android.content.Context, clip: CalibrationClip) = Intent(context, CalibrationActivity::class.java).putExtra(EXTRA_CLIP, clip.name).putExtra(EXTRA_PROFILE, Prefs(context).currentProfile)
        internal fun sample(context: android.content.Context, sample: BundledCalibrationSample) = clip(context, sample.kind).putExtra(EXTRA_SAMPLE, sample.id)
    }
}
