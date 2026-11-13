package app.ember.tv.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.outlined.Info
import app.ember.tv.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyRow
import app.ember.tv.screensaver.Photos
import app.ember.tv.screensaver.ScreensaverFeed
import app.ember.tv.HomeViewModel
import app.ember.tv.data.CatalogDef
import androidx.compose.ui.platform.testTag
import app.ember.tv.data.NavTabs
import app.ember.tv.data.Prefs
import app.ember.tv.tracker.SmartRows
import app.ember.tv.tracker.LinkMode
import app.ember.tv.tracker.Tracker
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import app.ember.tv.MainActivity
import app.ember.tv.data.ApiKeys
import app.ember.tv.data.Legal
import app.ember.tv.data.About
import app.ember.tv.data.MetaEnhance
import androidx.compose.ui.draw.clip
import app.ember.tv.data.Meta
import app.ember.tv.data.Posters
import app.ember.tv.data.PosterConfig
import app.ember.tv.data.Backup
import app.ember.tv.data.Profile
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Settings pages, split out of SettingsScreen.kt. Watching: player, addons, catalogs, Live TV, subtitles, stream format, where to watch, Play button.

// ---------------------------------------------------------------- Playback



// ---------------------------------------------------------------- Player engine

/** One setting with several answers (#270): a row showing the current answer that opens a menu; [label] defaults to the section title. */
@Composable
internal fun Choice(options: List<Pair<String, String>>, selected: String, label: String? = null, onPick: (String) -> Unit) {
    val title = label ?: LocalSettingsSection.current.orEmpty()
    var open by remember { mutableStateOf(false) }
    val value = options.firstOrNull { it.first == selected }?.second ?: selected
    options.forEach { RecordSettingOption(it.second) }
    SettingValueRow(title, value, modifier = Modifier.widthIn(max = 760.dp)) { open = true }
    if (open) OptionMenu(title, options.map { (id, text) -> MenuOption(text, selected = id == selected) { onPick(id) } }) { open = false }
}

@Composable
internal fun Note(text: String) = Text(text, color = Mute, fontSize = 12.sp, modifier = Modifier.width(760.dp))

/** A labelled progress bar for downloading and installing an update (#212). */
@Composable
internal fun UpdateProgressBar(stage: String, progress: Float) {
    val p = progress.coerceIn(0f, 1f)
    Column(Modifier.width(560.dp).semantics(mergeDescendants = true) { contentDescription = "$stage the update, ${(p * 100).toInt()} percent" },
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(stage, fontSize = 14.sp, color = Fg, modifier = Modifier.weight(1f))
            Text("${(p * 100).toInt()}%", fontSize = 14.sp, color = Fg)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Fg.copy(alpha = .18f))) {
            Box(Modifier.fillMaxWidth(p).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary))
        }
    }
}

/** Settings > System > Updates: new versions from the GitHub repository; Ember installs them and restarts. */
@Composable
internal fun UpdatesSection(vm: HomeViewModel) {
    Section(stringResource(R.string.ui_updates), "Ember ${app.ember.tv.BuildConfig.VERSION_NAME} · build ${app.ember.tv.BuildConfig.VERSION_CODE}",id="updates") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // What is installed now, next to what the check finds.
            Text(stringResource(R.string.tx_installed_version_build, app.ember.tv.BuildConfig.VERSION_NAME, app.ember.tv.BuildConfig.VERSION_CODE) +
                (if (vm.updates.updateBeta) " · beta channel" else " · stable channel"), fontSize = 15.sp, color = Fg)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(stringResource(R.string.ui_check_now)) { vm.updates.checkForUpdate(manual = true) }
                vm.updates.update?.let { u ->
                    if (vm.updates.updateProgress == null && vm.updates.installProgress == null) Chip("Install ${u.name}", selected = true) { vm.updates.installUpdate() }
                    if (vm.updates.updateProgress != null) Chip("Cancel") { vm.updates.cancelUpdate() }
                }
            }
            // #212: one bar through downloading and installing.
            vm.updates.updateProgress?.let { UpdateProgressBar("Downloading", it) }
            vm.updates.installProgress?.let { UpdateProgressBar("Installing", it) }
            vm.updates.updateMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
            vm.updates.update?.notes?.takeIf { it.isNotBlank() }?.let { Note(it.take(600)) }
            ToggleRow(
                "Beta updates",
                if (vm.updates.updateBeta) "Published beta builds from the public Ember repository. No token required."
                else "Published releases from the public Ember repository. No token required.",
                vm.updates.updateBeta,
            ) { vm.updates.toggleUpdateBeta() }
            if (vm.updates.updateBeta) {
                Choice(listOf("releases" to "Releases", "actions" to "Actions APK"), vm.updates.updateBetaSource, stringResource(R.string.tx_beta_update_source)) { vm.updates.updateBetaSource(it) }
                Note(if (vm.updates.updateBetaSource == "actions")
                    "Latest successful signed public beta build. Downloads use nightly.link; APKs expire after three days."
                    else "Only explicitly published public beta releases.")
            }
            ToggleRow(stringResource(R.string.ui_check_once_a_day), stringResource(R.string.ui_when_ember_starts_a_message_appears), vm.updates.updateAutoCheck) { vm.updates.toggleUpdateAutoCheck() }
            Note("Stable updates use published releases. Actions APKs are available after a successful beta build. Turning beta off returns to stable releases when a newer build is available.")
        }
    }
}
