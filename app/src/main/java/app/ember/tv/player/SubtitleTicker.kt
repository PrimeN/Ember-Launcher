package app.ember.tv.player

import android.os.Handler

/**
 * Draws Ember's own subtitles and keeps a repeat range: periodically only while something is playing that
 * needs it, and once on [refresh] (a seek, a pause, a new file or a timing change) otherwise.
 */
internal class SubtitleTicker(private val handler:Handler,private val enabled:()->Boolean,
    private val periodic:()->Boolean,private val intervalMs: () -> Long = { 100L }, private val render:()->Unit):Runnable {
    override fun run() {
        if(!enabled())return
        render()
        if(periodic())handler.postDelayed(this,intervalMs())
    }
    fun refresh(){handler.removeCallbacks(this);run()}
}
