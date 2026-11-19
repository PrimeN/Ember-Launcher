package app.ember.tv.home

import android.app.Notification
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

/** One notification as quick settings shows it. */
internal data class TvNotification(
    val key:String,val pkg:String,val title:String,val text:String,val postedAt:Long,val clearable:Boolean,
    val open:android.app.PendingIntent?,
)

/**
 * Notifications for quick settings, read from the listener on demand. Nothing is kept: the list is read
 * again from Android each time it is shown or changes.
 */
internal object NotificationFeed {
    private var service:WeakReference<PlaybackAccessService>?=null
    private val revision=MutableStateFlow(0)
    /** Changes whenever a notification arrives or goes, so an open list reads again. */
    val changes:StateFlow<Int> get()=revision

    fun connected(s:PlaybackAccessService){service=WeakReference(s);changed()}
    fun disconnected(s:PlaybackAccessService){if(service?.get()===s)service=null;changed()}
    fun changed(){revision.value++}

    /** Current notifications from other apps, newest first; ongoing ones (downloads, media) and Ember's own are left out. */
    fun current(own:String):List<TvNotification> {
        val listener=service?.get() ?: return emptyList()
        val active=runCatching {listener.activeNotifications?.toList()}.getOrNull().orEmpty()
        return visible(active.mapNotNull {from(it)},own)
    }

    internal fun visible(items:List<Pair<TvNotification,Boolean>>,own:String):List<TvNotification> =
        items.filter {(n,ongoing)->!ongoing && n.pkg!=own && (n.title.isNotBlank() || n.text.isNotBlank())}
            .map {it.first}.distinctBy {it.key}.sortedByDescending {it.postedAt}.take(MAX)

    private fun from(sbn:StatusBarNotification):Pair<TvNotification,Boolean>? {
        val n=sbn.notification ?: return null
        // Group summaries repeat their children; private notifications show only that they exist.
        if(n.flags and Notification.FLAG_GROUP_SUMMARY!=0)return null
        val secret=n.visibility==Notification.VISIBILITY_SECRET
        val extras=n.extras
        val title=if(secret)"" else extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text=if(secret)"" else (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val ongoing=sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE!=0
        return TvNotification(sbn.key,sbn.packageName,title.take(120),text.take(400),sbn.postTime,sbn.isClearable,n.contentIntent) to ongoing
    }

    fun dismiss(keys:List<String>) {
        val listener=service?.get() ?: return
        runCatching {listener.cancelNotifications(keys.toTypedArray())}
    }

    const val MAX=12
}
