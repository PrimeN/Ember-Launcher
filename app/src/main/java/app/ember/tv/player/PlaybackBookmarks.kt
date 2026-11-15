package app.ember.tv.player

import android.content.Context
import app.ember.tv.data.LocalActivity
import app.ember.tv.data.CachePolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class PlaybackBookmark(val id:String,val positionMs:Long,val name:String,val created:Long)

/** Titles are keyed by digest; labels contain only the user's text, never transport addresses. */
internal class PlaybackBookmarks(context:Context,scope:String) {
    private val store=context.getSharedPreferences("bookmarks",Context.MODE_PRIVATE)
    private val prefix=LocalActivity.prefix(scope)
    private fun key(title:String)=prefix+CachePolicy.digest(title)
    fun read(title:String):List<PlaybackBookmark> = decode(store.getString(key(title),null))
    fun add(title:String,position:Long,name:String):Boolean = synchronized(lock) {
        if(position<0 || title.isBlank() || (!store.contains(key(title)) && store.all.size>=512))return@synchronized false
        val values=read(title)
        if(values.size>=50)return@synchronized false
        val at=position.coerceAtMost(604_800_000L)
        val updated=values+PlaybackBookmark(java.util.UUID.randomUUID().toString(),at,label(name).ifBlank {"Bookmark"},System.currentTimeMillis())
        val edit=store.edit().putString(key(title),json.encodeToString(updated.sortedBy {it.positionMs}))
        edit.commit()
    }
    fun rename(title:String,id:String,name:String):Boolean = synchronized(lock) {
        val values=read(title);val text=label(name)
        if(text.isBlank() || values.none {it.id==id})return@synchronized false
        store.edit().putString(key(title),json.encodeToString(values.map {if(it.id==id)it.copy(name=text) else it})).commit()
    }
    fun remove(title:String,id:String):Boolean = synchronized(lock) {
        val values=read(title);if(values.none {it.id==id})return@synchronized false
        val updated=values.filterNot {it.id==id};val edit=store.edit()
        if(updated.isEmpty())edit.remove(key(title)) else edit.putString(key(title),json.encodeToString(updated))
        edit.commit()
    }
    companion object {
        private val lock=Any()
        private val json=Json {ignoreUnknownKeys=true}
        fun valid(raw:String):Boolean {
            if(raw.length>64*1024)return false
            val values=runCatching {json.decodeFromString<List<PlaybackBookmark>>(raw)}.getOrNull() ?: return false
            return values.size<=50 && values.map {it.id}.distinct().size==values.size && values.all {
                it.id.isNotBlank() && it.id.length<=80 && it.positionMs in 0..604_800_000L && it.name.isNotBlank() && it.name==label(it.name)
            }
        }
        fun label(value:String)=value.filterNot {it.isISOControl()}.trim().take(80)
        fun decode(raw:String?):List<PlaybackBookmark> = raw?.takeIf {it.length<=64*1024}?.let {
            runCatching {json.decodeFromString<List<PlaybackBookmark>>(it)}.getOrNull()
                ?.take(50)?.filter {b->b.id.isNotBlank() && b.positionMs in 0..604_800_000L && b.name==label(b.name)}?.distinctBy {b->b.id}?.sortedBy {b->b.positionMs}
        }.orEmpty()
    }
}
