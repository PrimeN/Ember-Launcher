package app.ember.tv.player

import androidx.core.content.edit

import android.content.Context

/**
 * Remembers the audio language and subtitle choice per show (Settings > Player), so the next
 * episode starts the same way: a dubbed track, subtitles in a language, or subtitles off.
 */
class TrackMemory(context: Context,scope:String=app.ember.tv.data.LocalActivity.SHARED) {
    private val prefix=app.ember.tv.data.LocalActivity.prefix(scope)
    private val sp = context.getSharedPreferences("trackMemory", Context.MODE_PRIVATE)

    /** [audio]: a language code; [subtitles]: "off" or a language code; null when not set. */
    data class Choice(val audio: String?, val subtitles: String?)

    fun get(show: String): Choice? {
        val a = sp.getString(prefix+"a:$show", null)
        val s = sp.getString(prefix+"s:$show", null)
        return if (a == null && s == null) null else Choice(a, s)
    }

    fun putAudio(show: String, language: String) = sp.edit { putString(prefix+"a:$show", language) }
    fun putSubtitles(show: String, choice: String) = sp.edit { putString(prefix+"s:$show", choice) }
    fun clear() {val keys=sp.all.keys.filter {key->if(prefix.isEmpty())key.startsWith("a:") || key.startsWith("s:") else key.startsWith(prefix)};sp.edit {keys.forEach {remove(it)}}}
}
