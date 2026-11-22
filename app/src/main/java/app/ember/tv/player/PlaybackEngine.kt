package app.ember.tv.player

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import android.os.Handler
import android.os.Looper
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
internal class PlaybackEngine(private val context:Context,val standard:ExoPlayer,private val config:()->PlaybackConfig,
    private val compatibilityFactory:((EngineSource,Long,TrackSelectionParameters)->Player)?=null,
    private val automaticSwitchAllowed: () -> Boolean = { true },
    private val changed:(Boolean)->Unit) {
    private var active:Player=standard
    private val listeners=linkedMapOf<Player.Listener,Player.Listener>()
    private var surfaceMethod:Method?=null
    private var surface:Any?=null
    private var source:EngineSource?=null
    private var attempted=false
    private var mode=config().engine
    private var released=false
    private var health:DecoderHealth?=if(config().rememberDecoderChoices)DecoderHealth.current() else null
    private val handler=Handler(Looper.getMainLooper())
    private val seekFlush:Runnable=Runnable {navigation.flush()}
    private val navigation:NavigationSeekQueue=NavigationSeekQueue({handler.postDelayed(seekFlush,60)},{handler.removeCallbacks(seekFlush)}) {at,fast->
        if(!released)seekNow(at,fast)
    }
    private var stable:Runnable?=null
    private fun memoryKey()=source?.let {app.ember.tv.data.CachePolicy.digest("${it.key}|${it.filename.orEmpty()}|${it.audio!=null}")}
    private val stabilityListener=object:Player.Listener {
        override fun onIsPlayingChanged(isPlaying:Boolean) {
            stable?.let(handler::removeCallbacks);stable=null
            if(!isPlaying || !config().rememberDecoderChoices || mode!="auto")return
            val owner=active;val key=memoryKey()?:return
            stable=Runnable {
                stable=null
                if(!released && active===owner && owner.isPlaying && memoryKey()==key && mode=="auto" && config().rememberDecoderChoices)
                    health?.rememberEngine(key,if(owner===standard)"standard" else "compatibility")
            }.also {handler.postDelayed(it,15_000)}
        }
    }
    init {
        standard.addListener(stabilityListener)
        if(config().rememberDecoderChoices && health==null)DecoderHealth.loadAsync(context) {if(!released){health=it;if(active.isPlaying)stabilityListener.onIsPlayingChanged(true)}}
    }
    val compatibility get()=if(active===standard)null else active as? MpvPlayer
    internal fun snapshot():EngineSource?=source
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
                "release" -> {released=true;navigation.cancel();stable?.let(handler::removeCallbacks);active.removeListener(stabilityListener);listeners.values.forEach(active::removeListener);if(active!==standard)active.release();active=standard;standard.release();listeners.clear();null}
                "getCurrentPosition", "getContentPosition" -> navigation.target ?: if(method.name=="getContentPosition")active.contentPosition else active.currentPosition
                "seekBack", "seekForward" -> {
                    val delta=if(method.name=="seekBack")-active.seekBackIncrement else active.seekForwardIncrement
                    navigate(PlaybackSeeking.target(navigation.target?:active.currentPosition,delta,active.duration),SeekPreference.fast(config().seekMode));null
                }
                "prepare" -> {val current=source
                    val learned=if(config().rememberDecoderChoices)memoryKey()?.let {health?.engine(it)} else null
                    val automatic=current?.urls?.firstOrNull()?.let {PlaybackEnginePolicy.nativeFirst(it,current.filename)}==true
                    if(active===standard && (mode=="compatibility" || mode=="auto" && (learned=="compatibility" || learned==null&&automatic))) {
                        if(!switch(automatic = mode == "auto"))standard.prepare()
                    } else active.prepare();null}
                "getVideoFormat" -> compatibility?.currentTracks?.groups?.firstOrNull {it.type==androidx.media3.common.C.TRACK_TYPE_VIDEO&&it.isSelected}?.getTrackFormat(0) ?: if(active===standard)standard.videoFormat else null
                "getAudioFormat" -> compatibility?.currentTracks?.groups?.firstOrNull {it.type==androidx.media3.common.C.TRACK_TYPE_AUDIO&&it.isSelected}?.getTrackFormat(0) ?: if(active===standard)standard.audioFormat else null
                else -> {
                    if(method.name in setOf("seekTo","seekToDefaultPosition","seekToNextMediaItem","seekToPreviousMediaItem","setMediaItem","setMediaItems","setMediaSource","setMediaSources","replaceMediaItem","replaceMediaItems","removeMediaItem","removeMediaItems","seekToNext","seekToPrevious","clearMediaItems","stop"))navigation.cancel()
                    if(method.name in setOf("setVideoSurface","setVideoSurfaceView","setVideoSurfaceHolder","setVideoTextureView")) {surfaceMethod=common[key(method)];surface=arguments.firstOrNull()}
                    if(method.name.startsWith("clearVideo")){surfaceMethod=null;surface=null}
                    val target=if(active!==standard)common[key(method)] else null
                    if(target!=null)target.invoke(active,*arguments) else method.invoke(standard,*arguments)
                }
            }
        } catch(e:InvocationTargetException){throw e.targetException}
    } as ExoPlayer
    fun navigate(target: Long, fast: Boolean, coalesce:Boolean=true) {
        val bounded=target.coerceIn(0,active.duration.takeIf {it>0}?:Long.MAX_VALUE)
        if(coalesce)navigation.request(bounded,fast) else {navigation.cancel();seekNow(bounded,fast)}
    }
    fun flushNavigation(){navigation.flush()}
    private fun seekNow(target:Long,fast:Boolean) {
        compatibility?.let { it.seekNavigation(target,fast);return }
        PlaybackSeeking.seek(standard,target,fast)
    }
    fun configure(value:EngineSource) {
        navigation.cancel()
        stable?.let(handler::removeCallbacks);stable=null
        val old=active.takeUnless {it===standard}
        if(old!=null){old.removeListener(stabilityListener);listeners.values.forEach(old::removeListener);old.release();active=standard;standard.addListener(stabilityListener);listeners.values.forEach(standard::addListener)
            surfaceMethod?.let {method->surface?.let {method.invoke(standard,it)}};changed(false)}
        source=value;attempted=false
    }
    fun mode(value:String) {
        mode=value
        if(value=="compatibility") {attempted=false;switch()}
        else if(value=="standard" && compatibility!=null) {
            val at=navigation.target?:active.currentPosition;val wanted=active.playWhenReady;val current=source?:return
            configure(current);standard.seekTo(at);standard.prepare();standard.playWhenReady=wanted
        }
    }
    fun recover(code:Int):Boolean {
        if(!PlaybackEnginePolicy.compatibilityError(code))return false
        if(mode=="auto")memoryKey()?.let {health?.forgetEngine(it)}
        if(mode=="standard" || active!==standard)return false
        return switch(automatic = true)
    }

    private fun canSwitchAutomatically(current: EngineSource): Boolean {
        val c = config()
        return automaticSwitchAllowed() && !c.tunneling && c.ffmpegVideo !in setOf("always", "off") &&
            c.ffmpeg !in setOf("prefer", "off") &&
            c.decoderRules.preference(current.urls.firstOrNull().orEmpty(), standard.videoFormat?.sampleMimeType,
                current.filename) in setOf("inherit", "auto")
    }

    fun switch(automatic: Boolean = false):Boolean {
        if(attempted||active!==standard)return false
        val current=source?:return false
        if (automatic && (mode != "auto" || !canSwitchAutomatically(current))) return false
        val audio=standard.audioFormat ?: standard.currentTracks.groups.firstOrNull {it.type==androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSelected}?.let {group->
            (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat)
        }
        if(!PlaybackEnginePolicy.preservesAudio(audio?.sampleMimeType,audio?.drmInitData!=null || standard.videoFormat?.drmInitData!=null))return false
        attempted=true
        val position=(navigation.target?:standard.currentPosition).coerceAtLeast(0)
        navigation.cancel()
        val desired=standard.playWhenReady
        val state=standard.playbackState
        val native=try {
            val input=current.copy(durationMs=current.durationMs ?: standard.duration.takeIf {it>0&&current.urls.size==1})
            compatibilityFactory?.invoke(input,position,standard.trackSelectionParameters) ?: MpvPlayer(context,input,config(),position,standard.trackSelectionParameters)
        } catch(_:Exception){return false} catch(_:LinkageError){return false}
        try {
            standard.removeListener(stabilityListener);listeners.values.forEach(standard::removeListener)
            standard.clearVideoSurface();standard.stop()
            active=native;native.addListener(stabilityListener);listeners.values.forEach(native::addListener)
            surfaceMethod?.let {method->surface?.let {method.invoke(native,it)}}
            native.playWhenReady=desired
            native.playbackParameters=standard.playbackParameters
            native.volume=standard.volume
            native.prepare();changed(true);return true
        } catch(e:Throwable) {
            if(e !is Exception && e !is LinkageError)throw e
            stable?.let(handler::removeCallbacks);stable=null
            runCatching {native.removeListener(stabilityListener)};listeners.values.forEach {runCatching {native.removeListener(it)}}
            runCatching {native.release()};active=standard
            standard.addListener(stabilityListener);listeners.values.forEach(standard::addListener)
            runCatching {surfaceMethod?.let {method->surface?.let {method.invoke(standard,it)}}}
            standard.seekTo(position);standard.playWhenReady=desired
            if(state==Player.STATE_READY || state==Player.STATE_BUFFERING)runCatching {standard.prepare()}
            runCatching {changed(false)}
            return false
        }
    }
}
