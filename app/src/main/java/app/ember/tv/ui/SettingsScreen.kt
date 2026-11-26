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
internal enum class SettingsTab(val label: String, val group: String, private val mergedInto: String? = null) {
    // #463: seven groups by what you're doing. Every page keeps its name, so search results,
    // shortcuts and saved positions still resolve; a page that joined another opens that page.
    SEARCH("Search settings", "Search"),
    // Home: what Home shows, Light's Home, and the screensaver.
    WIDGETS("Home layout", "Home"),
    HOME_ROWS("Banner & lists", "Home", "WIDGETS"),
    HOME_TOOLS("Apps & folders", "Home", "WIDGETS"),
    LIGHT_HOME("Ember Light Home", "Home"),
    HOME_EXTRAS("Screensaver & weather", "Home"),
    SCREENSAVER("Screensaver", "Home", "HOME_EXTRAS"),
    WEATHER("Weather", "Home", "HOME_EXTRAS"),
    // Look: how Ember looks and moves.
    APPEARANCE("Theme & navbar", "Look"),
    TOP_MENU("Top menu", "Look", "APPEARANCE"),
    POSTERS("Posters & cards", "Look"),
    SIZE("Size & effects", "Look"),
    PERFORMANCE("Performance & effects", "Look", "SIZE"),
    // Watching: the player, which stream it plays, sound and picture, languages.
    PLAYER("Player", "Watching"),
    WATCH_STATUS("Watched & spoilers", "Watching", "PLAYER"),
    PLAY_BUTTON("Choosing a stream", "Watching"),
    STREMIO("Stream results & badges", "Watching", "PLAY_BUTTON"),
    AUDIO_VIDEO("Audio & video", "Watching"),
    SUBTITLES("Languages & subtitles", "Watching"),
    // Content: where titles come from and what's said about them.
    ADDONS("Addons & catalogs", "Content"),
    CATALOGS("Catalogs", "Content", "ADDONS"),
    DISCOVER("Discover", "Content"),
    METADATA("Metadata & where to watch", "Content"),
    STREAMING("Where to watch", "Content", "METADATA"),
    LIVE("Live TV", "Content"),
    // Accounts.
    PROFILES("Profiles & PIN", "Accounts"),
    TRACKERS("Trackers", "Accounts"),
    SERVERS("Media servers", "Accounts"),
    KEYS("API keys", "Accounts"),
    // System: the TV, the phone and Ember's own housekeeping.
    DEFAULT_HOME("Startup", "System"),
    DEVICE_TOOLS("TV, remote & system access", "System"),
    REMOTE("Remote", "System", "DEVICE_TOOLS"),
    SYSTEM_ACCESS("System access", "System", "DEVICE_TOOLS"),
    PHONE("Phone setup", "System"),
    BACKUP("Backup & restore", "System"),
    FEATURES("Features", "System"),
    DEBUG("Storage & diagnostics", "System"),
    CACHE("Cache management", "System", "DEBUG"),
    // About: what Ember is, its updates and its licences (#67).
    ABOUT("About & updates", "About"),
    UPDATES("Updates", "About", "ABOUT"),
    LEGAL("Legal", "About");

    /**
     * #164: a page that became part of another keeps its name, so search results, shortcuts and
     * saved positions still resolve; opening it opens [host], which shows its settings.
     */
    val host: SettingsTab get() = mergedInto?.let { name -> entries.first { it.name == name } } ?: this
    val merged: Boolean get() = mergedInto != null
}

/** A Settings group's name as shown in the sidebar. */
internal fun settingsGroupLabel(group: String): String = group

/**
 * #324: where a page lives, as messages write it: "Settings › Sources › Addons & catalogs". Built from
 * the pages' own names (a merged page gives the page that shows it), so directions follow any move.
 */
internal val SettingsTab.path: String get() = host.let { "Settings › ${settingsGroupLabel(it.group)} › ${it.label}" }

/**
 * Settings: page names grouped on the left, the chosen page on the right. Moving through the
 * names switches pages; Right goes into the page, and Left comes back to the same name.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(vm: HomeViewModel) {
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
    fun openPage(page:SettingsTab) {location=SettingsLocation.open(page);lastTopic=page.host;focusContent=true}
    val openKeys = {searchTarget=null;returnToSearch=false;openPage(SettingsTab.KEYS)}
    LaunchedEffect(vm.look.settingsJump) {
        vm.look.settingsJump?.let {name->
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
            // #463: Home layout: widgets and collections, then the apps on Home and folders.
            SettingsTab.HOME_ROWS, SettingsTab.WIDGETS -> {
                item(key = "widgets") { WidgetsSettings(vm) }
                item(key="home-apps"){HomeToolsPage(vm, HomeToolsPart.APPS)}; appearancePage(vm, SettingsTab.HOME_TOOLS); item(key="home-tools"){HomeToolsPage(vm, HomeToolsPart.FOLDERS)}
            }
            SettingsTab.LIGHT_HOME -> {
                if (vm.look.uiStyle != app.ember.tv.data.InterfaceStyles.LIGHT) item(key = "light-note") {
                    Note(stringResource(R.string.st_these_options_apply_when_appearance_interface, SettingsTab.APPEARANCE.path))
                }
                appearancePage(vm, SettingsTab.LIGHT_HOME)
            }
            // #318: the apps on Home and how they look, then folders, the Apps page and what is hidden.
            SettingsTab.HOME_TOOLS -> { item(key="home-apps"){HomeToolsPage(vm, HomeToolsPart.APPS)}; appearancePage(vm, SettingsTab.HOME_TOOLS); item(key="home-tools"){HomeToolsPage(vm, HomeToolsPart.FOLDERS)} }
            SettingsTab.HOME_EXTRAS -> { item(key = "screensaver") { ScreensaverSection(vm) }; item(key="home-extras"){HomeToolsPage(vm, HomeToolsPart.EXTRAS)}; item(key = "weather") { WeatherSection(vm) } }
            SettingsTab.APPEARANCE, SettingsTab.REMOTE -> appearancePage(vm, tab)
            // #463: size and spacing, then performance and focus effects.
            SettingsTab.SIZE, SettingsTab.PERFORMANCE -> { appearancePage(vm, SettingsTab.SIZE); appearancePage(vm, SettingsTab.PERFORMANCE) }
            SettingsTab.POSTERS -> {
                // #463: how titles look: the details page, labels, then posters and the ratings on them.
                appearancePage(vm, SettingsTab.POSTERS)
                item(key = "posters") { PostersPage(vm, openKeys) }
                ratingsPage(vm, openKeys)
            }
            // #463: where details come from, then where each title can be watched.
            SettingsTab.METADATA, SettingsTab.STREAMING -> { item(key = "metadata") { MetadataPage(vm, openKeys) }; streamingPage(vm, openKeys) }
            SettingsTab.WEATHER -> item(key = "weather") { WeatherSection(vm) }
            // #387: Simple shows the everyday options; a search hit shows the page in full.
            SettingsTab.PLAYER -> {
                playerPage(vm, playerKeys(PLAYER_KEYS, vm.playerSettings.advanced, searchTarget))
                // #463: what counts as watched sits with the player that marks it.
                item(key="watch-status") {WatchStatusSettings(vm)}
            }
            SettingsTab.AUDIO_VIDEO -> playerPage(vm, playerKeys(AUDIO_VIDEO_KEYS, vm.playerSettings.advanced, searchTarget))
            SettingsTab.WATCH_STATUS -> item(key="watch-status") {WatchStatusSettings(vm)}
            // #463: which stream Play picks, then how the stream list shows them.
            SettingsTab.PLAY_BUTTON -> { item(key = "playbutton") { PlayButtonPage(vm) }; item(key = "stremio") { StremioFormatPage(vm) } }
            SettingsTab.ADDONS -> {
                item(key = "addonstatus") { SourceStatusButton("Addon status", vm.catalogs.health.entries.values, vm.catalogs::retry) }
                addonsPage(vm)
                // #164: their catalogs follow on the same page.
                catalogsPage(vm)
            }
            SettingsTab.CATALOGS -> {
                item(key = "catalogstatus") { SourceStatusButton("Addon status", vm.catalogs.health.entries.values, vm.catalogs::retry) }
                catalogsPage(vm)
            }
            SettingsTab.STREMIO -> item(key = "stremio") { StremioFormatPage(vm) }
            SettingsTab.SUBTITLES -> { playerPage(vm, LANGUAGE_KEYS); item(key = "subtitles") { SubtitlesPage(vm, openKeys) } }
            SettingsTab.LIVE -> item(key = "livetv") { LiveTvSettingsPage(vm) }
            SettingsTab.TRACKERS -> {
                item(key = "trackers") {
                    Section(stringResource(R.string.ui_trackers), stringResource(R.string.ui_continue_watching_and_watchlists_come_from)) {
                        vm.trackerMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
                    }
                }
                item(key = "trackerstatus") { SourceStatusButton("Tracker status", vm.trackerHealth, vm::retryTracker) }
                item(key = "trackergrid") { TrackersGrid(vm, openKeys) }
                item(key = "trackersources") { TrackerSourcesCard(vm) }
                item(key = "trackersync") { TrackerSyncCard(vm) }
            }
            SettingsTab.SERVERS -> {
                item(key = "serverstatus") { SourceStatusButton("Media server status", vm.serverHealth, vm::retryServer) }
                item(key = "servers") { MediaServersSection(vm) }
                item(key = "server-presentation") { ServerPresentationSettings(vm) }
            }
            SettingsTab.PROFILES -> item(key = "profiles") { ProfilesPage(vm) }
            SettingsTab.KEYS -> {
                item(key = "keyshead") { ApiKeysHeader(vm) }
                item(key = "keysgrid") { ApiKeysGrid(vm) }
            }
            SettingsTab.DEFAULT_HOME -> {
                // #318: the everyday choice first; the one-time Ember as Home steps after it.
                item(key = "startup") { HomeToolsPage(vm, HomeToolsPart.STARTUP) }
                item(key = "defaulthome") { DefaultHomePage() }
            }
            SettingsTab.SYSTEM_ACCESS -> item(key = "system-access") { SystemAccessPage(vm) }
            SettingsTab.DEVICE_TOOLS -> {
                // #164: the remote's settings lead the page they joined.
                appearancePage(vm, SettingsTab.REMOTE)
                item(key = "device-tools") { DeviceToolsSettings(vm) }
                item(key = "engineering") { EngineeringMenusSettings(vm) }
                // #463: what Ember changes on the TV itself, beside the TV's own tools.
                item(key = "system-access") { SystemAccessPage(vm) }
            }
            SettingsTab.PHONE -> item(key = "phone") { PhoneSetupPage(vm) }
            SettingsTab.SCREENSAVER -> item(key = "screensaver") { ScreensaverSection(vm) }
            SettingsTab.BACKUP -> {
                item(key = "backup") { BackupPage(vm) }
                item(key = "restore") { RestorePage(vm) }
            }
            SettingsTab.ABOUT -> { aboutPage(vm); item(key="updates") {UpdatesSection(vm)} }
            SettingsTab.CACHE -> CACHE_KEYS.forEach { part -> item(key=part) { CacheManagementSettings(part) } }
            SettingsTab.UPDATES -> item(key="updates") {UpdatesSection(vm)}
            SettingsTab.DEBUG -> {
                CACHE_KEYS.forEach { part -> item(key=part) { CacheManagementSettings(part) } }
                item(key="debug-build") {Section(stringResource(R.string.st_build_information),id="debug-build") {
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
        if (SettingsReset.available(tab)) item(key = "reset-page") { ResetPageButton(tab) }
    }

    // Page-only search results need the same guarded focus handoff as exact section hits.
    val searchRequest = remember(searchTarget, tab, returnToSearch) {
        if (location.page!=null && returnToSearch && tab != SettingsTab.SEARCH) SettingsTarget(searchTarget) else null
    }
    val sectionIndex = searchTarget?.let { target ->
        val keys = when (tab) {
            SettingsTab.APPEARANCE, SettingsTab.SIZE, SettingsTab.PERFORMANCE, SettingsTab.REMOTE -> lookKeys(tab, vm.look.uiStyle)
            SettingsTab.PLAYER -> playerKeys(PLAYER_KEYS, vm.playerSettings.advanced, target)
            SettingsTab.AUDIO_VIDEO -> playerKeys(AUDIO_VIDEO_KEYS, vm.playerSettings.advanced, target)
            SettingsTab.CACHE -> CACHE_KEYS
            SettingsTab.DEBUG -> SettingsMerges.diagnosticsKeys
            SettingsTab.DEVICE_TOOLS -> lookKeys(SettingsTab.REMOTE, vm.look.uiStyle) + listOf("device-tools", "engineering")
            else -> emptyList()
        }
        keys.indexOf(target).takeIf { it >= 0 }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsTarget provides searchRequest, LocalSettingsPage provides tab) {
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
