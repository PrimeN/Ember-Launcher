package app.ember.tv.player

import androidx.core.net.toUri

import android.content.*
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.*
import android.view.*
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.jdtech.mpv.MPVLib
import java.io.File
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** libmpv exposed through Media3's Player contract, so Ember keeps its existing TV controls. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class MpvPlayer(private val context: Context, val source: EngineSource, private val config: PlaybackConfig,
    private val initialPosition: Long, initialParameters: TrackSelectionParameters) : SimpleBasePlayer(Looper.getMainLooper()), MPVLib.EventObserver {
    private val handler=Handler(Looper.getMainLooper())
    private var closed=false
    // #311: how Compatibility playback decodes video.
    private var requestedDecoderProfile=config.compatibilityDecoder
    private var allowHi10p=config.hi10pFallback
    private var appliedDecoder:NativeDecoderChoice?=null
    private var manualSoftwareVideo: Boolean? = null
    private var navigationSeek = false
    private var approximateSeek = false
    private var requestedEffects = config.audioEffects
    private var appliedEffects: String? = null
    private var currentVideoMime: String? = null
    private var deinterlacing=config.deinterlacing
    private var native=run {NativePlayerFiles.prepare(context);MPVLib.create(context)} ?: error("Compatibility player could not start.")
    // gpu-next by default; if it cannot start or fails on a file, one retry on the stable gpu renderer.
    private val rendererSession=RendererSession(RendererPolicy.initial(config.compatibilityRenderer))
    private val renderer get()=rendererSession.active
    private var status=Player.STATE_IDLE
    private var wanted=false
    private var focusPaused=false
    private var busy=false
    private var error:PlaybackException?=null
    private var position=initialPosition
    private var partPosition=0L
    private var buffered=0L
    private var repeat=Player.REPEAT_MODE_OFF
    private var playback=PlaybackParameters.DEFAULT
    private var parameters=initialParameters
    private var gain=1f
    private var boostGain=1.0
    private var size=VideoSize.UNKNOWN
    private var tracks=Tracks.EMPTY
    private var rendered=false
    private var seekable=!source.live
    private var loading=true
    private var stopping=false
    // A load error ends one file; a later load or an intentional stop must not inherit it.
    private val fileEvents=NativeFileEvents({task,delay->handler.postDelayed(task,delay)},
        {!closed&&!stopping&&status!=Player.STATE_ENDED&&native.getPropertyBoolean("idle-active")==true},{failedFile()})
    private var pcmAttempted=false
    private var transport:NativeHttpTransport?=null
    private var output:Any?=null
    private var holder:SurfaceHolder?=null
    private var texture:TextureView?=null
    private var ownedSurface:Surface?=null
    private val descriptors=mutableListOf<ParcelFileDescriptor>()
    private val temporary=mutableListOf<File>()
    private val durations=source.durations.toMutableList()
    var partIndex=0;private set
    private var pendingSeek=initialPosition
    private var waitingSeek=true
    private var secondaryLanguage:String?=null
    private var additionalAudio:String?=source.audio
    private var wholeTitleAudio=false
    private var audioOffset=0L
    private var externalSubtitle:File?=null
    private var externalGlobal=false
    private var subtitleOffset=0L
    private var subtitleRate=1f
    private var previousTextureListener:TextureView.SurfaceTextureListener?=null
    private var surfaceSize=androidx.media3.common.util.Size.UNKNOWN
    private val audioManager=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val session=C.AUDIO_SESSION_ID_UNSET
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener({ change ->
            if(!closed) { when(change) { AudioManager.AUDIOFOCUS_GAIN -> focusPaused=false; AudioManager.AUDIOFOCUS_LOSS -> {wanted=false;focusPaused=false}; else -> focusPaused=true }
                native.setPropertyBoolean("pause",!wanted||focusPaused);invalidateState() }
        },handler).build()
    private val noisy=object:BroadcastReceiver() {override fun onReceive(c:Context,i:Intent){if(i.action==AudioManager.ACTION_AUDIO_BECOMING_NOISY)playWhenReady=false}}
    private val callback=object:SurfaceHolder.Callback {
        override fun surfaceCreated(h:SurfaceHolder){if(!closed)native.attachSurface(h.surface)}
        override fun surfaceChanged(h:SurfaceHolder,f:Int,w:Int,height:Int){if(!closed){native.attachSurface(h.surface);native.setPropertyString("android-surface-size","${w}x$height");surfaceSize=androidx.media3.common.util.Size(w,height);invalidateState()}}
        override fun surfaceDestroyed(h:SurfaceHolder){if(!closed)native.detachSurface()}
    }
    private val textureCallback=object:TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(t:android.graphics.SurfaceTexture,w:Int,h:Int) {if(!closed){ownedSurface?.release();ownedSurface=Surface(t);native.attachSurface(ownedSurface!!);native.setPropertyString("android-surface-size","${w}x$h");surfaceSize=androidx.media3.common.util.Size(w,h);invalidateState()}}
        override fun onSurfaceTextureSizeChanged(t:android.graphics.SurfaceTexture,w:Int,h:Int) {if(!closed){native.setPropertyString("android-surface-size","${w}x$h");surfaceSize=androidx.media3.common.util.Size(w,h);invalidateState()}}
        override fun onSurfaceTextureDestroyed(t:android.graphics.SurfaceTexture):Boolean {if(!closed)native.detachSurface();ownedSurface?.release();ownedSurface=null;return true}
        override fun onSurfaceTextureUpdated(t:android.graphics.SurfaceTexture) {}
    }
    private fun initializeNative() {
            // mpv's own log can carry stream links and headers; it is never requested.
            check(native.requestLogMessages("no")>=0) {"mpv log suppression unavailable"}
            fun option(k:String,v:String) { check(native.setOptionString(k,v)>=0) { "Compatibility player option unavailable: $k" } }
            option("config","no");option("load-scripts","no");option("ytdl","no")
            option("msg-level","all=no");option("terminal","no");option("osd-level","0");option("input-default-bindings","no")
            option("vo",renderer);option("gpu-context","android");option("opengl-es","yes")
            option("hwdec",NativeDecoderProfile.choose(requestedDecoderProfile,null,"inherit",config.ffmpegVideo=="always",deinterlacing,false,false,allowHi10p).hardware)
            option("ao","audiotrack,opensles")
            option("load-unsafe-playlists","no");option("access-references","no")
            option("tls-verify","yes");option("tls-ca-file",caFile().absolutePath)
            option("keep-open","yes");option("idle","yes");option("cache","yes")
            // #309: custom buffering replaces the defaults; the read time limit always applies.
            if(config.stream.custom)StreamConfig.plan(context,config).nativeOptions().forEach {(key,value)->option(key,value)}
            else {option("demuxer-max-bytes",(48L*1024*1024).toString());option("demuxer-max-back-bytes",(16L*1024*1024).toString())
                option("network-timeout",config.stream.normalized().readSeconds.toString())}
            option("audio-pitch-correction","yes");option("volume-max","400")
            if(source.audioLanguages.isNotEmpty())option("alang",source.audioLanguages.joinToString(","))
            if(source.subtitleLanguages.isNotEmpty())option("slang",source.subtitleLanguages.joinToString(","))
            if(config.passthrough)option("audio-spdif",if(config.passthroughMode=="manual")config.formats.joinToString(",") { when(it){"dtshd"->"dts-hd";"eac3joc"->"eac3";else->it} } else "ac3,eac3,dts,dts-hd,truehd")
            native.init();native.addObserver(this)
            picture(config.deband,config.interpolation,config.scaler,config.deinterlacing)
            listOf("time-pos","duration","demuxer-cache-time").forEach {native.observeProperty(it,MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)}
            listOf("pause","paused-for-cache","eof-reached","seekable").forEach {native.observeProperty(it,MPVLib.MpvFormat.MPV_FORMAT_FLAG)}
            listOf("track-list","video-params","audio-out-params").forEach {native.observeProperty(it,MPVLib.MpvFormat.MPV_FORMAT_NONE)}
    }
    init {
        try {
            transport=NativeHttpTransport(source.headers,source.urls+listOfNotNull(source.audio),timing=config.stream)
            rendererSession.initialize({initializeNative()},{
                native.destroy()
                native=MPVLib.create(context) ?: throw IllegalStateException("Stable renderer unavailable")
                appliedDecoder=null
            })
            if(Build.VERSION.SDK_INT>=33)context.registerReceiver(noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        } catch(e:Throwable) {transport?.close();native.destroy();throw e}
    }
    private fun caFile():File {
        val file=File(context.cacheDir,"player-ca.pem")
        val factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val certificates=factory.trustManagers.filterIsInstance<X509TrustManager>().flatMap {it.acceptedIssuers.toList()}
        check(certificates.isNotEmpty()) {"System certificate store unavailable."}
        file.writeText(certificates.joinToString("\n") {"-----BEGIN CERTIFICATE-----\n"+java.util.Base64.getMimeEncoder(64,byteArrayOf(10)).encodeToString(it.encoded)+"\n-----END CERTIFICATE-----"})
        return file
    }
    val partOffset get()=prefix(partIndex)
    private fun prefix(index:Int)=durations.take(index).sumOf {it?:0L}
    private fun total():Long = if(durations.all {it!=null&&it>0})durations.sumOf {it!!} else source.durationMs?.takeIf {it>0} ?: C.TIME_UNSET
    private fun uri(value:String):String {
        if(value.startsWith("http://")||value.startsWith("https://"))return transport!!.expose(value)
        if(!value.startsWith("content://"))return value
        val fd=context.contentResolver.openFileDescriptor(value.toUri(),"r") ?: error("File permission is unavailable.")
        descriptors+=fd;return "fd://${fd.fd}"
    }
    private fun load(index:Int,at:Long=0) {
        if(index!=partIndex&&!externalGlobal)externalSubtitle=null
        partIndex=index;pendingSeek=at;waitingSeek=true;position=at;loading=true;status=Player.STATE_BUFFERING;busy=true;stopping=false
        native.setPropertyBoolean("pause",true)
        native.command(arrayOf("loadfile",uri(source.urls[index]),"replace"))
        invalidateState()
    }
    private fun refreshTracks() {
        val groups=mutableListOf<Tracks.Group>()
        for(i in 0 until (native.getPropertyInt("track-list/count")?:0).coerceIn(0,256)) {
            val path="track-list/$i/";val id=native.getPropertyInt(path+"id")?:continue
            val type=native.getPropertyString(path+"type")?:continue
            if(type !in setOf("video","audio","sub"))continue
            val codec=native.getPropertyString(path+"codec").orEmpty()
            val mime=when(codec){
                "aac"->MimeTypes.AUDIO_AAC;"ac3"->MimeTypes.AUDIO_AC3;"eac3"->MimeTypes.AUDIO_E_AC3;"dts","dca"->MimeTypes.AUDIO_DTS;"truehd"->MimeTypes.AUDIO_TRUEHD
                "h264"->MimeTypes.VIDEO_H264;"hevc"->MimeTypes.VIDEO_H265;"av1"->MimeTypes.VIDEO_AV1;"vp9"->MimeTypes.VIDEO_VP9
                "mpeg2video"->MimeTypes.VIDEO_MPEG2;"vp8"->MimeTypes.VIDEO_VP8;"vc1"->MimeTypes.VIDEO_VC1
                "vvc","h266"->"video/vvc";"ac4"->"audio/ac4";"mpegh_3d_audio"->"audio/mhm1";"iamf"->"audio/iamf"
                "ass","ssa"->MimeTypes.TEXT_SSA;"hdmv_pgs_subtitle"->MimeTypes.APPLICATION_PGS;"dvd_subtitle"->MimeTypes.APPLICATION_VOBSUB
                else->when(type){"audio"->"audio/"+codec;"sub"->MimeTypes.APPLICATION_SUBRIP;else->"video/"+codec}
            }
            val format=Format.Builder().setId("mpv:$type:$id").setSampleMimeType(mime).setLabel(native.getPropertyString(path+"title")?:codec)
                .setLanguage(native.getPropertyString(path+"lang")).setSelectionFlags(if(native.getPropertyBoolean(path+"forced")==true)C.SELECTION_FLAG_FORCED else 0)
                .apply {if(type=="video"){setWidth(native.getPropertyInt("video-params/w")?:-1);setHeight(native.getPropertyInt("video-params/h")?:-1);setFrameRate(native.getPropertyDouble("container-fps")?.toFloat()?:-1f)
                    val transfer=when(native.getPropertyString("video-params/gamma")){"pq"->C.COLOR_TRANSFER_ST2084;"hlg"->C.COLOR_TRANSFER_HLG;else->null}
                    transfer?.let {setColorInfo(ColorInfo.Builder().setColorTransfer(it).build())}}
                    if(type=="audio") {setChannelCount(native.getPropertyInt(path+"demux-channel-count")?:-1);setSampleRate(native.getPropertyInt(path+"demux-samplerate")?:-1)} }.build()
            groups+=Tracks.Group(TrackGroup(format.id!!,format),false,intArrayOf(C.FORMAT_HANDLED),booleanArrayOf(native.getPropertyBoolean(path+"selected")==true))
        }
        tracks=Tracks(groups)
        currentVideoMime=groups.firstOrNull {it.type==C.TRACK_TYPE_VIDEO}?.getTrackFormat(0)?.sampleMimeType
        applyVideoDecoder(); applyAudioEffects()
        size=VideoSize(native.getPropertyInt("video-params/w")?:0,native.getPropertyInt("video-params/h")?:0)
    }
    private fun applyTracks(value:TrackSelectionParameters) {
        for(type in listOf(C.TRACK_TYPE_AUDIO,C.TRACK_TYPE_TEXT)) {
            val property=if(type==C.TRACK_TYPE_AUDIO)"aid" else "sid"
            if(type in value.disabledTrackTypes) {native.setPropertyString(property,"no");if(type==C.TRACK_TYPE_TEXT){native.setPropertyString("secondary-sid","no");secondaryLanguage=null;externalSubtitle=null;externalGlobal=false;subtitleDelay(subtitleOffset)};continue}
            val override=value.overrides.values.firstOrNull {it.type==type}
            if(type==C.TRACK_TYPE_TEXT&&override!=null){externalGlobal=false;externalSubtitle=null;subtitleDelay(subtitleOffset)}
            val chosen = override?.let {
                val format = it.trackIndices.firstOrNull()?.let(it.mediaTrackGroup::getFormat) ?: return@let null
                matchingPlaybackTrack(tracks, type, format)
            }
            chosen?.mediaTrackGroup?.id?.substringAfterLast(':')?.toIntOrNull()?.let { native.setPropertyInt(property, it) }
        }
    }
    private fun loaded() {
        loading=false;busy=false;status=Player.STATE_READY;rendered=true
        native.getPropertyDouble("duration")?.takeIf {it>0}?.let {durations[partIndex]=(it*1000).toLong()}
        refreshTracks();applyTracks(parameters)
        secondaryLanguage?.let {lang -> tracks.groups.firstOrNull {it.type==C.TRACK_TYPE_TEXT && it.getTrackFormat(0).language==lang && it.mediaTrackGroup.id.substringAfterLast(':').toIntOrNull()!=native.getPropertyInt("sid")}?.let {native.setPropertyInt("secondary-sid",it.mediaTrackGroup.id.substringAfterLast(':').toInt())} }
        val remaining=(pendingSeek-prefix(partIndex)).coerceAtLeast(0)
        val length=durations[partIndex]
        if(length!=null && remaining>=length && partIndex<source.urls.lastIndex){load(partIndex+1,pendingSeek);return}
        if(remaining>0 && native.getPropertyBoolean("seekable")==false) {pendingSeek=prefix(partIndex);position=pendingSeek;waitingSeek=false
            android.widget.Toast.makeText(context,"This source cannot resume. Starting from the beginning.",android.widget.Toast.LENGTH_LONG).show()
        } else if(remaining>0)native.command(arrayOf("seek",(remaining/1000.0).toString(),"absolute+exact")) else waitingSeek=false
        additionalAudio?.let {native.command(arrayOf("audio-add",uri(it),"select"))}
        audioDelay(audioOffset)
        externalSubtitle?.let {native.command(arrayOf("sub-add",it.absolutePath,"select"))}
        subtitleDelay(subtitleOffset)
        native.setPropertyBoolean("pause",!wanted||focusPaused);invalidateState()
    }
    private fun eof() {
        if(loading||stopping)return
        native.getPropertyDouble("duration")?.let {durations[partIndex]=(it*1000).toLong()}
        if(partIndex<source.urls.lastIndex)load(partIndex+1,prefix(partIndex+1))
        else if(repeat!=Player.REPEAT_MODE_OFF)load(0,0)
        else {status=Player.STATE_ENDED;busy=false;invalidateState()}
    }
    private fun onMain(action:()->Unit){handler.post {if(!closed)try {action()}catch(_:Exception){fail()}}}
    private fun failedFile() {
        if(transport?.failureCode==null && rendererSession.fallback()) {
            native.setPropertyString("vo","gpu")
            android.widget.Toast.makeText(context,"Trying the stable video renderer",android.widget.Toast.LENGTH_SHORT).show()
            load(partIndex,position);return
        }
        if(!pcmAttempted && config.passthrough && transport?.failureCode==null && native.getPropertyString("audio-codec-name")!=null) {
            pcmAttempted=true;native.setPropertyString("audio-spdif","")
            android.widget.Toast.makeText(context,"Trying decoded audio output",android.widget.Toast.LENGTH_SHORT).show()
            load(partIndex,position);return
        }
        fail()
    }
    private fun fail(){status=Player.STATE_IDLE;busy=false;error=PlaybackException("Compatibility playback failed. Try another version or server quality.",null,transport?.failureCode?:PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED);invalidateState()}
    override fun eventProperty(property:String)=onMain {refreshTracks();invalidateState()}
    override fun eventProperty(property:String,value:Long) {}
    override fun eventProperty(property:String,value:String)=onMain {refreshTracks();invalidateState()}
    override fun eventProperty(property:String,value:Double)=onMain {
        when(property){"time-pos"->{val at=(value*1000).toLong();if(!loading && (!waitingSeek || approximateSeek || kotlin.math.abs(at-(pendingSeek-prefix(partIndex)))<2000)){waitingSeek=false;approximateSeek=false;partPosition=at;position=prefix(partIndex)+partPosition}};"duration"->{if(value>0)durations[partIndex]=(value*1000).toLong()};"demuxer-cache-time"->buffered=prefix(partIndex)+(value*1000).toLong()};invalidateState()
    }
    override fun eventProperty(property:String,value:Boolean)=onMain {
        when(property){"paused-for-cache"->{busy=value;if(!loading && status!=Player.STATE_ENDED && status!=Player.STATE_IDLE)status=if(value)Player.STATE_BUFFERING else Player.STATE_READY};"eof-reached"->if(value)eof();"seekable"->seekable=value};invalidateState()
    }
    override fun event(eventId:Int)=onMain {
        when(eventId){MPVLib.MpvEvent.MPV_EVENT_START_FILE->fileEvents.started();MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED->loaded();MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG->{refreshTracks();invalidateState()}}
    }
    /** libmpv 1.1.0 reports why a file ended; only an error retries. */
    override fun eventEndFile(reason:Int,error:Int)=onMain {
        if(!stopping)fileEvents.ended(reason)
    }
    override fun getState():State {
        val commands=Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,Player.COMMAND_PREPARE,Player.COMMAND_STOP,Player.COMMAND_RELEASE,
            Player.COMMAND_SET_SPEED_AND_PITCH,Player.COMMAND_SET_REPEAT_MODE,Player.COMMAND_SET_VIDEO_SURFACE,
            Player.COMMAND_SET_VOLUME,Player.COMMAND_GET_VOLUME,Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,Player.COMMAND_GET_TIMELINE,Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TRACKS,Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,Player.COMMAND_GET_TEXT,
        ).apply {if(seekable)addAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,Player.COMMAND_SEEK_BACK,Player.COMMAND_SEEK_FORWARD,Player.COMMAND_SEEK_TO_DEFAULT_POSITION)}.build()
        val item=MediaItemData.Builder(source.key).setMediaItem(MediaItem.Builder().setMediaId(source.key).setMediaMetadata(MediaMetadata.Builder().setTitle(source.title).build()).build())
            .setTracks(tracks).setIsSeekable(seekable).setIsDynamic(source.live).setDurationUs(total().let {if(it==C.TIME_UNSET)it else it*1000}).build()
        val firstFrame=rendered;rendered=false
        return State.Builder().setAvailableCommands(commands).setPlaylist(listOf(item)).setCurrentMediaItemIndex(0).setPlaybackState(status)
            .setPlayWhenReady(wanted,Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).setPlaybackSuppressionReason(if(focusPaused)Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS else Player.PLAYBACK_SUPPRESSION_REASON_NONE)
            .setIsLoading(busy).setContentPositionMs(position).setContentBufferedPositionMs {buffered.coerceAtLeast(position)}
            .setTotalBufferedDurationMs {(buffered-position).coerceAtLeast(0)}.setTrackSelectionParameters(parameters).setPlaybackParameters(playback)
            .setVideoSize(size).setSurfaceSize(surfaceSize).setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()).setAudioSessionId(session).setVolume(gain).setRepeatMode(repeat).setPlayerError(error).setNewlyRenderedFirstFrame(firstFrame).build()
    }
    private fun done():ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handlePrepare():ListenableFuture<*> {error=null;load(0,initialPosition);return done()}
    override fun handleSetPlayWhenReady(playWhenReady:Boolean):ListenableFuture<*> {wanted=playWhenReady;if(wanted)focusPaused=audioManager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        native.setPropertyBoolean("pause",!wanted||focusPaused);return done()}
    fun seekNavigation(positionMs: Long, fast: Boolean) {
        navigationSeek=fast
        try { seekTo(positionMs) } finally { navigationSeek=false }
    }
    override fun handleSeek(mediaItemIndex:Int,positionMs:Long,seekCommand:Int):ListenableFuture<*> {
        approximateSeek=navigationSeek
        val target=positionMs.coerceAtLeast(0);var index=0
        while(index<source.urls.lastIndex && durations[index]!=null && target>=prefix(index)+durations[index]!!)index++
        if(index!=partIndex)load(index,target) else {pendingSeek=target;waitingSeek=true;native.command(arrayOf("seek",((target-prefix(index)).coerceAtLeast(0)/1000.0).toString(),SeekPreference.flags(if(navigationSeek) "fast" else "precise",false)));position=target;status=Player.STATE_READY;native.setPropertyBoolean("pause",!wanted||focusPaused)}
        return done()
    }
    override fun handleSetTrackSelectionParameters(trackSelectionParameters:TrackSelectionParameters):ListenableFuture<*> {parameters=trackSelectionParameters;applyTracks(parameters);refreshTracks();return done()}
    override fun handleSetPlaybackParameters(playbackParameters:PlaybackParameters):ListenableFuture<*> {playback=playbackParameters;native.setPropertyDouble("speed",playback.speed.toDouble());return done()}
    override fun handleSetRepeatMode(repeatMode:Int):ListenableFuture<*> {repeat=repeatMode;return done()}
    override fun handleSetVolume(volume:Float):ListenableFuture<*> {gain=volume;native.setPropertyDouble("volume",volume*100.0*boostGain);return done()}
    override fun handleStop():ListenableFuture<*> {stopping=true;wanted=false;status=Player.STATE_IDLE;busy=false;native.command(arrayOf("stop"));return done()}
    override fun handleSetVideoOutput(videoOutput:Any):ListenableFuture<*> {
        detachOutput();output=videoOutput
        when(videoOutput){is Surface->native.attachSurface(videoOutput);is SurfaceView->attach(videoOutput.holder);is SurfaceHolder->attach(videoOutput)
            is TextureView->{texture=videoOutput;previousTextureListener=videoOutput.surfaceTextureListener;videoOutput.surfaceTextureListener=textureCallback;if(videoOutput.isAvailable)videoOutput.surfaceTexture?.let {textureCallback.onSurfaceTextureAvailable(it,videoOutput.width,videoOutput.height)}}}
        return done()
    }
    private fun attach(value:SurfaceHolder) {holder?.removeCallback(callback);holder=value;value.addCallback(callback);if(value.surface.isValid)native.attachSurface(value.surface)}
    private fun detachOutput() {
        holder?.removeCallback(callback);holder=null
        texture?.let {if(it.surfaceTextureListener===textureCallback)it.surfaceTextureListener=previousTextureListener};texture=null;previousTextureListener=null
        native.detachSurface();ownedSurface?.release();ownedSurface=null;output=null
    }
    override fun handleClearVideoOutput(videoOutput:Any?):ListenableFuture<*> {if(videoOutput==null||videoOutput===output)detachOutput();return done()}
    override fun handleRelease():ListenableFuture<*> {if(!closed){closed=true;detachOutput();handler.removeCallbacksAndMessages(null);runCatching {context.unregisterReceiver(noisy)};audioManager.abandonAudioFocusRequest(focus);native.removeObserver(this);transport?.close();native.destroy();ownedSurface?.release();descriptors.forEach {it.close()};temporary.forEach {it.delete()}};return done()}
    fun geometry(zoom:String,ratio:Float) {
        native.setPropertyDouble("video-zoom",0.0)
        native.setPropertyDouble("panscan",if(zoom=="crop")1.0 else 0.0)
        native.setPropertyBoolean("keepaspect",zoom!="stretch")
        native.setPropertyString("video-aspect-override",if(ratio>0)ratio.toString() else "no")
    }
    fun volumeBoost(db:Double){boostGain=Math.pow(10.0,db/20.0);native.setPropertyDouble("volume",gain*100.0*boostGain)}
    fun audioDelay(ms:Long){audioOffset=ms;native.setPropertyDouble("audio-delay",(ms-(if(wholeTitleAudio)prefix(partIndex) else 0L))/1000.0)}
    fun subtitleDelay(ms:Long){subtitleOffset=ms;native.setPropertyDouble("sub-delay",SubtitleTiming.nativeDelay(ms,subtitleRate,if(externalGlobal)prefix(partIndex) else 0L))}
    fun subtitleSpeed(value:Float){subtitleRate=SubtitleTiming.rate(value);native.setPropertyDouble("sub-speed",SubtitleTiming.nativeSpeed(value));subtitleDelay(subtitleOffset)}
    val secondaryId get()=native.getPropertyInt("secondary-sid")
    fun secondary(id:Int?) {
        if(id!=null && id==native.getPropertyInt("sid"))return
        secondaryLanguage=tracks.groups.firstOrNull {it.type==C.TRACK_TYPE_TEXT&&it.mediaTrackGroup.id.substringAfterLast(':').toIntOrNull()==id}?.getTrackFormat(0)?.language
        native.setPropertyString("secondary-sid",id?.toString()?:"no")
    }
    fun secondaryDelay(ms:Long){native.setPropertyDouble("secondary-sub-delay",ms/1000.0)}
    fun external(text:String,format:String,wholeFile:Boolean=false):Boolean {
        if(format !in setOf("srt","vtt","ass"))return false
        val file=File.createTempFile("player-sub-",".$format",context.cacheDir);file.writeText(text);temporary+=file
        parameters=parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
        externalSubtitle=file;externalGlobal=wholeFile;subtitleDelay(subtitleOffset)
        native.command(arrayOf("sub-add",file.absolutePath,"select"));refreshTracks();invalidateState();return true
    }
    fun externalFile(file:File,companions:List<File> = emptyList()) {
        temporary+=file;temporary+=companions;externalSubtitle=file;externalGlobal=true;subtitleDelay(subtitleOffset)
        parameters=parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
        native.command(arrayOf("sub-add",file.absolutePath,"select"));refreshTracks();invalidateState()
    }
    fun additionalAudio(uri:String){additionalAudio=uri;wholeTitleAudio=true;native.command(arrayOf("audio-add",uri(uri),"select"));audioDelay(audioOffset)}
    fun picture(deband:Boolean,interpolate:Boolean,scale:String,deinterlace:String = "off") {
        deinterlacing=deinterlace
        native.setPropertyBoolean("deband",deband)
        applyVideoDecoder()
        val mode=NativeDeinterlacing.mode(deinterlacing)
        if(native.getPropertyString("deinterlace")!=mode)native.setPropertyString("deinterlace",mode)
        native.setPropertyString("video-sync",if(interpolate)"display-resample" else "audio")
        native.setPropertyBoolean("interpolation",interpolate);native.setPropertyString("scale",scale)
    }
    fun captionStyle(c:app.ember.tv.data.SubtitleConfig) {
        native.setPropertyString("sub-ass-override",if(c.authoredStyles&&!c.systemStyle)"no" else "force")
        native.setPropertyString("sub-color",if(c.color=="yellow")"#FFFFE650" else "#FFFFFFFF")
        native.setPropertyDouble("sub-font-size",when(c.size){"small"->30.0;"large"->48.0;"xlarge"->58.0;else->38.0})
        native.setPropertyDouble("sub-pos",when(c.position){"low"->100.0;"high"->80.0;else->95.0})
        native.setPropertyString("sub-border-style",if(c.background=="box")"background-box" else "outline-and-shadow")
        native.setPropertyString("sub-back-color",if(c.background=="box")"#BE000000" else "#00000000")
        native.setPropertyString("sub-border-color","#FF000000");native.setPropertyDouble("sub-border-size",if(c.background=="outline")2.5 else 0.0)
        native.setPropertyDouble("sub-shadow-offset",if(c.background=="shadow")2.5 else 0.0)
        native.setPropertyBoolean("sub-bold",false);native.setPropertyBoolean("sub-italic",false)
        // #219: the chosen font, as far as this player can find it; a custom one is looked up in Ember's font folder.
        runCatching {
            when(app.ember.tv.data.SubtitleFonts.normalize(c.font)) {
                app.ember.tv.data.SubtitleFonts.DEFAULT -> {}
                app.ember.tv.data.SubtitleFonts.CUSTOM -> if(app.ember.tv.data.SubtitleFonts.hasCustom(context)&&c.customFontName.isNotBlank()) {
                    native.setPropertyString("sub-fonts-dir",app.ember.tv.data.SubtitleFonts.file(context).parentFile!!.absolutePath)
                    native.setPropertyString("sub-font",c.customFontName)
                }
                else -> app.ember.tv.data.SubtitleFonts.systemFamily(c.font)?.let {native.setPropertyString("sub-font",it)}
            }
        }
        if(c.systemStyle) {
            val manager=context.getSystemService(Context.CAPTIONING_SERVICE) as? android.view.accessibility.CaptioningManager
            manager?.let {
                val style=it.userStyle
                fun color(value:Int)="#%08X".format(java.util.Locale.ROOT,value)
                native.setPropertyString("sub-color",color(style.foregroundColor))
                native.setPropertyString("sub-back-color",color(style.backgroundColor))
                native.setPropertyString("sub-border-style",if(android.graphics.Color.alpha(style.backgroundColor)>0)"background-box" else "outline-and-shadow")
                native.setPropertyString("sub-border-color",color(style.edgeColor))
                native.setPropertyDouble("sub-border-size",if(style.edgeType==android.view.accessibility.CaptioningManager.CaptionStyle.EDGE_TYPE_OUTLINE)2.5 else 0.0)
                native.setPropertyDouble("sub-shadow-offset",if(style.edgeType==android.view.accessibility.CaptioningManager.CaptionStyle.EDGE_TYPE_DROP_SHADOW)2.5 else 0.0)
                native.setPropertyDouble("sub-font-size",38.0*it.fontScale*when(c.size){"small"->0.8;"large"->1.25;"xlarge"->1.5;else->1.0})
                native.setPropertyBoolean("sub-bold",style.typeface?.isBold==true);native.setPropertyBoolean("sub-italic",style.typeface?.isItalic==true)
            }
        }
    }
    fun chapters():List<app.ember.tv.mediaserver.Chapter> =(0 until (native.getPropertyInt("chapter-list/count")?:0).coerceAtMost(2000)).mapNotNull {i->
        val time=native.getPropertyDouble("chapter-list/$i/time")?:return@mapNotNull null
        app.ember.tv.mediaserver.Chapter(native.getPropertyString("chapter-list/$i/title")?:"Chapter ${i+1}",(time*1000).toLong()+prefix(partIndex))
    }
    val audioEffectsAvailable get() = AudioProcessingPolicy.allowed(
        tracks.groups.firstOrNull {it.type==C.TRACK_TYPE_AUDIO && it.isSelected}?.getTrackFormat(0)?.sampleMimeType,
        config.passthrough,isPassthrough(),pcmAttempted)
    fun audioEffects(value: AudioEffects): Boolean { requestedEffects=value;return applyAudioEffects() }
    private fun applyAudioEffects(): Boolean {
        val count=tracks.groups.firstOrNull {it.type==C.TRACK_TYPE_AUDIO && it.isSelected}?.getTrackFormat(0)?.channelCount ?: 2
        val value=if(audioEffectsAvailable)requestedEffects.nativeFilters(count) else ""
        if(value==appliedEffects)return true
        // Unsupported optional processing must not turn a playable title into an error.
        return runCatching { native.setPropertyString("af",value); appliedEffects=value;true }.getOrDefault(false)
    }
    fun isPassthrough()=native.getPropertyString("audio-out-params/format")?.startsWith("spdif")==true
    private fun decoderChoice():NativeDecoderChoice {
        val preference=config.decoderRules.preference(source.urls[partIndex],currentVideoMime,source.filename)
        val count=(native.getPropertyInt("track-list/count")?:0).coerceIn(0,2000)
        val selected=(0 until count).firstOrNull {native.getPropertyString("track-list/$it/type")=="video" && native.getPropertyBoolean("track-list/$it/selected")==true}
        val profile=selected?.let {native.getPropertyString("track-list/$it/codec-profile")}
        return NativeDecoderProfile.choose(requestedDecoderProfile,manualSoftwareVideo,preference,config.ffmpegVideo=="always",deinterlacing,
            NativeDecoderProfile.shield(Build.MANUFACTURER,Build.MODEL),NativeDecoderProfile.hi10p(currentVideoMime,profile,native.getPropertyString("video-params/pixelformat")),allowHi10p)
    }
    private fun applyVideoDecoder() {
        val choice=decoderChoice()
        if(choice==appliedDecoder)return
        choice.options.forEach {(key,value)->native.setPropertyString(key,value)}
        appliedDecoder=choice
    }
    fun decoderProfile(value:String,hi10p:Boolean=allowHi10p) {
        requestedDecoderProfile=NativeDecoderProfile.normalized(value);allowHi10p=hi10p
        manualSoftwareVideo=null;appliedDecoder=null;applyVideoDecoder()
    }
    val decoderProfileLabel get()=if(appliedDecoder?.hi10p==true)"Automatic · Hi10P software fallback" else NativeDecoderProfile.choices.first {it.first==NativeDecoderProfile.normalized(requestedDecoderProfile)}.second
    fun softwareVideo(enabled:Boolean){manualSoftwareVideo=enabled;applyVideoDecoder()}
    val softwareVideo get()=native.getPropertyString("hwdec-current")=="no" || native.getPropertyString("hwdec")=="no"
    /** An explicit choice from Settings or the player's options; it clears an earlier automatic fallback. */
    fun renderer(value:String) {
        rendererSession.select(RendererPolicy.initial(value))
        native.setPropertyString("vo",renderer)
    }
    val rendererLabel get()=RendererPolicy.label(renderer)+if(rendererSession.recovered)" · after a failure" else ""
    /** #415: live numbers for the stream info panel; null where mpv doesn't know yet. */
    fun liveStats()=MpvLiveStats(
        dropped=listOfNotNull(native.getPropertyInt("frame-drop-count"),native.getPropertyInt("decoder-frame-drop-count")).sum(),
        aheadSeconds=native.getPropertyDouble("demuxer-cache-duration"),
        speedBps=native.getPropertyInt("cache-speed")?.toLong()?.times(8),
        hwdec=native.getPropertyString("hwdec-current"),
        renderer=(native.getPropertyString("current-vo") ?: renderer)+if(rendererSession.recovered)" (stable fallback)" else "",
        audioOut=native.getPropertyString("audio-out-params/format"),
    )
    fun diagnostics()=mapOf("Engine" to "Compatibility (libmpv)","Video decoder" to native.getPropertyString("hwdec-current").orEmpty(),
        "Renderer" to (native.getPropertyString("current-vo") ?: renderer)+if(rendererSession.recovered)" (stable fallback)" else "",
        "Decoded video transfer" to native.getPropertyString("video-params/gamma").orEmpty(),
        "Deinterlacing mode" to deinterlacing,
        "Renderer input transfer" to native.getPropertyString("video-out-params/gamma").orEmpty(),
        "Audio output" to native.getPropertyString("audio-out-params/format").orEmpty())
}

internal data class MpvLiveStats(val dropped:Int,val aheadSeconds:Double?,val speedBps:Long?,val hwdec:String?,val renderer:String,val audioOut:String?)
