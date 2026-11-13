package app.ember.tv.remote

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.HomeViewModel

/**
 * #241: the phone remote's switch. Off by default; once on, it stays on (and comes back after a
 * restart) until switched off. Its settings live in their own file, never in a backup.
 */
internal object PhoneRemote {
    private var server: RemoteServer? = null
    private var owner: HomeViewModel? = null
    private var authority: RemoteAuth? = null
    var url by mutableStateOf<String?>(null); private set

    fun auth(context: Context): RemoteAuth = authority ?: RemoteAuth(RemoteAuth.prefsStore(context.applicationContext)).also { authority = it }

    private fun prefs(context: Context) = context.getSharedPreferences(RemoteAuth.PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean(ON, false)

    /** The TV's name for the page title: the name set in Android settings, else its model. */
    fun tvName(context: Context): String = runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL ?: "TV"

    /** Starts the server for [vm] when the remote is on; returns its address, or null. */
    @Synchronized fun sync(context: Context, vm: HomeViewModel, accent: String): String? {
        if (!enabled(context)) { stop(); return null }
        if (server?.running == true && owner === vm) return server?.url.also { url = it }
        stop()
        val controller = RemoteController(vm)
        val s = RemoteServer(auth(context), controller::call, { tvName(context) }, { accent })
        val started = runCatching { s.start() }.getOrNull()
        if (started == null) { s.stop(); return null }
        server = s; owner = vm; url = started
        return started
    }

    @Synchronized fun setEnabled(context: Context, vm: HomeViewModel, on: Boolean, accent: String): String? {
        prefs(context).edit().putBoolean(ON, on).apply()
        return if (on) sync(context, vm, accent) else { stop(); null }
    }

    /** When Ember's main screen goes away for good, so does the server it answered for. */
    @Synchronized fun release(vm: HomeViewModel) { if (owner === vm) stop() }

    @Synchronized fun stop() { server?.stop(); server = null; owner = null; url = null }

    private const val ON = "on"
}
