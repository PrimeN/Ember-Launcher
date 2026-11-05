package app.ember.tv.data

import android.content.Context
import androidx.compose.runtime.*
import app.ember.tv.mediaserver.ServerRef
import app.ember.tv.tracker.TokenStore
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Profile-local identity references; no account token, signed URL, or password is copied. */
@Serializable
data class HomeShortcut(val kind:String,val label:String,val ref:String,val folder:String?=null) {
    val id:String get()=AddonAccess.key("$kind|$ref|${folder.orEmpty()}")
    fun valid():Boolean = label.isNotBlank() && label.length<=160 && when(kind) {
        "server" -> ServerRef.parse(ref)!=null
        "collection" -> ref.isNotBlank() && !folder.isNullOrBlank()
        "network" -> ref.isNotBlank() && runCatching {
            require(folder.orEmpty().split('/').none {it==".."||it=="."||'\\' in it||it.any(Char::isISOControl)})
        }.isSuccess
        else -> false
    }
}
enum class ShortcutWrite(val message:String?) {
    SAVED(null), INVALID("This folder cannot be pinned. Check its name and reference."),
    FULL("Home supports up to 40 shortcuts. Remove one before adding another."),
    UNAVAILABLE("Encrypted storage is unavailable. The shortcut was not changed.")
}
class HomeShortcutStore(context:Context) {
    private val store=TokenStore(context)
    private val json=Json {ignoreUnknownKeys=true}
    fun read(profile:String):List<HomeShortcut> = runCatching {
        json.decodeFromString<List<HomeShortcut>>(store.get("home.shortcuts.$profile")?:"[]").filter {it.valid()}.distinctBy {it.id}.take(40)
    }.getOrDefault(emptyList())
    companion object {private val lock=Any()}
    fun toggle(profile:String,value:HomeShortcut):ShortcutWrite = synchronized(lock) {
        val safe=value.copy(label=value.label.trim().take(160))
        if(!safe.valid())return@synchronized ShortcutWrite.INVALID
        val old=read(profile)
        val exists=old.any {it.id==safe.id}
        if(!exists && old.size>=40)return@synchronized ShortcutWrite.FULL
        val updated=if(exists)old.filterNot {it.id==safe.id} else old+safe
        if(store.putEncrypted("home.shortcuts.$profile",json.encodeToString(updated)))ShortcutWrite.SAVED else ShortcutWrite.UNAVAILABLE
    }
}
class HomeShortcuts(context:Context,private val scope:CoroutineScope,private val profile:()->String,private val notice:(String)->Unit) {
    private val store=HomeShortcutStore(context)
    var items by mutableStateOf<List<HomeShortcut>>(emptyList());private set
    private var revision=0L
    private var loadedProfile:String?=null
    fun reload() {
        val who=profile();val visit=++revision
        if(loadedProfile!=who){items=emptyList();loadedProfile=who}
        scope.launch {val loaded=withContext(Dispatchers.IO){store.read(who)};ensureActive();if(who==profile()&&visit==revision)items=loaded}
    }
    fun contains(value:HomeShortcut)=items.any {it.id==value.id}
    fun toggle(value:HomeShortcut) {
        val who=profile();val visit=++revision
        scope.launch {
            val (status,updated)=withContext(Dispatchers.IO){val status=store.toggle(who,value);status to if(status==ShortcutWrite.SAVED)store.read(who) else null}
            ensureActive()
            if(who==profile()&&visit==revision) {
                if(updated==null)notice(status.message ?: "Could not change this shortcut.")
                else {items=updated;notice(if(contains(value))"Pinned to Home. Reorder in Home settings." else "Removed from Home shortcuts.")}
            }
        }
    }
}
