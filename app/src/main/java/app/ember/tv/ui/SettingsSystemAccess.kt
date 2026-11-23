package app.ember.tv.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.home.DefaultHome
import app.ember.tv.home.HomeButtonService
import app.ember.tv.system.AdBlockDns
import app.ember.tv.system.AdbPairPage
import app.ember.tv.system.SystemAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > System > System access (#408): connect Ember to the TV's own debugging service once,
 * then change Home, Google TV's recommendations and animation speed with one press. Every change
 * has its way back on the same row.
 */
@Composable
internal fun SystemAccessPage(vm: HomeViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    var rev by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        rev++
        onPauseOrDispose {}
    }
    val state by SystemAccess.state.collectAsState()
    val note by SystemAccess.note.collectAsState()
    val granted = remember(rev, state) { SystemAccess.canWriteSettings(app) }
    val devOn = remember(rev) { SystemAccess.developerOptionsOn(app) }
    val emberHome = remember(rev, state) { DefaultHome.emberIsDefault(app) }
    val stock = remember(rev) { DefaultHome.stockPackages(app) }
    val stockOff = remember(rev, state) { stock.isNotEmpty() && stock.none { DefaultHome.isEnabled(app, it) } }
    val recsInstalled = remember(rev) { DefaultHome.isInstalled(app, SystemTweaks.RECOMMENDATIONS) }
    val recsOn = remember(rev, state) { DefaultHome.isEnabled(app, SystemTweaks.RECOMMENDATIONS) }
    val fast = remember(rev, state) { SystemTweaks.fastAnimations(app) }
    val serviceOn = remember(rev, state) { DefaultHome.serviceOn(app) }
    val adBlock = remember(rev, state) { AdBlockDns.refresh(app) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf(false) }
    var confirmHome by remember { mutableStateOf(false) }
    var confirmGiveBack by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { scope.launch { SystemAccess.release() } } }

    fun act(block: suspend () -> String?) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            message = runCatching { block() }.getOrNull()
            busy = false
            rev++
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Section(
            "Connection", "Lets Ember change TV settings itself, with no computer.", id = "system-connection",
            info = "Ember connects to the TV's own debugging service, the same one a computer uses with adb.\n\n" +
                "On most Google TVs that's Wireless debugging: pair once with the code the TV shows. Older TVs ask \"Allow debugging?\" once instead.\n\n" +
                "After the first connection Ember keeps permission to change secure settings (Private DNS, animation speed), so those keep working even with debugging off.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(statusLine(state, granted, devOn), fontSize = 15.sp,
                    color = if (state == SystemAccess.State.CONNECTED || granted) MaterialTheme.colorScheme.primary else Fg)
                (message ?: note)?.let { Text(it, color = Mute, fontSize = 14.sp, modifier = Modifier.width(760.dp)) }
                if (!devOn) SettingActionRow("Turn on Developer options", "Settings › System › About › Android TV OS build: press OK 7 times.") {
                    DefaultHome.open(context, Settings.ACTION_DEVICE_INFO_SETTINGS)
                }
                SettingActionRow(if (busy) "Connecting…" else "Connect", "Uses Wireless debugging, or USB debugging on older TVs.") {
                    act { if (SystemAccess.connect(app)) "Connected." else null }
                }
                if (SystemAccess.wireless) SettingActionRow("Pair with a code", "Once, for Wireless debugging (most Google TVs).") { pairing = true }
                SettingActionRow("Open Developer options") {
                    if (!DefaultHome.open(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) message = "Turn on Developer options first."
                }
            }
        }

        Section(
            "Home screen", "Make Ember the TV's Home, or give it back.", id = "system-home",
            info = "Ember sets itself as the Home app and switches off the TV's own home screen, which stays installed. " +
                "Give it back switches it on again and makes it Home.\n\nIf you uninstall Ember while the TV's home is off, the TV has no Home screen: give it back first.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        emberHome && stockOff -> "Ember is Home and the TV's own home is off."
                        emberHome -> "Ember is Home."
                        else -> "Home: ${DefaultHome.currentHome(app)?.let { DefaultHome.label(app, it) } ?: "the TV asks"}."
                    },
                    fontSize = 15.sp,
                )
                if (!(emberHome && (stockOff || stock.isEmpty()))) SettingActionRow("Make Ember the TV's Home", "Switches the TV's own home screen off.") { confirmHome = true }
                if (stock.any { !DefaultHome.isEnabled(app, it) }) SettingActionRow("Give Home back to the TV", "Switches the TV's own home screen on again.") { confirmGiveBack = true }
                if (!serviceOn) SettingActionRow("Turn on the Home button service", "Sends the Home button to Ember on TVs that keep their own home.") {
                    act { SystemTweaks.homeButtonService(app) }
                }
            }
        }

        Section("TV tweaks", "System-wide changes; each switches back the same way.", id = "system-tweaks") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleRow("Fast animations", "Menus and screens animate twice as fast in every app.", fast) {
                    act { SystemTweaks.setFastAnimations(app, !fast) }
                }
                if (recsInstalled) ToggleRow("Google TV recommendations", "A background service only Google's home uses. Off saves memory.", recsOn) {
                    act { SystemTweaks.setPackage(app, SystemTweaks.RECOMMENDATIONS, !recsOn) }
                }
                SettingActionRow("Ad blocking", adBlockLine(adBlock)) { vm.openSettingsPage(SettingsTab.KEYS.name) }
            }
        }
    }

    if (pairing) PairDialog { pairing = false; rev++ }
    if (confirmHome) ConfirmDestructive(
        "Make Ember the TV's Home?",
        "The TV's own home screen is switched off; it stays installed. Give Home back here before you uninstall Ember.",
        { confirmHome = false }, "Make Ember Home",
    ) { act { SystemTweaks.makeEmberHome(app, stock) } }
    if (confirmGiveBack) ConfirmDestructive(
        "Give Home back to the TV?",
        "The TV's own home screen is switched on again and made Home. Ember stays installed.",
        { confirmGiveBack = false }, "Give back",
    ) { act { SystemTweaks.giveHomeBack(app, stock) } }
}

private fun statusLine(state: SystemAccess.State, granted: Boolean, devOn: Boolean): String = when (state) {
    SystemAccess.State.CONNECTED -> "Connected."
    SystemAccess.State.WORKING -> "Connecting…"
    SystemAccess.State.APPROVE_ON_TV -> "Waiting for Allow on the TV…"
    else -> when {
        granted -> "Ember can change secure settings. Connect for Home and app changes."
        !devOn -> "Not set up. Developer options are off."
        state == SystemAccess.State.NEEDS_PAIRING -> "Not paired yet."
        else -> "Not connected."
    }
}

private fun adBlockLine(state: AdBlockDns.State): String = when (state) {
    AdBlockDns.State.ON -> "On: Private DNS uses your NextDNS profile."
    AdBlockDns.State.NO_PROFILE -> "Add your NextDNS profile in API keys."
    AdBlockDns.State.NEEDS_ACCESS -> "Waiting for System access."
    AdBlockDns.State.FEATURE_OFF -> "Off in Features."
}

/** Pairing with Wireless debugging: the code is typed on the phone, since the TV's code screen closes when Ember opens. */
@Composable
private fun PairDialog(close: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val link = remember { AdbPairPage.start(app) }
    DisposableEffect(Unit) { onDispose { AdbPairPage.stop() } }
    val state by SystemAccess.state.collectAsState()
    val note by SystemAccess.note.collectAsState()
    var code by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state) { if (state == SystemAccess.State.CONNECTED) result = "Paired and connected." }
    MediaDialog("Pair with Wireless debugging", close, resizeForKeyboard = true, wide = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(560.dp)) {
                Text("1. Open Developer options › Wireless debugging and switch it on.", fontSize = 15.sp)
                Text("2. Choose Pair device with pairing code and leave it open.", fontSize = 15.sp)
                Text("3. On your phone, scan the code here and type the 6 digits the TV shows.", fontSize = 15.sp)
                Text("The TV closes its code screen when another app opens, so the phone sends the code while it stays open.", color = Mute, fontSize = 13.sp)
                ButtonRow {
                    Chip("Open Developer options") { DefaultHome.open(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
                }
                Text("If your TV keeps the code on screen, you can type it here instead:", color = Mute, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextInput(code, { code = it.filter(Char::isDigit).take(6) }, "6-digit code", width = 260, numeric = true)
                    Chip("Pair") { scope.launch { result = SystemAccess.pair(app, code) } }
                }
                (result ?: note)?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (link != null) {
                    QrCode(link, 220.dp)
                    Text("Phone on the same Wi-Fi", color = Mute, fontSize = 13.sp)
                } else {
                    Text("Connect the TV to your home network to pair from a phone.", color = Mute, fontSize = 13.sp, modifier = Modifier.width(220.dp))
                }
            }
        }
        ButtonRow { Chip(if (state == SystemAccess.State.CONNECTED) "Done" else "Close") { close() } }
    }
}

/** The changes System access makes (#408). Each returns a line for the person, or null. */
internal object SystemTweaks {
    const val RECOMMENDATIONS = "com.google.android.tvrecommendations"
    private val SCALES = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")

    fun fastAnimations(context: Context): Boolean =
        runCatching { Settings.Global.getFloat(context.contentResolver, "window_animation_scale", 1f) < 0.9f }.getOrDefault(false)

    suspend fun setFastAnimations(context: Context, fast: Boolean): String? = withContext(Dispatchers.IO) {
        if (!SystemAccess.canWriteSettings(context)) SystemAccess.connect(context)
        if (!SystemAccess.canWriteSettings(context)) return@withContext "Connect first."
        val value = if (fast) 0.5f else 1f
        val ok = runCatching { SCALES.all { Settings.Global.putFloat(context.contentResolver, it, value) } }.getOrDefault(false)
        if (ok) null else "The TV didn't accept the change."
    }

    suspend fun setPackage(context: Context, pkg: String, on: Boolean): String? {
        val out = SystemAccess.run(context, if (on) "pm enable $pkg" else "pm disable-user --user 0 $pkg") ?: return "Connect first."
        return if (DefaultHome.isEnabled(context, pkg) == on) null else out.trim().ifEmpty { "The TV didn't accept the change." }
    }

    suspend fun makeEmberHome(context: Context, stock: List<String>): String? {
        val home = ComponentName(context.packageName, "${context.packageName}.MainActivity").flattenToShortString()
        val off = stock.joinToString("") { "; pm disable-user --user 0 $it" }
        SystemAccess.run(context, "cmd package set-home-activity $home$off") ?: return "Connect first."
        return if (DefaultHome.emberIsDefault(context)) "Ember is now Home." else "Done. If Home still opens the TV's home, restart the TV."
    }

    suspend fun giveHomeBack(context: Context, stock: List<String>): String? {
        SystemAccess.run(context, stock.joinToString("; ") { "pm enable $it" }) ?: return "Connect first."
        val main = stock.firstOrNull { it != DefaultHome.SETUP_WRAITH }
        val activity = main?.let { pkg ->
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg), 0)
                .firstOrNull()?.activityInfo?.let { ComponentName(it.packageName, it.name).flattenToShortString() }
        }
        if (activity != null) SystemAccess.run(context, "cmd package set-home-activity $activity")
        return "The TV's home is back."
    }

    /** Switches on Ember's Home button service (accessibility) without opening the TV's settings. */
    suspend fun homeButtonService(context: Context): String? = withContext(Dispatchers.IO) {
        if (!SystemAccess.canWriteSettings(context)) SystemAccess.connect(context)
        if (!SystemAccess.canWriteSettings(context)) return@withContext "Connect first."
        val me = ComponentName(context, HomeButtonService::class.java).flattenToString()
        val cr = context.contentResolver
        val list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() && it != me } + me
        val ok = runCatching {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":")) &&
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.getOrDefault(false)
        if (ok) "The Home button service is on." else "The TV didn't accept the change."
    }
}
