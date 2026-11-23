package app.ember.tv.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
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
                "release" -> {released=true;navigation.cancel();cancelTrackRestore();stable?.let(handler::removeCallbacks);active.removeListener(stabilityListener);listeners.values.forEach(active::removeListener);if(active!==standard)active.release();active=standard;standard.release();listeners.clear();null}
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
                    if (active === standard && method.name == "setTrackSelectionParameters") cancelTrackRestore()
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
        cancelTrackRestore()
        stable?.let(handler::removeCallbacks);stable=null
        if (active !== standard) {
            returnToStandard()
            changed(false)
        }
        source=value;attempted=false
    }

    fun mode(value:String) {
        mode=value
        if(value=="compatibility" && active===standard) {
            attempted=false
            switch()
        } else if(value=="standard" && active!==standard) {
            val outgoing = active
            val position = navigation.target ?: outgoing.currentPosition
            val wanted = outgoing.playWhenReady
            val speed = outgoing.playbackParameters
            val volume = outgoing.volume
            val repeat = outgoing.repeatMode
            val parameters = selectedTracks(outgoing)
            navigation.cancel()
            stable?.let(handler::removeCallbacks);stable=null
            returnToStandard()
            standard.trackSelectionParameters = parameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
            restoreTrackSelection(parameters)
            standard.seekTo(position)
            standard.playbackParameters=speed
            standard.volume=volume
            standard.repeatMode=repeat
            standard.playWhenReady=wanted
            standard.prepare()
            changed(false)
        }
    }

    private fun returnToStandard() {
        active.removeListener(stabilityListener)
        listeners.values.forEach(active::removeListener)
        active.release()
        active=standard
        standard.addListener(stabilityListener)
        listeners.values.forEach(standard::addListener)
        surfaceMethod?.let { method -> surface?.let { method.invoke(standard,it) } }
    }

    private var trackRestore: Player.Listener? = null

    private fun cancelTrackRestore() {
        trackRestore?.let(standard::removeListener)
        trackRestore=null
    }

    private fun restoreTrackSelection(parameters: TrackSelectionParameters) {
        cancelTrackRestore()
        val overrides = parameters.overrides.values.filter { it.type == C.TRACK_TYPE_AUDIO || it.type == C.TRACK_TYPE_TEXT }.toMutableList()
        if (overrides.isEmpty()) return
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val ready = overrides.filter { override -> tracks.groups.any { it.type == override.type } }
                if (ready.isEmpty()) return
                overrides.removeAll(ready.toSet())
                if (overrides.isEmpty()) cancelTrackRestore()
                val builder = standard.trackSelectionParameters.buildUpon()
                for (override in ready) {
                    val target = override.trackIndices.firstOrNull()?.let(override.mediaTrackGroup::getFormat) ?: continue
                    matchingPlaybackTrack(tracks, override.type, target)?.let(builder::setOverrideForType)
                }
                standard.trackSelectionParameters=builder.build()
            }
        }
        trackRestore=listener
        standard.addListener(listener)
        listener.onTracksChanged(standard.currentTracks)
    }

    private fun selectedTracks(owner: Player): TrackSelectionParameters {
        val existing = owner.trackSelectionParameters
        return existing.buildUpon().also { builder ->
            for (type in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
                if (type in existing.disabledTrackTypes) continue
                val chosen = existing.overrides.values.firstOrNull { it.type == type }
                if (chosen != null && owner.currentTracks.groups.any { it.mediaTrackGroup == chosen.mediaTrackGroup }) continue
                val group = owner.currentTracks.groups.firstOrNull { it.type == type && it.isSelected } ?: continue
                val index = (0 until group.length).firstOrNull(group::isTrackSelected) ?: continue
                builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            }
        }.build()
    }

    fun recover(error: PlaybackException): Boolean {
        val mime = (error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?.sampleMimeType
        return recover(PlaybackFailureClassifier.classify(error), mime?.takeIf(MimeTypes::isVideo))
    }

    fun recover(code: Int): Boolean = recover(PlaybackFailureClassifier.classify(code))

    private fun recover(failure: PlaybackFailure, videoMime: String? = null): Boolean {
        if (mode != "auto" || active !== standard || !failure.changesCore) return false
        if (failure.changesDecoder) {
            memoryKey()?.let { if (health?.engine(it) == "standard") health?.forgetEngine(it) }
        }
        return switch(automatic = true, videoMime = videoMime, unreadable = failure == PlaybackFailure.EXTRACTOR)
    }

    private fun canSwitchAutomatically(current: EngineSource, videoMime: String?, unreadable: Boolean = false): Boolean {
        // #383: a file Standard cannot read at all (an MKV with zlib-compressed tracks) never reaches a
        // decoder, so decoder and tunneling preferences do not keep it from the engine that can.
        if (unreadable) return true
        val c = config()
        val mime = videoMime ?: standard.videoFormat?.sampleMimeType
            ?: standard.currentTracks.groups.firstNotNullOfOrNull { group ->
                if (group.type != C.TRACK_TYPE_VIDEO) null else (0 until group.length)
                    .firstOrNull(group::isTrackSelected)?.let { group.getTrackFormat(it).sampleMimeType }
            }
        if (mime == null && c.decoderRules.codecs.values.any { it == "hardware" || it == "software" }) return false
        return automaticSwitchAllowed() && !c.tunneling && c.ffmpegVideo !in setOf("always", "off") &&
            c.ffmpeg !in setOf("prefer", "off") &&
            c.decoderRules.preference(current.urls.firstOrNull().orEmpty(), mime,
                current.filename) in setOf("inherit", "auto")
    }

    fun switch(automatic: Boolean = false, videoMime: String? = null, unreadable: Boolean = false): Boolean {
        if(attempted||active!==standard)return false
        val current=source?:return false
        if (automatic && (mode != "auto" || !canSwitchAutomatically(current, videoMime, unreadable))) return false
        val parameters = selectedTracks(standard)
        val audio = if (C.TRACK_TYPE_AUDIO in parameters.disabledTrackTypes) null else {
            parameters.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }?.let {
                it.trackIndices.firstOrNull()?.let(it.mediaTrackGroup::getFormat)
            } ?: standard.audioFormat
        }
        if (!PlaybackEnginePolicy.preservesAudio(audio?.sampleMimeType, audio?.drmInitData != null ||
                standard.videoFormat?.drmInitData != null ||
                standard.currentMediaItem?.localConfiguration?.drmConfiguration != null)) return false
        attempted=true
        val position=(navigation.target?:standard.currentPosition).coerceAtLeast(0)
        val desired=standard.playWhenReady
        val state=standard.playbackState
        val speed=standard.playbackParameters
        val volume=standard.volume
        val repeat=standard.repeatMode
        val native=try {
            val input=current.copy(live = current.live || standard.isCurrentMediaItemLive, durationMs=current.durationMs ?: standard.duration.takeIf {it>0&&current.urls.size==1})
            compatibilityFactory?.invoke(input,position,parameters) ?: MpvPlayer(context,input,config(),position,parameters)
        } catch(_:Exception){return false} catch(_:LinkageError){return false}
        try {
            navigation.cancel()
            cancelTrackRestore()
            standard.removeListener(stabilityListener);listeners.values.forEach(standard::removeListener)
            standard.clearVideoSurface();standard.stop()
            active=native;native.addListener(stabilityListener);listeners.values.forEach(native::addListener)
            surfaceMethod?.let {method->surface?.let {method.invoke(native,it)}}
            native.playbackParameters=speed
            native.volume=volume
            native.repeatMode=repeat
            native.playWhenReady=desired
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

/** Match a selection across backends even when their track-group IDs differ. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun matchingPlaybackTrack(tracks: androidx.media3.common.Tracks, type: Int, target: Format): TrackSelectionOverride? {
    val language = app.ember.tv.data.Subtitles.normalise(target.language)
    val forced = target.selectionFlags and C.SELECTION_FLAG_FORCED
    val candidates = tracks.groups.filter { it.type == type }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it, true) }.map { group to it }
    }.filter { (group, index) ->
        val format = group.getTrackFormat(index)
        app.ember.tv.data.Subtitles.normalise(format.language) == language &&
            (format.selectionFlags and C.SELECTION_FLAG_FORCED) == forced
    }
    val match = candidates.firstOrNull { (group, index) -> group.getTrackFormat(index).id == target.id && target.id != null }
        ?: candidates.maxByOrNull { (group, index) ->
            val format = group.getTrackFormat(index)
            (if (target.label != null && target.label == format.label) 4 else 0) +
                (if (target.sampleMimeType == format.sampleMimeType) 2 else 0) +
                (if (target.channelCount > 0 && target.channelCount == format.channelCount) 1 else 0)
        }
    return match?.let { (group, index) -> TrackSelectionOverride(group.mediaTrackGroup, index) }
}
