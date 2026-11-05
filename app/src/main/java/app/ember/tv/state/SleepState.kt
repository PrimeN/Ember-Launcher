package app.ember.tv.state

import androidx.compose.runtime.mutableIntStateOf
import app.ember.tv.data.SleepTimer
import app.ember.tv.ui.QuickActions

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

/** Quick settings > Sleep timer. Split out of HomeViewModel. */
class SleepState(
    private val app: Application,
    private val scope: CoroutineScope,
) {
    var sleepMinutes by mutableIntStateOf(0); private set
    var sleepLeftText by mutableStateOf<String?>(null); private set
    private var sleepJob: kotlinx.coroutines.Job? = null
    internal var nowMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() }
    internal var pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
    internal var onSleep: () -> Unit = { SleepTimer.fire(); QuickActions.screensaver(app) }

    /** Sleep timer: after [minutes], stops playback and starts the screensaver. 0 turns it off. */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepMinutes = minutes.coerceAtLeast(0)
        sleepLeftText = null
        if (minutes <= 0) return
        val end = nowMillis() + minutes * 60_000L
        sleepJob = scope.launch {
            while (true) {
                val left = end - nowMillis()
                if (left <= 0) break
                val m = (left + 59_999) / 60_000
                sleepLeftText = "Sleeping in $m min. Stops what is playing and starts the screensaver."
                pause(30_000L.coerceAtMost(left))
            }
            ensureActive()
            sleepMinutes = 0
            sleepLeftText = null
            onSleep()
        }
    }
}
