package app.ember.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.ember.tv.R

@Composable
internal fun SettingsTab.summary():String=stringResource(when(this) {
    SettingsTab.SEARCH->R.string.settings_summary_search
    SettingsTab.FEATURES->R.string.settings_summary_features
    SettingsTab.TOP_MENU->R.string.settings_summary_menu
    SettingsTab.DISCOVER->R.string.settings_summary_discover
    SettingsTab.HOME_TOOLS->R.string.settings_summary_apps_folders
    SettingsTab.SIZE->R.string.settings_summary_size
    SettingsTab.PERFORMANCE->R.string.settings_summary_performance
    SettingsTab.REMOTE->R.string.settings_summary_remote
    SettingsTab.LIGHT_HOME->R.string.settings_summary_light_home
    SettingsTab.HOME_EXTRAS->R.string.settings_summary_extras_weather
    SettingsTab.AUDIO_VIDEO->R.string.settings_summary_audio_video
    SettingsTab.HOME_ROWS->R.string.settings_summary_home
    SettingsTab.WIDGETS->R.string.settings_summary_widgets
    SettingsTab.APPEARANCE->R.string.settings_summary_appearance
    SettingsTab.POSTERS->R.string.settings_summary_artwork_metadata
    SettingsTab.METADATA->R.string.settings_summary_metadata
    SettingsTab.WEATHER->R.string.settings_summary_weather
    SettingsTab.PLAYER->R.string.settings_summary_player
    SettingsTab.WATCH_STATUS->R.string.settings_summary_watch_status
    SettingsTab.UPDATES->R.string.settings_summary_updates
    SettingsTab.CACHE->R.string.settings_summary_cache
    SettingsTab.DEBUG->R.string.settings_summary_storage_diagnostics
    SettingsTab.PLAY_BUTTON->R.string.settings_summary_play
    SettingsTab.ADDONS->R.string.settings_summary_addons_catalogs
    SettingsTab.CATALOGS->R.string.settings_summary_catalogs
    SettingsTab.STREMIO->R.string.settings_summary_stremio
    SettingsTab.SUBTITLES->R.string.settings_summary_subtitles
    SettingsTab.LIVE->R.string.settings_summary_live
    SettingsTab.STREAMING->R.string.settings_summary_streaming
    SettingsTab.PROFILES->R.string.settings_summary_profiles
    SettingsTab.TRACKERS->R.string.settings_summary_trackers
    SettingsTab.SERVERS->R.string.settings_summary_servers
    SettingsTab.KEYS->R.string.settings_summary_keys
    SettingsTab.DEFAULT_HOME->R.string.settings_summary_startup_screensaver
    SettingsTab.DEVICE_TOOLS->R.string.settings_summary_tv_remote
    SettingsTab.PHONE->R.string.settings_summary_phone
    SettingsTab.SCREENSAVER->R.string.settings_summary_screensaver
    SettingsTab.BACKUP->R.string.settings_summary_backup
    SettingsTab.ABOUT->R.string.settings_summary_about
    SettingsTab.LEGAL->R.string.settings_summary_legal
})
