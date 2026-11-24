package app.ember.tv.player

import app.ember.tv.iptv.*
import app.ember.tv.R
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// PlayerActivity's playback statistics (Settings > Player > Stats).
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.rate(bits: Long): String = when {
    bits <= 0 -> "—"
    bits >= 1_000_000_000 -> String.format(Locale.US, "%.2f Gbps", bits / 1e9)
    bits >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bits / 1e6)
    else -> String.format(Locale.US, "%d kbps", bits / 1000)
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.containerName(p: ExoPlayer): String {
    val mime = p.currentMediaItem?.localConfiguration?.mimeType ?: p.videoFormat?.containerMimeType ?: p.audioFormat?.containerMimeType
    val path = p.currentMediaItem?.localConfiguration?.uri?.lastPathSegment.orEmpty().lowercase()
    return when {
        mime == MimeTypes.APPLICATION_M3U8 || path.endsWith(".m3u8") -> "HLS"
        mime == MimeTypes.APPLICATION_MPD || path.endsWith(".mpd") -> "DASH"
        mime == MimeTypes.VIDEO_MATROSKA || path.endsWith(".mkv") -> "Matroska / MKV"
        mime == MimeTypes.VIDEO_WEBM || path.endsWith(".webm") -> "WebM"
        mime == MimeTypes.VIDEO_MP2T || path.endsWith(".ts") -> "MPEG-TS"
        path.endsWith(".mov") -> getString(R.string.pl_quicktime_mov)
        mime == MimeTypes.VIDEO_MP4 || path.endsWith(".mp4") || path.endsWith(".m4v") -> "MP4"
        mime != null -> mime.substringAfter('/')
        else -> "—"
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.videoLine(f: Format?): String {
    f ?: return getString(R.string.ui_none)
    val codec = PlaybackFormats.video(f.sampleMimeType, f.codecs)
    val hdr = PlaybackFormats.hdr(f.sampleMimeType, f.codecs, f.colorInfo?.colorTransfer,
        hdr10PlusMetadata && playbackEngine?.compatibility == null, playbackEngine?.compatibility != null)
    val bitrate = listOf(f.bitrate, f.averageBitrate, f.peakBitrate).firstOrNull { it > 0 }?.let { rate(it.toLong()) }
    val fps = f.frameRate.takeIf { it > 0 }?.let { String.format(Locale.US, "%.3f", it).trimEnd('0').trimEnd('.') + " fps" }
    val depth = f.colorInfo?.lumaBitdepth?.takeIf { it > 0 }?.let { "$it-bit" }
    return listOfNotNull(codec, hdr, if (f.width > 0) "${f.width}×${f.height}" else null, bitrate, fps, depth).joinToString(", ")
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.audioLine(f: Format?): String {
    f ?: return getString(R.string.ui_none)
    val ch = when (f.channelCount) {
        1 -> "1.0"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"
        else -> if (f.channelCount > 0) "${f.channelCount} ch" else null
    }
    val hz = f.sampleRate.takeIf { it > 0 }?.let { "$it Hz" }
    val bitrate = listOf(f.bitrate, f.averageBitrate, f.peakBitrate).firstOrNull { it > 0 }?.let { rate(it.toLong()) }
    val out = when {
        passthrough() -> getString(R.string.pl_passthrough)
        playbackEngine?.compatibility != null ||
            standardAudioTrack?.let { androidx.media3.common.util.Util.isEncodingLinearPcm(it.encoding) } == true -> getString(R.string.pl_decoded)
        else -> null
    }
    return listOfNotNull(Playback.audioName(f), hz, ch, bitrate, out).joinToString(", ")
}

/**
 * #415: the stream info panel, one sample a second. Source, Video, Audio, Network and System
 * sections; a coloured dot marks what is healthy (green), getting close (amber) or a problem (red).
 * "Simple" shows the essentials; "Full" shows everything Ember can read.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.updateStats() {
    val p = player
    if (!advanced || p == null || config.hud == "off") {
        controls.stats = emptyList()
        return
    }
    val full = config.hud == "full"
    val now = android.os.SystemClock.elapsedRealtime()
    val st = statsState
    val mpv = playbackEngine?.compatibility
    val live = mpv?.liveStats()
    val rows = ArrayList<StatsRow>()
    fun row(group: StatsGroup, label: String, value: String?, dot: StatsDot = StatsDot.NONE) {
        if (!value.isNullOrBlank()) rows += StatsRow(group, label, value, dot)
    }
    val tracks = (mpv ?: p).currentTracks
    val video = if (mpv != null) SubtitleStyleProbe.selected(tracks, C.TRACK_TYPE_VIDEO) else p.videoFormat ?: SubtitleStyleProbe.selected(tracks, C.TRACK_TYPE_VIDEO)
    val audio = if (mpv != null) SubtitleStyleProbe.selected(tracks, C.TRACK_TYPE_AUDIO) else p.audioFormat ?: SubtitleStyleProbe.selected(tracks, C.TRACK_TYPE_AUDIO)
    val durationMs = (mpv ?: p).duration.takeIf { it > 0 && it != C.TIME_UNSET }

    // ---- Source
    val url = p.currentMediaItem?.localConfiguration?.uri
    if (full) row(StatsGroup.SOURCE, "Add-on", prevAddon)
    if (full) row(StatsGroup.SOURCE, "Provider", streamProvider)
    row(StatsGroup.SOURCE, "Server", streamTransfer.host ?: url?.host)
    val file = streamRelease?.takeIf { it.isNotBlank() } ?: url?.lastPathSegment?.takeIf { '.' in it }
    row(StatsGroup.SOURCE, "File", file)
    val size = streamDeclaredSize.takeIf { it > 0 } ?: streamTransfer.fileSize.takeIf { it > 0 }
    if (full) row(StatsGroup.SOURCE, "Container", containerName(p))
    row(StatsGroup.SOURCE, "Size", size?.let(StreamStatsFormat::bytes))
    val declared = video?.let { listOf(it.bitrate, it.averageBitrate, it.peakBitrate).firstOrNull { b -> b > 0 }?.toLong() }
    val fileBitrate = if (size != null && durationMs != null) size * 8_000 / durationMs else null
    row(StatsGroup.SOURCE, "File bitrate", fileBitrate?.let { StreamStatsFormat.mbps(it) + " average" } ?: declared?.let(StreamStatsFormat::mbps))

    // ---- Video
    row(StatsGroup.VIDEO, "Engine", if (mpv != null) "Compatibility (mpv) · ${live?.renderer}" else "Standard (Media3)")
    video?.let { f ->
        val depth = f.colorInfo?.lumaBitdepth?.takeIf { it > 0 }?.let { "$it-bit" }
        row(StatsGroup.VIDEO, "Video", listOfNotNull(
            if (f.width > 0) "${f.width}×${f.height}" else null,
            f.frameRate.takeIf { it > 0 }?.let { StreamStatsFormat.fps(it) + " fps" },
            PlaybackFormats.video(f.sampleMimeType, f.codecs), depth,
        ).joinToString(" · "))
        row(StatsGroup.VIDEO, "HDR", StreamStatsFormat.hdrKind(f, hdr10PlusMetadata && mpv == null))
        row(StatsGroup.VIDEO, "DV", StreamStatsFormat.dolbyVision(f.codecs))
        if (full) row(StatsGroup.VIDEO, "Mastering", StreamStatsFormat.mastering(f.colorInfo?.hdrStaticInfo))
        if (full) row(StatsGroup.VIDEO, "Codec", f.codecs)
    }
    if (video == null) row(StatsGroup.VIDEO, "Video", getString(R.string.ui_none))
    val tunnelled = mpv == null && config.tunneling
    row(StatsGroup.VIDEO, "Decoder", if (mpv != null) live?.hwdec?.ifBlank { "software" } ?: "software" else videoDecoder?.let { if (tunnelled) "$it · tunnelled" else it })
    val drops = if (mpv != null) live?.dropped ?: 0 else dropped
    if (st.dropped in 0 until drops) st.droppedAt = now
    st.dropped = drops
    if (tunnelled) row(StatsGroup.VIDEO, "Dropped", "Not counted while tunnelled")
    else row(StatsGroup.VIDEO, "Dropped", "$drops frames", st.recency(drops, st.droppedAt, now))
    val display = if (android.os.Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
    display?.mode?.let { m ->
        val fps = video?.frameRate?.takeIf { it > 0 }
        val single = runCatching { display.supportedModes.count { it.physicalWidth == m.physicalWidth && it.physicalHeight == m.physicalHeight } <= 1 }.getOrDefault(false)
        val dot = when {
            tunnelled || single || fps == null -> StatsDot.NONE
            StreamStatsFormat.refreshMatches(m.refreshRate, fps) -> StatsDot.GOOD
            config.frameRate == "off" -> StatsDot.NONE
            else -> StatsDot.WARN
        }
        row(StatsGroup.VIDEO, "Display", "${m.physicalWidth}×${m.physicalHeight} · " +
            String.format(Locale.US, "%.2f Hz", m.refreshRate) + if (single) " · 1 mode" else "", dot)
    }
    if (full) {
        @Suppress("DEPRECATION") val types = runCatching { windowManager.defaultDisplay.hdrCapabilities?.supportedHdrTypes }.getOrNull() ?: intArrayOf()
        row(StatsGroup.VIDEO, "TV supports", types.toList().mapNotNull { when (it) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> null } }
            .joinToString(", ").ifEmpty { "SDR (no HDR reported)" })
    }
    if (markersFromIntroDb && markers.isNotEmpty() && full) row(StatsGroup.VIDEO, "Skip markers", "IntroDB (community timings)")

    // ---- Audio
    audio?.let { f ->
        val output = when {
            mpv != null -> live?.audioOut?.let { if (it.startsWith("spdif", true)) "passthrough" else "decoded ($it)" }
            passthrough() -> "passthrough"
            standardAudioTrack != null -> "decoded to PCM" + (StreamStatsFormat.channels(Integer.bitCount(standardAudioTrack!!.channelConfig))?.let { " $it" } ?: "")
            else -> null
        }
        val dot = when {
            mpv == null && standardAudioTrack == null -> StatsDot.NONE
            codecs.pcm -> StatsDot.WARN
            else -> StatsDot.GOOD
        }
        row(StatsGroup.AUDIO, "Audio", listOfNotNull(Playback.audioName(f), StreamStatsFormat.channels(f.channelCount),
            f.sampleRate.takeIf { it > 0 }?.let { "${it / 1000} kHz" }, f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.takeIf { l -> l.isNotBlank() } }, output)
            .joinToString(" · "), dot)
        if (full && mpv == null) row(StatsGroup.AUDIO, "Decoder", audioDecoder ?: if (passthrough()) "none (sent to the receiver)" else null)
    }
    if (audio == null) row(StatsGroup.AUDIO, "Audio", getString(R.string.ui_none))
    if (mpv == null) {
        if (st.underrunsSeen in 0 until st.underruns) st.underrunsAt = now
        st.underrunsSeen = st.underruns
        row(StatsGroup.AUDIO, "Underruns", st.underruns.toString(), st.recency(st.underruns, st.underrunsAt, now))
    }
    row(StatsGroup.AUDIO, "Route", StreamStatsFormat.route(this))
    if (full && audioDelay.us != 0L) row(StatsGroup.AUDIO, "Lip sync", "${audioDelay.us / 1000} ms")

    // ---- Network
    val position = (mpv ?: p).currentPosition
    val aheadS = live?.aheadSeconds ?: ((p.bufferedPosition - p.currentPosition).coerceAtLeast(0) / 1000.0)
    val bufferDot = if (mpv == null && p.playbackState == androidx.media3.common.Player.STATE_BUFFERING) StatsDot.BAD
        else StreamStatsFormat.buffer(aheadS, position, durationMs ?: 0, if (mpv != null) position + (aheadS * 1000).toLong() else p.bufferedPosition)
    row(StatsGroup.NETWORK, "Buffer", String.format(Locale.US, "%.1f s ahead", aheadS), bufferDot)
    val speed = live?.speedBps ?: st.speed(streamTransfer.bytes.get(), p.isLoading, now) ?: loadSpeed.takeIf { it > 0 }
    speed?.let { row(StatsGroup.NETWORK, "Speed", StreamStatsFormat.mbps(it), StreamStatsFormat.speed(it, fileBitrate ?: declared)) }
    st.pingMs?.let { row(StatsGroup.NETWORK, "Server latency", "$it ms", StreamStatsFormat.ping(it)) }
    if (full && mpv == null) {
        if (st.loadErrorsSeen in 0 until st.loadErrors) st.loadErrorsAt = now
        st.loadErrorsSeen = st.loadErrors
        row(StatsGroup.NETWORK, "Load errors", st.loadErrors.toString(), st.recency(st.loadErrors, st.loadErrorsAt, now))
        if (bandwidth > 0) row(StatsGroup.NETWORK, "Estimate", StreamStatsFormat.mbps(bandwidth))
    }
    // The serving host's connect time, every 5 seconds, off the main thread.
    val host = streamTransfer.host ?: url?.host
    if (host != null && url?.scheme?.startsWith("http") == true && now - st.pingAt > 5_000) {
        st.pingAt = now
        val port = streamTransfer.port.takeIf { it > 0 } ?: if ((streamTransfer.scheme ?: url.scheme) == "https") 443 else 80
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { StreamStatsFormat.ping(host, port)?.let { st.pingMs = it } }
    }

    // ---- System
    if (full) {
        st.cpu.percent()?.let { row(StatsGroup.SYSTEM, "App CPU", "$it %", StreamStatsFormat.cpu(it)) }
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        val native = android.os.Debug.getNativeHeapAllocatedSize()
        row(StatsGroup.SYSTEM, "Memory", "Java heap ${used shr 20}/${rt.maxMemory() shr 20} MB · native heap ${native shr 20} MB",
            StreamStatsFormat.heap(used.toDouble() / rt.maxMemory()))
        val temp = StreamStatsFormat.chipTemperature()
        if (temp != null) row(StatsGroup.SYSTEM, "Chip temp", String.format(Locale.US, "%.1f °C", temp), StreamStatsFormat.temperature(temp))
        else if (android.os.Build.VERSION.SDK_INT >= 29) {
            val status = (getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus
            row(StatsGroup.SYSTEM, "Heat", listOf("Normal", "Light", "Moderate", "Severe", "Critical", "Emergency", "Shutdown").getOrElse(status) { "?" },
                when { status <= 1 -> StatsDot.GOOD; status == 2 -> StatsDot.WARN; else -> StatsDot.BAD })
        }
        StreamStatsFormat.cpuClock()?.let { (text, capped) -> row(StatsGroup.SYSTEM, "CPU clock", text, if (capped) StatsDot.WARN else StatsDot.GOOD) }
        val mem = android.app.ActivityManager.MemoryInfo().also { (getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }
        row(StatsGroup.SYSTEM, "Device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
            String.format(Locale.US, "%.1f GB RAM, %.1f GB free", mem.totalMem / 1e9, mem.availMem / 1e9), if (mem.lowMemory) StatsDot.BAD else StatsDot.NONE)
    }
    controls.stats = rows
}
