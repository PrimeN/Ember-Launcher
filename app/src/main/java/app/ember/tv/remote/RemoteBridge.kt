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
        fun search(query: String)
        fun home()
    }

    @Volatile var focusedText: TextTarget? = null
    @Volatile var screen: Screen? = null

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
