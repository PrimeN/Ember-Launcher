package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Settings as IcSettingsIcon
import androidx.compose.material.icons.outlined.PlayArrow as IcPlayIconVector
import androidx.compose.material.icons.outlined.Wifi as IcWifiIcon
import androidx.compose.material.icons.outlined.SettingsEthernet as IcEthernetIcon
import androidx.compose.material.icons.outlined.Tv as IcTvIcon
import androidx.compose.material.icons.automirrored.outlined.VolumeUp as IcSoundIcon
import androidx.compose.material.icons.outlined.Bluetooth as IcBluetoothIcon
import androidx.compose.material.icons.outlined.Apps as IcAppsIcon
import androidx.compose.material.icons.outlined.NightsStay as IcScreensaverIcon
import androidx.compose.material.icons.automirrored.outlined.Input as IcInputIcon

/**
 * Quick settings: a panel on the right. At the top, Ember's settings and the TV's settings side by
 * side, then shortcuts to the TV's own pages, its inputs (HDMI), a sleep timer and
 * Ember's profile and theme. Current TV information comes last.
 */
@Composable
fun QuickPanel(vm: HomeViewModel, onClose: () -> Unit, onSettings: () -> Unit, onReturn: Pair<String, () -> Unit>? = null) {
    if(!vm.settingsAllowed)return
    val context = LocalContext.current
    val accent = FloatingPanelStyle.accent
    val snapshot by rememberQuickPanelSnapshot()
    val firmwareMenus by rememberFirmwareShortcuts()
    val inputs = snapshot?.inputs.orEmpty()
    val first = remember { FocusRequester() }
    var audioPicker by remember { mutableStateOf(false) }
    val info = snapshot?.info ?: QuickActions.Info("—", "", false, "Loading…", "Display", "Audio", emptyList())
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompactPanelContent {
            DialogMotion()
            // A little smaller than the pages, so more fits in the panel.
            CompositionLocalProvider(
                LocalFloating provides true,
                LocalContentColor provides Color(0xFFE8EAED),
            ) {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().guardOpeningPress()) {
                    Column(
                        Modifier
                            // Full height against the right edge, like the TV's own settings panel.
                            .align(Alignment.CenterEnd)
                            .padding(horizontal=12.dp,vertical=12.dp)
                            .width(MenuSizing.panelWidth(maxWidth*MenuSizing.COMPACT_PANEL_SCALE, androidx.compose.ui.platform.LocalDensity.current.fontScale))
                            .fillMaxHeight()
                            .floatingPanelSurface()
                            .verticalScroll(rememberMenuScrollState())
                            .padding(bottom = 20.dp),
                    ) {
                        // ---- header: date and time on the left; Ember's and the TV's settings on the
                        // right (#59). No profile button (#206): the Profile section below switches. ----
                        Row(
                            Modifier.fillMaxWidth().background(FloatingPanelStyle.header).padding(start = 26.dp, end = 22.dp, top = 24.dp, bottom = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(info.date, fontSize = 15.sp, color = Fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(info.time, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                            HeaderButton(stringResource(R.string.ui_ember_settings), if (onReturn == null) Modifier.focusRequester(first) else Modifier,
                                onClick = { onClose(); onSettings() }) { EmberMark(26.dp) }
                            HeaderButton(stringResource(R.string.ui_qs_tv_settings), onClick = {
                                onClose(); vm.openSettingsGuarded { QuickActions.open(context, Settings.ACTION_SETTINGS) }
                            }) { androidx.tv.material3.Icon(IcSettings, contentDescription = null, modifier = Modifier.size(24.dp)) }
                        }

                        // The TV's own settings need the PIN from a Kids profile, like Ember's.
                        fun tv(action: String) {
                            onClose()
                            vm.openSettingsGuarded { QuickActions.open(context, action) }
                        }

                        // ---- settings: Ember's and the TV's, then the TV's pages ----
                        // "Back to …": the video you just left, from where it stopped. It comes first.
                        // #216: across the panel and a third of the screen high, not one tile in the grid.
                        // #248: the same column, padding and gap as the tiles below it.
                        Tiles(top = onReturn?.let { (title, go) -> @Composable { ReturnTile(title, Modifier.focusRequester(first)) { onClose(); go() } } },
                            tiles = 
                            listOf(
                                Tile(stringResource(R.string.ui_network), info.networkShort, if (info.ethernet) IcEthernet else IcWifi) { tv(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) },
                                Tile(stringResource(R.string.ui_qs_picture), info.displayShort, IcTv) {
                                    val menu = firmwareMenus.firstOrNull { it.kind == app.ember.tv.device.DeviceMenuKind.PICTURE }
                                    if (menu == null) tv(Settings.ACTION_DISPLAY_SETTINGS)
                                    else { onClose(); vm.openSettingsGuarded { app.ember.tv.device.DeviceMenus.open(context, menu) } }
                                },
                                Tile(stringResource(R.string.ui_sound), info.soundShort, IcSpeaker) {
                                    vm.openSettingsGuarded {
                                        if (AudioOutputShortcut.openSystemPicker(context)) onClose() else audioPicker = true
                                    }
                                },
                                Tile("Volume", "Adjust device volume", IcSound) { tv(Settings.Panel.ACTION_VOLUME) },
                                Tile(stringResource(R.string.ui_qs_remotes), "Bluetooth and accessories", IcBluetooth) { tv(Settings.ACTION_BLUETOOTH_SETTINGS) },
                                Tile(stringResource(R.string.ui_qs_apps), "Storage, permissions, updates", IcApps) { tv(Settings.ACTION_APPLICATION_SETTINGS) },
                                Tile(stringResource(R.string.settings_device_tools), stringResource(R.string.settings_summary_device_tools), IcTools) { onClose(); vm.openSettingsPage(SettingsTab.DEVICE_TOOLS.name) },
                                Tile("Open media", "Files, USB and network folders", IcFolder) { onClose(); vm.openSettingsGuarded { context.startActivity(android.content.Intent(context, app.ember.tv.player.MediaFilesActivity::class.java)) } },
                                Tile(stringResource(R.string.ui_screensaver_now), "Start it now", IcScreensaver) { QuickActions.screensaver(context); onClose() },
                            ) + firmwareMenus.filter { it.kind == app.ember.tv.device.DeviceMenuKind.DEVICE }.take(1).map { menu ->
                                Tile("Device controls", menu.title, IcRemote) {
                                    onClose(); vm.openSettingsGuarded { app.ember.tv.device.DeviceMenus.open(context, menu) }
                                }
                            },
                        )

                        if(vm.launcherExtras.options.nowPlaying && !vm.profiles.profile.kids) {
                            Heading("Now Playing",accent)
                            Box(Modifier.padding(horizontal=22.dp,vertical=8.dp)){NowPlayingPanel(vm)}
                        }
                        // ---- inputs ----
                        if (inputs.isNotEmpty()) {
                            Heading(stringResource(R.string.ui_inputs), accent)
                            Tiles(inputs.map { input -> Tile(input.label, input.connected?.let { stringResource(if (it) R.string.device_connected else R.string.device_disconnected) }, IcInput) { onClose(); vm.openSettingsGuarded { QuickActions.openInput(context, input.id) } } })
                        }

                        // ---- sleep timer ----
                        Heading(stringResource(R.string.ui_sleep_timer), accent)
                        // #213: a slider instead of five buttons.
                        StepSlider(stringResource(R.string.ui_sleep_timer), listOf(0, 15, 30, 45, 60, 90, 120), vm.sleep.sleepMinutes, { m -> if (m == 0) "Off" else "$m min" },
                            Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 6.dp), width = 360.dp) { vm.sleep.setSleepTimer(it) }
                        Text(
                            vm.sleep.sleepLeftText ?: "Minutes. Stops what is playing and starts the screensaver.",
                            fontSize = TvTokens.Type.Caption.sp,
                            color = Mute,
                            modifier = Modifier.padding(start = 28.dp, bottom = 4.dp),
                        )

                        // ---- Ember: profile and theme ----
                        if (vm.profiles.all.size > 1) {
                            Heading(stringResource(R.string.ui_profile), accent)
                            ButtonRow(Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 6.dp), spacing = 8.dp) {
                                vm.profiles.all.forEach { p -> Chip(p.name, selected = p.id == vm.profiles.profileId, maxLines = 1) { vm.profiles.switchProfile(p.id) } }
                            }
                        }
                        Heading(stringResource(R.string.ui_interface), accent)
                        ButtonRow(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 6.dp), spacing = 8.dp) {
                            app.ember.tv.data.InterfaceStyles.options.forEach { (id, label) ->
                                Chip(label, selected = vm.look.uiStyle == id, maxLines = 1) { vm.look.updateUiStyle(id) }
                            }
                        }
                        Heading(stringResource(R.string.ui_qs_theme), accent)
                        ButtonRow(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 6.dp), spacing = 8.dp) {
                            Chip(stringResource(R.string.ui_night), selected = vm.look.theme == "night", maxLines = 1) { vm.look.updateTheme("night") }
                            Chip(stringResource(R.string.ui_day), selected = vm.look.theme == "day", maxLines = 1) { vm.look.updateTheme("day") }
                            Chip(stringResource(R.string.ui_auto), selected = vm.look.theme == "auto", maxLines = 1) { vm.look.updateTheme("auto") }
                            Chip(stringResource(R.string.ui_oled), selected = vm.look.oled, maxLines = 1) { vm.look.updateOled(!vm.look.oled) }
                        }

                        // ---- this TV right now ----
                        Heading(stringResource(R.string.ui_qs_now), accent)
                        Column(
                            Modifier.padding(horizontal = 22.dp).fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(vertical = 6.dp),
                        ) {
                            info.lines.forEach { (k, v) -> InfoLine(k, v) }
                        }
                    }
                }
            }
            LaunchedEffect(Unit) {
                androidx.compose.runtime.withFrameNanos { }
                runCatching { first.requestFocus() }
            }
        }
    }
    if (audioPicker) AudioOutputDialog { audioPicker = false }
}

/** A round button in the panel's header; its name is spoken, not shown (#64). */
@Composable
private fun HeaderButton(label: String, modifier: Modifier = Modifier, filled: Color? = null, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Focusable(onClick = onClick,
        modifier = modifier.size(48.dp).semantics { contentDescription = label },
        radius = TvTokens.Shape.Pill, container = filled ?: WashHi,
        focusedContainer = filled ?: PillColor, focusedContent = OnPillColor, outline = filled != null, focusedScale = TvTokens.Focus.Round) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { icon() }
    }
}

private class Tile(
    val title: String,
    val note: String?,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val modifier: Modifier = Modifier,
    val highlight: Boolean = false,
    val onClick: () -> Unit,
)

/** The video you just left (#216): the panel's width, a third of the screen's height. */
@Composable
private fun ReturnTile(title: String, modifier: Modifier, onClick: () -> Unit) {
    // #230: a quarter of the earlier third of the screen, one line.
    val height = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp / 12).coerceAtLeast(44).dp
    var focused by remember { mutableStateOf(false) }
    val soft = if (focused) OnPillColor.copy(alpha = .75f) else Mute
    // #248: the tiles' own colours and icon, and no padding of its own (the column gives it).
    Focusable(onClick = onClick, modifier = modifier.fillMaxWidth().height(height).onFocusChanged { focused = it.isFocused }.testTag("quick_return"),
        radius = TvTokens.Shape.Control, container = Panel, focusedContainer = PillColor, focusedContent = OnPillColor, outline = false, focusedScale = TvTokens.Focus.None) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MaterialGlyph(IcPlayIcon, if (focused) OnPillColor else Fg, 22.dp)
            Text(stringResource(R.string.tx_back_to), fontSize = 13.sp, color = soft, maxLines = 1)
            Text(title, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.tx_continue), fontSize = 12.sp, color = soft, maxLines = 1)
        }
    }
}

/** Two tiles a row: icon, name and a short line (often what the TV is doing right now). */
@Composable
private fun Tiles(tiles: List<Tile>, top: (@Composable () -> Unit)? = null) {
    val accent = FloatingPanelStyle.accent
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val fontScale=androidx.compose.ui.platform.LocalDensity.current.fontScale
    val columns=MenuSizing.quickColumns(maxWidth,fontScale)
    // Every tile the same size: room for the icon, a one-line name and a two-line note.
    val tileHeight=MenuSizing.quickTileHeight(fontScale)
    Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        top?.invoke()
        tiles.chunked(columns).forEach { pair ->
            Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { t ->
                    var focused by remember(t.title) {mutableStateOf(false)}
                    Focusable(onClick=t.onClick,
                        modifier=t.modifier.weight(1f).height(tileHeight).onFocusChanged {focused=it.isFocused},
                        radius=TvTokens.Shape.Control,container=if(t.highlight)WashHi else Panel,
                        focusedContainer=PillColor,focusedContent=OnPillColor,outline=false,focusedScale=TvTokens.Focus.None) {
                        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            MaterialGlyph(t.icon,if(focused)OnPillColor else if(t.highlight)accent else Fg,22.dp)
                            Text(t.title,fontSize=15.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                            t.note?.let {Text(it,fontSize=12.sp,color=if(focused)OnPillColor.copy(alpha=.75f) else Mute,maxLines=2,overflow=TextOverflow.Ellipsis)}
                        }
                    }
                }
                if(pair.size<columns)Spacer(Modifier.weight(1f))
            }
        }
    }
    }
}

/** One fact about the TV; focusable so the remote can scroll through them. */
@Composable
private fun InfoLine(label: String, value: String) {
    var focused by remember {mutableStateOf(false)}
    Focusable(onClick={},modifier=Modifier.fillMaxWidth().onFocusChanged {focused=it.isFocused},radius=TvTokens.Shape.Card,
        container=Color.Transparent,focusedContainer=PillColor,focusedContent=OnPillColor,outline=false,focusedScale=TvTokens.Focus.None) {
        Row(Modifier.padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(label,fontSize=13.sp,color=if(focused)OnPillColor.copy(alpha=.75f) else Mute,modifier=Modifier.weight(.8f))
            Text(value,fontSize=13.sp,lineHeight=18.sp,modifier=Modifier.weight(1.4f))
        }
    }
}

@Composable
private fun Heading(text: String, accent: Color) {
    Text(text, color = accent, fontSize = 14.sp, modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 2.dp))
}

private val IcPlayIcon get() = Icons.Outlined.IcPlayIconVector
private val IcSettings get() = Icons.Outlined.IcSettingsIcon
private val IcWifi get() = Icons.Outlined.IcWifiIcon
private val IcEthernet get() = Icons.Outlined.IcEthernetIcon
private val IcTv get() = Icons.Outlined.IcTvIcon
private val IcSound get() = Icons.AutoMirrored.Outlined.IcSoundIcon
private val IcBluetooth get() = Icons.Outlined.IcBluetoothIcon
private val IcApps get() = Icons.Outlined.IcAppsIcon
private val IcScreensaver get() = Icons.Outlined.IcScreensaverIcon
private val IcInput get() = Icons.AutoMirrored.Outlined.IcInputIcon
// Each tile its own picture (#132): Sound vs Volume, Picture vs the device tools, Apps vs media.
private val IcSpeaker get() = Icons.Outlined.Speaker
private val IcTools get() = Icons.Outlined.Build
private val IcFolder get() = Icons.Outlined.FolderOpen
private val IcRemote get() = Icons.Outlined.SettingsRemote

/** What the quick settings do on the TV. */
object QuickActions {
    /** HDMI and other pass-through inputs, as (name, input id). TVs only; streaming boxes have none. */
    fun inputs(context: Context): List<Pair<String, String>> = app.ember.tv.device.DeviceInputs.list(context).map { it.label to it.id }

    fun openInput(context: Context, id: String) = app.ember.tv.device.DeviceInputs.open(context, id)

    fun open(context: Context, action: String) {
        val fallback = when (action) {
            Settings.Panel.ACTION_INTERNET_CONNECTIVITY -> Settings.ACTION_WIFI_SETTINGS
            Settings.Panel.ACTION_VOLUME -> Settings.ACTION_SOUND_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        if (!app.ember.tv.device.openFirstDeviceTarget(listOf(action, fallback, Settings.ACTION_SETTINGS).distinct()) {
                context.startActivity(Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) Toast.makeText(context, "This TV could not open that settings page.", Toast.LENGTH_LONG).show()
    }

    /** Manual ambient mode belongs to Ember; no private Android dream activity is required. */
    fun screensaver(context: Context) {
        val intent = Intent(context, app.ember.tv.screensaver.ScreensaverActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            app.ember.tv.data.CrashLog.note("Could not open Ember screensaver", it)
            Toast.makeText(context, context.getString(R.string.screensaver_launch_failed), Toast.LENGTH_LONG).show()
        }
    }

    /** What the panel shows about the TV right now. */
    class Info(
        val time: String,
        val date: String,
        val ethernet: Boolean,
        val networkShort: String,
        val displayShort: String,
        val soundShort: String,
        val lines: List<Pair<String, String>>,
    )

    fun info(context: Context): Info {
        val now = java.util.Date()
        val time = android.text.format.DateFormat.getTimeFormat(context).format(now)
        val date = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(now)
        val net = runCatching { network(context) }.getOrNull()
        val disp = runCatching { display(context) }.getOrNull()
        val sound = runCatching { sound(context) }.getOrNull()
        val lines = ArrayList<Pair<String, String>>()
        lines += "Network" to (net?.long ?: "Not connected")
        net?.ip?.let { lines += "IP address" to it }
        disp?.let {
            lines += "Picture" to it.long
            if (it.rates.isNotEmpty()) lines += "Frame rates" to it.rates
            if (it.hdr.isNotEmpty()) lines += "HDR" to it.hdr
        }
        sound?.let { lines += "Sound out" to it.long }
        runCatching { storage(context) }.getOrNull()?.let { lines += "Storage" to it }
        runCatching { memory(context) }.getOrNull()?.let { lines += "Memory" to it }
        lines += "Device" to "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"
        lines += "Ember" to "${app.ember.tv.BuildConfig.VERSION_NAME} (${app.ember.tv.BuildConfig.VERSION_CODE})"
        return Info(time, date, net?.ethernet == true, net?.short ?: "Not connected", disp?.short ?: "Resolution and HDR", sound?.short ?: "Audio output", lines)
    }

    private class Net(val short: String, val long: String, val ip: String?, val ethernet: Boolean)

    private fun network(context: Context): Net? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(active) ?: return null
        val ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val kind = when {
            ethernet -> "Ethernet"
            wifi -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Connected"
        }
        val parts = ArrayList<String>()
        if (wifi) {
            val wi = caps.transportInfo as? android.net.wifi.WifiInfo
            @Suppress("DEPRECATION")
            val info = wi ?: runCatching { (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo }.getOrNull()
            info?.let { w ->
                val band = when {
                    w.frequency in 5900..7200 -> "6 GHz"
                    w.frequency >= 4900 -> "5 GHz"
                    w.frequency in 2400..2500 -> "2.4 GHz"
                    else -> null
                }
                band?.let(parts::add)
                if (w.rssi in -126..-1) {
                    val bars = when {
                        w.rssi >= -55 -> "excellent"
                        w.rssi >= -67 -> "good"
                        w.rssi >= -75 -> "fair"
                        else -> "weak"
                    }
                    parts += "signal $bars (${w.rssi} dBm)"
                }
                if (w.linkSpeed > 0) parts += "link ${w.linkSpeed} Mbps"
            }
        }
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) parts += "no internet"
        val ip = cm.getLinkProperties(active)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
        val short = (listOf(kind) + parts.take(1)).joinToString(" · ")
        return Net(short, (listOf(kind) + parts).joinToString(" · "), ip, ethernet)
    }

    private class Disp(val short: String, val long: String, val rates: String, val hdr: String)

    @Suppress("DEPRECATION")
    private fun display(context: Context): Disp {
        val d = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val mode = d.mode
        val types: IntArray = d.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
        val hdr = ArrayList<String>()
        for (t in types) {
            when (t) {
                android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> hdr += "Dolby Vision"
                android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 -> hdr += "HDR10"
                android.view.Display.HdrCapabilities.HDR_TYPE_HLG -> hdr += "HLG"
                android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> hdr += "HDR10+"
            }
        }
        val res = when {
            mode.physicalHeight >= 2100 -> "4K"
            mode.physicalHeight >= 1400 -> "1440p"
            mode.physicalHeight >= 1000 -> "1080p"
            mode.physicalHeight >= 700 -> "720p"
            else -> "${mode.physicalHeight}p"
        }
        val hz = rate(mode.refreshRate)
        // Refresh rates the screen offers at this size: what frame-rate matching can switch to.
        val rates = d.supportedModes
            .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
            .map { it.refreshRate }.distinct().sorted().joinToString(", ") { rate(it) }
            .let { if (it.isNotEmpty()) "$it Hz" else "" }
        return Disp("$res · $hz Hz", "${mode.physicalWidth}×${mode.physicalHeight} at $hz Hz", rates, hdr.joinToString(", "))
    }

    private fun rate(r: Float): String = if (kotlin.math.abs(r - Math.round(r)) < 0.05f) "%.0f".format(r) else "%.2f".format(r).trimEnd('0')

    private class Snd(val short: String, val long: String)

    /** Where sound goes (the most likely output) and what it can pass through. */
    private fun sound(context: Context): Snd? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val outs = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val order = buildList {
            if (android.os.Build.VERSION.SDK_INT >= 31) add(android.media.AudioDeviceInfo.TYPE_HDMI_EARC)
            add(android.media.AudioDeviceInfo.TYPE_HDMI_ARC)
            add(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (android.os.Build.VERSION.SDK_INT >= 31) add(android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
            add(android.media.AudioDeviceInfo.TYPE_USB_DEVICE)
            add(android.media.AudioDeviceInfo.TYPE_USB_HEADSET)
            add(android.media.AudioDeviceInfo.TYPE_HDMI)
            add(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        }
        val dev = order.firstNotNullOfOrNull { t -> outs.firstOrNull { it.type == t } } ?: return null
        val name = when (dev.type) {
            android.media.AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
            android.media.AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TV speakers"
            else -> if (android.os.Build.VERSION.SDK_INT >= 31 && dev.type == android.media.AudioDeviceInfo.TYPE_HDMI_EARC) "HDMI eARC" else "Bluetooth"
        }
        val product = dev.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL }
        val enc = dev.encodings.toSet()
        val formats = buildList {
            if (android.media.AudioFormat.ENCODING_E_AC3_JOC in enc) add("Atmos")
            if (android.media.AudioFormat.ENCODING_DOLBY_TRUEHD in enc) add("TrueHD")
            if (android.media.AudioFormat.ENCODING_E_AC3 in enc) add("DD+")
            else if (android.media.AudioFormat.ENCODING_AC3 in enc) add("Dolby Digital")
            if (android.media.AudioFormat.ENCODING_DTS_HD in enc) add("DTS-HD")
            else if (android.media.AudioFormat.ENCODING_DTS in enc) add("DTS")
        }
        val ch = dev.channelCounts.maxOrNull()?.takeIf { it > 2 }?.let { if (it == 8) "7.1" else if (it == 6) "5.1" else "$it ch" }
        val long = listOfNotNull(name + (product?.let { " ($it)" } ?: ""), ch, formats.joinToString(", ").ifEmpty { null }).joinToString(" · ")
        return Snd(listOfNotNull(name, formats.firstOrNull()).joinToString(" · "), long)
    }

    private fun storage(context: Context): String {
        val s = android.os.StatFs(context.filesDir.absolutePath)
        return "${gb(s.availableBytes)} free of ${gb(s.totalBytes)}"
    }

    private fun memory(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val m = android.app.ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        return "${gb(m.availMem)} free of ${gb(m.totalMem)}"
    }

    private fun gb(b: Long): String = if (b >= 1L shl 30) "%.1f GB".format(b / (1L shl 30).toDouble()) else "${b / (1L shl 20)} MB"
}
