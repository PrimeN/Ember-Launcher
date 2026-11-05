package app.ember.tv.iptv

import java.util.concurrent.atomic.AtomicReference

/** Removing an obsolete owner must never discard a concurrently registered replacement. */
internal class ActiveController<T:Any>(private val alive:(T)->Boolean) {
    private val current=AtomicReference<T?>(null)
    fun set(value:T?) {current.set(value)}
    fun get():T? {
        while(true) {
            val owner=current.get() ?: return null
            if(alive(owner))return owner
            if(current.compareAndSet(owner,null))return null
        }
    }
}

/** A caller can fall back to its own controller if the selected UI owner disappears. */
internal suspend fun <T:Any> refreshActiveController(owner:T?,alive:(T)->Boolean,refresh:suspend (T)->Unit):Boolean {
    if(owner==null || !alive(owner))return false
    refresh(owner)
    return alive(owner)
}
