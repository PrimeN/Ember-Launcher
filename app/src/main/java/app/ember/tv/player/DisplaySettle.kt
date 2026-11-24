package app.ember.tv.player

import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * #427: after a display mode switch the TV re-syncs HDMI, and the box drops its HDMI sound link
 * meanwhile. Playback waits at least [MIN_MS], then until Android reports the link up again,
 * [EXTRA_MS] more at most. A box that never reports the link just waits the minimum.
 */
object DisplaySettle {
    const val MIN_MS = 2_000L
    const val EXTRA_MS = 3_000L
    private const val POLL_MS = 100L

    /** [hdmiAudio]: 1 up, 0 down, null when the box gives no report. */
    fun settled(sinceMs: Long, hdmiAudio: Int?): Boolean = when {
        sinceMs >= MIN_MS + EXTRA_MS -> true
        sinceMs < MIN_MS -> false
        else -> hdmiAudio != 0
    }

    suspend fun await(context: Context, requestedAt: Long) {
        while (true) {
            val since = SystemClock.elapsedRealtime() - requestedAt
            if (since < MIN_MS) { delay(MIN_MS - since); continue }
            if (settled(since, hdmiAudio(context))) return
            delay(POLL_MS)
        }
    }

    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun hdmiAudio(context: Context): Int? = runCatching {
        context.registerReceiver(null, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG))
            ?.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, -1)?.takeIf { it == 0 || it == 1 }
    }.getOrNull()
}
