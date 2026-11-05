package app.ember.tv.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The app icon and TV banner follow the accent colour (Settings > Appearance): each colourway is
 * an activity-alias in the manifest with its own icon and banner, and only the chosen one is
 * switched on. Ember as Home is not affected (that is MainActivity itself, always on).
 */
// Holds the application context only (set once from EmberApp), never an Activity.
@android.annotation.SuppressLint("StaticFieldLeak")
object AppIcon {
    /** Alias names in the order of ui/Theme.kt's Accents. */
    private val ALIASES = listOf("IconEmber", "IconEmberGrey", "IconBlue", "IconGreen", "IconRose")

    @Volatile private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** Switches the launcher icon to the colourway at [index]; nothing happens if it is already on. */
    fun sync(index: Int) {
        val ctx = app ?: return
        val pm = ctx.packageManager
        val want = ALIASES.getOrElse(ColorThemes.artworkIndex(index)) { ALIASES[0] }
        fun component(name: String) = ComponentName(ctx, "app.ember.tv.$name")
        fun isOn(name: String): Boolean = when (pm.getComponentEnabledSetting(component(name))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> name == ALIASES[0] // the manifest default
        }
        runCatching {
            if (ALIASES.all { (it == want) == isOn(it) }) return
            // The new one first, so there is never a moment without a launcher entry.
            pm.setComponentEnabledSetting(component(want), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            ALIASES.filter { it != want && isOn(it) }.forEach {
                pm.setComponentEnabledSetting(component(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
