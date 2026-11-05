package app.ember.tv.data

import androidx.core.content.edit

import android.content.Context
import android.content.SharedPreferences

/** Persist only hashed source identities and successful timestamps, never names or URLs. */
object SourceFreshness {
    private var prefs: SharedPreferences? = null
    fun init(context: Context) { prefs = context.applicationContext.getSharedPreferences("source-freshness", Context.MODE_PRIVATE) }
    private fun key(namespace: String, id: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest("$namespace:$id".toByteArray()).joinToString("") { "%02x".format(it) }
    fun read(namespace: String?, id: String): Long? = namespace?.let {
        prefs?.getLong(key(it, id), 0L)?.takeIf { time -> time > 0 }
    }
    fun write(namespace: String?, id: String, time: Long) {
        if (namespace != null) prefs?.edit { putLong(key(namespace, id), time) }
    }
}
