package app.ember.tv.ui

import app.ember.tv.retryServer
import app.ember.tv.retryTracker

import androidx.compose.foundation.gestures.animateScrollBy

import kotlinx.coroutines.launch

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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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

/** The pages of Settings, in side-list order. Each page holds one topic; [group] heads the list. */
internal enum class SettingsTab(val label: String, val group: String) {
    SEARCH("Search settings", "Search"),
    // Look & feel: how Ember looks and responds, in every interface.
    APPEARANCE("Appearance", "Look"),
    SIZE("Size & spacing", "Look"),
    PERFORMANCE("Performance & effects", "Look"),
    REMOTE("Remote", "Look"),
    // Home: what the Home screen shows and what happens at startup.
    HOME_ROWS("Home layout", "Home"),
    LIGHT_HOME("Light Home", "Home"),
    TOP_MENU("Top menu", "Home"),
    DISCOVER("Discover", "Home"),
    HOME_TOOLS("Apps & folders", "Home"),
    DEFAULT_HOME("Startup", "Home"),
    HOME_EXTRAS("Extras", "Home"),
    FEATURES("Features", "Home"),
    WEATHER("Weather", "Home"),
    SCREENSAVER("Screensaver", "Home"),
    // Playback: the player and what it picks.
    PLAYER("Player", "Playback"),
    AUDIO_VIDEO("Audio & video", "Playback"),
    SUBTITLES("Languages & subtitles", "Playback"),
    PLAY_BUTTON("Play button", "Playback"),
    WATCH_STATUS("Watched & spoilers", "Playback"),
    // Sources: where titles, streams and their details come from.
    ADDONS("Addons", "Sources"),
    CATALOGS("Catalogs", "Sources"),
    STREMIO("Stream results & badges", "Sources"),
    LIVE("Live TV", "Sources"),
    STREAMING("Where to watch", "Sources"),
    POSTERS("Posters & ratings", "Sources"),
    METADATA("Metadata", "Sources"),
    PROFILES("Profiles & PIN", "Accounts"),
    TRACKERS("Trackers", "Accounts"),
    SERVERS("Media servers", "Accounts"),
    KEYS("API keys", "Accounts"),
    DEVICE_TOOLS("TV & device", "System"),
    CACHE("Cache Management", "System"),
    BACKUP("Backup & restore", "System"),
    PHONE("Phone setup", "System"),
    UPDATES("Updates", "System"),
    DEBUG("Diagnostics", "System"),
    // About: what Ember is and its licences, in their own section (#67).
    ABOUT("About Ember", "About"),
    LEGAL("Legal", "About"),
}

/**
 * Settings: page names grouped on the left, the chosen page on the right. Moving through the
 * names switches pages; Right goes into the page, and Left comes back to the same name.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(vm: HomeViewModel, onClose: (() -> Unit)? = null, onOpenFull: (String) -> Unit = {}) {
    if(!vm.settingsAllowed)return // Never compose editable controls behind an unapproved/restored route.
    // Settings opens on its first category every time; Back inside Settings still returns to where you were.
    var location by remember {mutableStateOf(SettingsLocation())}
    val tab=location.page ?: location.category.representative
    // Search starts empty each time Settings opens: what was typed is not kept after leaving.
    var searchQuery by remember(vm.profiles.profileId) { mutableStateOf("") }
    var searchTarget by remember(vm.profiles.profileId) { mutableStateOf<String?>(null) }
    var returnToSearch by remember(vm.profiles.profileId) { mutableStateOf(false) }
    var lastTopic by remember {mutableStateOf<SettingsTab?>(null)}
    var focusContent by remember {mutableStateOf(true)}
    fun openPage(page:SettingsTab) {location=SettingsLocation.open(page);lastTopic=page;focusContent=true}
    val openKeys = {searchTarget=null;returnToSearch=false;openPage(SettingsTab.KEYS)}
    LaunchedEffect(vm.look.settingsJump, onClose == null) {
        vm.look.settingsJump?.takeIf {onClose==null}?.let {name->
            SettingsLocation.jump(name)?.let {location=it;lastTopic=it.page;focusContent=true;searchTarget=null;returnToSearch=false}
            vm.look.settingsJump=null
        }
    }
    val context = LocalContext.current

    // After a restore the app restarts so every screen reads the restored settings.
    LaunchedEffect(vm.backup.restartNeeded) {
        if (vm.backup.restartNeeded) {
            delay(900)
            restartApp(context)
        }
    }

    val page: LazyListScope.() -> Unit = {
        when (tab) {
            SettingsTab.SEARCH -> item(key = "search") { SettingsSearchPage(vm, searchQuery, { searchQuery = it }) { t, section -> searchTarget = section; returnToSearch = true; openPage(t) } }
            SettingsTab.FEATURES -> item(key = "features") { FeaturesPage(vm) }
            SettingsTab.TOP_MENU -> item(key = "topmenu") { TopMenuSection(vm) }
            SettingsTab.DISCOVER -> item(key = "discover-sections") { DiscoverSettings(vm) }
            SettingsTab.HOME_ROWS -> { item(key = "home") { HomeRowsSection(vm) }; appearancePage(vm, SettingsTab.HOME_ROWS) }
            SettingsTab.LIGHT_HOME -> {
                if (vm.look.uiStyle != app.ember.tv.data.InterfaceStyles.LIGHT) item(key = "light-note") {
                    Note("These options apply when Appearance > Interface is Light.")
                }
                appearancePage(vm, SettingsTab.LIGHT_HOME)
            }
            SettingsTab.HOME_TOOLS -> item(key="home-tools"){HomeToolsPage(vm, HomeToolsPart.FOLDERS)}
            SettingsTab.HOME_EXTRAS -> item(key="home-extras"){HomeToolsPage(vm, HomeToolsPart.EXTRAS)}
            SettingsTab.APPEARANCE, SettingsTab.SIZE, SettingsTab.PERFORMANCE, SettingsTab.REMOTE -> appearancePage(vm, tab)
            SettingsTab.POSTERS -> {
                item(key = "posters") { PostersPage(vm, openKeys) }
                ratingsPage(vm, openKeys)
            }
            SettingsTab.METADATA -> item(key = "metadata") { MetadataPage(vm, openKeys) }
            SettingsTab.WEATHER -> item(key = "weather") { WeatherSection(vm) }
            SettingsTab.PLAYER -> playerPage(vm)
            SettingsTab.AUDIO_VIDEO -> playerPage(vm, AUDIO_VIDEO_KEYS)
            SettingsTab.WATCH_STATUS -> item(key="watch-status") {WatchStatusSettings(vm)}
            SettingsTab.PLAY_BUTTON -> item(key = "playbutton") { PlayButtonPage(vm) }
            SettingsTab.ADDONS -> {
                item(key = "addonstatus") { SourceStatusButton("Addon status", vm.catalogs.health.entries.values, vm.catalogs::retry) }
                addonsPage(vm)
            }
            SettingsTab.CATALOGS -> {
                item(key = "catalogstatus") { SourceStatusButton("Addon status", vm.catalogs.health.entries.values, vm.catalogs::retry) }
                catalogsPage(vm)
            }
            SettingsTab.STREMIO -> item(key = "stremio") { StremioFormatPage(vm) }
            SettingsTab.SUBTITLES -> { playerPage(vm, LANGUAGE_KEYS); item(key = "subtitles") { SubtitlesPage(vm, openKeys) } }
            SettingsTab.LIVE -> item(key = "livetv") { LiveTvSettingsPage(vm) }
            SettingsTab.STREAMING -> streamingPage(vm, openKeys)
            SettingsTab.TRACKERS -> {
                item(key = "trackers") {
                    Section(stringResource(R.string.ui_trackers), stringResource(R.string.ui_continue_watching_and_watchlists_come_from)) {
                        vm.trackerMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
                    }
                }
                item(key = "trackerstatus") { SourceStatusButton("Tracker status", vm.trackerHealth, vm::retryTracker) }
                items(vm.hub.trackers, key = { "tr:${it.id}" }) { t -> TrackerRow(vm, t, openKeys) }
            }
            SettingsTab.SERVERS -> {
                item(key = "serverstatus") { SourceStatusButton("Media server status", vm.serverHealth, vm::retryServer) }
                item(key = "servers") { MediaServersSection(vm) }
                item(key = "server-presentation") { ServerPresentationSettings(vm) }
            }
            SettingsTab.PROFILES -> item(key = "profiles") { ProfilesPage(vm) }
            SettingsTab.KEYS -> {
                item(key = "keyshead") { ApiKeysHeader(vm) }
                items(ApiKeys.SERVICES, key = { "key:${it.name}" }) { s -> ApiKeyCard(vm, s) }
            }
            SettingsTab.DEFAULT_HOME -> {
                item(key = "defaulthome") { DefaultHomePage() }
                item(key = "startup") { HomeToolsPage(vm, HomeToolsPart.STARTUP) }
            }
            SettingsTab.DEVICE_TOOLS -> {
                item(key = "device-tools") { DeviceToolsSettings(vm) }
                item(key = "engineering") { EngineeringMenusSettings(vm) }
            }
            SettingsTab.PHONE -> item(key = "phone") { PhoneSetupPage(vm) }
            SettingsTab.SCREENSAVER -> item(key = "screensaver") { ScreensaverSection(vm) }
            SettingsTab.BACKUP -> {
                item(key = "backup") { BackupPage(vm) }
                item(key = "restore") { RestorePage(vm) }
            }
            SettingsTab.ABOUT -> aboutPage(vm)
            SettingsTab.CACHE -> CACHE_KEYS.forEach { part -> item(key=part) { CacheManagementSettings(part) } }
            SettingsTab.UPDATES -> item(key="updates") {UpdatesSection(vm)}
            SettingsTab.DEBUG -> {
                item(key="debug-build") {Section("Build information",id="debug-build") {
                    Text("${app.ember.tv.BuildConfig.VERSION_NAME} · ${app.ember.tv.BuildConfig.BUILD_TYPE}",color=Fg)
                    if(app.ember.tv.BuildConfig.DEBUG)Note(stringResource(R.string.ui_this_is_a_debug_build_updates))
                }}
                item(key="image-cache") {ArtworkCacheSettings()}
                item(key="provider-diagnostics") {ProviderDiagnosticsSettings()}
                item(key="crashlog") {CrashLogSection()}
            }
            // Privacy policy, open source, services and disclaimer on one page.
            SettingsTab.LEGAL -> {
                legalPage("privacy", "Privacy policy", "Last updated ${Legal.UPDATED}.", Legal.PRIVACY)
                legalPage("opensource", "Open source", "${Legal.APP} is built with these open-source projects. Thank you to their authors.", Legal.OPEN_SOURCE)
                legalPage("services", "Services", "The services ${Legal.APP} can connect to, and what each provides.", Legal.SERVICES)
                item(key = "disclaimer") { DisclaimerPage() }
            }
        }
    }

    if (onClose != null) {
        FloatingSettings(vm, onClose = onClose, onOpenFull = onOpenFull)
        return
    }
    // Page-only search results need the same guarded focus handoff as exact section hits.
    val searchRequest = remember(searchTarget, tab, returnToSearch) {
        if (location.page!=null && returnToSearch && tab != SettingsTab.SEARCH) SettingsTarget(searchTarget) else null
    }
    val sectionIndex = searchTarget?.let { target ->
        val keys = when (tab) {
            SettingsTab.APPEARANCE, SettingsTab.SIZE, SettingsTab.PERFORMANCE, SettingsTab.REMOTE -> lookKeys(tab, vm.look.uiStyle)
            SettingsTab.PLAYER -> PLAYER_KEYS
            SettingsTab.AUDIO_VIDEO -> AUDIO_VIDEO_KEYS
            SettingsTab.CACHE -> CACHE_KEYS
            SettingsTab.DEBUG -> listOf("debug-build","image-cache","provider-diagnostics","crash-log")
            else -> emptyList()
        }
        keys.indexOf(target).takeIf { it >= 0 }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsTarget provides searchRequest) {
        ModernSettings(location=location, onCategory={category->
            val destination=location.fromSidebar(category,focusContent)
            if(destination!=location) {
                searchTarget=null;returnToSearch=false;location=destination;focusContent=false;lastTopic=null
            }
        }, onCategoryClick={category->
            searchTarget=null;returnToSearch=false;location=SettingsLocation.category(category);focusContent=true;lastTopic=null
        }, onPage={destination->searchTarget=null;returnToSearch=false;openPage(destination)},
            onPageBack={
                if(returnToSearch) {searchTarget=null;returnToSearch=false;openPage(SettingsTab.SEARCH)}
                else location.topics()?.let {location=it;focusContent=true}
            }, backToSearch=returnToSearch, lastTopic=lastTopic, focusContent=focusContent,
            onFocusHandled={focusContent=false},targetIndex=sectionIndex,page=page)
    }
}

internal fun restartApp(context: Context) {
    context.startActivity(Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java)))
    Runtime.getRuntime().exit(0)
}


/**
 * Up and Down scroll the page when there is nothing more to focus that way, so notes, long
 * texts and the crash log above or below the buttons can still be read with the remote.
 */
@Composable
internal fun Modifier.dpadScroll(state: androidx.compose.foundation.lazy.LazyListState): Modifier {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val step = with(androidx.compose.ui.platform.LocalDensity.current) { 220.dp.toPx() }
    return this.onKeyEvent { e ->
        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKeyEvent false
        val by = when (e.key) {
            androidx.compose.ui.input.key.Key.DirectionDown -> step
            androidx.compose.ui.input.key.Key.DirectionUp -> -step
            else -> return@onKeyEvent false
        }
        // Move focus as usual when there is something that way; otherwise scroll the page.
        val dir = if (by > 0) androidx.compose.ui.focus.FocusDirection.Down else androidx.compose.ui.focus.FocusDirection.Up
        if (focus.moveFocus(dir)) return@onKeyEvent true
        if ((by > 0 && !state.canScrollForward) || (by < 0 && !state.canScrollBackward)) return@onKeyEvent false
        scope.launch { state.animateScrollBy(by) }
        true
    }
}

/**
 * For the page side of Settings: Back goes to the menu on the left first (a second Back leaves
 * Settings), and Left always reaches the menu even when nothing on the page is to the left.
 */
@Composable
internal fun Modifier.settingsPage(side: androidx.compose.ui.focus.FocusRequester, onBack: (() -> Unit)? = null): Modifier {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val menuKey = if (rtl) androidx.compose.ui.input.key.Key.DirectionRight else androidx.compose.ui.input.key.Key.DirectionLeft
    return this.onKeyEvent { e ->
        when (e.key) {
            menuKey -> {
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                    !focus.moveFocus(if (rtl) androidx.compose.ui.focus.FocusDirection.Right else androidx.compose.ui.focus.FocusDirection.Left)
                ) runCatching { side.requestFocus() }
                e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
            }
            androidx.compose.ui.input.key.Key.Back -> {
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) {if(onBack!=null)onBack() else runCatching {side.requestFocus()}}
                true
            }
            else -> false
        }
    }
}
