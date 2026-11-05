package app.ember.tv.home

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import app.ember.tv.MainActivity
import app.ember.tv.data.CrashLog
import app.ember.tv.data.Prefs

/** System-bound Home redirection; window contents are never requested or inspected. */
class HomeButtonService : AccessibilityService() {
    private val prefs by lazy {Prefs(applicationContext)}
    private val launches=HomeLaunchGate()
    private val keys=HomeKeySequence()
    private var windows:HomeWindowMatcher?=null
    private val callbacks=HomeCallbackGuard({issue,error,first->
        HomeServiceRuntime.problem(issue)
        if(first)CrashLog.note("Home button service: ${issue.name}",error)
    },HomeServiceRuntime::recovered)

    private var stopWake:(()->Unit)?=null
    fun openForStartup() {openEmber()}
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance=this
        stopWake?.invoke();stopWake=StartupEvents.watch(this)
        HomeServiceRuntime.connected()
        applyConfig()
        callbacks.attempt(HomeServiceIssue.STARTUP) {
            if(prefs.homeOverride && prefs.homeAtBoot && SystemClock.elapsedRealtime()<3*60_000L && !DefaultHome.emberIsDefault(this))openEmber()
        }
    }

    override fun onDestroy() {
        disconnected()
        super.onDestroy()
    }
    override fun onUnbind(intent:Intent?):Boolean {
        disconnected()
        return super.onUnbind(intent)
    }
    private fun disconnected() {
        stopWake?.invoke();stopWake=null
        keys.reset()
        if(instance===this){instance=null;HomeServiceRuntime.disconnected()}
    }

    /** Retain Android's static capabilities; request key filtering only when explicitly selected. */
    fun applyConfig() {
        callbacks.attempt(HomeServiceIssue.HOME_DETECTION) {
            windows=HomeWindowMatcher(packageName,DefaultHome.STOCK_HOMES,DefaultHome.systemHomeTargets(this))
        }
        callbacks.attempt(HomeServiceIssue.CONFIGURATION) {
            val info=serviceInfo ?: return@attempt
            val catchKey=prefs.homeOverride && prefs.homeOverrideMode=="key"
            info.flags=if(catchKey)info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                else info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
            info.eventTypes=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.packageNames=(windows?.packages ?: DefaultHome.STOCK_HOMES).toTypedArray()
            serviceInfo=info
        }
    }

    private fun active()=prefs.homeOverride && SystemClock.elapsedRealtime()>bypassUntil && !DefaultHome.emberIsDefault(this)

    override fun onAccessibilityEvent(event:AccessibilityEvent?) {
        if(event?.eventType!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)return
        callbacks.attempt(HomeServiceIssue.WINDOW_EVENT) {
            val detector=windows ?: HomeWindowMatcher(packageName,DefaultHome.STOCK_HOMES,emptySet())
            if(detector.matches(event.packageName?.toString(),event.className?.toString()) && active())openEmber()
        }
    }

    override fun onKeyEvent(event:KeyEvent?):Boolean {
        if(event?.keyCode!=KeyEvent.KEYCODE_HOME)return false
        if(event.action==KeyEvent.ACTION_UP)return keys.up()
        var handled=false
        callbacks.attempt(HomeServiceIssue.HOME_KEY) {
            if(event.action==KeyEvent.ACTION_DOWN)handled=keys.down(event.repeatCount>0,
                !event.isCanceled && prefs.homeOverrideMode=="key" && active(),::openEmber)
        }
        return handled
    }
    override fun onInterrupt(){keys.reset()}

    private fun openEmber():Boolean=launches.open(SystemClock.elapsedRealtime()) {
        callbacks.attempt(HomeServiceIssue.LAUNCH) {
            startActivity(Intent(this,MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
    }

    companion object {
        @Volatile var instance:HomeButtonService?=null
        @Volatile var bypassUntil=0L
    }
}
