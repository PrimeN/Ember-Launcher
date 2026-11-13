package app.ember.tv.ui

import app.ember.tv.toggleFeature

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

@Composable
internal fun LiveTvSettingsPage(vm: HomeViewModel) {
    if (!vm.featureOn(app.ember.tv.data.Features.LIVE)) {
        Section(stringResource(R.string.ui_live_tv), stringResource(R.string.ui_switched_off_in_settings_features_so)) {
            Chip(stringResource(R.string.ui_turn_live_tv_on)) { vm.toggleFeature(app.ember.tv.data.Features.LIVE) }
        }
        return
    }
    val iptv = vm.iptv
    var kind by remember { mutableStateOf("m3u") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf("") }
    var format by remember { mutableStateOf("ts") }
    var advanced by remember { mutableStateOf(false) }
    var guideTools by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Section(stringResource(R.string.ui_live_tv), stringResource(R.string.ui_iptv_channels_with_a_programme_guide)) {
            SourceStatusButton("IPTV source status",iptv.health.entries.values) {id->
                if(id.endsWith(":guide"))iptv.refreshGuide(id.removeSuffix(":guide")) else iptv.refresh(force=true,only=id)
            }
            iptv.guideProgress?.let { Text(it,color=Mute,fontSize=12.sp) }
            Chip("Programme guide tools") { guideTools = true }
            Note(
                stringResource(R.string.ui_add_an_m3u_playlist_link_or) +
                    "use channel up and down (or Up and Down) to change channel, and Info for the playback panel. " +
                    "Addon catalogs for Live TV are chosen in Settings > Addons & catalogs.",
            )
        }

        Section(stringResource(R.string.ui_your_sources), if (iptv.sources.isEmpty()) "None yet." else "${iptv.channels.size} channels · guide for ${iptv.guide.size}") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                iptv.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
                iptv.sources.forEach { s ->
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontSize = 16.sp)
                            Text(
                                (if (s.kind == "xtream") "Xtream Codes" else "M3U playlist") + " · " +
                                    "${iptv.channels.count { it.sourceId == s.id }} channels" +
                                    if (s.epgUrl.isNotBlank()) " · own guide link" else "",
                                color = Mute,
                                fontSize = 12.sp,
                            )
                        }
                        SwitchChip("", s.enabled) { iptv.setEnabled(s.id, !s.enabled) }
                        var removing by remember(s.id) { mutableStateOf(false) }
                        Chip(stringResource(R.string.ui_remove)) { removing = true }
                        if (removing) ConfirmDestructive("Remove ${s.name}?",
                            "Its channels, sign-in and guide link are removed from this TV.",
                            { removing = false }, stringResource(R.string.ui_remove)) { iptv.remove(s.id) }
                    }
                }
                if (iptv.sources.isNotEmpty()) {
                    ButtonRow(spacing = 10.dp) {
                        Chip(if (iptv.loading) "Loading channels..." else "Reload channels") { iptv.refresh(force = false) }
                        Chip(if (iptv.guideLoading) "Loading the guide..." else "Reload the guide") { iptv.refreshGuide() }
                    }
                }
            }
        }

        Section(stringResource(R.string.ui_add_a_source)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Choice(listOf("m3u" to "M3U playlist link", "xtream" to "Xtream Codes login"), kind, "Type") { kind = it }
                TextInput(name, { name = it }, "Name (optional), e.g. My provider")
                if (kind == "m3u") {
                    TextInput(url, { url = it }, "Playlist link, e.g. http://provider.tv/get.php?...", width = 760)
                } else {
                    TextInput(url, { url = it }, "Server, e.g. http://provider.tv:8080", width = 760)
                    TextInput(user, { user = it }, "Username")
                    TextInput(pass, { pass = it }, "Password", password = true)
                    Choice(listOf("ts" to "MPEG-TS (most compatible)", "m3u8" to "HLS"), format, "Streams") { format = it }
                }
                SettingsDisclosure(stringResource(R.string.settings_advanced_options),advanced,Modifier.settingsAnchor("iptv-advanced")) {advanced=!advanced}
                if (advanced) {
                    TextInput(epg, { epg = it }, "Guide (XMLTV) link, if not the provider's own", width = 760)
                    TextInput(agent, { agent = it }, "User agent, only if your provider asks for one", width = 760)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(if (vm.iptvSetup.iptvAdding) "Adding..." else "Add source") {
                        vm.iptvSetup.addIptvSource(kind, name, url, user, pass, epg, agent, format) {
                            name = ""; url = ""; user = ""; pass = ""; epg = ""; agent = ""
                        }
                    }
                    vm.iptvSetup.iptvAddMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, modifier = Modifier.width(600.dp)) }
                }
                Note(
                    stringResource(R.string.ui_logins_and_links_are_stored_encrypted) +
                        "The guide comes from the playlist (url-tvg) or the Xtream server unless you give a link, and refreshes every 12 hours.",
                )
            }
        }
    }
    if (guideTools) EpgTools(iptv) { guideTools = false }
}

// ---------------------------------------------------------------- Subtitles
