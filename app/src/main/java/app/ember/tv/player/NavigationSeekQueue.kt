package app.ember.tv.player

/** A fixed window, rather than a trailing debounce: holding a key cannot starve playback. */
internal class NavigationSeekQueue(private val schedule:()->Unit,private val unschedule:()->Unit,
    private val apply:(Long,Boolean)->Unit) {
    var target:Long?=null;private set
    private var fast=false
    fun request(position:Long,approximate:Boolean) {
        val first=target==null
        target=position.coerceAtLeast(0);fast=approximate
        if(first)schedule()
    }
    fun flush() {val value=target?:return;val approximate=fast;target=null;unschedule();apply(value,approximate)}
    fun cancel(){target=null;unschedule()}
}
