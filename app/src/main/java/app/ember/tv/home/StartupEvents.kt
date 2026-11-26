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
    /**
     * #465: an update Ember started. When Android replaces Ember it closes it, leaving whatever was
     * under it (often Android's own Settings); Ember reopens on Home once the new copy is in.
     */
    fun updateStarted(context:Context) {
        context.getSharedPreferences("home_runtime",Context.MODE_PRIVATE).edit(commit=true) {putLong("update_at",System.currentTimeMillis())}
    }
    /** True once, right after an update Ember started in the last half hour was installed. */
    fun takeUpdate(context:Context):Boolean {
        val p=context.getSharedPreferences("home_runtime",Context.MODE_PRIVATE)
        val at=p.getLong("update_at",0L).takeIf {it>0} ?: return false
        p.edit(commit=true) {remove("update_at")}
        return System.currentTimeMillis()-at in 0..UPDATE_WINDOW_MS
    }
    const val UPDATE_WINDOW_MS=30*60_000L
    /** Whether the service should open Home: Ember was just updated by itself. */
    fun pendingUpdate(context:Context):Boolean=pending(context)?.first=="update"
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
    override fun onReceive(context:Context,intent:Intent){when(intent.action){
        Intent.ACTION_BOOT_COMPLETED->{
            StartupEvents.mark(context,"boot")
            if(LauncherExtrasState(Prefs(context)).options.atBoot)HomeButtonService.instance?.openForStartup()
        }
        // #465: the new Ember is in; open Home again, as before the update.
        Intent.ACTION_MY_PACKAGE_REPLACED->if(StartupEvents.takeUpdate(context)) {
            StartupEvents.mark(context,"update")
            val service=HomeButtonService.instance
            if(service!=null)service.openAfterUpdate()
            else runCatching {
                context.startActivity(Intent(context,app.ember.tv.MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            }
        }
    }}
}
