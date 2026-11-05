package app.ember.tv.home

import androidx.compose.runtime.*
import app.ember.tv.data.Prefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Serializable data class PresentationItem(val id:String,val name:String,val uri:String,val video:Boolean=false)
@Serializable data class LauncherExtras(
    val destination:String="home",val target:String="",val atBoot:Boolean=false,val onWake:Boolean=false,
    val nowPlaying:Boolean=false,val slides:List<PresentationItem> = emptyList(),
    val slideSeconds:Int=10,val keepAwake:Boolean=false,val lockedApps:Set<String> = emptySet(),
) {
    fun normalized()=copy(destination=destination.takeIf {it in setOf("home","app","input","presentation")} ?: "home",
        target=target.take(512),slides=slides.distinctBy {it.id}.take(40),slideSeconds=slideSeconds.coerceIn(5,120))
    fun startup(reason:String,kids:Boolean)=!kids && destination!="home" && when(reason){"boot"->atBoot;"wake"->onWake;else->false}
}
class LauncherExtrasState(private val prefs:Prefs) {
    private val json=Json {ignoreUnknownKeys=true}
    var options by mutableStateOf(runCatching {json.decodeFromString<LauncherExtras>(prefs.launcherExtrasJson).normalized()}.getOrDefault(LauncherExtras()));private set
    fun update(change:(LauncherExtras)->LauncherExtras){options=change(options).normalized();prefs.launcherExtrasJson=json.encodeToString(options)}
    fun addSlide(item:PresentationItem){require(options.slides.size<40){"Maximum 40 presentation items"};update {it.copy(slides=it.slides+item)}}
}
