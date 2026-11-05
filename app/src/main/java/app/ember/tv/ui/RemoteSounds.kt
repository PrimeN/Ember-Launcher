package app.ember.tv.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.KeyEvent
import app.ember.tv.R

/**
 * Optional clicks for the remote's arrows and OK, in every Ember screen and menu (not the player).
 * Ember plays its own short sounds: many TVs ship with system sound effects switched off or missing,
 * so View.playSoundEffect stays silent there.
 */
internal object RemoteSounds {
    @Volatile var enabled = false
        private set
    private var pool: SoundPool? = null
    private var move = 0
    private var select = 0
    private val loaded = HashSet<Int>()

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) release()
    }

    /** Loads the sounds ahead of the first press, so it is not silent. */
    fun warm(context: Context) { if (enabled) prepare(context) }

    /** Main thread. Returns nothing and never consumes the key. */
    fun onKey(context: Context, event: KeyEvent) {
        if (!enabled || event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        val selecting = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> false
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> true
            else -> return
        }
        val sounds = prepare(context) ?: return
        val sound = if (selecting) select else move
        if (sound != 0 && synchronized(loaded) { sound in loaded }) sounds.play(sound, VOLUME, VOLUME, 1, 0, 1f)
    }

    private fun prepare(context: Context): SoundPool? {
        pool?.let { return it }
        return runCatching {
            SoundPool.Builder().setMaxStreams(2).setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build().also { created ->
                created.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
                val app = context.applicationContext
                move = created.load(app, R.raw.remote_move, 1)
                select = created.load(app, R.raw.remote_select, 1)
                pool = created
            }
        }.getOrNull()
    }

    private fun release() {
        pool?.release(); pool = null
        synchronized(loaded) { loaded.clear() }
        move = 0; select = 0
    }

    private const val VOLUME = .35f
}
