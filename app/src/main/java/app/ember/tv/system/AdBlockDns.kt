package app.ember.tv.system

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.ApiKeys
import app.ember.tv.data.Features
import app.ember.tv.data.Prefs

/**
 * Ad blocking for the whole TV (#409): the TV's Private DNS points at the person's NextDNS profile
 * (Settings > API keys), so ads and trackers are blocked in every app. Switched off in
 * Settings > Features, Ember puts back the Private DNS that was there before. Changing Private DNS
 * needs System access once (Ember then keeps the permission).
 */
object AdBlockDns {
    const val FIELD = "NEXTDNS_PROFILE"
    const val SERVICE = "NextDNS"

    enum class State { ON, NO_PROFILE, NEEDS_ACCESS, FEATURE_OFF }

    /** The last state seen, for Settings. */
    var state by mutableStateOf(State.NO_PROFILE); private set

    private const val MODE = "private_dns_mode"
    private const val SPECIFIER = "private_dns_specifier"
    private const val HOSTNAME = "hostname"
    private const val STORE = "ember_system"
    private const val OURS = "adblock.host"
    private const val PREVIOUS_MODE = "adblock.previousMode"
    private const val PREVIOUS_HOST = "adblock.previousHost"

    private val profileId = Regex("^[a-z0-9]{4,16}$")
    private val hostName = Regex("^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")

    /**
     * The DNS-over-TLS host for what was entered: a NextDNS profile ID ("abc123"), a NextDNS link
     * ("https://dns.nextdns.io/abc123") or any Private DNS host name. Null when it is neither.
     */
    fun host(entered: String): String? {
        val text = entered.trim().lowercase().removePrefix("https://").removePrefix("tls://").trimEnd('/')
        if (text.isEmpty()) return null
        if (text.startsWith("dns.nextdns.io/")) return text.removePrefix("dns.nextdns.io/").takeIf { profileId.matches(it) }?.let { "$it.dns.nextdns.io" }
        if (profileId.matches(text)) return "$text.dns.nextdns.io"
        return text.takeIf { hostName.matches(it) }
    }

    fun featureOn(context: Context): Boolean = Features.AD_BLOCK !in Prefs(context).featuresOff

    /** Private DNS is set to [host] right now. */
    fun active(context: Context, host: String): Boolean {
        val cr = context.contentResolver
        return Settings.Global.getString(cr, MODE) == HOSTNAME && Settings.Global.getString(cr, SPECIFIER) == host
    }

    /** Reads the current state without changing anything. */
    fun refresh(context: Context): State {
        val host = host(ApiKeys.get(FIELD))
        return when {
            host == null -> State.NO_PROFILE
            !featureOn(context) -> State.FEATURE_OFF
            active(context, host) -> State.ON
            else -> State.NEEDS_ACCESS
        }.also { state = it }
    }

    /**
     * Makes Private DNS match the Features switch and the profile: on with a profile sets it, off
     * (or no profile) puts back what was there, but only if Private DNS is still Ember's.
     */
    fun sync(context: Context): State {
        val app = context.applicationContext
        val cr = app.contentResolver
        val store = app.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val host = host(ApiKeys.get(FIELD))
        val canWrite = SystemAccess.canWriteSettings(app)
        val ours = store.getString(OURS, null)
        val mode = runCatching { Settings.Global.getString(cr, MODE) }.getOrNull()
        val current = runCatching { Settings.Global.getString(cr, SPECIFIER) }.getOrNull()
        if (host != null && featureOn(app)) {
            if (mode == HOSTNAME && current == host) {
                store.edit().putString(OURS, host).apply()
                return State.ON.also { state = it }
            }
            if (!canWrite) return State.NEEDS_ACCESS.also { state = it }
            val wrote = runCatching {
                if (ours == null) store.edit().putString(PREVIOUS_MODE, mode).putString(PREVIOUS_HOST, current).apply()
                Settings.Global.putString(cr, SPECIFIER, host)
                Settings.Global.putString(cr, MODE, HOSTNAME)
            }.getOrDefault(false)
            if (!wrote) return State.NEEDS_ACCESS.also { state = it }
            store.edit().putString(OURS, host).apply()
            return State.ON.also { state = it }
        }
        if (ours != null && mode == HOSTNAME && current == ours && canWrite) {
            runCatching {
                Settings.Global.putString(cr, MODE, store.getString(PREVIOUS_MODE, null) ?: "opportunistic")
                Settings.Global.putString(cr, SPECIFIER, store.getString(PREVIOUS_HOST, null))
            }
        }
        if (ours != null && (mode != HOSTNAME || current != ours || canWrite)) {
            store.edit().remove(OURS).remove(PREVIOUS_MODE).remove(PREVIOUS_HOST).apply()
        }
        return (if (host == null) State.NO_PROFILE else State.FEATURE_OFF).also { state = it }
    }
}
