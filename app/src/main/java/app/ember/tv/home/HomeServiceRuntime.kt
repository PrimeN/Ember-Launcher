package app.ember.tv.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class HomeServiceIssue { CONFIGURATION, HOME_DETECTION, STARTUP, WINDOW_EVENT, HOME_KEY, LAUNCH }
internal data class HomeServiceHealth(val connected:Boolean=false,val issue:HomeServiceIssue?=null)
internal object HomeServiceRuntime {
    private val current=MutableStateFlow(HomeServiceHealth())
    val health=current.asStateFlow()
    fun connected(){current.value=HomeServiceHealth(connected=true)}
    fun disconnected(){current.value=current.value.copy(connected=false)}
    fun problem(issue:HomeServiceIssue){current.value=current.value.copy(issue=issue)}
    fun recovered(issue:HomeServiceIssue){if(current.value.issue==issue)current.value=current.value.copy(issue=null)}
}

/** Framework callbacks must not die because a configuration/package-manager call failed. */
internal class HomeCallbackGuard(
    private val failure:(HomeServiceIssue,RuntimeException,Boolean)->Unit,
    private val success:(HomeServiceIssue)->Unit={},
) {
    private val reported=mutableSetOf<HomeServiceIssue>()
    fun attempt(issue:HomeServiceIssue,action:()->Unit):Boolean = try {
        action();reported.remove(issue);runCatching {success(issue)};true
    } catch(error:RuntimeException) {
        runCatching {failure(issue,error,reported.add(issue))};false
    }
}

/** A first launch is never throttled, and failed launches remain immediately retryable. */
internal class HomeLaunchGate {
    private var last:Long?=null
    fun open(now:Long,launch:()->Boolean):Boolean {
        if(last?.let {now>=it && now-it<1_000}==true)return true
        return launch().also {if(it)last=now}
    }
}

/** Only consume Home after a successful launch. Keep the matching Up balanced, without relaunching. */
internal class HomeKeySequence {
    private var handled=false
    fun down(repeated:Boolean,allowed:Boolean,launch:()->Boolean):Boolean {
        if(repeated)return handled
        handled=false
        if(allowed)handled=launch()
        return handled
    }
    fun up():Boolean=handled.also {handled=false}
    fun reset(){handled=false}
}

internal data class HomeWindowTarget(val packageName:String,val className:String)
internal class HomeWindowMatcher(private val ownPackage:String,private val legacy:Set<String>,private val targets:Set<HomeWindowTarget>) {
    val packages:Set<String> =(legacy+targets.map {it.packageName})-ownPackage
    fun matches(pkg:String?,window:String?):Boolean {
        if(pkg.isNullOrBlank() || pkg==ownPackage)return false
        val inPackage=targets.filter {it.packageName==pkg}
        if(inPackage.isEmpty())return pkg in legacy
        return window!=null && inPackage.any {it.className==window}
    }
}
