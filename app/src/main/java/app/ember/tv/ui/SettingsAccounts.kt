package app.ember.tv.ui

import app.ember.tv.saveApiKey

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

// Settings pages, split out of SettingsScreen.kt. Accounts: API keys and profiles.

// ---------------------------------------------------------------- API keys

@Composable
internal fun ApiKeysHeader(vm: HomeViewModel) {
    Section(stringResource(R.string.ui_api_keys), stringResource(R.string.ui_for_services_that_need_one_keys)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.ui_typing_a_long_key_with_the),
                color = Mute,
                fontSize = 13.sp,
                modifier = Modifier.width(760.dp),
            )
            vm.keysMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
        }
    }
}

/**
 * Settings > API keys as a grid of services (#214): each tile shows whether its key is there and
 * whether it is Required or Optional; OK opens the service's dialog to read about it and enter keys.
 */
@Composable
internal fun ApiKeysGrid(vm: HomeViewModel) {
    @Suppress("UNUSED_VARIABLE")
    val rev = vm.keysRev // read so the tiles redraw after a save
    var open by remember { mutableStateOf<ApiKeys.Service?>(null) }
    val services = ApiKeys.SERVICES.sortedWith(compareBy({ !ApiKeys.required(it) }, { it.name.lowercase() }))
    ServiceGrid(services.map { s -> ServiceTileInfo(s.name, s.name, keyMark(ApiKeys.status(ApiKeys.ready(s), ApiKeys.required(s))),
        if (ApiKeys.required(s)) "Required" else "Optional", tagStrong = ApiKeys.required(s)) }) { t -> open = services.firstOrNull { it.name == t.id } }
    open?.let { s -> ApiKeyDialog(vm, s) { open = null } }
}

private fun keyMark(status: ApiKeys.Status) = when (status) {
    ApiKeys.Status.SET -> ServiceMark.OK; ApiKeys.Status.MISSING_REQUIRED -> ServiceMark.PROBLEM; ApiKeys.Status.MISSING_OPTIONAL -> ServiceMark.OFF
}

/** A service's dialog (#214): what it's for, its limits, where to get a key, and the fields. */
@Composable
private fun ApiKeyDialog(vm: HomeViewModel, s: ApiKeys.Service, close: () -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val rev = vm.keysRev
    MediaDialog(s.name, close, resizeForKeyboard = true, wide = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ServiceStatusMark(keyMark(ApiKeys.status(ApiKeys.ready(s), ApiKeys.required(s))))
            ServiceTag(if (ApiKeys.required(s)) "Required" else "Optional", ApiKeys.required(s))
            Text(if (ApiKeys.ready(s)) "Key set" else "No key yet", fontSize = 14.sp)
        }
        Text(s.usedFor, fontSize = 14.sp)
        ApiKeys.LIMITS[s.name]?.let { Text(stringResource(R.string.tx_limits, it), color = Mute, fontSize = 13.sp) }
        Text(stringResource(R.string.ui_get_one, s.where), color = Mute, fontSize = 13.sp)
        vm.keysMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
        s.fields.forEach { f -> KeyField(vm, f) }
        ButtonRow { Chip(stringResource(R.string.st_done)) { close() } }
    }
}

@Composable
internal fun ApiKeyCard(vm: HomeViewModel, s: ApiKeys.Service) {
    @Suppress("UNUSED_VARIABLE")
    val rev = vm.keysRev // read so the card redraws after a save
    val ready = s.fields.filterNot { it.optional }.all { ApiKeys.get(it.id).isNotBlank() }
    Column(
        modifier = Modifier.width(760.dp).background(Panel, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.name, fontSize = 18.sp)
            Text(if (ready) "Ready" else "Not set", color = if (ready) MaterialTheme.colorScheme.primary else Mute, fontSize = 13.sp)
        }
        Text(s.usedFor, color = Mute, fontSize = 13.sp)
        Text(stringResource(R.string.ui_get_one, s.where), color = Mute, fontSize = 12.sp)
        s.fields.forEach { f -> KeyField(vm, f) }
    }
}

@Composable
internal fun KeyField(vm: HomeViewModel, f: ApiKeys.Field) {
    var value by remember(f.id) { mutableStateOf("") }
    val entered = ApiKeys.isEntered(f.id)
    val current = ApiKeys.get(f.id)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            f.label + ": " + when {
                entered -> "entered on this TV (${ApiKeys.hint(current)})"
                current.isNotBlank() -> "built into the app (${ApiKeys.hint(current)})"
                else -> "not set"
            },
            fontSize = 14.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextInput(value, { value = it }, if (entered || current.isNotBlank()) "New ${f.label.lowercase()}" else f.label)
            Chip(stringResource(R.string.ui_save)) {
                if (value.isNotBlank()) {
                    vm.saveApiKey(f.id, value)
                    value = ""
                }
            }
            var removing by remember(f.id) { mutableStateOf(false) }
            if (entered) Chip(stringResource(R.string.ui_remove)) { removing = true }
            if (removing) ConfirmDestructive("Remove your ${f.label}?",
                "Ember goes back to the key built into the app if it has one. Otherwise, features that need it stop working until you enter it again.",
                { removing = false }, stringResource(R.string.ui_remove)) { vm.saveApiKey(f.id, "") }
        }
    }
}

// ---------------------------------------------------------------- Profiles

/** A profile's photo (#222): one of the pictures on this TV, or a link to one. */
@Composable
internal fun ProfilePhotoPicker(name: String, close: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(Photos.granted(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    var pictures by remember { mutableStateOf<List<String>?>(null) }
    var link by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(allowed) {
        if (allowed) pictures = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Photos.slides(context, null, ProfilePhotos.MAX).map { it.image } }.getOrDefault(emptyList()) }
    }
    MediaDialog("Photo for $name", close, resizeForKeyboard = true, wide = true) {
        Text(stringResource(R.string.tx_from_this_tv_s_pictures), fontSize = 15.sp)
        when {
            !allowed -> Chip(stringResource(R.string.ui_allow_access_to_pictures)) { ask.launch(Photos.permission) }
            pictures == null -> Text(stringResource(R.string.tx_loading), color = Mute, fontSize = 13.sp)
            pictures!!.isEmpty() -> Text(stringResource(R.string.ui_no_pictures_found_on_this_tv), color = Mute, fontSize = 13.sp)
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                pictures!!.chunked(ProfilePhotos.PER_ROW).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        line.forEach { uri ->
                            Focusable(onClick = { onPick(uri) }, modifier = Modifier.size(96.dp).testTag("profile_photo").semantics { contentDescription = "Photo " + (pictures!!.indexOf(uri) + 1) }, radius = TvTokens.Shape.Pill, container = PanelHi, focusedScale = TvTokens.Focus.Round) {
                                coil.compose.AsyncImage(uri, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
                            }
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.tx_or_a_link_to_a_picture), fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextInput(link, { link = it.take(500) }, "https://…")
            Chip(stringResource(R.string.st_use_this_picture)) { Profile.usablePhoto(link)?.let(onPick) ?: run { message = "Enter a link that starts with https://." } }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
        ButtonRow { Chip(stringResource(R.string.ui_cancel)) { close() } }
    }
}

internal object ProfilePhotos { const val MAX = 24; const val PER_ROW = 8 }

/** Settings > Profiles: add, edit and remove profiles, and the PIN that guards Kids profiles. */
@Composable
internal fun ProfilesPage(vm: HomeViewModel) {
    var editId by remember { mutableStateOf(vm.profiles.profileId) }
    var settingPin by remember { mutableStateOf(false) }
    var kidsSetup by remember {mutableStateOf<Profile?>(null)}
    var newKids by remember {mutableStateOf(false)}
    var deleting by remember {mutableStateOf<Profile?>(null)}
    var choosingPhoto by remember {mutableStateOf(false)}
    var pendingLockChange by remember {mutableStateOf<(() -> Unit)?>(null)}
    deleting?.let {target->ConfirmDestructive("Delete ${target.name}?","This removes the profile. It does not delete history on connected tracker accounts.",{deleting=null}) {
        vm.profiles.deleteProfile(target.id);editId=vm.profiles.profileId
    }}
    val p = vm.profiles.all.firstOrNull { it.id == editId } ?: vm.profiles.profile
    var name by remember(p.id) { mutableStateOf(p.name) }
    if (choosingPhoto) ProfilePhotoPicker(p.name, { choosingPhoto = false }) { photo -> vm.profiles.updateProfile(p.id) { it.copy(photo = photo) }; choosingPhoto = false }
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Section(stringResource(R.string.ui_profiles), stringResource(R.string.ui_each_profile_has_its_own_home)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    vm.profiles.all.forEach { pr ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ProfileAvatar(pr, 60.dp) { editId = pr.id }
                            Text(
                                pr.name + (if (pr.id == vm.profiles.profileId) " ✓" else ""),
                                fontSize = 13.sp,
                                fontWeight = if (pr.id == editId) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
                ButtonRow(spacing = 10.dp) {
                    Chip(stringResource(R.string.ui_add_profile)) { editId = vm.profiles.addProfile(kids = false) }
                    Chip(stringResource(R.string.ui_add_kids_profile)) {newKids=true}
                }
                if (vm.profiles.all.size > 1) {
                    ToggleRow(stringResource(R.string.ui_ask_who_s_watching), stringResource(R.string.ui_show_the_profiles_when_ember_starts), vm.profiles.askProfileAtStart) { vm.profiles.toggleAskProfileAtStart() }
                }
            }
        }
        Section(stringResource(R.string.ui_edit_profile, p.name), if (p.id == vm.profiles.profileId) stringResource(R.string.st_the_profile_in_use) else null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextInput(name, { name = it }, "Name", width = 360)
                    Chip(stringResource(R.string.ui_save_name)) { vm.profiles.updateProfile(p.id) { it.copy(name = name.trim().ifBlank { it.name }) } }
                    if (p.id != vm.profiles.profileId) Chip(stringResource(R.string.ui_use_this_profile)) { vm.profiles.switchProfile(p.id) }
                    if (vm.profiles.all.size > 1) Chip(stringResource(R.string.ui_delete)) {
                        deleting=p
                    }
                }
                ProfileColorPicker(p.color) {color->
                    vm.profiles.updateProfile(p.id) {it.copy(color=color)}
                }
                // #222: a photo instead of the letter.
                ButtonRow(spacing = 10.dp) {
                    Chip(if (p.photo == null) stringResource(R.string.st_use_a_photo) else stringResource(R.string.st_change_photo)) { choosingPhoto = true }
                    if (p.photo != null) Chip(stringResource(R.string.st_remove_photo)) { vm.profiles.updateProfile(p.id) { it.copy(photo = null) } }
                }
                ToggleRow(stringResource(R.string.ui_kids_profile), stringResource(R.string.ui_asks_for_the_pin_to_open), p.kids) {
                    if(!p.kids)kidsSetup=p.copy(kids=true,personal=false,kidsSetupRequired=true)
                    else vm.profiles.updateProfile(p.id) {it.copy(kids=false)}
                }
                if (!p.kids) {
                    ToggleRow(stringResource(R.string.ui_personal_rows), stringResource(R.string.ui_continue_watching_up_next_your_watchlist), p.personal) {
                        vm.profiles.updateProfile(p.id) { it.copy(personal = !it.personal) }
                    }
                }
                if(p.kids) {
                    Chip(if(p.kidsSetupComplete)stringResource(R.string.st_review_parent_setup) else stringResource(R.string.st_complete_parent_setup)) {kidsSetup=p}
                }
                Section(stringResource(R.string.st_local_activity),stringResource(R.string.st_local_history_resume_positions_and_remembered)) {
                    Choice(listOf("local" to "This profile", "shared" to "Shared household"),p.activityMode) {value->
                        vm.profiles.updateProfile(p.id){it.copy(activityMode=app.ember.tv.data.LocalActivity.mode(value))}
                    }
                    Note(stringResource(R.string.st_changing_this_choice_keeps_both_sets))
                }
                if(p.kids)KidsContentSettings(p) {change->
                    val save={vm.profiles.updateProfile(p.id,change)}
                    if(vm.pin.pinSet || !app.ember.tv.data.KidsContentPolicy.restricted(change(p)))save() else {pendingLockChange=save;settingPin=true}
                }
                ToggleRow(stringResource(R.string.ui_live_tv), null, p.live) { vm.profiles.updateProfile(p.id) { it.copy(live = !it.live) } }
                ToggleRow(stringResource(R.string.ui_all_catalogs), stringResource(R.string.ui_off_only_the_catalogs_ticked_below), p.catalogs == null) {
                    vm.profiles.updateProfile(p.id) { it.copy(catalogs = if (it.catalogs == null) emptySet() else null) }
                }
                p.catalogs?.let { allowed ->
                    if (vm.allCatalogs.isEmpty()) Note(stringResource(R.string.ui_catalogs_appear_here_once_the_addons))
                    vm.allCatalogs.filter { it.homeable || it.browsable }.forEach { c ->
                        ToggleRow(c.label, c.addon.name, c.key in allowed) {
                            vm.profiles.updateProfile(p.id) { it.copy(catalogs = (it.catalogs ?: emptySet()).let { set -> if (c.key in set) set - c.key else set + c.key }) }
                        }
                    }
                }
                ToggleRow(stringResource(R.string.ui_all_apps), stringResource(R.string.ui_off_only_the_apps_ticked_below), p.apps == null) {
                    vm.profiles.updateProfile(p.id) { it.copy(apps = if (it.apps == null) emptySet() else null) }
                }
                p.apps?.let { allowed ->
                    vm.apps.forEach { a ->
                        ToggleRow(a.label, null, a.pkg in allowed) {
                            vm.profiles.updateProfile(p.id) { it.copy(apps = (it.apps ?: emptySet()).let { set -> if (a.pkg in set) set - a.pkg else set + a.pkg }) }
                        }
                    }
                }
            }
        }
        Section(stringResource(R.string.ui_pin), stringResource(R.string.ui_a_four_digit_pin_guards_kids)) {
            ButtonRow(spacing = 10.dp) {
                Chip(if (vm.pin.pinSet) stringResource(R.string.st_change_pin) else stringResource(R.string.st_set_a_pin)) {
                    if (vm.pin.pinSet) vm.pin.withPin("Enter the current PIN") { settingPin = true } else settingPin = true
                }
                if (vm.pin.pinSet && vm.profiles.all.none {app.ember.tv.data.KidsContentPolicy.restricted(it)}) Chip(stringResource(R.string.ui_remove_pin)) { vm.pin.withPin("Enter the PIN to remove it") { vm.pin.setPin(null) } }
            }
            if(vm.profiles.all.any {app.ember.tv.data.KidsContentPolicy.restricted(it)})Note(stringResource(R.string.st_remove_kids_content_locks_before_removing))
            if (!vm.pin.pinSet && vm.profiles.all.any { it.kids }) Note(stringResource(R.string.ui_no_pin_yet_a_kids_profile))
        }
    }
    if(newKids || kidsSetup!=null)KidsSetupDialog(vm,kidsSetup,close={newKids=false;kidsSetup=null},onReady={editId=it})
    if (settingPin) {
        PinPad("Choose a new PIN", onDone = { pin ->
            vm.pin.setPin(pin)
            pendingLockChange?.invoke()
            pendingLockChange=null
            settingPin = false
            true
        }, onCancel = { settingPin = false;pendingLockChange=null })
    }
}
