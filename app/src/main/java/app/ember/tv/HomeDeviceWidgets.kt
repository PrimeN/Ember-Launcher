package app.ember.tv

import app.ember.tv.data.AppEntry
import app.ember.tv.data.HomeWidget
import app.ember.tv.data.HomeWidgets

/** Apps opened from Ember for this profile, newest first, that it may still open. */
internal fun HomeViewModel.recentAppEntries(limit: Int): List<AppEntry> {
    recentAppsVersion
    val own = getApplication<android.app.Application>().packageName
    val launchable = appAccess.page(false).associateBy { it.pkg }
    return recentApps.list(profiles.profileId).filter { it != own }.mapNotNull(launchable::get).take(limit)
}

internal fun HomeViewModel.recordAppLaunch(pkg: String) {
    recentApps.record(profiles.profileId, pkg)
    recentAppsVersion++
}

internal fun HomeViewModel.forgetRecentApp(pkg: String) {
    recentApps.forget(profiles.profileId, pkg)
    recentAppsVersion++
}

/** True while a visible widget shows other apps' Continue watching, so Home reads it. */
internal fun HomeViewModel.wantsAppsContinue(): Boolean = !profile.kids && personalOn && widgets.list.any { w ->
    w.source.provider == HomeWidgets.DEVICE && w.source.catalogId == HomeWidgets.APPS_CONTINUE &&
        layout.any { (id, on) -> on && id == HomeWidgets.section(w.id) }
}

/** How many items a device widget has right now, for the widget creator. */
internal fun HomeViewModel.deviceWidgetCount(w: HomeWidget): Int = when (w.source.catalogId) {
    HomeWidgets.APPS_RECENT -> recentAppEntries(w.limit).size
    HomeWidgets.APPS_CONTINUE -> if (personalOn) appsContinue.take(w.limit).size else 0
    else -> 0
}

/** The Home block for a widget showing something on this TV; null when it has nothing to show. */
internal fun HomeViewModel.deviceBlock(id: String, w: HomeWidget): HomeViewModel.HomeBlock? = when (w.source.catalogId) {
    HomeWidgets.APPS_RECENT -> recentAppEntries(w.limit).takeIf { it.isNotEmpty() }?.let { HomeViewModel.HomeBlock.AppList(id, w.title, it, w) }
    HomeWidgets.APPS_CONTINUE -> when {
        !personalOn -> null
        !appsContinueAllowed -> HomeViewModel.HomeBlock.AppMediaPermission(id, w.title)
        else -> appsContinue.take(w.limit).takeIf { it.isNotEmpty() }?.let { HomeViewModel.HomeBlock.AppMedia(id, w.title, it, w) }
    }
    else -> null
}
