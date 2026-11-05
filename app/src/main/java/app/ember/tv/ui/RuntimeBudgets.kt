package app.ember.tv.ui

import androidx.core.content.edit

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.*
import app.ember.tv.BuildConfig
import app.ember.tv.data.FrameBudgetEvidence

/** Device-specific evidence stays outside user backups and never overrides manual performance modes. */
internal object RuntimeBudgets {
    var slowAuto by mutableStateOf(false);private set
    var pressured by mutableStateOf(false);private set
    @Volatile private var lastInput=0L
    @Volatile private var revision=0
    private val main=Handler(Looper.getMainLooper())
    private var pressureRevision=0
    fun input(){lastInput=SystemClock.elapsedRealtime()}
    fun reset(context:Context) {
        revision++;slowAuto=false
        context.applicationContext.getSharedPreferences("performance-runtime",0).edit { clear() }
    }
    fun observe(activity:Activity):()->Unit {
        val context=activity.applicationContext
        val prefs=context.getSharedPreferences("performance-runtime",0)
        slowAuto=prefs.getBoolean("slow",false) && System.currentTimeMillis()-prefs.getLong("at",0) in 0..7*86_400_000L
        if(BuildConfig.DEBUG)return {} // Debug overhead must not teach release Auto the wrong budget.
        val rate=activity.window.decorView.display?.refreshRate?.takeIf {it.isFinite()&&it>0} ?: 60f
        val thread=HandlerThread("ember-frame-budget").apply {start()}
        var evidence=FrameBudgetEvidence((1_000_000_000.0/rate).toLong())
        val attached=java.util.concurrent.atomic.AtomicBoolean(true)
        var seen=revision;val began=SystemClock.elapsedRealtime()
        val listener=Window.OnFrameMetricsAvailableListener {_,metrics,_->
            if(seen!=revision){seen=revision;evidence=FrameBudgetEvidence((1_000_000_000.0/rate).toLong())}
            val now=SystemClock.elapsedRealtime()
            if(attached.get() && now-began>2000 && now-lastInput in 0..1500 && !pressured && !slowAuto && evidence.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))) {
                val observedRevision=seen
                main.post {if(attached.get() && revision==observedRevision){slowAuto=true;prefs.edit { putBoolean("slow",true); putLong("at",System.currentTimeMillis()) }}}
            }
        }
        try {activity.window.addOnFrameMetricsAvailableListener(listener,Handler(thread.looper))}
        catch(_:RuntimeException){thread.quitSafely();return {}}
        return {attached.set(false);runCatching {activity.window.removeOnFrameMetricsAvailableListener(listener)};thread.quitSafely()}
    }
    fun trim(level:Int) {
        if(!pressureLevel(level))return
        main.post {
            pressured=true;val generation=++pressureRevision
            main.postDelayed({if(generation==pressureRevision)pressured=false},30_000)
        }
        AppArtwork.clear();FrostCache.clear()
    }
    fun pressureLevel(level:Int)=level==10 || level==15 || level>=40
}
