package app.ember.tv.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.util.Date

internal fun untilNextClockMinute(time:Long)=60_000L-Math.floorMod(time,60_000L)

/** One event-driven clock while subscribed; no 20-second navigation polling in the background. */
private object LauncherClock {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var shared:SharedFlow<String>?=null
    fun format(context:Context)=DateFormat.getTimeFormat(context).format(Date())
    @Synchronized fun flow(context:Context):SharedFlow<String> = shared ?: callbackFlow {
        fun refresh() {trySend(format(context))}
        val receiver=object:BroadcastReceiver() {override fun onReceive(c:Context?,i:Intent?)=refresh()}
        val observer=object:ContentObserver(Handler(Looper.getMainLooper())) {override fun onChange(selfChange:Boolean)=refresh()}
        val registered=runCatching {ContextCompat.registerReceiver(context,receiver,IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK);addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED);addAction(Intent.ACTION_LOCALE_CHANGED)
        },ContextCompat.RECEIVER_NOT_EXPORTED)}.isSuccess
        val observed=runCatching {context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.TIME_12_24),false,observer)}.isSuccess
        refresh()
        val fallback=if(!registered)launch {while(isActive){delay(untilNextClockMinute(System.currentTimeMillis()));refresh()}} else null
        awaitClose {
            fallback?.cancel()
            if(registered)runCatching {context.unregisterReceiver(receiver)}
            if(observed)runCatching {context.contentResolver.unregisterContentObserver(observer)}
        }
    }.distinctUntilChanged().shareIn(scope,SharingStarted.WhileSubscribed(stopTimeoutMillis=0,replayExpirationMillis=0),replay=1).also {shared=it}
}

@Composable internal fun rememberLauncherTime():String {
    val context=LocalContext.current.applicationContext
    val flow=remember(context){LauncherClock.flow(context)}
    val initial=remember(context){LauncherClock.format(context)}
    val time by flow.collectAsStateWithLifecycle(initialValue=initial,minActiveState=androidx.lifecycle.Lifecycle.State.RESUMED)
    return time
}
