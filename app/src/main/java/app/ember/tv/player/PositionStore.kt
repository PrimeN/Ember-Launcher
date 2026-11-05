package app.ember.tv.player

import androidx.core.content.edit

import android.content.Context

/** Remembers where you stopped in the built-in player, per title or episode. */
class PositionStore(context: Context,scope:String=app.ember.tv.data.LocalActivity.SHARED) {
    private val prefix=app.ember.tv.data.LocalActivity.prefix(scope)
    private val sp = context.getSharedPreferences("positions", Context.MODE_PRIVATE)

    /** Position and duration in milliseconds, or null when nothing is saved. */
    fun get(key: String): Pair<Long, Long>? {
        val v = sp.getString(prefix+key, null) ?: return null
        val parts = v.split(',')
        val pos = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val dur = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return pos to dur
    }

    fun put(key: String, positionMs: Long, durationMs: Long) =
        sp.edit { putString(prefix+key, "$positionMs,$durationMs") }

    fun clear(key: String) = sp.edit { remove(prefix+key) }
}
