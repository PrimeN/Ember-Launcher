package app.ember.tv.player

import android.content.Context
import android.media.AudioFormat
import androidx.core.content.edit

/**
 * #422: formats whose passthrough keeps failing on this TV. After two failures (an audio output
 * error, or the sound breaking up again and again) a format is not passed through any more:
 * DTS-HD is then sent as its DTS core, and anything else is decoded. Settings > Audio output
 * shows what was turned off and can try again.
 */
object PassthroughHealth {
    private const val FILE = "ember_audio_health"
    private const val FAILURES_TO_BLOCK = 2

    /** The format ids used by settings ("dtshd", "truehd"…) for an Android encoding. */
    fun id(encoding: Int): String? = when (encoding) {
        AudioFormat.ENCODING_AC3 -> "ac3"
        AudioFormat.ENCODING_E_AC3 -> "eac3"
        AudioFormat.ENCODING_E_AC3_JOC -> "eac3joc"
        AudioFormat.ENCODING_DTS -> "dts"
        AudioFormat.ENCODING_DTS_HD, ENCODING_DTS_UHD_P2 -> "dtshd"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "truehd"
        else -> null
    }

    fun blocked(context: Context): Set<String> = runCatching {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.all.filter { (k, v) -> k.startsWith("fail.") && (v as? Int ?: 0) >= FAILURES_TO_BLOCK }.keys.map { it.removePrefix("fail.") }.toSet()
    }.getOrDefault(emptySet())

    /** Counts one failure; true when this one turned the format's passthrough off. */
    fun record(context: Context, id: String): Boolean {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val count = prefs.getInt("fail.$id", 0) + 1
        prefs.edit { putInt("fail.$id", count) }
        return count == FAILURES_TO_BLOCK
    }

    fun reset(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { clear() }
    }

    /** android.media.AudioFormat.ENCODING_DTS_UHD_P2 (API 34). */
    private const val ENCODING_DTS_UHD_P2 = 30

    /** Sound breaking up this often while passed through counts as a failure (#422). */
    const val UNDERRUN_STORM = 8
    const val UNDERRUN_WINDOW_MS = 10_000L
}
