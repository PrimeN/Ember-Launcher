package app.ember.tv.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.data.Prefs
import app.ember.tv.home.DefaultHome
import app.ember.tv.system.Lighter
import kotlinx.coroutines.launch

/**
 * #473 System access › Make it lighter: free memory, keep apps from running in the background,
 * switch off Google TV extras, and turn animations and background scanning down.
 */
@Composable
internal fun LighterSection(app: Context, rev: Int, ready: Boolean, busy: Boolean, act: (suspend () -> String?) -> Unit) {
    val needs = if (ready) null else "Connect first."
    val memory = remember(rev, busy) { Lighter.memory(app) }
    val scale = remember(rev) { Lighter.animationScale(app) }
    val scanning = remember(rev) { Lighter.scanningOn(app) }
    val prefs = remember { Prefs(app) }
    var onPlay by remember { mutableStateOf(prefs.freeMemoryOnPlay) }
    var apps by remember { mutableStateOf(false) }
    val fire = remember { Lighter.isFireTv(app) }
    val extras = remember(rev) { Lighter.EXTRAS.filter { DefaultHome.isInstalled(app, it.pkg) }.map { it to DefaultHome.isEnabled(app, it.pkg) } }

    Section(
        "Make it lighter", if (memory.totalMb > 0) "Memory: ${Lighter.memoryLine(memory)}." else "More memory for Ember and your videos.", id = "system-lighter",
        info = "Each change here switches back the same way.\n\n" +
            "Closing background apps only closes apps you aren't using; they open again normally.\n\n" +
            "An app kept from running in the background still works when you open it, but can't download, refresh or play once you leave it.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingActionRow(if (busy) "Working…" else "Free memory now", "Closes apps waiting in the background.") { act { Lighter.freeMemory(app) } }
            ToggleRow("Free memory when a video starts", "Does the same each time Ember's player opens.", onPlay, unavailable = needs) {
                onPlay = !onPlay; prefs.freeMemoryOnPlay = onPlay
            }
            SettingActionRow("Apps in the background", "Choose which of your apps may keep running after you leave them.") { if (ready) apps = true else act { "Connect first." } }
            Choice(listOf("1" to "Normal", "0.5" to "Fast", "0" to "Off"), when { scale <= 0.01f -> "0"; scale < 0.9f -> "0.5"; else -> "1" },
                label = "Animations", unavailable = needs) { v -> act { Lighter.setAnimations(app, v.toFloat()) } }
            ToggleRow("Scanning while Wi-Fi and Bluetooth are off", "The TV keeps looking for networks and devices. Off saves a little.", scanning, unavailable = needs) {
                act { Lighter.setScanning(app, !scanning) }
            }
            if (extras.isNotEmpty()) {
                Text(if (fire) "Extras" else "Google TV extras · on means it's running", color = Mute, fontSize = 13.sp)
                extras.forEach { (e, on) ->
                    ToggleRow(e.name, (if (e.warn) "⚠ " else "") + "Off: " + e.loses.replaceFirstChar { it.lowercase() }, on, unavailable = needs) {
                        act { SystemTweaks.setPackage(app, e.pkg, !on) }
                    }
                }
            }
        }
    }
    if (apps) BackgroundAppsDialog(app) { apps = false }
}

/** Each app you installed, with a switch for running in the background. */
@Composable
private fun BackgroundAppsDialog(app: Context, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Lighter.BackgroundApp>?>(null) }
    var message by remember { mutableStateOf<String?>("Reading your apps…") }
    LaunchedEffect(Unit) {
        list = Lighter.backgroundApps(app)
        message = if (list == null) "Connect first." else if (list!!.isEmpty()) "You haven't installed any apps." else null
    }
    MediaDialog("Apps in the background", close, wide = true) {
        EditorHeading("Off keeps an app from running once you leave it", "It still opens and plays normally. Leave on the apps that download, play music or show notifications.")
        list?.forEach { a ->
            ToggleRow(a.name, if (a.allowed) "Can run in the background" else "Closed when you leave it", a.allowed) {
                scope.launch {
                    val error = Lighter.setBackground(app, a.pkg, !a.allowed)
                    if (error == null) list = list?.map { if (it.pkg == a.pkg) it.copy(allowed = !a.allowed) else it } else message = error
                }
            }
        }
        EditorFooter("Done", close, message, warn = list == null && message != "Reading your apps…") {}
    }
}
