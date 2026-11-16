package app.ember.tv.data

import android.content.Context
import androidx.core.content.edit

/**
 * Apps opened from Ember, most recent first, per profile, for the "Recently used apps" widget.
 * Ember is the launcher, so it records the apps it opens itself; no usage-access permission needed.
 */
internal class RecentApps(context: Context) {
    private val store = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun key(profile: String) = HomeTitleVisibility.prefix(profile) + "apps"

    fun list(profile: String): List<String> =
        store.getString(key(profile), null)?.split('\n')?.filter { it.isNotBlank() && it.length <= 255 }?.distinct()?.take(LIMIT).orEmpty()

    fun record(profile: String, pkg: String) {
        if (pkg.isBlank() || pkg.length > 255 || '\n' in pkg) return
        val next = (listOf(pkg) + list(profile).filter { it != pkg }).take(LIMIT)
        store.edit { putString(key(profile), next.joinToString("\n")) }
    }

    fun forget(profile: String, pkg: String) = store.edit { putString(key(profile), list(profile).filter { it != pkg }.joinToString("\n")) }

    fun removeProfile(profile: String) = store.edit { remove(key(profile)) }

    companion object {
        const val FILE = "recent_apps"
        const val LIMIT = 20
    }
}
