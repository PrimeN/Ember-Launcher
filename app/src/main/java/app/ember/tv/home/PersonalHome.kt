package app.ember.tv.home

import androidx.compose.runtime.*
import app.ember.tv.data.LightHomeOptions
import app.ember.tv.data.Prefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Serializable data class AppFolder(val id: String, val name: String, val packages: List<String> = emptyList())
@Serializable data class AppArt(val id: String, val data: String)
@Serializable data class HomeLayoutSnapshot(
    val order: List<String> = emptyList(), val hidden: Set<String> = emptySet(), val pageHidden: Set<String> = emptySet(),
    val sections: List<Pair<String, Boolean>> = emptyList(), val listStyles: Map<String,String> = emptyMap(),
    val appRows: Int = 1, val wallpaper: String = "art", val carousel: LightHomeOptions = LightHomeOptions(), val heroCatalog: String? = null,
) {
    fun apply(p: Prefs) { p.order=order;p.hidden=hidden;p.appsPageOff=pageHidden;p.homeLayout=sections;p.homeListStyle=listStyles
        p.homeAppRows=appRows;p.lightWallpaper=wallpaper;p.lightHome=carousel;p.heroCatalog=heroCatalog }
    companion object { fun capture(p: Prefs) = HomeLayoutSnapshot(p.order,p.hidden,p.appsPageOff,p.homeLayout,p.homeListStyle,p.homeAppRows,p.lightWallpaper,p.lightHome,p.heroCatalog) }
}
@Serializable data class PersonalHome(
    val layout: HomeLayoutSnapshot? = null, val folders: List<AppFolder> = emptyList(), val artwork: Map<String, AppArt> = emptyMap(),
    val mutedApps: Set<String> = emptySet(),
    val dismissed: Map<String,Long> = emptyMap(), val hideShortClips: Boolean = false, val hideCredits: Boolean = true,
    val wallpaperColours: Boolean = true,
)

/** Existing preferences remain the active desktop; inactive desktops are retained separately for backward-compatible restores. */
class PersonalHomes(private val prefs: Prefs) {
    private val json=Json {ignoreUnknownKeys=true}
    private var owner by mutableStateOf(prefs.currentProfile.takeIf {id->prefs.profiles.any {it.id==id}} ?: prefs.profiles.firstOrNull()?.id ?: app.ember.tv.data.Profile.MAIN)
    private var records by mutableStateOf(runCatching {json.decodeFromString<Map<String,PersonalHome>>(prefs.personalHomesJson)}.getOrDefault(emptyMap()))
    val current: PersonalHome get() = records[owner] ?: PersonalHome()
    fun canStoreArtwork(pkg:String,art:AppArt)=
        (pkg in current.artwork || current.artwork.size<100) &&
        records.values.sumOf {p->p.artwork.values.sumOf {it.data.length.toLong()}}-(current.artwork[pkg]?.data?.length ?: 0)+art.data.length<=6_000_000
    fun update(change: (PersonalHome)->PersonalHome) { records=records+(owner to change(current));save() }
    fun switchTo(id: String, valid: Set<String>) {
        if (id != owner) {
            val previous=current.copy(layout=HomeLayoutSnapshot.capture(prefs))
            val next=records[id] ?: PersonalHome(layout=previous.layout)
            records=records+(owner to previous)+(id to next);owner=id
            next.layout?.apply(prefs)
        }
        records=records.filterKeys {it in valid};save()
    }
    private fun save() {prefs.personalHomesJson=json.encodeToString(records)}
    fun dismiss(id:String, engaged:Long) = update {it.copy(dismissed=(it.dismissed+(id to engaged)).entries.toList().takeLast(300).associate {e->e.toPair()})}
    fun removeProfile(id:String){records=records-id;save()}
    fun folderFor(pkg:String) = current.folders.firstOrNull {pkg in it.packages}
    fun putFolder(id:String?, name:String, packages:List<String>) {
        val title=name.trim().replace(Regex("\\s+")," ").take(40);require(title.isNotBlank())
        val key=id ?: java.util.UUID.randomUUID().toString()
        update {p->require(p.folders.any {it.id==id} || p.folders.size<24) {"Maximum 24 folders"}
            val replacement=AppFolder(key,title,packages.distinct().take(200))
            val folders=p.folders.map {if(it.id==key)replacement else it.copy(packages=it.packages-packages.toSet())}
            p.copy(folders=if(folders.any {it.id==key})folders else folders+replacement) }
    }
    fun removeFolder(id:String) = update {it.copy(folders=it.folders.filterNot {f->f.id==id})}
    fun assign(pkg:String, folder:String?) = update {p->p.copy(folders=p.folders.map {f->f.copy(packages=if(f.id==folder)(f.packages+pkg).distinct() else f.packages-pkg)})}
}
