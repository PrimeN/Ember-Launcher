package app.ember.tv.ui

import app.ember.tv.addServer
import app.ember.tv.disconnectPlex
import app.ember.tv.disconnectTracker
import app.ember.tv.discoverServers
import app.ember.tv.linkUsername
import app.ember.tv.refreshPhotos
import app.ember.tv.removeServer
import app.ember.tv.startLink
import app.ember.tv.startPlexLink
import app.ember.tv.toggleScreensaverSource
import app.ember.tv.toggleTracker
import app.ember.tv.toggleTrackerReports
import app.ember.tv.quickConnect
import app.ember.tv.toggleTrackerSync
import app.ember.tv.syncTrackers
import app.ember.tv.applyHeldRemovals
import app.ember.tv.updatePhotoFolder

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
import androidx.compose.ui.graphics.toArgb
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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

// Settings pages, split out of SettingsScreen.kt. System and accounts pages that share helpers: trackers, media servers, weather, screensaver, backup, restore, phone setup.

// ---------------------------------------------------------------- Backup & restore

/** What a backup holds, shown on both the Back up and Restore pages. */
internal const val BACKUP_CONTENTS =
    "One file with everything: settings, top menu, Home rows, addons, watchlist, screensaver, playback positions, tracker and media server sign-ins, and API keys entered here."

@Composable
internal fun BackupStatus(vm: HomeViewModel) {
    if (vm.backup.backupBusy) Text(stringResource(R.string.ui_working), color = Mute, fontSize = 14.sp)
    vm.backup.backupMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
}

@Composable
internal fun PassphraseField(value: String, onChange: (String) -> Unit, restoring: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextInput(value, onChange, if (restoring) "Passphrase, if the backup has one" else "Passphrase (recommended)", password = true)
        Text(
            when {
                restoring -> "Only needed for backups saved with a passphrase."
                value.isEmpty() -> "Without a passphrase the file is plain text, and anyone who gets it can use your sign-ins and keys."
                else -> "The backup is encrypted with this passphrase. It is not saved anywhere, so keep it safe: without it the backup cannot be restored."
            },
            color = Mute,
            fontSize = 13.sp,
            modifier = Modifier.width(760.dp),
        )
    }
}

@Composable
internal fun BackupPage(vm: HomeViewModel) {
    val context = LocalContext.current
    var passphrase by remember { mutableStateOf("") }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.backup.saveBackupTo(it, passphrase) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Section(stringResource(R.string.ui_back_up)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(BACKUP_CONTENTS, color = Mute, fontSize = 13.sp, modifier = Modifier.width(760.dp))
                BackupStatus(vm)
            }
        }
        Section(stringResource(R.string.ui_passphrase)) { PassphraseField(passphrase, { passphrase = it }, restoring = false) }
        Section(stringResource(R.string.ui_save)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ButtonRow(spacing = 10.dp) {
                    Chip(stringResource(R.string.ui_save_to_downloads)) {
                        // Android 10+ (the minimum) saves to Downloads through MediaStore without a permission.
                        vm.backup.saveBackupToDownloads(passphrase)
                    }
                    if (vm.backup.backupPickerAvailable) Chip(stringResource(R.string.ui_choose_where_to_save)) { createFile.launch(Backup.fileName()) }
                }
                Text(
                    stringResource(R.string.ui_saves_to_download_ember_on_this),
                    color = Mute,
                    fontSize = 12.sp,
                    modifier = Modifier.width(760.dp),
                )
            }
        }
    }
}

@Composable
internal fun RestorePage(vm: HomeViewModel) {
    var passphrase by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.backup.openBackup(it, passphrase) }
    }
    LaunchedEffect(Unit) { vm.backup.refreshBackupFiles() }
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }

    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Section(stringResource(R.string.ui_restore), stringResource(R.string.ui_replaces_everything_on_this_tv_with)) {
            BackupStatus(vm)
        }

        vm.backup.pendingRestore?.let { (_, sum) ->
            Column(
                modifier = Modifier.width(760.dp).background(PanelHi, RoundedCornerShape(12.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.ui_restore_this_backup), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        sum.created?.let { "Made ${it.take(16).replace('T', ' ')}" },
                        "${sum.addons} addon" + if (sum.addons == 1) "" else "s",
                        "${sum.signIns} sign-in" + if (sum.signIns == 1) "" else "s",
                        "${sum.keys} API key" + if (sum.keys == 1) "" else "s",
                    ).joinToString(" · "),
                    fontSize = 14.sp,
                )
                Text(stringResource(R.string.ui_everything_on_this_tv_is_replaced), color = Mute, fontSize = 13.sp)
                ButtonRow(spacing = 10.dp) {
                    Chip(stringResource(R.string.ui_restore_and_restart)) { vm.backup.confirmRestore() }
                    Chip(stringResource(R.string.ui_cancel)) { vm.backup.cancelRestore() }
                }
            }
        }

        Section(stringResource(R.string.ui_passphrase)) { PassphraseField(passphrase, { passphrase = it }, restoring = true) }

        Section(stringResource(R.string.ui_from_this_tv)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (vm.backup.backupPickerAvailable) Chip(stringResource(R.string.ui_choose_a_backup_file)) { openFile.launch(arrayOf("*/*")) }
                if (vm.backup.backupFiles.isEmpty()) {
                    Text(stringResource(R.string.ui_no_backups_saved_on_this_tv), color = Mute, fontSize = 13.sp)
                } else {
                    vm.backup.backupFiles.take(8).forEach { f ->
                        Row(
                            modifier = Modifier.width(760.dp).background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(f.name, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(dateFormat.format(Date(f.modified)), color = Mute, fontSize = 13.sp)
                            Chip(stringResource(R.string.ui_restore)) { vm.backup.openBackup(f.uri, passphrase) }
                        }
                    }
                }
            }
        }

        Section(stringResource(R.string.ui_from_a_link), stringResource(R.string.ui_for_a_new_tv_or_after)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextInput(link, { link = it }, "Direct link to a backup file")
                    Chip(stringResource(R.string.ui_restore_from_link)) { vm.backup.openBackupLink(link, passphrase) }
                }
                Text(
                    stringResource(R.string.ui_put_the_backup_somewhere_the_tv),
                    color = Mute,
                    fontSize = 12.sp,
                    modifier = Modifier.width(760.dp),
                )
            }
        }
    }
}

/** Show, hide and reorder the tabs in the top menu. Home and Settings are always shown. */
@Composable
internal fun TopMenuSection(vm: HomeViewModel) {
    Section(stringResource(R.string.ui_top_menu), stringResource(R.string.ui_choose_which_tabs_appear_and_in)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.nav.forEachIndexed { i, (id, shown) ->
                Row(
                    modifier = Modifier.width(760.dp).background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tabTitle(id), fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (NavTabs.locked(id)) {
                        Text(stringResource(R.string.ui_always_shown), color = Mute, fontSize = 13.sp)
                    } else {
                        Chip(if (shown) "Shown" else "Hidden", selected = shown) { vm.toggleTab(id) }
                    }
                    if (i > 0) Chip(stringResource(R.string.ui_move_left)) { vm.moveTab(id, -1) }
                    if (i < vm.nav.lastIndex) Chip(stringResource(R.string.ui_move_right)) { vm.moveTab(id, 1) }
                }
            }
            Chip(stringResource(R.string.ui_reset_to_default)) { vm.resetTabs() }
        }
    }
}

/** Region and explanation for Where to watch; the service rows follow it in the list. */
@Composable
internal fun StreamingServicesHeader(vm: HomeViewModel, openKeys: () -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val rev = vm.keysRev
    var region by remember { mutableStateOf("") }
    Section(stringResource(R.string.ui_where_to_watch), stringResource(R.string.ui_lists_the_streaming_services_that_have)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ButtonRow(spacing = 10.dp) {
                Chip(stringResource(R.string.ui_off), selected = vm.watch.watchProvider == "off") { vm.watch.updateWatchProvider("off") }
                Chip(stringResource(R.string.ui_tmdb_justwatch_data), selected = vm.watch.watchProvider == "tmdb") { vm.watch.updateWatchProvider("tmdb") }
                Chip(stringResource(R.string.ui_watchmode), selected = vm.watch.watchProvider == "watchmode") { vm.watch.updateWatchProvider("watchmode") }
                Chip("Streaming Availability", selected = vm.watch.watchProvider == "streaming") { vm.watch.updateWatchProvider("streaming") }
            }
            Text(
                when (vm.watch.watchProvider) {
                    "streaming" -> "Direct title links, quality and regional prices through RapidAPI. Requires a Streaming Availability plan; results are cached to reduce quota use. Other title features work without it."
                    "tmdb" -> "Free with your TMDB key and no monthly limit. Data from JustWatch: subscription, free, rent and buy, for 140+ countries. " +
                        "No prices or links into the apps: a service opens its app (or its store page)."
                    "watchmode" -> "Opens titles directly in the service's app where it can, and shows prices and quality. " +
                        "The free plan allows about 1,000 lookups a month."
                    else -> "Off: title screens show no streaming services."
                },
                color = Mute,
                fontSize = 13.sp,
                modifier = Modifier.width(760.dp),
            )
            if (vm.watch.watchProvider != "off") {
                if (!vm.watch.whereToWatchReady) {
                    Text(
                        when (vm.watch.watchProvider) {
                            "tmdb" -> "Needs a TMDB API key."
                            "streaming" -> "Needs a Streaming Availability RapidAPI key and an active provider plan."
                            else -> "Needs a Watchmode API key (free at api.watchmode.com)."
                        },
                        color = Mute,
                        fontSize = 14.sp,
                        modifier = Modifier.width(760.dp),
                    )
                    Chip(stringResource(R.string.ui_add_the_key_in_api_keys)) { openKeys() }
                } else {
                    Text(stringResource(R.string.ui_region_line, vm.watch.watchRegion), fontSize = 15.sp)
                    vm.watch.servicesMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextInput(region, { region = it }, "Two-letter country, e.g. US")
                        Chip(stringResource(R.string.ui_set_region)) { vm.watch.updateWatchRegion(region) }
                    }
                    Text(stringResource(R.string.ui_your_services_ones_with_an_installed), fontSize = 15.sp)
                    if (vm.watch.listedServices.isEmpty()) Text(
                        if (vm.watch.servicesLoading) stringResource(R.string.ui_loading_services)
                        else if (vm.watch.servicesMessage != null) "Set the region again to retry."
                        else "No services available for this region.", color = Mute, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
internal fun MediaServersSection(vm: HomeViewModel) {
    var choosingUser by remember { mutableStateOf(false) }
    var userAccount by remember { mutableStateOf<app.ember.tv.mediaserver.ServerAccount?>(null) }
    var kind by remember { mutableStateOf("jellyfin") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    @Suppress("UNUSED_VARIABLE") val health = vm.serverHealth.toList() // redraw when a load finishes
    val signedOut = vm.serversSignedOut

    Section(stringResource(R.string.ui_media_servers), stringResource(R.string.ui_continue_and_recently_added_rows_from)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            vm.serverMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }

            // Plex: one sign-in covers every server on the account.
            Column(
                modifier = Modifier.width(760.dp).background(Panel, RoundedCornerShape(12.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ui_plex), fontSize = 18.sp)
                    Text(if (!vm.plexConnected) "Not connected" else if ("plex" in signedOut) "Signed out" else "Connected", color = Mute, fontSize = 13.sp)
                }
                if (vm.plexConnected && "plex" in signedOut) {
                    // #94: Plex refused the saved sign-in.
                    Text("Plex no longer accepts Ember's sign-in (it was revoked or the password changed).", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                    Chip("Sign in again") { vm.startPlexLink() }
                }
                Text(stringResource(R.string.ui_sign_in_with_a_code_at), color = Mute, fontSize = 13.sp)
                if (vm.plexConnected) {
                    Note("User: ${vm.plexUserName ?: "Account owner"}")
                    ButtonRow(spacing = 8.dp) {
                        Chip("Switch user") { userAccount = null; choosingUser = true }
                        Chip(stringResource(R.string.ui_disconnect)) { vm.disconnectPlex() }
                    }
                } else Chip(stringResource(R.string.ui_connect_plex)) { vm.startPlexLink() }
            }

            // Jellyfin and Emby: one entry per server.
            vm.serverAccounts.forEach { a ->
                Row(
                    modifier = Modifier.width(760.dp).background(Panel, RoundedCornerShape(12.dp)).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontSize = 18.sp)
                        if (a.userName.isNotBlank()) Note("User: ${a.userName}")
                        Text("${a.kind.replaceFirstChar { it.uppercase() }} · ${a.url}", color = Mute, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (a.key in signedOut) Text("This server signed Ember out (device removed or password changed).",
                            color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                    }
                    // #94: fills in the form below with this server, ready for the password or Quick Connect.
                    if (a.key in signedOut) Chip("Sign in again") { kind = a.kind; url = a.url; user = a.userName; password = "" }
                    Chip("Switch user") { userAccount = a; choosingUser = true }
                    var removing by remember(a.key) { mutableStateOf(false) }
                    Chip(stringResource(R.string.ui_remove)) { removing = true }
                    if (removing) ConfirmDestructive("Remove ${a.name}?",
                        "Ember signs out of this server and stops showing its libraries. Nothing is deleted on the server.",
                        { removing = false }, stringResource(R.string.ui_remove)) { vm.removeServer(a.key) }
                }
            }

            Column(
                modifier = Modifier.width(760.dp).background(Panel, RoundedCornerShape(12.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.ui_add_a_jellyfin_or_emby_server), fontSize = 18.sp)
                Chip(if (vm.discovering) "Looking..." else "Find servers on my network") { vm.discoverServers() }
                vm.found?.forEach { f ->
                    if (f.kind == "plex") {
                        Text(
                            "Plex server found: ${f.name}. " + if (vm.plexConnected) "It is already on your account." else "Connect Plex above to use it.",
                            color = Mute,
                            fontSize = 13.sp,
                        )
                    } else {
                        Chip(stringResource(R.string.ui_use_server, f.name, f.kind.replaceFirstChar { it.uppercase() }, f.url)) {
                            kind = f.kind
                            url = f.url
                        }
                    }
                }
                ButtonRow(spacing = 10.dp) {
                    Chip(stringResource(R.string.ui_jellyfin), selected = kind == "jellyfin") { kind = "jellyfin" }
                    Chip(stringResource(R.string.ui_emby), selected = kind == "emby") { kind = "emby" }
                }
                TextInput(url, { url = it }, "Server address, e.g. 192.168.1.20:8096")
                TextInput(user, { user = it }, "Username")
                TextInput(password, { password = it }, "Password", password = true)
                ButtonRow(spacing = 10.dp) {
                    Chip(stringResource(R.string.ui_add_server)) {
                        vm.addServer(kind, url, user, password)
                        password = ""
                    }
                    // #97: approve a code from the Jellyfin app instead of typing a password.
                    if (kind == "jellyfin") Chip("Use Quick Connect") { vm.quickConnect(url) }
                }
                if (kind == "jellyfin") Text("Quick Connect: enter the address, then approve the code in the Jellyfin app or web (Profile › Quick Connect). No username or password needed.",
                    color = Mute, fontSize = 12.sp)
            }
        }
    }
    if (choosingUser) ServerUserPicker(vm, userAccount) { choosingUser = false }
}

@Composable
internal fun ScreensaverSection(vm: HomeViewModel) {
    val context = LocalContext.current
    val askForPictures = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshPhotos() }
    Section(stringResource(R.string.ui_screensaver), stringResource(R.string.screensaver_manual_summary)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ButtonRow {
                Chip(stringResource(R.string.ui_screensaver_now)) { QuickActions.screensaver(context) }
                Chip(stringResource(R.string.screensaver_tv_settings)) { QuickActions.open(context, android.provider.Settings.ACTION_DREAM_SETTINGS) }
            }
            // One switch per source, in a column so every source fits on screen.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ScreensaverFeed.ALL.forEach { (id, label) ->
                    ToggleRow(label, null, id in vm.screensaverSources) { vm.toggleScreensaverSource(id) }
                }
            }
            if (ScreensaverFeed.PHOTOS in vm.screensaverSources) {
                if (!vm.photosAllowed) {
                    Text(stringResource(R.string.ui_ember_needs_permission_to_read_pictures), color = Mute, fontSize = 13.sp)
                    Chip(stringResource(R.string.ui_allow_access_to_pictures)) { askForPictures.launch(Photos.permission) }
                } else if (vm.photoFolders.isEmpty()) {
                    Text(stringResource(R.string.ui_no_pictures_found_on_this_tv), color = Mute, fontSize = 13.sp)
                } else {
                    Text(stringResource(R.string.ui_folder), fontSize = 15.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
                        item(key = "all") { Chip(stringResource(R.string.ui_all_folders), selected = vm.photoFolder == null) { vm.updatePhotoFolder(null) } }
                        items(vm.photoFolders, key = { it.first }) { (name, count) ->
                            Chip("$name ($count)", selected = vm.photoFolder == name) { vm.updatePhotoFolder(name) }
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.ui_paintings_are_public_domain_works_from),
                color = Mute,
                fontSize = 12.sp,
                modifier = Modifier.width(760.dp),
            )
        }
    }
}

@Composable
internal fun WeatherSection(vm: HomeViewModel) {
    var city by remember { mutableStateOf("") }
    Section(stringResource(R.string.ui_weather), stringResource(R.string.ui_shown_in_the_top_bar_and)) {
        ButtonRow {app.ember.tv.weather.WeatherProviders.choices.forEach {(id,label)->Chip(label,selected=vm.weather.provider==id){vm.weather.updateProvider(id)}}}
        SettingToggle("Use fallback weather sources",null,vm.weather.fallback){vm.weather.updateFallback(!vm.weather.fallback)}
        vm.weather.details?.let {Text("${it.source} · " + if(vm.weather.updatedAt>0)java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(vm.weather.updatedAt)) else "Saved forecast",color=Mute,fontSize=13.sp)}
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                vm.weather.weatherPlaceName?.let { "Showing weather for $it" + (vm.weather.weatherText?.let { w -> ": $w" } ?: "") } ?: "Off",
                fontSize = 15.sp,
            )
            vm.weather.weatherMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
            TextInput(city, { city = it }, "City, e.g. Toronto")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
                item(key = "city") { Chip(stringResource(R.string.ui_use_this_city)) { vm.weather.setWeatherCity(city) } }
                item(key = "approx") { Chip(stringResource(R.string.ui_use_my_approximate_area)) { vm.weather.useApproximateLocation() } }
                item(key = "unit") { Chip(if (vm.weather.fahrenheit) "Showing °F" else "Showing °C") { vm.weather.updateFahrenheit(!vm.weather.fahrenheit) } }
                if (vm.weather.weatherPlaceName != null) item(key = "off") { Chip(stringResource(R.string.ui_turn_off)) { vm.weather.weatherOff() } }
            }
            Text(
                stringResource(R.string.ui_approximate_area_guesses_your_city_from),
                color = Mute,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
internal fun TextInput(value: String, onChange: (String) -> Unit, placeholder: String, password: Boolean = false, width: Int = 520, numeric: Boolean = false) {
    TvTextField(value, onChange, Modifier.width(width.dp), placeholder = placeholder, password = password, keyboardType = if(numeric)androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Text)
}

/** Keep trackers in sync (#90): the switch, the last round, and drops waiting for your say. */
@Composable
internal fun TrackerSyncCard(vm: HomeViewModel) {
    Column(
        modifier = Modifier.width(760.dp).background(Panel, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Keep trackers in sync", fontSize = 18.sp)
        Text("Copies watched history, watchlist and ratings between Trakt, Simkl and WeTrakr and Ember's own watchlist, " +
            "both ways, including removals. The first sync copies everything each one already has.", color = Mute, fontSize = 13.sp)
        ButtonRow(spacing = 10.dp) {
            SwitchChip("Sync", vm.trackerSyncOn) { vm.toggleTrackerSync() }
            if (vm.trackerSyncOn && !vm.trackerSyncBusy) Chip("Sync now") { vm.syncTrackers(force = true) }
        }
        vm.trackerSyncStatus?.let { Text(it, color = Mute, fontSize = 13.sp) }
        if (vm.trackerSyncHeld.isNotEmpty()) {
            Text(vm.trackerSyncHeld.entries.joinToString(" ") { (name, n) -> "$name suddenly reports $n fewer titles than last time." } +
                " Those removals were not copied to your other trackers, in case it was a bad read. Check on the service, then:",
                color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            Chip("Copy those removals too") { vm.applyHeldRemovals() }
        }
        Text("Not synced: Simkl watchlist removals (Simkl would delete the history too) and anime, whose episode numbers differ between services.",
            color = Mute, fontSize = 12.sp)
    }
}

@Composable
internal fun TrackerRow(vm: HomeViewModel, t: Tracker, openKeys: () -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val rev = vm.trackerRev // read so this row redraws after sign-in changes
    val connected = t.isConnected()
    var username by remember(t.id) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .width(760.dp)
            .background(Panel, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(t.name, fontSize = 18.sp)
            Text(
                when {
                    !t.configured -> "Needs an API key"
                    connected -> "Connected" + (t.accountLabel()?.let { " · $it" } ?: "")
                    else -> "Not connected"
                },
                color = Mute,
                fontSize = 13.sp,
            )
        }
        Text(t.blurb, color = Mute, fontSize = 13.sp)
        when {
            !t.configured -> Chip(stringResource(R.string.ui_add_the_key_in_api_keys)) { openKeys() }
            connected -> ButtonRow(spacing = 10.dp) {
                SwitchChip(stringResource(R.string.ui_use_on_home), t.id !in vm.trackersOff) { vm.toggleTracker(t.id) }
                // Separate from Home (#80): a hidden tracker still hears what you watch unless this is off.
                if (t.scrobbles) SwitchChip(stringResource(R.string.ui_report_what_i_watch), t.id !in vm.trackersQuiet) { vm.toggleTrackerReports(t.id) }
                Chip(stringResource(R.string.ui_disconnect)) { vm.disconnectTracker(t.id) }
            }
            t.mode == LinkMode.DEVICE_CODE -> Chip(stringResource(R.string.ui_connect_name, t.name)) { vm.startLink(t.id) }
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput(username, { username = it }, "Your ${t.name} username")
                Chip(stringResource(R.string.ui_link_account)) { vm.linkUsername(t.id, username) }
            }
        }
    }
}

// ---------------------------------------------------------------- Phone setup

/** Explicit, non-persistent opt-in. The server independently enforces the same 15-minute lease. */
@Composable
internal fun PhoneSetupPage(vm: HomeViewModel) {
    val accentHex = String.format("#%06X", MaterialTheme.colorScheme.primary.toArgb() and 0xFFFFFF)
    val setup = remember(vm.profiles.profileId) { app.ember.tv.phone.PhoneSetup(vm::phoneHandler, accentHex) }
    var link by remember(setup) { mutableStateOf<String?>(null) }
    var seconds by remember(setup) { androidx.compose.runtime.mutableLongStateOf(0L) }
    var status by remember(setup) { mutableStateOf("Phone setup is off. Turn it on when you are ready to connect.") }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val startError = stringResource(R.string.ui_this_tv_has_no_address_on)
    fun disable(message: String) {
        setup.stop()
        vm.clearPendingContent()
        link = null
        seconds = 0
        status = message
    }
    androidx.compose.runtime.DisposableEffect(setup, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                disable("Phone setup turned off when Ember left the foreground. Turn it on to reconnect.")
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            setup.stop()
            vm.clearPendingContent()
            vm.clearPhoneLog()
        }
    }
    LaunchedEffect(setup, link) {
        while (link != null) {
            val remaining = setup.remainingMillis
            if (remaining <= 0) {
                disable("The 15-minute session expired. Turn Phone setup on again and scan the new code.")
                break
            }
            seconds = (remaining + 999) / 1000
            delay(1000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Section(stringResource(R.string.ui_set_up_from_your_phone), stringResource(R.string.ui_no_typing_with_the_remote_add)) {
            ToggleRow("Phone setup", "Off by default. Automatically turns off after 15 minutes, when you leave this page, or when Ember leaves the foreground.", link != null) {
                if (link != null) disable("Phone setup is off. The previous link no longer works.")
                else {
                    val started = runCatching { setup.start() }.getOrNull()
                    if (started == null) {
                        setup.stop()
                        status = startError
                    } else {
                        seconds = (setup.remainingMillis + 999) / 1000
                        link = started
                    }
                }
            }
            val l = link
            if (l == null) {
                Note(status)
            } else {
                Text("Turns off in %02d:%02d".format(seconds / 60, seconds % 60),
                    color = MaterialTheme.colorScheme.primary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    QrCode(l, 210.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(420.dp)) {
                        Text(stringResource(R.string.ui_1_connect_your_phone_to_the), fontSize = 15.sp)
                        Text(stringResource(R.string.ui_2_scan_the_code_with_the), fontSize = 15.sp)
                        Text(stringResource(R.string.ui_3_fill_in_what_you_need), fontSize = 15.sp)
                        Text(stringResource(R.string.ui_or_open_this_address), color = Mute, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        Text(l, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Note(stringResource(R.string.ui_the_page_works_only_while_this))
                    }
                }
            }
        }
        vm.pendingContent?.let { pending ->
            Section("Review phone change", pending.summary) {
                ButtonRow {
                    Chip(if (vm.approvingContent) "Saving…" else "Approve") { vm.approveContent() }
                    Chip("Reject") { if (!vm.approvingContent) vm.clearPendingContent() }
                }
            }
        }
        Section(stringResource(R.string.ui_received_from_your_phone)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (vm.phoneLog.isEmpty()) Note(stringResource(R.string.ui_nothing_yet))
                vm.phoneLog.forEach { Text("• $it", fontSize = 14.sp) }
                vm.iptvSetup.iptvAddMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
            }
        }
    }
}

/** A QR code drawn with ZXing's encoder, dark on white with a quiet border. */
@Composable
internal fun QrCode(text: String, size: androidx.compose.ui.unit.Dp) {
    val matrix = remember(text) {
        runCatching {
            com.google.zxing.qrcode.QRCodeWriter().encode(
                text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 1),
            )
        }.getOrNull()
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp)) {
        if (matrix != null) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val cell = this.size.width / matrix.width
                for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                    if (matrix.get(x, y)) {
                        drawRect(Color.Black, topLeft = androidx.compose.ui.geometry.Offset(x * cell, y * cell), size = androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
        }
    }
}
