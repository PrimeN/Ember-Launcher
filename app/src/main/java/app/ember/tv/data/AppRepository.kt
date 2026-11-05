package app.ember.tv.data

import android.content.Context
import android.content.Intent

object AppRepository {
    /** Lists launchable apps, TV apps first, without duplicates and without this launcher. */
    fun load(context: Context, checkActive: () -> Unit = {}): List<AppEntry> {
        val pm = context.packageManager
        val queries = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).map { category ->
            { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0).map { category to it } }
        }
        return discoverPackageEntries(queries,
            packageName = { (_, resolved) -> resolved.activityInfo?.packageName?.takeIf { it != context.packageName } },
            read = read@ { (category, resolved) ->
                val info = resolved.activityInfo ?: return@read null
                val pkg = info.packageName
                val activity = info.name?.takeIf { it.isNotBlank() } ?: return@read null
                val launch = Intent(Intent.ACTION_MAIN).addCategory(category).setClassName(pkg, activity)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val label = firstUsableMetadata({ resolved.loadLabel(pm)?.toString()?.trim()?.takeIf { it.isNotEmpty() } }) ?: pkg
                val updated = firstUsableMetadata({ pm.getPackageInfo(pkg, 0).lastUpdateTime }) ?: 0L
                AppEntry(pkg, label, launch, artworkVersion = updated)
            }, checkActive = checkActive)
    }

    /** Applies the user's saved order. Apps not in the list go last, A to Z. */
    fun sorted(apps: List<AppEntry>, order: List<String>): List<AppEntry> {
        val index = order.withIndex().associate { it.value to it.index }
        return apps.sortedWith(
            compareBy<AppEntry> { index[it.pkg] ?: Int.MAX_VALUE }.thenBy { it.label.lowercase() }
        )
    }
}

/** Invoke only for a visible app tile, preserving banner/icon and per-activity fallbacks. */
internal fun <T> visibleAppArtwork(circle:Boolean,activityBanner:()->T?,applicationBanner:()->T?,
    provided:()->T?,activityIcon:()->T?,applicationIcon:()->T?,fallback:()->T?):T? =
    if(circle)firstUsableMetadata(activityIcon,applicationIcon,provided,fallback)
    else firstUsableMetadata(activityBanner,applicationBanner,provided,activityIcon,applicationIcon,fallback)
