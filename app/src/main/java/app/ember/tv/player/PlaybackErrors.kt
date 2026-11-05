package app.ember.tv.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * How the player sorts a playback failure: what is worth retrying, and how to name the format that
 * failed. Shared by Live TV retries and decoder recovery in [PlayerActivity]; pure, so it is unit-tested.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object PlaybackErrors {
    private val connection = setOf(
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    )
    private val decoding = setOf(
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    )

    /** A dropped connection or a stalled link: retried from the same position. */
    fun connection(code: Int) = code in connection

    /** Live channels also retry a bad HTTP status, since providers often answer the next request. */
    fun liveNetwork(code: Int) = code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS || connection(code)

    /** The stream's container could not be read: a live link may work in its other form. */
    fun malformedContainer(code: Int) = code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
        code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED

    /** The decoder failed or cannot handle the format: another decoder may. */
    fun decoding(code: Int) = code in decoding

    /** The sound output failed (often pass-through the receiver refused): decoded PCM may work. */
    fun audioOutput(code: Int) = code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
        code == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED

    /**
     * The stream type a link names, for links ExoPlayer cannot tell from their extension: HLS for a
     * path with .m3u8 or a query asking for it (Xtream's output=m3u8), DASH for .mpd. Null lets the
     * player read the file itself.
     */
    fun mimeHint(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val path = parsed.encodedPath.lowercase(java.util.Locale.ROOT)
        val formats = setOf("output", "format", "type", "mime", "mimetype", "content-type", "ext", "extension", "stream_type")
        val values = parsed.queryParameterNames.filter { it.lowercase(java.util.Locale.ROOT) in formats }
            .flatMap { parsed.queryParameterValues(it) }
            .mapNotNull { it?.lowercase(java.util.Locale.ROOT) }
        return when {
            path.endsWith(".m3u8") || values.any { it == "m3u8" || it == "hls" || it.endsWith(".m3u8") || it in setOf("application/x-mpegurl", "application/vnd.apple.mpegurl") } -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") || values.any { it == "mpd" || it == "dash" || it == "application/dash+xml" } -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    /** An unreadable file from a web link that named no type: live addons often serve HLS without .m3u8. */
    fun mayBeHls(code: Int, url: String?): Boolean =
        malformedContainer(code) && url != null && url.toHttpUrlOrNull() != null && mimeHint(url) == null

    /** IPTV links often exist as both .ts and .m3u8: the other form, or null when there is none. */
    fun alternateLiveUrl(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val file = parsed.encodedPathSegments.lastOrNull() ?: return null
        val alternate = when {
            file.endsWith(".ts", ignoreCase = true) -> file.dropLast(3) + ".m3u8"
            file.endsWith(".m3u8", ignoreCase = true) -> file.dropLast(5) + ".ts"
            else -> return null
        }
        return parsed.newBuilder().setEncodedPathSegment(parsed.encodedPathSegments.lastIndex, alternate).build().toString()
    }

    /** The failed format, e.g. "video HEVC dvhe.08.06 3840×2160", for the error message. */
    fun describe(f: Format?): String {
        if (f == null) return ""
        val kind = if (f.sampleMimeType?.let { MimeTypes.isAudio(it) } == true) "sound" else "video"
        return listOfNotNull(
            kind,
            f.sampleMimeType?.substringAfter('/')?.uppercase(),
            f.codecs,
            if (f.width > 0) "${f.width}×${f.height}" else null,
            if (f.channelCount > 0) "${f.channelCount} ch" else null,
        ).joinToString(" ")
    }

    /** "1:02:03" or "2:03". */
    fun clock(ms: Long): String {
        val t = ms / 1000
        return if (t >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", t / 3600, t % 3600 / 60, t % 60)
        else String.format(java.util.Locale.US, "%d:%02d", t / 60, t % 60)
    }
}
