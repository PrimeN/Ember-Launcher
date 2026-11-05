package app.ember.tv.state

import androidx.compose.runtime.mutableIntStateOf
import app.ember.tv.data.Pin

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The profile PIN: asking for it, checking it (with a lockout), setting it. Split out of HomeViewModel. */
class PinState(
    private val prefs: Prefs,
    private val notify: (String) -> Unit,
) {
    internal var appLocks:()->Boolean={false}
    var revision by mutableIntStateOf(0);private set
    val pinSet: Boolean get() = prefs.pinHash != null
    /** A PIN request on screen: what it is for, and what happens when the right PIN is entered. */
    var pinPrompt by mutableStateOf<Pair<String, () -> Unit>?>(null)

    fun withPin(reason: String, action: () -> Unit) {
        if (!pinSet) action() else pinPrompt = reason to action
    }

    /** True and runs the waiting action when [pin] is right. */
    private var pinFails = 0
    private var pinLockedUntil = 0L
    internal var nowMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    /** Seconds left before the PIN can be tried again (after five wrong tries), or 0. */
    val pinWait: Long get() = ((pinLockedUntil - nowMillis()).coerceAtLeast(0) + 999) / 1000

    fun enterPin(pin:String)=checkPin(pin,true)
    fun verifyPin(pin:String)=checkPin(pin,false)
    private fun checkPin(pin: String,runPending:Boolean): Boolean {
        if (pinWait > 0) return false
        val ok = prefs.pinHash == Pin.hash(pin, prefs.pinSalt)
        if (!ok && ++pinFails >= 5) {
            // Five wrong tries: wait a minute, then a little longer each time.
            pinLockedUntil = nowMillis() + 60_000L * (pinFails - 4)
        }
        if (ok) pinFails = 0
        if (ok && runPending) {
            val action = pinPrompt?.second
            pinPrompt = null
            action?.invoke()
        }
        return ok
    }

    fun setPin(pin: String?) {
        if(pin==null && appLocks()) {notify("Remove app PIN locks before removing the PIN.");return}
        if(pin==null && prefs.profiles.any {app.ember.tv.data.KidsContentPolicy.restricted(it)}) {
            notify("Remove Kids content locks before removing the PIN.")
            return
        }
        prefs.pinHash = pin?.let { Pin.hash(it, prefs.pinSalt) }
        revision++
        notify(if (pin == null) "PIN removed." else "PIN saved.")
    }
}
