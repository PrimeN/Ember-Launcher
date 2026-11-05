package app.ember.tv.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import app.ember.tv.R
import java.util.Locale

internal enum class DeviceMenuKind { PICTURE, DEVICE, ENGINEERING, INPUTS }
internal data class DeviceMenu(val title: String, val component: ComponentName, val kind: DeviceMenuKind, val action: String = Intent.ACTION_MAIN, val alternatives: List<Pair<ComponentName, String>> = emptyList())

/** Manufacturer activities are shortcuts only: never root, shell, secure settings or reset commands. */
internal fun <T> openFirstDeviceTarget(targets: List<T>, open: (T) -> Unit): Boolean =
    targets.any { target -> runCatching { open(target) }.isSuccess }

internal object DeviceMenus {
    // Package visibility is deliberately limited to these manufacturers and the public settings actions.
    val packages = listOf("mediatek.factorymenu.ui", "com.mediatek.engineermode", "com.mediatek.tvsettings",
        "com.droidlogic.tv.settings", "com.droidlogic.settings", "com.xiaomi.mitv.settings",
        "com.formovie.gsettings", "com.formovie.fmmediaselect", "com.fengmi.testsuite",
        "com.fengmi.autofocustest", "com.fengos.settings", "com.xgimi.settings",
        "com.xgimi.systemsettings", "com.xgimi.setting", "com.xgimi.tv.settings",
        "com.mediatek.wwtv.tvcenter", "com.google.android.tvlauncher", "com.mitv.tvhome.atv")
    val actions = listOf(Settings.ACTION_DISPLAY_SETTINGS, "mediatek.tvsetting.factory.intent.action.MainmenuActivity",
        "com.xiaomi.mitv.settings.SCREENSCALE", "android.settings.TV_INPUT_SETTINGS",
        "com.xiaomi.mitv.settings.INPUTSOURCE_POPUP", "com.mitv.tvhome.atv.app.tv.INPUTSOURCE_POPUP")

    /** Pure policy also used on launch, since an app can be disabled or have its permission changed. */
    fun accessible(exported: Boolean, enabled: Boolean, system: Boolean, permissionGranted: Boolean) =
        exported && enabled && system && permissionGranted

    fun kind(packageName: String, className: String): DeviceMenuKind? {
        val name = className.substringAfterLast('.').lowercase(Locale.ROOT)
        if (listOf("reset", "erase", "burnin", "aging", "firmware", "upgrade").any { it in name }) return null
        if (packageName == "mediatek.factorymenu.ui" && name.substringAfterLast('.') in setOf("mainmenuactivity", "mainactivity") ||
            packageName == "com.mediatek.engineermode" && name == "engineermode" ||
            packageName == "com.fengmi.testsuite" && name.substringAfterLast('.') in setOf("lunchactivity", "engactivity") ||
            listOf("factorymenu", "factorymode", "factorysettings", "factoryactivity", "engineermode", "engineering", "servicemenu").any { it in name }) return DeviceMenuKind.ENGINEERING
        if (packageName == "com.formovie.fmmediaselect" || listOf("inputsource", "sourceselect", "inputselect", "inputspanel").any { it in name }) return DeviceMenuKind.INPUTS
        if (packageName == "com.fengmi.autofocustest" || listOf("picture", "display", "focus", "keystone", "screenscale").any { it in name }) return DeviceMenuKind.PICTURE
        if (className in setOf("com.droidlogic.tv.settings.MainSettings", "com.droidlogic.tv.settings.more.MorePrefFragmentActivity",
                "com.formovie.gsettings.ui.activity.MainActivity", "com.xiaomi.mitv.settings.entry.MainActivity") || name == "settingsactivity" || name == "mainsettings") return DeviceMenuKind.DEVICE
        return null
    }

    private fun allowed(context: Context, info: ActivityInfo): Boolean {
        val pm = context.packageManager
        val app = info.applicationInfo ?: return false
        val state = pm.getComponentEnabledSetting(ComponentName(info.packageName, info.name))
        val enabled = when (state) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> info.enabled
            else -> false
        }
        val permission = info.permission
        return accessible(info.exported, enabled && app.enabled,
            app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
            permission.isNullOrEmpty() || pm.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED)
    }

    fun detect(context: Context): List<DeviceMenu> {
        val pm = context.packageManager
        val found = LinkedHashMap<ComponentName, DeviceMenu>()
        fun add(info: ActivityInfo, forced: DeviceMenuKind? = null, action: String = Intent.ACTION_MAIN) {
            runCatching {
                val kind = forced ?: kind(info.packageName, info.name) ?: return@runCatching
                if (!allowed(context, info)) return@runCatching
                val component = ComponentName(info.packageName, info.name)
                val label = info.loadLabel(pm).toString().trim().takeIf { it.isNotEmpty() } ?: info.name.substringAfterLast('.')
                found.putIfAbsent(component, DeviceMenu(label, component, kind, action))
            }
        }
        actions.forEach { action ->
            runCatching { pm.queryIntentActivities(Intent(action), 0) }.getOrDefault(emptyList()).forEach { result ->
                result.activityInfo?.let { info -> add(info, when (action) {
                    Settings.ACTION_DISPLAY_SETTINGS, "com.xiaomi.mitv.settings.SCREENSCALE" -> DeviceMenuKind.PICTURE
                    "android.settings.TV_INPUT_SETTINGS", "com.xiaomi.mitv.settings.INPUTSOURCE_POPUP",
                    "com.mitv.tvhome.atv.app.tv.INPUTSOURCE_POPUP" -> DeviceMenuKind.INPUTS
                    else -> DeviceMenuKind.ENGINEERING
                }, action) }
            }
        }
        packages.forEach { pkg ->
            runCatching { pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities.orEmpty() }
                .getOrDefault(emptyArray()).forEach { add(it) }
            // Some projector settings packages expose only a launcher activity with a generic name.
            if (pkg.contains("xgimi") || pkg == "com.xiaomi.mitv.settings") runCatching {
                val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
                launch?.component?.let { component -> runCatching { pm.getActivityInfo(component, 0) }.getOrNull()?.let { add(it, DeviceMenuKind.DEVICE) } }
            }
        }
        return found.values.groupBy { Triple(it.component.packageName, it.kind, it.title) }.values.map { group ->
            group.first().copy(alternatives = group.drop(1).map { it.component to it.action })
        }.sortedWith(compareBy({ it.kind.ordinal }, { it.title.lowercase(Locale.ROOT) }, { it.component.flattenToString() }))
    }

    fun open(context: Context, menu: DeviceMenu) {
        val opened = openFirstDeviceTarget(listOf(menu.component to menu.action) + menu.alternatives) { (component, action) ->
            val info = context.packageManager.getActivityInfo(component, 0)
            check(allowed(context, info))
            context.startActivity(Intent(action).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (!opened) Toast.makeText(context, R.string.device_menu_unavailable, Toast.LENGTH_LONG).show()
    }
}
