package app.ember.tv.player

import android.content.Context
import android.view.SurfaceView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** ListenerSet compares adapters during removal, so Object methods must use proxy identity. */
internal fun playbackListener(listener: Player.Listener, player: () -> Player): Player.Listener =
    Proxy.newProxyInstance(Player.Listener::class.java.classLoader, arrayOf(Player.Listener::class.java)) { proxy, callback, values ->
        try {
            when (callback.name) {
                "equals" -> proxy === values?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "EmberPlaybackListener"
                "onEvents" -> { listener.onEvents(player(), values!![1] as Player.Events); null }
                else -> callback.invoke(listener, *(values ?: emptyArray()))
            }
        } catch (e: InvocationTargetException) { throw e.targetException }
    } as Player.Listener

/** One public player identity. Backend changes retain controls, position and reporting owners. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackEngine(private val context:Context,val standard:ExoPlayer,private val config:()->PlaybackConfig,private val changed:(Boolean)->Unit) {
    private var active:Player=standard
    private val listeners=linkedMapOf<Player.Listener,Player.Listener>()
    private var surfaceMethod:Method?=null
    private var surface:Any?=null
    private var source:EngineSource?=null
    private var attempted=false
    private var mode=config().engine
    val compatibility get()=active as? MpvPlayer
    private fun key(method:Method)=method.name+method.parameterTypes.joinToString {it.name}
    private val common=Player::class.java.methods.associateBy(::key)
    val player:ExoPlayer=Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,arrayOf(ExoPlayer::class.java)) { proxy,method,args ->
        val arguments=args?:emptyArray()
        try {
            when(method.name) {
                "equals" -> proxy===arguments.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "EmberPlaybackEngine"
                "addListener" -> {val listener=arguments[0] as Player.Listener
                    val adapter=listeners.getOrPut(listener) {playbackListener(listener) {player}};active.addListener(adapter);null}
                "removeListener" -> {listeners.remove(arguments[0])?.let(active::removeListener);null}
                // Drop the released libmpv instance: native calls on a destroyed handle crash the process.
                "release" -> {listeners.values.forEach(active::removeListener);compatibility?.release();active=standard;standard.release();listeners.clear();null}
                "prepare" -> {if(active===standard && (mode=="compatibility" || mode=="auto" && source?.urls?.firstOrNull()?.let {runCatching {PlaybackEnginePolicy.nativeFirst(it)}.getOrDefault(false)}==true)) {
                        if(!switch())standard.prepare()
                    } else active.prepare();null}
                "getVideoFormat" -> compatibility?.currentTracks?.groups?.firstOrNull {it.type==androidx.media3.common.C.TRACK_TYPE_VIDEO&&it.isSelected}?.getTrackFormat(0) ?: if(active===standard)standard.videoFormat else null
                "getAudioFormat" -> compatibility?.currentTracks?.groups?.firstOrNull {it.type==androidx.media3.common.C.TRACK_TYPE_AUDIO&&it.isSelected}?.getTrackFormat(0) ?: if(active===standard)standard.audioFormat else null
                else -> {
                    if(method.name in setOf("setVideoSurface","setVideoSurfaceView","setVideoSurfaceHolder","setVideoTextureView")) {surfaceMethod=common[key(method)];surface=arguments.firstOrNull()}
                    if(method.name.startsWith("clearVideo")){surfaceMethod=null;surface=null}
                    val target=if(active!==standard)common[key(method)] else null
                    if(target!=null)target.invoke(active,*arguments) else method.invoke(standard,*arguments)
                }
            }
        } catch(e:InvocationTargetException){throw e.targetException}
    } as ExoPlayer
    fun configure(value:EngineSource) {
        val old=compatibility
        if(old!=null){listeners.values.forEach(old::removeListener);old.release();active=standard;listeners.values.forEach(standard::addListener)
            surfaceMethod?.let {method->surface?.let {method.invoke(standard,it)}};changed(false)}
        source=value;attempted=false
    }
    fun mode(value:String) {
        mode=value
        if(value=="compatibility") {attempted=false;switch()}
        else if(value=="standard" && compatibility!=null) {
            val at=active.currentPosition;val wanted=active.playWhenReady;val current=source?:return
            configure(current);standard.seekTo(at);standard.prepare();standard.playWhenReady=wanted
        }
    }
    fun recover(code:Int):Boolean = mode!="standard" && compatibility==null && PlaybackEnginePolicy.compatibilityError(code) && switch()
    fun switch():Boolean {
        if(attempted||compatibility!=null)return false
        val current=source?:return false
        attempted=true
        val position=standard.currentPosition.coerceAtLeast(0)
        val desired=standard.playWhenReady
        val native=try {MpvPlayer(context,current.copy(durationMs=current.durationMs ?: standard.duration.takeIf {it>0&&current.urls.size==1}),config(),position,standard.trackSelectionParameters)} catch(_:Exception){return false} catch(_:LinkageError){return false}
        listeners.values.forEach(standard::removeListener)
        standard.clearVideoSurface();standard.stop()
        active=native;listeners.values.forEach(native::addListener)
        surfaceMethod?.let {method->surface?.let {method.invoke(native,it)}}
        native.playWhenReady=desired
        native.playbackParameters=standard.playbackParameters
        native.volume=standard.volume
        native.prepare();changed(true);return true
    }
}
