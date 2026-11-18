package app.ember.tv.player

import androidx.core.content.edit

import android.content.Context
import android.os.Build
import androidx.media3.common.Format
import app.ember.tv.BuildConfig
import app.ember.tv.data.CachePolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class ProvenDecoder(val name:String,val software:Boolean=false,val savedAt:Long)
@Serializable
internal data class DecoderHealthDocument(val environment:String,val entries:Map<String,ProvenDecoder> = emptyMap())

/** Bounded hints contain codec characteristics, not titles, media addresses or account data. */
internal class DecoderHealthMemory(val environment:String,initial:DecoderHealthDocument?=null,private val clock:()->Long=System::currentTimeMillis) {
    private var entries=if(initial?.environment==environment)initial.entries.filterValues {it.name.isNotBlank() && it.name.length<=160 && clock()-it.savedAt in 0 until TTL}.entries.sortedByDescending {it.value.savedAt}.take(128).associate {it.toPair()} else emptyMap()
    @Synchronized fun get(key:String):ProvenDecoder?=entries[key]?.takeIf {clock()-it.savedAt in 0 until TTL}
    @Synchronized fun remember(key:String,value:ProvenDecoder) {
        if(key.isBlank() || value.name.isBlank() || value.name.length>160)return
        entries=(entries.filterValues {clock()-it.savedAt in 0 until TTL}+(key to value)).entries.sortedByDescending {it.value.savedAt}.take(128).associate {it.toPair()}
    }
    @Synchronized fun forget(key:String){entries=entries-key}
    @Synchronized fun clear(){entries=emptyMap()}
    @Synchronized fun document()=DecoderHealthDocument(environment,entries)
    companion object {const val TTL=7*24*3600_000L}
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class DecoderHealth private constructor(private val store:android.content.SharedPreferences,val memory:DecoderHealthMemory,private var generation:Long) {
    @Synchronized private fun syncReset() {val now=resets.get();if(generation!=now){memory.clear();generation=now}}
    @Synchronized fun hint(format:Format):ProvenDecoder? {syncReset();return profile(format)?.let(memory::get)}
    @Synchronized fun remember(format:Format,name:String,software:Boolean) {syncReset();profile(format)?.let {key->memory.remember(key,ProvenDecoder(name,software,System.currentTimeMillis()));save()}}
    @Synchronized fun forget(format:Format){syncReset();profile(format)?.let {memory.forget(it);save()}}
    @Synchronized fun engine(key:String):String? {syncReset();return memory.get("engine:$key")?.name?.takeIf {it in setOf("standard","compatibility")}}
    @Synchronized fun rememberEngine(key:String,engine:String) {syncReset();if(engine in setOf("standard","compatibility")){memory.remember("engine:$key",ProvenDecoder(engine,savedAt=System.currentTimeMillis()));save()}}
    @Synchronized fun forgetEngine(key:String){syncReset();memory.forget("engine:$key");save()}
    private fun save() {val snapshot=memory.document();val at=generation;writer.execute {if(resets.get()==at)store.edit {putString("records",json.encodeToString(snapshot))}}}
    companion object {
        @Volatile private var cached:DecoderHealth?=null
        private val resets=java.util.concurrent.atomic.AtomicLong()
        private val json=Json {ignoreUnknownKeys=true}
        private val writer=java.util.concurrent.Executors.newSingleThreadExecutor {r->Thread(r,"ember-decoder-memory").apply {isDaemon=true}}
        fun current():DecoderHealth?=cached
        fun loadAsync(context:Context,ready:(DecoderHealth)->Unit) {
            val app=context.applicationContext
            writer.execute {runCatching {load(app)}.getOrNull()?.let {health->android.os.Handler(android.os.Looper.getMainLooper()).post {ready(health)}}}
        }
        /** Call on IO: codec enumeration and preference loading never hold up navigation. */
        @Synchronized fun load(context:Context):DecoderHealth {
            cached?.let {return it}
            val generation=resets.get()
            val names=runCatching {android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos.map {it.name}.sorted().joinToString("|")}.getOrDefault("")
            val modules=listOf("com.android.media.swcodec","com.google.android.media.swcodec","com.android.media").joinToString("|") {name->runCatching {
                @Suppress("DEPRECATION") val info=context.packageManager.getPackageInfo(name,android.content.pm.PackageManager.MATCH_APEX)
                @Suppress("DEPRECATION") "$name:${info.longVersionCode}"
            }.getOrDefault(name)}
            // Beta installs can replace native decoders without changing the version code.
            @Suppress("DEPRECATION") val installedAt=runCatching {context.packageManager.getPackageInfo(context.packageName,0).lastUpdateTime}.getOrDefault(0L)
            val environment=CachePolicy.digest("${Build.FINGERPRINT}|${Build.VERSION.SECURITY_PATCH}|${BuildConfig.VERSION_CODE}|$installedAt|$names|$modules")
            val store=context.applicationContext.getSharedPreferences("decoder_health",Context.MODE_PRIVATE)
            val document=store.getString("records",null)?.takeIf {it.length<=128*1024}?.let {runCatching {json.decodeFromString<DecoderHealthDocument>(it)}.getOrNull()}
            return DecoderHealth(store,DecoderHealthMemory(environment,document),generation).also {cached=it}
        }
        fun clear(context:Context){resets.incrementAndGet();writer.execute {context.applicationContext.getSharedPreferences("decoder_health",Context.MODE_PRIVATE).edit {clear()}}}
        fun profile(f:Format):String? {
            val mime=f.sampleMimeType?.takeIf {it.startsWith("video/")} ?: return null
            if(f.width<=0 || f.height<=0)return null
            return CachePolicy.digest("$mime|${f.codecs.orEmpty().take(128)}|${f.width}x${f.height}|${f.frameRate}|${f.colorInfo?.colorTransfer}|${f.colorInfo?.colorSpace}|${f.colorInfo?.lumaBitdepth}|${f.colorInfo?.chromaBitdepth}|${f.drmInitData!=null}")
        }
        fun softwareSafe(f:Format)=f.width>0 && f.height>0 && f.width.toLong()*f.height<=1920L*1080 &&
            f.drmInitData==null && f.sampleMimeType!=androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION &&
            f.colorInfo?.colorTransfer !in setOf(androidx.media3.common.C.COLOR_TRANSFER_ST2084,androidx.media3.common.C.COLOR_TRANSFER_HLG)
    }
}
