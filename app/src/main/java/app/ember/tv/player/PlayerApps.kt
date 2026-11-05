package app.ember.tv.player

import androidx.core.net.toUri

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** An installed app that can play video links. */
data class PlayerApp(val pkg: String, val label: String)

object PlayerApps {
    /** Installed players that accept a web video link. */
    fun videoPlayers(context: Context, checkActive: () -> Unit = {}): List<PlayerApp> {
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType("https://example.com/video.mp4".toUri(), "video/*")
        return query(context, probe, checkActive)
    }

    /** Apps that open magnet links, for torrent sources. */
    fun torrentApps(context: Context): List<PlayerApp> =
        query(context, Intent(Intent.ACTION_VIEW, "magnet:?xt=urn:btih:0000000000000000000000000000000000000000".toUri()))

    private fun query(context: Context, intent: Intent, checkActive: () -> Unit = {}): List<PlayerApp> {
        val pm = context.packageManager
        return app.ember.tv.data.discoverPackageEntries(listOf({ pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY) }),
            packageName = { resolved -> resolved.activityInfo?.takeIf {
                playerActivityAllowed(it.packageName, it.name, context.packageName, it.exported, it.enabled, it.applicationInfo?.enabled == true)
            }?.packageName },
            read = read@ { resolved ->
                val pkg = resolved.activityInfo?.packageName ?: return@read null
                val label = app.ember.tv.data.firstUsableMetadata(
                    { resolved.loadLabel(pm)?.toString()?.takeIf(::usablePlayerLabel) },
                    { resolved.activityInfo?.applicationInfo?.loadLabel(pm)?.toString()?.takeIf(::usablePlayerLabel) },
                )?.trim() ?: pkg
                PlayerApp(pkg, label)
            }, checkActive = checkActive).sortedBy { it.label.lowercase() }
    }
}

/** Resolvers/choosers route an intent; they are not installed playback engines. */
internal fun playerActivityAllowed(pkg: String?, activity: String?, ownPackage: String,
    exported: Boolean, enabled: Boolean, appEnabled: Boolean): Boolean =
    !pkg.isNullOrBlank() && pkg !in setOf(ownPackage, "android", "com.android.intentresolver") &&
        !activity.isNullOrBlank() && !activity.startsWith("com.android.internal.app.ResolverActivity") &&
        !activity.startsWith("com.android.internal.app.ChooserActivity") && exported && enabled && appEnabled

internal fun usablePlayerLabel(label: String): Boolean =
    label.isNotBlank() && !label.trim().equals("none", ignoreCase = true) && !label.trim().equals("null", ignoreCase = true)
