package app.ember.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.ember.tv.R
import app.ember.tv.data.NavTabs

/** The menu name of a tab, from the string resources (so it can be translated). */
@Composable
fun tabTitle(id: String): String = when (id) {
    NavTabs.HOME -> stringResource(R.string.tab_home)
    NavTabs.WATCHLIST -> stringResource(R.string.tab_watchlist)
    NavTabs.LIVE -> stringResource(R.string.tab_live)
    NavTabs.DISCOVER -> stringResource(R.string.tab_discover)
    NavTabs.LIBRARY -> stringResource(R.string.tab_library)
    NavTabs.APPS -> stringResource(R.string.tab_apps)
    NavTabs.SEARCH -> stringResource(R.string.tab_search)
    NavTabs.SETTINGS -> stringResource(R.string.tab_settings)
    NavTabs.QUICK -> stringResource(R.string.tab_quick)
    else -> NavTabs.label(id)
}

/** A Settings page's name, from the string resources. */
@Composable
internal fun SettingsTab.title(): String = stringResource(
    when (this) {
        SettingsTab.CACHE -> R.string.settings_cache_management
        SettingsTab.SEARCH -> R.string.settings_search
        SettingsTab.FEATURES -> R.string.settings_features
        SettingsTab.TOP_MENU -> R.string.settings_top_menu
        SettingsTab.DISCOVER -> R.string.tab_discover
        SettingsTab.HOME_TOOLS -> R.string.settings_page_apps_folders
        SettingsTab.SIZE -> R.string.settings_page_size
        SettingsTab.PERFORMANCE -> R.string.settings_page_performance
        SettingsTab.REMOTE -> R.string.settings_page_remote
        SettingsTab.LIGHT_HOME -> R.string.settings_page_light_home
        SettingsTab.HOME_EXTRAS -> R.string.settings_page_extras
        SettingsTab.AUDIO_VIDEO -> R.string.settings_page_audio_video
        SettingsTab.HOME_ROWS -> R.string.settings_home_rows
        SettingsTab.WIDGETS -> R.string.settings_page_widgets
        SettingsTab.APPEARANCE -> R.string.settings_appearance
        SettingsTab.POSTERS -> R.string.settings_page_posters
        SettingsTab.METADATA -> R.string.settings_metadata
        SettingsTab.WEATHER -> R.string.settings_weather
        SettingsTab.PLAYER -> R.string.settings_player
        SettingsTab.WATCH_STATUS -> R.string.settings_watch_status
        SettingsTab.UPDATES -> R.string.settings_updates
        SettingsTab.DEBUG -> R.string.settings_page_diagnostics
        SettingsTab.PLAY_BUTTON -> R.string.settings_play_button
        SettingsTab.ADDONS -> R.string.settings_addons
        SettingsTab.CATALOGS -> R.string.settings_catalogs
        SettingsTab.STREMIO -> R.string.settings_page_stream_results
        SettingsTab.SUBTITLES -> R.string.settings_page_languages
        SettingsTab.LIVE -> R.string.settings_live
        SettingsTab.STREAMING -> R.string.settings_streaming
        SettingsTab.PROFILES -> R.string.settings_page_profiles
        SettingsTab.TRACKERS -> R.string.settings_trackers
        SettingsTab.SERVERS -> R.string.settings_servers
        SettingsTab.KEYS -> R.string.settings_keys
        SettingsTab.DEFAULT_HOME -> R.string.settings_page_startup
        SettingsTab.DEVICE_TOOLS -> R.string.settings_page_tv_device
        SettingsTab.PHONE -> R.string.settings_phone
        SettingsTab.SCREENSAVER -> R.string.settings_screensaver
        SettingsTab.BACKUP -> R.string.settings_page_backup
        SettingsTab.ABOUT -> R.string.settings_about
        SettingsTab.LEGAL -> R.string.group_legal
    },
)

/** A Settings group's name (General, Watching...), from the string resources. */
@Composable
internal fun SettingsTab.groupTitle(): String = stringResource(
    when (group) {
        "Search" -> R.string.group_search
        "Look" -> R.string.group_look
        "Home" -> R.string.group_home
        "Playback" -> R.string.group_playback
        "Sources" -> R.string.group_sources
        "Accounts" -> R.string.group_accounts
        "System" -> R.string.group_system
        "About" -> R.string.group_about
        else -> R.string.group_look
    },
)
