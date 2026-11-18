package app.ember.tv.player

import android.view.KeyEvent

/** Remote buttons with one meaning during playback: Captions, Audio, Stop, Space and 0–9. */
internal object PlaybackKeys {
    enum class Action { CAPTIONS, AUDIO, STOP, TOGGLE, PERCENT }

    fun action(code: Int): Action? = when (code) {
        KeyEvent.KEYCODE_CAPTIONS -> Action.CAPTIONS
        KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> Action.AUDIO
        KeyEvent.KEYCODE_MEDIA_STOP -> Action.STOP
        KeyEvent.KEYCODE_SPACE -> Action.TOGGLE
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9, in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> Action.PERCENT
        else -> null
    }

    fun digit(code: Int) = if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) code - KeyEvent.KEYCODE_0 else code - KeyEvent.KEYCODE_NUMPAD_0

    /** 0 is the start, 5 the middle, 9 nine tenths in. */
    fun position(duration: Long, digit: Int): Long? = if (duration > 0 && digit in 0..9) duration / 10 * digit else null
}

/** True when the key was one of [PlaybackKeys]; digits are left to Live TV, where they pick a channel. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.playbackKey(event: KeyEvent): Boolean {
    if (event.isAltPressed || event.isCtrlPressed || event.isMetaPressed || currentFocus?.onCheckIsTextEditor() == true ||
        panelVisible || advanced && controls.menu != null) return false
    val action = PlaybackKeys.action(event.keyCode) ?: return false
    if (action == PlaybackKeys.Action.PERCENT && (live || trailer)) return false
    if (action in setOf(PlaybackKeys.Action.CAPTIONS, PlaybackKeys.Action.AUDIO) && !(advanced && subsInitialized)) return false
    val p = player ?: return false
    if (event.action != KeyEvent.ACTION_UP || event.isCanceled) return true
    when (action) {
        PlaybackKeys.Action.CAPTIONS -> openMenu("options", "subtitles")
        PlaybackKeys.Action.AUDIO -> openMenu("options", "audio")
        PlaybackKeys.Action.STOP -> endPlayback()
        PlaybackKeys.Action.TOGGLE -> { p.playWhenReady = !p.playWhenReady; showControls() }
        PlaybackKeys.Action.PERCENT -> if (p.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
            PlaybackKeys.position(p.duration, PlaybackKeys.digit(event.keyCode))?.let { navigateSeek(it); showControls() }
    }
    return true
}
