package app.ember.tv.player

import app.ember.tv.iptv.*
import app.ember.tv.R
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

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
    val out = getString(if (passthrough()) R.string.pl_passthrough else R.string.pl_decoded)
    return listOfNotNull(Playback.audioName(f), hz, ch, bitrate, out).joinToString(", ")
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.updateStats() {
    val p = player
    if (!advanced || p == null || config.hud == "off") {
        controls.stats = emptyList()
        return
    }
    val out = ArrayList<Pair<String, String>>()
    val full = config.hud == "full"
    out += "Engine" to if(playbackEngine?.compatibility!=null)"Compatibility" else "Standard"
    playbackEngine?.compatibility?.diagnostics()?.forEach {(k,v)->if(k!="Engine")out+=k to v}
    if (full) out += getString(R.string.pl_container) to containerName(p)
    out += getString(R.string.pl_video) to videoLine(p.videoFormat)
    out += getString(R.string.pl_audio) to audioLine(p.audioFormat)
    if (markersFromIntroDb && markers.isNotEmpty()) out += "Skip markers" to "IntroDB (community timings)"
    if (full) {
        @Suppress("DEPRECATION") val types = runCatching { windowManager.defaultDisplay.hdrCapabilities?.supportedHdrTypes }.getOrNull() ?: intArrayOf()
        val supported = types.toList().mapNotNull { when(it) {1->"Dolby Vision";2->"HDR10";3->"HLG";4->"HDR10+";else->null} }
        out += "Display HDR capabilities" to supported.joinToString(", ").ifEmpty { "Not reported" }
        out += "HDR output" to "Display capability does not confirm the active output signal"
    }
    val ahead = ((p.bufferedPosition - p.currentPosition) / 1000).coerceAtLeast(0)
    val health = when {
        p.playbackState == Player.STATE_BUFFERING -> getString(R.string.pl_buffering)
        ahead >= 15 -> getString(R.string.pl_healthy)
        ahead >= 5 -> getString(R.string.pl_fair)
        else -> getString(R.string.pl_low)
    }
    out += getString(R.string.pl_buffer_status) to getString(R.string.pl_buffer_value, health, ahead)
    if (full) {
        out += getString(R.string.pl_network_throughput) to rate(bandwidth)
        out += getString(R.string.pl_stream_speed) to rate(loadSpeed)
        out += getString(R.string.pl_decoders) to listOfNotNull(videoDecoder, audioDecoder ?: if (passthrough()) getString(R.string.pl_passthrough) else null).joinToString(" · ").ifBlank { "—" }
        out += getString(R.string.pl_dropped_frames) to dropped.toString()
        val display = if (android.os.Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
        display?.mode?.let { m ->
            out += getString(R.string.pl_display) to "${m.physicalWidth}×${m.physicalHeight} @ " + String.format(Locale.US, "%.3f", m.refreshRate).trimEnd('0').trimEnd('.') + " Hz"
        }
        val mem = android.app.ActivityManager.MemoryInfo().also { (getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }
        out += getString(R.string.pl_device_info) to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / " + String.format(Locale.US, "%.2f GB", mem.totalMem / 1e9)
        runCatching {
            val st = android.os.StatFs(filesDir.path)
            out += getString(R.string.pl_free_disk_space) to String.format(Locale.US, "%.2f GiB / %.2f GiB", st.availableBytes / 1_073_741_824.0, st.totalBytes / 1_073_741_824.0)
        }
    }
    controls.stats = out
}
