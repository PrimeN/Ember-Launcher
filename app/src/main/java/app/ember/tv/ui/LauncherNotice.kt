package app.ember.tv.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * #331: messages from the launcher's screens appear as Ember's own notice while Ember is in front,
 * like every other message there. With Ember in the background (an input or a TV menu just opened),
 * the system's pop-up is the only thing that can be seen, so it is used then.
 */
object LauncherNotice {
    @Volatile internal var sink: ((String) -> Unit)? = null

    fun show(context: Context, text: String) {
        val inEmber = sink
        if (inEmber != null) Handler(Looper.getMainLooper()).post { inEmber(text) }
        else Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
    }

    fun show(context: Context, text: Int) = show(context, context.getString(text))
}
