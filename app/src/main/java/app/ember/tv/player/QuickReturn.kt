package app.ember.tv.player

/** The last title played from Ember and the player it used ("builtin" or an app's package). */
data class LastPlay(val request: PlayRequest, val target: String, val at: Long)

/**
 * "Back to …": after a video is left part-way (Back, Home or another app), Ember Home offers to
 * return to the saved position with the remote's Play key or the first tile in Quick settings.
 */
object QuickReturn {
    /** Long enough for an errand or a phone call, short enough that stream links are still valid. */
    const val WINDOW_MS = 4 * 60 * 60_000L

    /** True while the title has a saved position (it was not finished) and the window is open. */
    fun available(last: LastPlay?, enabled: Boolean, now: Long, saved: Pair<Long, Long>?): Boolean =
        enabled && last != null && now - last.at in 0..WINDOW_MS && saved != null && saved.first > 0

    /** Off, the Play key, or Back twice: the same choice the setting shows. */
    val keys = listOf("play" to "Play button", "back" to "Press Back twice", "off" to "Off")
    fun key(config: PlaybackConfig) = if (!config.quickReturn) "off" else config.returnKey.takeIf { it == "play" || it == "back" } ?: "play"
    fun choose(config: PlaybackConfig, key: String) = if (key == "off") config.copy(quickReturn = false) else config.copy(quickReturn = true, returnKey = key)
    /** Two Back presses this close together count as a double press. */
    const val DOUBLE_PRESS_MS = 600L
    fun prompt(key: String) = if (key == "back") "Press Back twice to return to" else "Press Play to return to"

    /** The request that returns: the saved position, without asking Resume or Start over again. */
    fun request(last: LastPlay) = last.request.copy(startOver = false, resumeDecided = true)
}
