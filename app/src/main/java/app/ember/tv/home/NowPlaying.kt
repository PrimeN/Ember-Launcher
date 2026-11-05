package app.ember.tv.home

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** The system owns the binding; Ember never reads notification text or stores session history. */
class PlaybackAccessService:NotificationListenerService()
internal data class NowPlaying(val pkg:String,val title:String,val playing:Boolean,val controller:MediaController)
internal object PlaybackAccess {
    fun component(c:Context)=ComponentName(c,PlaybackAccessService::class.java)
    fun granted(c:Context)=runCatching {c.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(c))}.getOrDefault(false)
}
@Composable internal fun rememberNowPlaying(enabled:Boolean,allowed:Set<String>):NowPlaying? {
    val c=LocalContext.current;val lifecycle=LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateAsState()
    val granted=remember(c,state,enabled){enabled && PlaybackAccess.granted(c)}
    var item by remember {mutableStateOf<NowPlaying?>(null)}
    LaunchedEffect(c,lifecycle,enabled,allowed,granted) {
        item=null
        if(enabled && granted)lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            callbackFlow {
                val manager=c.getSystemService(MediaSessionManager::class.java);val handler=Handler(Looper.getMainLooper())
                var controllers=emptyList<MediaController>()
                fun refresh(){
                    val best=controllers.filter {it.packageName in allowed}.sortedByDescending {it.playbackState?.state==PlaybackState.STATE_PLAYING}.firstOrNull()
                    trySend(best?.let {NowPlaying(it.packageName,(it.metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                        ?: it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Media playing").take(240),it.playbackState?.state==PlaybackState.STATE_PLAYING,it)})
                }
                val callback=object:MediaController.Callback(){override fun onMetadataChanged(metadata:MediaMetadata?)=refresh()
                    override fun onPlaybackStateChanged(state:PlaybackState?)=refresh();override fun onSessionDestroyed()=refresh()}
                fun replace(list:List<MediaController>) {controllers.forEach {runCatching {it.unregisterCallback(callback)}}
                    controllers=list.filter {it.packageName in allowed}.take(12);controllers.forEach {runCatching {it.registerCallback(callback,handler)}};refresh()}
                val listener=MediaSessionManager.OnActiveSessionsChangedListener {replace(it.orEmpty())}
                runCatching {manager.addOnActiveSessionsChangedListener(listener,PlaybackAccess.component(c),handler);replace(manager.getActiveSessions(PlaybackAccess.component(c)))}
                awaitClose {controllers.forEach {runCatching {it.unregisterCallback(callback)}};runCatching {manager.removeOnActiveSessionsChangedListener(listener)}}
            }.conflate().collect {item=it}
        }
    }
    return if(enabled && granted && item?.pkg in allowed)item else null
}
