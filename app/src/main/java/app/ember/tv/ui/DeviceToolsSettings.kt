package app.ember.tv.ui

import app.ember.tv.openSettingsGuarded

import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.R
import app.ember.tv.device.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared page for all Ember interfaces. Hardware-specific shortcuts are discovered off the UI thread. */
@Composable
internal fun DeviceToolsSettings(vm: HomeViewModel) {
    if (!vm.settingsAllowed) return
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { revision++; onPauseOrDispose {} }
    val menus by produceState<List<DeviceMenu>?>(null, context, revision) {
        value = withContext(Dispatchers.IO) { DeviceMenus.detect(context).filter { it.kind != DeviceMenuKind.ENGINEERING } }
    }
    val snapshot by rememberQuickPanelSnapshot()
    val clips = remember(context) { CalibrationClips(context) }
    var clipRevision by remember { mutableIntStateOf(0) }
    var pendingClip by rememberSaveable { mutableStateOf<String?>(null) }
    var sampleMenu by remember { mutableStateOf<CalibrationClip?>(null) }
    val scope = rememberCoroutineScope()
    var pendingProfile by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val kind = CalibrationClip.entries.firstOrNull { it.name == pendingClip }
        val profile = pendingProfile
        pendingClip = null; pendingProfile = null
        if (uri != null && kind != null && vm.settingsAllowed && profile == vm.profiles.profileId) scope.launch {
            if (withContext(Dispatchers.IO) { clips.save(kind, uri) }) clipRevision++
            else Toast.makeText(context, R.string.cal_clip_permission_failed, Toast.LENGTH_LONG).show()
        }
    }
    fun start(intent: android.content.Intent) {
        vm.openSettingsGuarded {
            runCatching { context.startActivity(intent) }.onFailure {
                Toast.makeText(context, R.string.device_menu_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Section(stringResource(R.string.cal_patterns), stringResource(R.string.cal_intro), id = "calibration-patterns") {
            ButtonRow {
                CalibrationPattern.entries.forEach { pattern ->
                    Chip(stringResource(pattern.label)) { start(CalibrationActivity.pattern(context, pattern)) }
                }
            }
        }
        Section(stringResource(R.string.cal_video_patterns), stringResource(R.string.cal_video_intro), id = "calibration-video") {
            Note(stringResource(R.string.cal_video_credits))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CalibrationClip.entries.forEach { kind ->
                    val uri = remember(kind, clipRevision) { clips.get(kind) }
                    val hdrTypes = remember(revision) {
                        runCatching { context.getSystemService(WindowManager::class.java).defaultDisplay.hdrCapabilities?.supportedHdrTypes?.toList().orEmpty() }.getOrDefault(emptyList())
                    }
                    val supported = kind.hdrType == null || kind.hdrType in hdrTypes
                    Text(stringResource(kind.title), fontSize = 16.sp)
                    if (uri != null) {
                        val name by produceState<String?>(null, uri) { value = withContext(Dispatchers.IO) { app.ember.tv.player.MediaFiles.name(context, uri) } }
                        name?.let { Text(it, fontSize = 13.sp, color = Mute, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    }
                    if (!supported) Note(stringResource(R.string.cal_display_unsupported))
                    ButtonRow {
                        val included = BundledCalibrationSamples.forKind(kind)
                        if (included.isNotEmpty()) Chip(stringResource(R.string.cal_included_patterns, included.size)) {
                            if (supported) sampleMenu = kind
                            else Toast.makeText(context, R.string.cal_display_unsupported, Toast.LENGTH_LONG).show()
                        }
                        if (uri != null) {
                            // Keep Play focusable with an explanation on unsupported outputs rather than silently tone-map.
                            Chip(stringResource(R.string.cal_play_clip)) {
                                if (supported) start(CalibrationActivity.clip(context, kind))
                                else Toast.makeText(context, R.string.cal_display_unsupported, Toast.LENGTH_LONG).show()
                            }
                        }
                        Chip(stringResource(if (uri == null) R.string.cal_choose_clip else R.string.cal_replace_clip)) {
                            vm.openSettingsGuarded {
                                pendingClip = kind.name; pendingProfile = vm.profiles.profileId
                                runCatching { picker.launch(arrayOf("video/*", "application/octet-stream")) }.onFailure {
                                    pendingClip = null; pendingProfile = null
                                    Toast.makeText(context, R.string.cal_no_picker, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        if (uri != null) Chip(stringResource(R.string.cal_remove_clip)) { clips.remove(kind); clipRevision++ }
                    }
                }
            }
        }
        Section(stringResource(R.string.device_menus), stringResource(R.string.device_menus_intro), id = "device-menus") {
            if (menus == null) Note(stringResource(R.string.device_detecting))
            else if (menus.orEmpty().isEmpty()) Note(stringResource(R.string.device_no_menus))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                menus.orEmpty().groupBy { it.kind }.forEach { (kind, options) ->
                    Text(stringResource(when (kind) {
                        DeviceMenuKind.PICTURE -> R.string.device_picture
                        DeviceMenuKind.DEVICE -> R.string.device_manufacturer
                        DeviceMenuKind.ENGINEERING -> R.string.device_engineering
                        DeviceMenuKind.INPUTS -> R.string.device_inputs
                    }), fontSize = 15.sp, color = Mute)
                    ButtonRow {
                        options.forEach { menu ->
                            Chip(menu.title, modifier = Modifier.widthIn(max = 360.dp)) { vm.openSettingsGuarded { DeviceMenus.open(context, menu) } }
                        }
                    }
                }
            }
        }
        Section(stringResource(R.string.device_inputs), stringResource(R.string.device_inputs_intro), id = "device-inputs") {
            val inputs = snapshot?.inputs.orEmpty()
            if (snapshot == null) Note(stringResource(R.string.device_detecting))
            else if (inputs.isEmpty()) Note(stringResource(R.string.device_no_inputs))
            ButtonRow {
                inputs.forEach { input ->
                    val status = input.connected?.let { stringResource(if (it) R.string.device_connected else R.string.device_disconnected) }
                    Chip(input.label + (status?.let { " · $it" } ?: "")) { vm.openSettingsGuarded { DeviceInputs.open(context, input.id) } }
                }
            }
        }
    }
    sampleMenu?.let { kind ->
        OptionMenu(stringResource(kind.title), BundledCalibrationSamples.forKind(kind).map { sample ->
            MenuOption(stringResource(sample.title)) { start(CalibrationActivity.sample(context, sample)) }
        }) { sampleMenu = null }
    }
}
