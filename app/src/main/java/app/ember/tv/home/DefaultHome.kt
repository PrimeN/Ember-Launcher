package app.ember.tv.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

/** What Settings > Ember as Home shows and opens: who is Home now, and the ways to make it Ember. */
object DefaultHome {
    /** Google TV home. */
    const val GOOGLE_TV = "com.google.android.apps.tv.launcherx"
    /** Google TV's fallback home: takes over if Google TV home alone is turned off. */
    const val SETUP_WRAITH = "com.google.android.tungsten.setupwraith"
    /** Android TV home (older TVs and boxes). */
    const val ANDROID_TV = "com.google.android.tvlauncher"
    const val LEANBACK = "com.google.android.leanbacklauncher"

    /** Home screens the Home button service sends to Ember. */
    val STOCK_HOMES = setOf(GOOGLE_TV, ANDROID_TV, LEANBACK, SETUP_WRAITH, "com.amazon.tv.launcher")

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /** The app the system opens for Home, or null when it would ask. */
    fun currentHome(context: Context): String? =
        context.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it != "android" }

    fun emberIsDefault(context: Context): Boolean = currentHome(context) == context.packageName

    /** Query actual system HOME activities, including manufacturer launchers on projectors. */
    internal fun systemHomeTargets(context:Context):Set<HomeWindowTarget> =
        context.packageManager.queryIntentActivities(homeIntent(),0).flatMap { resolved->
            val activity=resolved.activityInfo ?: return@flatMap emptyList()
            val system=activity.applicationInfo.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0
            if(activity.packageName==context.packageName || (!system && activity.packageName !in STOCK_HOMES))emptyList()
            else listOfNotNull(activity.name,activity.targetActivity).distinct().map {name->HomeWindowTarget(activity.packageName,name)}
        }.toSet()

    /** Shown name of an app, or its package name. */
    fun label(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Installed and switched on. */
    fun isEnabled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    }.getOrDefault(false)

    fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
        true
    }.getOrDefault(false)

    /** The TV's own home packages that are on this device (Google TV home and its fallback, or Android TV home). */
    fun stockPackages(context: Context): List<String> =
        listOf(GOOGLE_TV, SETUP_WRAITH, ANDROID_TV, LEANBACK).filter { isInstalled(context, it) }

    /** True when the Home button service is switched on in the TV's Accessibility settings. */
    fun serviceOn(context: Context): Boolean {
        val me = ComponentName(context, HomeButtonService::class.java)
        val list = runCatching {Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)}.getOrNull().orEmpty()
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun developerOptionsOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    /** The TV's address on the home network, for "adb connect". */
    fun address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    /** Opens a system settings screen; false when this TV does not have it. */
    fun open(context: Context, action: String): Boolean = runCatching {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(context.packageManager) == null) return false
        context.startActivity(i)
        true
    }.getOrDefault(false)

    /** Opens Google TV's home screen without the service sending it back to Ember (for 60 seconds). */
    fun openStockHomeOnce(context: Context): Boolean {
        val pkg = stockPackages(context).firstOrNull { it != SETUP_WRAITH && isEnabled(context, it) } ?: return false
        val i = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: homeIntent().setPackage(pkg)
        HomeButtonService.bypassUntil = SystemClock.elapsedRealtime() + 60_000L
        return runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }
}
