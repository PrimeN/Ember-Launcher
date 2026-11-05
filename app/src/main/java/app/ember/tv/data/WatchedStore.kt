package app.ember.tv.data

import androidx.core.content.edit

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.security.MessageDigest
import java.util.Base64

/** Typed aliases prevent movie/TV collisions; only opaque references are hashed, never credentials saved. */
internal object WatchedKeys {
    private val hashes=object:LinkedHashMap<String,String>(128,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,String>?)=size>1024
    }
    private fun opaque(id:String)=synchronized(hashes) {hashes.getOrPut(id) {
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).copyOf(16))
    }}
    fun bases(meta:Meta):List<String> {
        val kind=if(meta.type=="movie")"m" else "s"
        val identity=HeroIdentity.of(meta)
        val parent=if(meta.season!=null && meta.episode!=null)meta.serverSeriesRef else null
        val ref=parent ?: app.ember.tv.mediaserver.ServerRef.of(meta)?.value
        return listOfNotNull(identity?.imdb?.let {"$kind:i:$it"},
            identity?.tmdb?.let {"$kind:t:$it"},ref?.let {"$kind:r:${opaque(it)}"},
            meta.id.takeIf(String::isNotBlank)?.let {"$kind:p:${opaque(it)}"}).distinct()
    }
    fun keys(meta:Meta)=bases(meta).map {base->if(meta.season!=null && meta.episode!=null)"$base@${meta.season}.${meta.episode}" else base}
    fun episode(show:Meta,ep:Episode)=show.copy(season=ep.season,episode=ep.episode,
        serverRef=ep.serverRef ?: show.serverRef,serverSeriesRef=ep.serverSeriesRef ?: show.serverSeriesRef ?: show.serverRef,
        progress=ep.progress,watched=ep.watched ?: show.watched?.takeIf {it})
}

internal data class WatchedIndex(val yes:Set<String> = emptySet(),val no:Set<String> = emptySet()) {
    private val incompleteShows=no.filter {it.contains('@')}.mapTo(hashSetOf()) {it.substringBeforeLast('@')}
    private fun explicit(keys:List<String>):Boolean? {
        keys.forEach {key->if(key in no)return false;if(key in yes)return true}
        return null
    }
    fun explicitStatus(meta:Meta):Boolean? {
        if(meta.season!=null && meta.episode!=null) {
            WatchedKeys.bases(meta).forEach {base->
                (explicit(listOf("$base@${meta.season}.${meta.episode}")) ?: explicit(listOf(base)))?.let {return it}
            }
        } else explicit(WatchedKeys.keys(meta))?.let {return it}
        return null
    }
    fun watched(meta:Meta):Boolean {
        if(meta.type!="movie" && meta.season==null && meta.episode==null && WatchedKeys.bases(meta).any {it in incompleteShows})return false
        return explicitStatus(meta) ?: meta.watched ?: (meta.progress?.let {it.isFinite() && it>=.92f && (meta.type=="movie" || meta.season!=null && meta.episode!=null)}==true)
    }
    fun changed(meta:Meta,value:Boolean):WatchedIndex {
        val keys=WatchedKeys.keys(meta).toSet()
        val whole=meta.type!="movie" && meta.season==null && meta.episode==null
        fun keep(key:String)=key !in keys && !(whole && keys.any {key.startsWith("$it@")})
        val positive=yes.filterTo(linkedSetOf(),::keep);val negative=no.filterTo(linkedSetOf(),::keep)
        if(value)positive.addAll(keys) else negative.addAll(keys)
        return WatchedIndex(positive,negative)
    }
}

/** Separate from the ten-title recent list and its frequent progress writes. Call disk access on IO. */
internal class WatchedStore(context:Context) {
    private val prefs=context.getSharedPreferences(FILE,Context.MODE_PRIVATE)
    fun load(profile:String):WatchedIndex {
        val prefix=HomeTitleVisibility.prefix(profile)
        fun keys(name:String)=runCatching {prefs.getStringSet(prefix+name,emptySet())!!.toSet()}.getOrDefault(emptySet())
        return WatchedIndex(keys("yes"),keys("no"))
    }
    fun mark(profile:String,meta:Meta,value:Boolean):WatchedIndex=synchronized(lock) {
        val before=load(profile);val after=before.changed(meta,value)
        save(profile,before,after)
    }
    /** Retrieved history is completion evidence, but never overrides an explicit local choice. */
    fun learn(profile:String,history:List<Meta>):WatchedIndex=synchronized(lock) {
        val before=load(profile)
        val after=history.filter {it.type=="movie" || it.season!=null && it.episode!=null}.fold(before) {index,meta->
            if(index.explicitStatus(meta)==null)index.changed(meta,true) else index
        }
        save(profile,before,after)
    }
    private fun save(profile:String,before:WatchedIndex,after:WatchedIndex):WatchedIndex {
        if(after!=before) {
            val prefix=HomeTitleVisibility.prefix(profile)
            prefs.edit { putStringSet(prefix+"yes",after.yes); putStringSet(prefix+"no",after.no) }
            version++
        }
        return after
    }
    companion object {
        const val FILE="ember_watched"
        fun playProfile(current:String,requested:String?)=current.takeIf {requested==null || requested==current}
        private val lock=Any()
        var version by mutableIntStateOf(0);private set
        private val writer by lazy {java.util.concurrent.Executors.newSingleThreadExecutor {r->Thread(r,"ember-watched").apply {isDaemon=true}}}
        fun record(context:Context,profile:String,meta:Meta) {
            val app=context.applicationContext
            writer.execute {runCatching {WatchedStore(app).mark(profile,meta,true)}.onFailure {CrashLog.note("Could not save watched status",it)}}
        }
        fun drain(){writer.submit {}.get()}
        fun restored(){synchronized(lock){version++}}
    }
}
