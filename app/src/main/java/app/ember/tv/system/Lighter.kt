package app.ember.tv.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * #473 Settings › System › System access › Make it lighter: memory, background apps, Google TV
 * extras, animations and background scanning. Every change has its way back.
 */
object Lighter {
    /** A Google TV or Android TV extra Ember can switch off, with what you'd lose. */
    data class Extra(val pkg: String, val name: String, val loses: String, val warn: Boolean = false)

    val EXTRAS = listOf(
        Extra("com.google.android.tvrecommendations", "Google TV recommendations", "Only Google's home uses it."),
        Extra("com.google.android.apps.tv.dreamx", "Ambient mode", "Google's photo screensaver; the plain Android one takes over."),
        Extra("com.google.android.katniss", "Google Assistant", "The remote's mic button and voice search stop working.", warn = true),
        Extra("com.google.android.videos", "Google TV app", "Google's film and show store."),
        Extra("com.google.android.play.games", "Play Games", "Google's games hub."),
        Extra("com.google.android.feedback", "Google feedback", "Sends crash reports to Google."),
    )

    /** Fire TV: Amazon's own build of Android. */
    fun isFireTv(context: Context): Boolean =
        context.packageManager.hasSystemFeature("amazon.hardware.fire_tv") ||
            android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)

    data class Memory(val freeMb: Long, val totalMb: Long)

    fun memory(context: Context): Memory {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return Memory(info.availMem / 1_048_576, info.totalMem / 1_048_576)
    }

    fun memoryLine(m: Memory): String = "${gb(m.freeMb)} free of ${gb(m.totalMb)}"
    private fun gb(mb: Long) = if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB"

    /** Closes apps waiting in the background; returns a line saying how much it freed. */
    suspend fun freeMemory(context: Context): String? {
        val before = memory(context).freeMb
        SystemAccess.run(context, "am kill-all") ?: return "Connect first."
        delay(1_200)
        val gained = (memory(context).freeMb - before).coerceAtLeast(0)
        return if (gained >= 20) "Freed ${gb(gained)}. ${memoryLine(memory(context))}." else "Nothing much was waiting in the background."
    }

    // Animations: 1 normal, 0.5 fast, 0 off.
    private val SCALES = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")

    fun animationScale(context: Context): Float =
        runCatching { Settings.Global.getFloat(context.contentResolver, "window_animation_scale", 1f) }.getOrDefault(1f)

    suspend fun setAnimations(context: Context, scale: Float): String? = withContext(Dispatchers.IO) {
        if (!SystemAccess.canWriteSettings(context)) SystemAccess.connect(context)
        if (!SystemAccess.canWriteSettings(context)) return@withContext "Connect first."
        val ok = runCatching { SCALES.all { Settings.Global.putFloat(context.contentResolver, it, scale) } }.getOrDefault(false)
        if (ok) null else "The TV didn't accept the change."
    }

    // Wi-Fi and Bluetooth scanning while they're "off".
    private val SCANS = listOf("wifi_scan_always_enabled", "ble_scan_always_enabled")

    fun scanningOn(context: Context): Boolean =
        SCANS.any { runCatching { Settings.Global.getInt(context.contentResolver, it, 0) == 1 }.getOrDefault(false) }

    suspend fun setScanning(context: Context, on: Boolean): String? = withContext(Dispatchers.IO) {
        if (!SystemAccess.canWriteSettings(context)) SystemAccess.connect(context)
        if (!SystemAccess.canWriteSettings(context)) return@withContext "Connect first."
        val ok = runCatching { SCANS.all { Settings.Global.putInt(context.contentResolver, it, if (on) 1 else 0) } }.getOrDefault(false)
        if (ok) null else "The TV didn't accept the change."
    }

    /** An app you installed, and whether it may keep running after you leave it. */
    data class BackgroundApp(val pkg: String, val name: String, val allowed: Boolean)

    /** Apps you installed that open from Home; not Ember. */
    fun installedApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val launch = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0) +
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        return launch.mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != context.packageName && it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .distinctBy { it.packageName }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
            .sortedBy { it.second.lowercase() }
    }

    /** Reads every app's background setting in one go. */
    suspend fun backgroundApps(context: Context): List<BackgroundApp>? {
        val apps = installedApps(context)
        if (apps.isEmpty()) return emptyList()
        val script = apps.joinToString("; ") { (p, _) -> "echo \"@$p\"; cmd appops get $p RUN_ANY_IN_BACKGROUND" }
        val out = SystemAccess.run(context, script) ?: return null
        val denied = parseDenied(out)
        return apps.map { (p, n) -> BackgroundApp(p, n, p !in denied) }
    }

    /** Packages whose RUN_ANY_IN_BACKGROUND is ignore or deny, from the output of [backgroundApps]'s script. */
    fun parseDenied(out: String): Set<String> {
        val denied = mutableSetOf<String>()
        var current: String? = null
        out.lineSequence().forEach { line ->
            val t = line.trim()
            when {
                t.startsWith("@") -> current = t.removePrefix("@")
                t.startsWith("RUN_ANY_IN_BACKGROUND") && (": ignore" in t || ": deny" in t) -> current?.let { denied += it }
            }
        }
        return denied
    }

    suspend fun setBackground(context: Context, pkg: String, allowed: Boolean): String? {
        require(Regex("[A-Za-z0-9_.]+").matches(pkg))
        // Stopping it as well frees what it holds now; it opens normally next time.
        val cmd = "cmd appops set $pkg RUN_ANY_IN_BACKGROUND ${if (allowed) "allow" else "ignore"}" + if (allowed) "" else "; am force-stop $pkg"
        val out = SystemAccess.run(context, cmd) ?: return "Connect first."
        return out.lineSequence().firstOrNull { "Error" in it || "Exception" in it }
    }
}
