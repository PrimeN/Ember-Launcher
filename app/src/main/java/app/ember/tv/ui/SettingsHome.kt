package app.ember.tv.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import app.ember.tv.home.HomeServiceRuntime
import app.ember.tv.home.HomeServiceIssue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.R
import app.ember.tv.data.Prefs
import app.ember.tv.home.DefaultHome
import app.ember.tv.home.HomeButtonService

/**
 * Settings > Ember as Home: how to make Ember open for Home on Google TV, which does not let you
 * choose another Home app. Method 1 is Ember's Home button service (accessibility), Method 2 turns
 * off Google TV's home with ADB, Method 3 is the system's own Home app setting where there is one.
 * The status at the top is read again whenever you come back from the TV's settings.
 */
@Composable
internal fun DefaultHomePage() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var rev by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        rev++
        onPauseOrDispose {}
    }
    var override by remember { mutableStateOf(prefs.homeOverride) }
    var mode by remember { mutableStateOf(prefs.homeOverrideMode) }
    var atBoot by remember { mutableStateOf(prefs.homeAtBoot) }
    var message by remember { mutableStateOf<String?>(null) }

    // Read on every resume (rev), since these change in the TV's own settings.
    val serviceHealth by HomeServiceRuntime.health.collectAsState()
    val home = remember(rev) { DefaultHome.currentHome(context) }
    val serviceOn = remember(rev,serviceHealth.connected) { DefaultHome.serviceOn(context) }
    val stock = remember(rev) { DefaultHome.stockPackages(context) }
    val devOn = remember(rev) { DefaultHome.developerOptionsOn(context) }
    val address = remember(rev) { DefaultHome.address() }
    val emberDefault = home == context.packageName

    val noAccessibility = stringResource(R.string.home_no_accessibility)
    val noDeveloper = stringResource(R.string.home_developer_off)
    val noStock = stringResource(R.string.home_no_stock)
    val noHomeSettings = stringResource(R.string.home_no_home_settings)

    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Note(stringResource(R.string.home_intro))

        Section(stringResource(R.string.home_status)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (home == null) stringResource(R.string.home_status_asks)
                    else stringResource(R.string.home_status_default, DefaultHome.label(context, home)),
                    fontSize = 15.sp,
                )
                Text(stringResource(when {
                    serviceHealth.connected -> R.string.home_status_service_on
                    serviceOn -> R.string.home_status_service_waiting
                    else -> R.string.home_status_service_off
                }), fontSize = 15.sp)
                serviceHealth.issue?.let {issue->
                    Text(stringResource(when(issue) {
                        HomeServiceIssue.CONFIGURATION -> R.string.home_service_config_problem
                        HomeServiceIssue.HOME_DETECTION -> R.string.home_service_detection_problem
                        HomeServiceIssue.LAUNCH -> R.string.home_service_launch_problem
                        else -> R.string.home_service_runtime_problem
                    }),fontSize=13.sp,color=Mute)
                }
                stock.filter { it != DefaultHome.SETUP_WRAITH }.forEach { pkg ->
                    val name = DefaultHome.label(context, pkg)
                    Text(
                        if (DefaultHome.isEnabled(context, pkg)) stringResource(R.string.home_status_stock_on, name)
                        else stringResource(R.string.home_status_stock_off, name),
                        fontSize = 15.sp,
                    )
                }
                when {
                    emberDefault -> Text(stringResource(R.string.home_status_done), color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
                    serviceHealth.connected && serviceHealth.issue==null && override -> Text(stringResource(R.string.home_status_working), color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
            }
        }

        Section(stringResource(R.string.home_m1_title), stringResource(R.string.home_m1_note)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Steps(
                    stringResource(R.string.home_m1_step1),
                    stringResource(R.string.home_m1_step2),
                    stringResource(R.string.home_m1_step3),
                    stringResource(R.string.home_m1_step4),
                )
                ButtonRow {
                    Chip(stringResource(R.string.home_open_accessibility)) {
                        if (!DefaultHome.open(context, Settings.ACTION_ACCESSIBILITY_SETTINGS)) message = noAccessibility
                    }
                }
                ToggleRow(stringResource(R.string.home_override), stringResource(R.string.home_override_note), override) {
                    override = !override
                    prefs.homeOverride = override
                    HomeButtonService.instance?.applyConfig()
                }
                if (override) {
                    Text(stringResource(R.string.home_mode), fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                    Choice(
                        listOf("screen" to stringResource(R.string.home_mode_screen), "key" to stringResource(R.string.home_mode_key)),
                        mode,
                    ) {
                        mode = it
                        prefs.homeOverrideMode = it
                        HomeButtonService.instance?.applyConfig()
                    }
                    Note(stringResource(if (mode == "key") R.string.home_mode_key_note else R.string.home_mode_screen_note))
                    ToggleRow(stringResource(R.string.home_at_boot), stringResource(R.string.home_at_boot_note), atBoot) {
                        atBoot = !atBoot
                        prefs.homeAtBoot = atBoot
                    }
                }
                ButtonRow {
                    Chip(stringResource(R.string.home_open_stock)) {
                        if (!DefaultHome.openStockHomeOnce(context)) message = noStock
                    }
                }
                Note(stringResource(R.string.home_open_stock_note))
                Note(stringResource(R.string.home_m1_fix))
                Note(stringResource(R.string.home_service_diagnostics))
            }
        }

        Section(stringResource(R.string.home_m2_title), stringResource(R.string.home_m2_note)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Steps(stringResource(R.string.home_m2_step1), stringResource(R.string.home_m2_step2))
                ButtonRow {
                    Chip(stringResource(R.string.home_open_developer)) {
                        if (!DefaultHome.open(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) message = noDeveloper
                    }
                }
                if (!devOn) Note(noDeveloper)
                Steps(stringResource(R.string.home_m2_step3))
                // Google TV: its home and the fallback home; Android TV: its home. Shown for this TV.
                val off = stock.ifEmpty { listOf(DefaultHome.GOOGLE_TV, DefaultHome.SETUP_WRAITH) }
                val ip = address ?: "TV-ADDRESS"
                Commands(
                    listOf("adb connect $ip:5555") +
                        off.map { "adb shell pm disable-user --user 0 $it" } +
                        "adb reboot",
                )
                if (address == null) Note(stringResource(R.string.home_no_address))
                Note(stringResource(R.string.home_m2_wireless))
                Steps(stringResource(R.string.home_m2_step4))
                Text(stringResource(R.string.home_m2_undo_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Commands(off.map { "adb shell pm enable --user 0 $it" } + "adb reboot")
                Note(stringResource(R.string.home_m2_lose))
            }
        }

        Section(stringResource(R.string.home_m3_title), stringResource(R.string.home_m3_note)) {
            ButtonRow {
                Chip(stringResource(R.string.home_open_home_settings)) {
                    if (!DefaultHome.open(context, Settings.ACTION_HOME_SETTINGS)) message = noHomeSettings
                }
            }
        }
    }
}

@Composable
private fun Steps(vararg lines: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(760.dp)) {
        lines.forEach { Text(it, fontSize = 15.sp) }
    }
}

/** Commands to type, one per line, in a dark box so they read like a terminal. */
@Composable
private fun Commands(lines: List<String>) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.width(760.dp).background(Panel, RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Fg) }
    }
}
