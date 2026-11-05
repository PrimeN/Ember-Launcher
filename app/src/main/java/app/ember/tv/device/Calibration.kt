package app.ember.tv.device

import androidx.core.content.edit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Display
import app.ember.tv.R
import kotlin.math.abs
import kotlin.math.floor

internal enum class CalibrationPattern(val label: Int, val instructions: Int, val fps: Int = 0) {
    SHARPNESS(R.string.cal_sharpness, R.string.cal_sharpness_help),
    OVERSCAN(R.string.cal_overscan, R.string.cal_overscan_help),
    BLACK(R.string.cal_black, R.string.cal_black_help),
    WHITE(R.string.cal_white, R.string.cal_white_help),
    GRAYSCALE(R.string.cal_gray, R.string.cal_gray_help),
    COLORS(R.string.cal_colors, R.string.cal_colors_help),
    JUDDER24(R.string.cal_judder24, R.string.cal_judder_help, 24),
    JUDDER60(R.string.cal_judder60, R.string.cal_judder_help, 60);
    fun neighbour(direction: Int) = entries[Math.floorMod(ordinal + direction, entries.size)]
}
internal data class CalibrationMode(val id: Int, val width: Int, val height: Int, val hz: Float)
internal object CalibrationMath {
    /** Prefer a real UHD output at the current cadence; never label an upscaled 1080p mode as 4K. */
    fun chooseMode(current: CalibrationMode, supported: List<CalibrationMode>): CalibrationMode = supported
        .filter { it.width >= it.height && it.width <= 4096 && it.height <= 2160 && it.width >= current.width.coerceAtMost(3840) &&
            abs(it.width.toDouble() / it.height - current.width.toDouble() / current.height) < .02 }
        .sortedWith(compareByDescending<CalibrationMode> { it.width.toLong() * it.height }
            .thenBy { abs(it.hz - current.hz) }).firstOrNull() ?: current
    /** Fixed-rate sample-and-hold animation exposes cadence judder without depending on CPU speed. */
    fun position(elapsedNanos: Long, fps: Int): Float {
        require(fps > 0)
        val frame = floor(elapsedNanos.coerceAtLeast(0) / 1_000_000_000.0 * fps).toLong()
        return (frame % (fps * 4)) / (fps * 4f)
    }
    /** Dolby Vision calibration must fail rather than silently selecting its HDR10/SDR base layer. */
    fun allowDecoder(kind: CalibrationClip, mime: String): Boolean =
        kind != CalibrationClip.DOLBY_VISION || !mime.startsWith("video/") || mime == "video/dolby-vision"
    fun clipMatches(kind: CalibrationClip, mime: String?, transfer: Int?): Boolean {
        if (mime?.startsWith("video/") != true) return false
        return when (kind) {
        CalibrationClip.DOLBY_VISION -> mime == "video/dolby-vision"
        CalibrationClip.HDR10 -> mime != "video/dolby-vision" && transfer == 6 // C.COLOR_TRANSFER_ST2084
        CalibrationClip.SDR -> mime != "video/dolby-vision" && transfer != 6 && transfer != 7
        }
    }
}
internal enum class CalibrationClip(val title: Int, val hdrType: Int?) {
    SDR(R.string.cal_video_sdr, null), HDR10(R.string.cal_video_hdr, Display.HdrCapabilities.HDR_TYPE_HDR10),
    DOLBY_VISION(R.string.cal_video_dv, Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)
}

/** Local document grants belong to this device, not a portable account/settings backup. */
internal class CalibrationClips(private val context: Context) {
    private val prefs = context.getSharedPreferences("device-calibration-clips", Context.MODE_PRIVATE)
    fun get(kind: CalibrationClip): Uri? = prefs.getString(kind.name, null)?.let(Uri::parse)?.takeIf { it.scheme == "content" }
    fun save(kind: CalibrationClip, uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val retained = runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
        if (!retained) return false
        prefs.edit { putString(kind.name, uri.toString()) }
        return true
    }
    fun remove(kind: CalibrationClip) {
        prefs.edit { remove(kind.name) }
    }
    // Android document grants are shared by all features. Removing this shortcut must not revoke
    // access retained by the regular file player or another open session for the same document.
}
