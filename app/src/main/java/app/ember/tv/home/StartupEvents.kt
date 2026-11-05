package app.ember.tv.home

import android.content.*
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.ember.tv.data.Prefs

/** Never launch an app from a boot broadcast: consume the request after Home is visible. */
object StartupEvents {
    private val changes=MutableStateFlow(0)
    val events=changes.asStateFlow()
    private var users=0
    private var registered:BroadcastReceiver?=null
    private var asleep=false
    @Synchronized fun mark(context:Context,reason:String) {
        val store=context.getSharedPreferences("home_runtime",Context.MODE_PRIVATE)
        val now=SystemClock.elapsedRealtime()
        if(store.getString("reason",null)==reason && now-store.getLong("at",Long.MIN_VALUE) in 0..2000)return
        store.edit {putString("reason",reason);putLong("at",now)};changes.value++
    }
    fun pending(context:Context):Pair<String,Long>? {
        val p=context.getSharedPreferences("home_runtime",Context.MODE_PRIVATE)
        val reason=p.getString("reason",null) ?: return null;val at=p.getLong("at",-1)
        return if(SystemClock.elapsedRealtime()-at in 0..180_000)reason to at else null
    }
    fun consume(context:Context,event:Pair<String,Long>) {
        val p=context.getSharedPreferences("home_runtime",Context.MODE_PRIVATE)
        if(p.getString("reason",null)==event.first && p.getLong("at",-1)==event.second)p.edit {remove("reason");remove("at")}
    }
    @Synchronized fun watch(context:Context):()->Unit {
        val app=context.applicationContext
        if(users++==0) {
            val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){when(i.action){
                Intent.ACTION_SCREEN_OFF->asleep=true
                Intent.ACTION_SCREEN_ON,Intent.ACTION_USER_PRESENT->if(asleep){asleep=false;mark(c,"wake")
                    if(LauncherExtrasState(Prefs(c)).options.onWake)HomeButtonService.instance?.openForStartup()}
            }}}
            runCatching {ContextCompat.registerReceiver(app,receiver,IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON);addAction(Intent.ACTION_USER_PRESENT)
            },ContextCompat.RECEIVER_NOT_EXPORTED)}.onSuccess {registered=receiver}
        }
        var closed=false
        return {synchronized(this){if(!closed){closed=true;if(--users==0){registered?.let {runCatching {app.unregisterReceiver(it)}};registered=null;asleep=false}}}}
    }
}
class LauncherBootReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent){if(intent.action==Intent.ACTION_BOOT_COMPLETED){
        StartupEvents.mark(context,"boot")
        if(LauncherExtrasState(Prefs(context)).options.atBoot)HomeButtonService.instance?.openForStartup()
    }}
}
