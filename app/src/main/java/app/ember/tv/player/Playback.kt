package app.ember.tv.player

import android.app.Activity
import android.content.Context
import android.media.AudioFormat
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.renderer.NextTextRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.serialization.Serializable
import java.util.Locale

/** Settings > Player: how the built-in player decodes, outputs and shows video and audio. */
@Serializable
data class PlaybackConfig(
    /** Send Dolby and DTS audio untouched to the receiver or soundbar (bitstream). */
    val passthrough: Boolean = true,
    /** "auto": trust what the HDMI link reports. "manual": use [formats], for receivers behind ARC/eARC that under-report. */
    val passthroughMode: String = "auto",
    /** Manual passthrough formats: ac3, eac3, eac3joc (DD+ Atmos), dts, dtshd, truehd. */
    val formats: Set<String> = setOf("ac3", "eac3", "eac3joc", "dts", "dtshd", "truehd"),
    /** Software audio (FFmpeg/MPEG-H): "on" (when needed), "prefer" (first), "off" (device only). */
    val ffmpeg: String = "on",
    /**
     * FFmpeg video (software, Nextlib: H.264, HEVC, MPEG-2, VP8/VP9 and AV1): "fallback" when the TV's
     * decoder fails (default), "always" (e.g. emulators and virtual machines), "off".
     */
    val ffmpegVideo: String = "fallback",
    /** Legacy layout preference, retained for old backups. New choices use [playerInterface]. */
    val playerLook: String = "advanced",
    /** Explicit Basic, Ember Modern or Ember X controls, independent of the launcher interface. */
    val playerInterface: String? = null,
    /** Tunneled playback: video and audio synced by the hardware. Better A/V sync on some TVs; breaks on others. */
    val tunneling: Boolean = false,
    /** Display refresh rate: "off", "seamless" (only if the switch has no blackout), "always" (match the video). */
    val frameRate: String = "always",
    /** Also switch the display resolution to the video's (otherwise the TV or projector keeps its own and scales). */
    val matchResolution: Boolean = false,
    /** Buffer: "normal", "large" (4K remuxes), "huge" (very high bitrates; needs plenty of memory). */
    val buffer: String = "large",
    /** Audio track at start: "quality" (Atmos/lossless first), "language" (TV language, then quality), "default" (the file's default). */
    val audioPick: String = "quality",
    /** Show the info panel (codec, HDR, audio output) for a few seconds when playback starts. */
    val infoOnStart: Boolean = false,
    /**
     * Dolby Vision: "auto" uses it only when the screen reports Dolby Vision (otherwise the HDR10
     * picture inside the file is played), "always" lets the TV or box handle it, "hdr10" never uses it.
     */
    val dolbyVision: String = "auto",
    /** Sound timing (lip sync) in ms: positive plays the sound later than the picture, negative earlier. */
    val audioDelayMs: Int = 0,
    /** Server intro and recap markers: "button" (show Skip), "auto" (skip by itself) or "off". */
    val skipIntro: String = "button",
    /** Public IntroDB lookup only when server markers are absent; opt-in, and disabled together with Skip intro. */
    val introDb: Boolean = false,
    /** With a resume point: "ask" (Resume or Start over), "resume" or "start" (always from the beginning). */
    val resume: String = "ask",
    /** Shows start the next episode with the audio language and subtitles chosen last time. */
    val rememberTracks: Boolean = true,
    /** Keep playing in a small window when you press Home (where the TV supports picture-in-picture). */
    val pip: Boolean = false,
    /** Back during a video asks "Stop watching?" first; Back again (or Stop) leaves. */
    val confirmExit: Boolean = false,
    /** After leaving a video, Ember Home offers "Back to …" (Play key or Quick settings) for a few hours. */
    val quickReturn: Boolean = true,
    /** The key for "Back to …" on Home: "play" (Play or Play/Pause) or "back" (Back twice quickly). */
    val returnKey: String = "play",
    /** EXPERIMENTAL: play YouTube trailers in the built-in player (NewPipe Extractor), falling back to the YouTube app. */
    val trailersInApp: Boolean = false,
    /** Picture on screen (player Options > Video > Zoom): "fit", "crop", "stretch". */
    val zoom: String = "fit",
    /** Playback stats over the video: "off", "simple", "full". */
    val hud: String = "off",
    /** Both engines keep Ember controls; Automatic uses compatibility only where needed. */
    val engine: String = "auto",
    val deband: Boolean = false,
    val interpolation: Boolean = false,
    /** Compatibility engine: off (default), auto for interlaced frames, or always. */
    val deinterlacing: String = "off",
    val scaler: String = "bilinear",
)

@OptIn(UnstableApi::class)
object Playback {
    val FORMAT_LABELS = listOf(
        "ac3" to "Dolby Digital",
        "eac3" to "Dolby Digital Plus",
        "eac3joc" to "DD+ Atmos",
        "dts" to "DTS",
        "dtshd" to "DTS-HD / DTS:X",
        "truehd" to "Dolby TrueHD / Atmos",
    )

    private fun encoding(id: String): Int? = when (id) {
        "ac3" -> AudioFormat.ENCODING_AC3
        "eac3" -> AudioFormat.ENCODING_E_AC3
        "eac3joc" -> AudioFormat.ENCODING_E_AC3_JOC
        "dts" -> AudioFormat.ENCODING_DTS
        "dtshd" -> AudioFormat.ENCODING_DTS_HD
        "truehd" -> AudioFormat.ENCODING_DOLBY_TRUEHD
        else -> null
    }

    /** Which decoders to use; changed while playing when a decoder fails, then the player retries. */
    class CodecChoice {
        /** Decode Dolby Vision as its HDR10 (HEVC) base layer. */
        @Volatile var dvAsHevc: Boolean = false
        /** Try software video decoders first (only sensible up to 1080p). */
        @Volatile var preferSoftware: Boolean = false
        @Volatile var pcm: Boolean = false
        /** Let FFmpeg decode the video: after the TV's decoder failed, or always when set so. */
        @Volatile var ffmpegVideo: Boolean = false
    }

    /** True when the screen (or the receiver in between) reports Dolby Vision. */
    @Suppress("DEPRECATION")
    fun screenHasDolbyVision(activity: Activity): Boolean = runCatching {
        activity.windowManager.defaultDisplay.hdrCapabilities?.supportedHdrTypes
            ?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true
    }.getOrDefault(false)

    /** Sound timing shared with the audio output, changeable while playing. */
    class AudioDelay {
        @Volatile var us: Long = 0
    }

    /**
     * Shifts the audio clock that the picture follows: reporting the position [AudioDelay.us] ahead
     * shows each frame earlier, so the sound lands later relative to the picture (and the reverse).
     * Works for decoded and passthrough audio alike.
     */
    private class DelayedSink(sink: AudioSink, private val delay: AudioDelay, private val choice: CodecChoice) : androidx.media3.exoplayer.audio.ForwardingAudioSink(sink) {
        override fun supportsFormat(format: Format): Boolean = (!choice.pcm || format.sampleMimeType == MimeTypes.AUDIO_RAW) && super.supportsFormat(format)
        override fun getFormatSupport(format: Format): Int = if(choice.pcm && format.sampleMimeType != MimeTypes.AUDIO_RAW) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
            val p = super.getCurrentPositionUs(sourceEnded)
            return if (p == AudioSink.CURRENT_POSITION_NOT_SET) p else maxOf(0L, p + delay.us)
        }
    }

    /**
     * Renderers with Nextlib's FFmpeg audio decoders (TrueHD, DTS, AC3, E-AC3, FLAC, ALAC, MP3,
     * AAC, Opus, Vorbis) and passthrough control.
     */
    class Renderers(
        context: Context,
        private val c: PlaybackConfig,
        private val delay: AudioDelay = AudioDelay(),
        private val codecs: CodecChoice = CodecChoice(),
        private val manager: DecoderManager = DecoderManager(),
    ) : NextRenderersFactory(context) {
        init {
            setDecoderManager(manager)
            setExtensionRendererMode(
                when (c.ffmpeg) {
                    "prefer" -> EXTENSION_RENDERER_MODE_PREFER
                    "off" -> EXTENSION_RENDERER_MODE_OFF
                    else -> EXTENSION_RENDERER_MODE_ON
                },
            )
            // Lets Dolby Vision fall back to the HEVC (HDR10) layer, and any codec to a second decoder.
            setEnableDecoderFallback(true)
        }

        /** Nextlib owns extension ordering and recovery modes. The platform renderer additionally
         * validates the DV profile before offering an HDR10 base layer, and respects that mode.
         */
        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: ArrayList<Renderer>,
        ) {
            val start = out.size
            val mode = if(c.ffmpegVideo == "off") EXTENSION_RENDERER_MODE_OFF else EXTENSION_RENDERER_MODE_ON
            super.buildVideoRenderers(context,mode,mediaCodecSelector,enableDecoderFallback,eventHandler,eventListener,allowedVideoJoiningTimeMs,out)
            for(i in start until out.size) if(out[i] is androidx.media3.exoplayer.video.MediaCodecVideoRenderer) {
                out[i] = object : androidx.media3.exoplayer.video.MediaCodecVideoRenderer(context,mediaCodecSelector,allowedVideoJoiningTimeMs,true,eventHandler,eventListener,50) {
                    override fun getDecoderInfos(selector: MediaCodecSelector, format: Format, secure: Boolean): List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
                        val list = if(codecs.dvAsHevc && format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) {
                            if(!DolbyVisionPolicy.hdr10Base(format.codecs,format.colorInfo?.let { it.hdrStaticInfo!=null && it.colorTransfer==C.COLOR_TRANSFER_ST2084 && it.colorSpace==C.COLOR_SPACE_BT2020 }==true)) emptyList()
                            else selector.getDecoderInfos(MimeTypes.VIDEO_H265,secure,false)
                        } else super.getDecoderInfos(selector,format,secure)
                        return when(manager.videoMode) {
                            DecoderMode.HARDWARE -> list.filter { it.hardwareAccelerated }
                            DecoderMode.SOFTWARE -> list.filter { it.softwareOnly }
                            DecoderMode.FFMPEG -> emptyList()
                            else -> list
                        }
                    }
                }
            }
        }

        /** Nextlib exposes embedded subtitle delay/speed while preserving Media3 cues. */
        override fun buildTextRenderers(
            context: Context,
            output: TextOutput,
            outputLooper: Looper,
            extensionRendererMode: Int,
            out: ArrayList<Renderer>,
        ) {
            out.add(NextTextRenderer(output, outputLooper))
        }

        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
            val b = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            when {
                // PCM only: everything is decoded on the TV.
                !c.passthrough -> b.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                c.passthroughMode == "manual" -> {
                    val enc = (listOf(AudioFormat.ENCODING_PCM_16BIT) + c.formats.mapNotNull { encoding(it) }).toIntArray()
                    @Suppress("DEPRECATION")
                    b.setAudioCapabilities(AudioCapabilities(enc, 8))
                }
                // auto: the sink follows what the HDMI link reports, and updates when it changes.
            }
            return DelayedSink(b.build(), delay, codecs)
        }
    }

    fun loadControl(c: PlaybackConfig, context: Context): DefaultLoadControl {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memory = android.app.ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val bytes = PlaybackBuffer.bytes(c.buffer, Runtime.getRuntime().maxMemory(), manager.isLowRamDevice || memory.lowMemory)
        val min = if(c.buffer == "normal") 30_000 else if(c.buffer == "huge") 60_000 else 50_000
        val max = if(c.buffer == "normal") 60_000 else if(c.buffer == "huge") 240_000 else 120_000
        return DefaultLoadControl.Builder().setBufferDurationsMs(min,max,2_500,5_000)
            .setTargetBufferBytes(bytes).setPrioritizeTimeOverSizeThresholds(false).build()
    }

    // ---- audio track choice ----

    /** Higher is better: object audio and lossless first, then lossy surround, then stereo. */
    fun audioScore(f: Format): Int {
        val mime = f.sampleMimeType.orEmpty()
        val codecs = f.codecs.orEmpty()
        val base = when {
            mime == MimeTypes.AUDIO_TRUEHD -> 90
            mime == MimeTypes.AUDIO_E_AC3_JOC || codecs.contains("ec+3") -> 88
            mime == MimeTypes.AUDIO_DTS_X -> 86
            mime == MimeTypes.AUDIO_DTS_HD -> 84
            mime == MimeTypes.AUDIO_FLAC || mime == MimeTypes.AUDIO_RAW || mime == MimeTypes.AUDIO_ALAC -> 80
            mime == MimeTypes.AUDIO_DTS -> 60
            mime == MimeTypes.AUDIO_E_AC3 -> 55
            mime == MimeTypes.AUDIO_AC3 -> 50
            mime == MimeTypes.AUDIO_OPUS -> 40
            mime == MimeTypes.AUDIO_AAC -> 30
            else -> 20
        }
        val label = (f.label.orEmpty()).lowercase()
        val commentary = if ("comment" in label) -100 else 0
        return base * 10 + f.channelCount.coerceIn(0, 9) + commentary
    }

    /** Picks the audio track to start with, or null to keep the player's choice. */
    /** [language]: the show's remembered audio language, which wins over the setting when the file has it. */
    fun bestAudio(tracks: Tracks, c: PlaybackConfig, language: String? = null): TrackSelectionOverride? {
        if (c.audioPick == "default" && language == null) return null
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val options = groups.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it, true) }.map { g to it } }
        if (options.size < 2) return null
        val lang = Locale.getDefault().language
        val remembered = language?.let { want ->
            options.filter { (g, i) -> g.getTrackFormat(i).language?.substringBefore('-')?.lowercase() == want.substringBefore('-').lowercase() }
        }.orEmpty()
        val pool = if (remembered.isNotEmpty()) remembered else if (c.audioPick == "language") {
            options.filter { (g, i) -> g.getTrackFormat(i).language?.startsWith(lang) == true }.ifEmpty { options }
        } else {
            options
        }
        val (g, i) = pool.maxByOrNull { (g, i) -> audioScore(g.getTrackFormat(i)) } ?: return null
        return TrackSelectionOverride(g.mediaTrackGroup, i)
    }

    // ---- display mode (refresh rate and resolution) ----

    /**
     * Picks a display mode for the video: a refresh rate that is a whole multiple of the frame rate
     * (23.976 → 23.976 or 47.95 Hz; 25 → 50 Hz), at the current resolution unless [matchResolution].
     * Returns null when nothing better than the current mode exists.
     */
    fun modeFor(display: Display, fps: Float, width: Int, height: Int, matchResolution: Boolean): Display.Mode? {
        if (fps <= 0f) return null
        val current = display.mode
        val modes = display.supportedModes.toList()
        val sameSize = if (matchResolution && width > 0 && height > 0) {
            // Smallest mode that holds the video, e.g. 1080p for 1080p video, 2160p for 4K.
            val fits = modes.filter { it.physicalWidth >= width * 0.95 && it.physicalHeight >= height * 0.95 }
            val minW = fits.minOfOrNull { it.physicalWidth } ?: current.physicalWidth
            modes.filter { it.physicalWidth == minW }
        } else {
            modes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        }
        fun multiple(rate: Float): Int? {
            val k = Math.round(rate / fps)
            return if (k >= 1 && kotlin.math.abs(rate - k * fps) < 0.02f * k) k else null
        }
        val best = sameSize.mapNotNull { m -> multiple(m.refreshRate)?.let { k -> m to k } }
            .minByOrNull { (_, k) -> k } // the lowest multiple: 24 Hz beats 48 Hz beats 120 Hz
            ?.first
            ?: return if (matchResolution) sameSize.maxByOrNull { it.refreshRate }?.takeIf { it.modeId != current.modeId } else null
        return best.takeIf { it.modeId != current.modeId }
    }

    fun applyMode(activity: Activity, mode: Display.Mode?) {
        mode ?: return
        val lp = activity.window.attributes
        lp.preferredDisplayModeId = mode.modeId
        activity.window.attributes = lp
    }

    // ---- info panel ----

    fun describeVideo(f: Format?, decoder: String?, hdr10Plus: Boolean = false, unknown: Boolean = false): String {
        f ?: return "Video: none"
        val codec = PlaybackFormats.video(f.sampleMimeType, f.codecs)
        val hdr = PlaybackFormats.hdr(f.sampleMimeType, f.codecs, f.colorInfo?.colorTransfer, hdr10Plus, unknown)
        val fps = if (f.frameRate > 0) String.format(Locale.US, "%.3f fps", f.frameRate).replace(".000", "") else null
        val rate = f.bitrate.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f Mbps", it / 1_000_000.0) }
        return "Video: " + listOfNotNull(codec, "${f.width}×${f.height}", fps, hdr, rate).joinToString(" · ") +
            (decoder?.let { "\n  decoder $it" } ?: "")
    }

    /** A sound format's name, e.g. "Dolby TrueHD" or "DTS-HD". */
    fun audioName(f: Format): String = when (f.sampleMimeType) {
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus Atmos"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_DTS_X -> "DTS:X"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        else -> PlaybackFormats.audio(f.sampleMimeType) ?: f.sampleMimeType ?: "?"
    }

    fun describeAudio(f: Format?, decoder: String?): String {
        f ?: return "Audio: none"
        val mime = f.sampleMimeType
        val codec = when (mime) {
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus Atmos"
            MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_DTS_X -> "DTS:X"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_OPUS -> "Opus"
            else -> PlaybackFormats.audio(mime) ?: mime ?: "?"
        }
        val encoded = mime in setOf(
            MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_DTS_X, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS,
        )
        // Passthrough skips the decoder, so an encoded format with no decoder means bitstream output.
        val output = when {
            decoder == null && encoded -> "passthrough (bitstream to receiver)"
            decoder != null && decoder.contains("ffmpeg", true) -> "decoded by FFmpeg to PCM"
            decoder != null -> "decoded by $decoder to PCM"
            else -> null
        }
        val ch = when (f.channelCount) {
            8 -> "7.1"
            6 -> "5.1"
            2 -> "2.0"
            1 -> "mono"
            else -> if (f.channelCount > 0) "${f.channelCount} ch" else null
        }
        return "Audio: " + listOfNotNull(codec, ch, f.language?.uppercase(), f.label).joinToString(" · ") +
            (output?.let { "\n  $it" } ?: "")
    }
}
