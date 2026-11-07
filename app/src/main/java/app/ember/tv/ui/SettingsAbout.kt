package app.ember.tv.ui

import androidx.compose.runtime.mutableIntStateOf
import app.ember.tv.applyPrivate

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.layout.heightIn
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

// Settings pages, split out of SettingsScreen.kt. About and Legal pages.

// ---------------------------------------------------------------- About

internal fun LazyListScope.aboutPage(vm: HomeViewModel) {
    item(key = "abouthead") {
        Column(
            modifier = Modifier.width(860.dp).background(Panel, RoundedCornerShape(20.dp)).padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EmberAppIcon(96.dp)
            Text(Legal.APP, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.ui_version_line, app.ember.tv.BuildConfig.VERSION_NAME, app.ember.tv.BuildConfig.VERSION_CODE), color = Mute, fontSize = 14.sp)
        }
    }
    item(key = "aboutwhy") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ui_why_app, Legal.APP), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Mute)
            Column(Modifier.width(860.dp).background(Panel, RoundedCornerShape(16.dp)).padding(vertical = 6.dp)) {
                About.WHY.forEach { p ->
                    Focusable(onClick = {}, modifier = Modifier.fillMaxWidth(), radius = app.ember.tv.ui.TvTokens.Shape.Card, container = Panel) {
                        Text(p, fontSize = 14.sp, lineHeight = 21.sp, color = Fg, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                    }
                }
            }
        }
    }
    item(key = "aboutlogtitle") { Text(stringResource(R.string.ui_changelog), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Mute) }
    items(About.CHANGELOG, key = { "rel:${it.version}" }) { r ->
        val first = r.version == About.CHANGELOG.last().version
        var taps by remember { mutableIntStateOf(0) }
        var asking by remember { mutableStateOf(false) }
        if (asking) PrivateUnlockDialog(vm) { asking = false }
        // The first release: ten presses ask for the password of the private set (when the build has one).
        Focusable(onClick = { if (first) { taps++; if (taps >= 10) { taps = 0; asking = true } } }, modifier = Modifier.width(860.dp), radius = app.ember.tv.ui.TvTokens.Shape.Large, container = Panel) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.version, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(r.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (r.version == About.CHANGELOG.first().version) Text(stringResource(R.string.ui_current), color = Mute, fontSize = 12.sp)
                }
                r.changes.forEach { c ->
                    Text("•  $c", fontSize = 13.sp, lineHeight = 19.sp, color = Fg)
                }
            }
        }
    }
    item(key = "aboutmade") {
        Box(Modifier.width(860.dp).background(Panel, RoundedCornerShape(16.dp)).padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.ui_made_by, About.AUTHOR), color = Mute, fontSize = 14.sp)
        }
    }
}

/** Password for the private set built into this APK (API keys, addons, IPTV). */
@Composable
private fun PrivateUnlockDialog(vm: HomeViewModel, onClose: () -> Unit) {
    var pass by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val available = app.ember.tv.data.PrivateBundle.available
    val field = remember { androidx.compose.ui.focus.FocusRequester() }
    val unlock = {
        if (pass.isNotEmpty() && !vm.privateBusy) {
            message = "Opening…"
            vm.applyPrivate(pass) { m -> message = m; if (m.startsWith("Loaded")) { done = true; pass = "" } }
        }
    }
    MediaDialog("Private set",onClose,resizeForKeyboard=true,initialFocus=field) {
            if (!available) {
                Text("This build has nothing to unlock.", color = Mute, fontSize = 14.sp)
                Chip(stringResource(R.string.ui_close), modifier = Modifier.fillMaxWidth().focusRequester(field)) { onClose() }
            } else {
                Text("Enter the password to load your API keys, addons, IPTV sources and collections.", color = Mute, fontSize = 14.sp)
                if (!done) {
                    TvTextField(pass, { pass = it }, Modifier.fillMaxWidth().focusRequester(field), placeholder = "Password", password = true, onSubmit = { unlock() })
                }
                message?.let { Text(it, fontSize = 14.sp, lineHeight = 20.sp) }
                ButtonRow(spacing = 10.dp) {
                    if (!done) Chip(if (vm.privateBusy) "Working…" else "Unlock") { unlock() }
                    Chip(if (done) stringResource(R.string.ui_close) else stringResource(R.string.ui_cancel)) { onClose() }
                }
            }
    }
}

// ---------------------------------------------------------------- Legal

internal fun LazyListScope.legalPage(id: String, title: String, subtitle: String, groups: List<Legal.Group>) {
    item(key = "$id:head") { Section(title, subtitle) {} }
    groups.forEachIndexed { gi, g ->
        item(key = "$id:$gi") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(g.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Mute)
                Column(
                    modifier = Modifier.width(860.dp).background(Panel, RoundedCornerShape(16.dp)).padding(vertical = 6.dp),
                ) {
                    g.items.forEachIndexed { i, it ->
                        LegalRow(it)
                        if (i < g.items.lastIndex) {
                            Box(Modifier.padding(start = 20.dp).fillMaxWidth().height(1.dp).background(PanelHi))
                        }
                    }
                }
                g.footer?.let { Text(it, color = Mute, fontSize = 12.sp, modifier = Modifier.width(860.dp).padding(start = 4.dp)) }
            }
        }
    }
}

/** One entry: name in the accent colour, then what it is. Focusable so the remote can scroll through. */
@Composable
internal fun LegalRow(item: Legal.Item) {
    Column {
        Focusable(onClick = {}, modifier = Modifier.fillMaxWidth(), radius = app.ember.tv.ui.TvTokens.Shape.Card, container = Panel) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
                Text(item.text, fontSize = 13.sp, lineHeight = 18.sp, color = Fg)
                item.link?.let { Text(it.removePrefix("https://"), fontSize = app.ember.tv.ui.TvTokens.Type.Caption.sp, color = Mute) }
            }
        }
        item.licenseAsset?.let { LegalLicenseDropdown(it) }
    }
}

/** Bounded, remote-scrollable disclosure; opening Legal never fetches a license online. */
@Composable
private fun LegalLicenseDropdown(asset: String) {
    var expanded by rememberSaveable(asset) { mutableStateOf(false) }
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsDisclosure("Full license text", expanded) { expanded = !expanded }
        if (expanded) {
            val blocks = remember(context, asset) {
                context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readLines() }
                    .chunked(4).map { it.joinToString("\n") }
            }
            // Separate focus targets let D-pad users reach the end of a long license.
            // A fixed upper bound also permits nesting inside the settings LazyColumn.
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), contentPadding = PaddingValues(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(blocks) { _, block ->
                    Focusable(onClick = {}, modifier = Modifier.fillMaxWidth(), radius = app.ember.tv.ui.TvTokens.Shape.Small,
                        container = Wash, focusedContainer = PillColor, focusedContent = OnPillColor,
                        outline = false, focusedScale = app.ember.tv.ui.TvTokens.Focus.None) {
                        Text(block, fontSize = 13.sp, lineHeight = 18.sp,
                            modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun DisclaimerPage() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Section(stringResource(R.string.ui_disclaimer)) {}
        Column(Modifier.width(860.dp).background(Panel, RoundedCornerShape(16.dp)).padding(vertical = 6.dp)) {
            Legal.DISCLAIMER.forEach { para ->
                Focusable(onClick = {}, modifier = Modifier.fillMaxWidth(), radius = app.ember.tv.ui.TvTokens.Shape.Card, container = Panel) {
                    Text(para, fontSize = 15.sp, lineHeight = 22.sp, color = Fg, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
        }
        Text("${Legal.APP} ${app.ember.tv.BuildConfig.VERSION_NAME}", color = Mute, fontSize = 12.sp)
    }
}
