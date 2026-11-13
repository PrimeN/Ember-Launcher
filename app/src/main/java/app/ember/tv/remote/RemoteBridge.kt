package app.ember.tv.remote

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #241: how the phone remote reaches the screen. The text field being edited registers itself so
 * the phone's keyboard types into it; Ember's main screen registers to play a source and open
 * search. Everything runs on the main thread.
 */
internal object RemoteBridge {
    /** A text field on the TV that the phone's keyboard can fill. */
    class TextTarget(val value: () -> String, val set: (String) -> Unit, val submit: () -> Unit, val password: Boolean)

    /** What Ember's main screen offers the remote. */
    interface Screen {
        fun play(request: app.ember.tv.player.PlayRequest)
        /** After a question on the TV: play with the built-in player or another app, as picked. */
        fun playWith(request: app.ember.tv.player.PlayRequest, target: String)
        fun search(query: String)
        fun home()
    }

    @Volatile var focusedText: TextTarget? = null
    @Volatile var screen: Screen? = null
    /** The Ember screen in front, if any: the launcher, the player or a dialog activity. */
    @Volatile private var resumed: java.lang.ref.WeakReference<android.app.Activity>? = null

    fun install(app: android.app.Application) = app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: android.app.Activity) { resumed = java.lang.ref.WeakReference(activity) }
        override fun onActivityPaused(activity: android.app.Activity) { if (resumed?.get() === activity) resumed = null }
        override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
        override fun onActivityStarted(activity: android.app.Activity) {}
        override fun onActivityStopped(activity: android.app.Activity) {}
        override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
        override fun onActivityDestroyed(activity: android.app.Activity) { if (resumed?.get() === activity) resumed = null }
    })

    /** Whether an Ember screen is in front, so keys from the phone have somewhere to go. */
    val inFront: Boolean get() = resumed?.get()?.let { !it.isFinishing } == true

    /**
     * #242: a key from the phone, handed to the Ember screen in front exactly as the TV remote's
     * key would be (Android does not let apps inject keys into the system, so the earlier way failed).
     * Back falls through to the screen's back handling when nothing on it used the key.
     */
    fun press(code: Int): Boolean = onMain {
        val activity = resumed?.get()?.takeIf { !it.isFinishing } ?: return@onMain false
        val now = android.os.SystemClock.uptimeMillis()
        val down = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, code, 0, 0,
            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_DPAD)
        val up = android.view.KeyEvent.changeAction(down, android.view.KeyEvent.ACTION_UP)
        val used = activity.dispatchKeyEvent(down) or activity.dispatchKeyEvent(up)
        if (!used && code == android.view.KeyEvent.KEYCODE_BACK) {
            (activity as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.onBackPressed() ?: activity.finish()
        }
        true
    } ?: false

    private val main = Handler(Looper.getMainLooper())

    /** Runs [block] on the main thread and waits for it, at most [timeoutMs]; null if it did not finish. */
    fun <T> onMain(timeoutMs: Long = 3000, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        var error: Throwable? = null
        val done = CountDownLatch(1)
        main.post { try { result = block() } catch (t: Throwable) { error = t } finally { done.countDown() } }
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        error?.let { throw it }
        return result
    }

    /** Types into the field being edited. Returns false when no field is. */
    fun type(text: String, replace: Boolean, submit: Boolean): Boolean = onMain {
        val target = focusedText ?: return@onMain false
        target.set(if (replace) text else target.value() + text)
        if (submit) target.submit()
        true
    } ?: false

    fun backspace(): Boolean = onMain {
        val target = focusedText ?: return@onMain false
        target.set(target.value().dropLast(1)); true
    } ?: false
}
