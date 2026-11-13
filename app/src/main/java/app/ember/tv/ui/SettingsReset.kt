package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.ember.tv.data.PreferenceKey
import app.ember.tv.data.PreferenceKeys as K
import app.ember.tv.player.PlaybackConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** #273: put one settings page back to its defaults. Accounts, addons, lists and keys are never touched. */
internal object SettingsReset {
    fun keys(tab: SettingsTab): List<PreferenceKey<*>> = when (tab) {
        SettingsTab.APPEARANCE -> listOf(K.ACCENT, K.ACCENT2, K.ACCENT3, K.THEME, K.OLED, K.ROW_ADDON_LABELS, K.TILE_LABELS_OFF,
            K.MEDIA_PAGE_LAYOUT, K.NAVBAR_STYLE, K.MODERN_CARDS, K.MENU_POSITION, K.HERO_ARTWORK_MODE)
        SettingsTab.SIZE -> listOf(K.UI_SIZE, K.UI_SCALE, K.TEXT_SIZE, K.SCREEN_MARGIN, K.CONTENT_DENSITY)
        SettingsTab.PERFORMANCE -> listOf(K.DEVICE_PERFORMANCE, K.CUSTOM_PERFORMANCE, K.ARTWORK_QUALITY, K.NAVIGATION_GLASS,
            K.NAVIGATION_GLASS_METHOD, K.CARD_FOCUS_STYLE, K.MEDIA_FOCUS_BACKGROUND, K.COLLECTION_FOCUS_BACKGROUND, K.APP_FOCUS_COLORS)
        SettingsTab.LIGHT_HOME -> listOf(K.LIGHT_HOME, K.LIGHT_WALLPAPER)
        SettingsTab.DEFAULT_HOME -> listOf(K.HOME_AT_BOOT, K.HOME_OVERRIDE, K.HOME_OVERRIDE_MODE)
        SettingsTab.HOME_EXTRAS -> listOf(K.LAUNCHER_EXTRAS, K.FAHRENHEIT, K.WEATHER_PROVIDER, K.WEATHER_FALLBACK, K.WEATHER_PLACE)
        SettingsTab.PLAYER -> listOf(K.PLAYER, K.AUTOPLAY, K.COUNTDOWN)
        SettingsTab.SUBTITLES -> listOf(K.SUBTITLE_CONFIG, K.SUBTITLES_ON)
        SettingsTab.PLAY_BUTTON -> listOf(K.PLAY_RULES)
        SettingsTab.STREMIO -> listOf(K.BADGE_MODE, K.STREAM_FORMAT_ON, K.STREAM_NAME_TEMPLATE, K.STREAM_DESC_TEMPLATE,
            K.DETAILED_BADGE_CATEGORIES_OFF, K.BADGES_OFF)
        SettingsTab.WATCH_STATUS -> listOf(K.SHOW_WATCHED_BADGES, K.BLUR_UNWATCHED_EPISODES)
        SettingsTab.STREAMING -> listOf(K.WATCH_PROVIDER, K.WATCH_REGION, K.SERVICES_ON, K.SERVICES_OFF, K.SERVICES_SOURCE)
        SettingsTab.POSTERS -> listOf(K.POSTER_CONFIG, K.META_CONFIG, K.RATINGS_ON, K.RATINGS_OFF, K.RATINGS_ORDER)
        SettingsTab.DISCOVER -> listOf(K.DISCOVER_SECTIONS, K.AI_DISCOVERY_ENABLED)
        SettingsTab.DEVICE_TOOLS -> listOf(K.REMOTE_SETTINGS_KEY, K.REMOTE_SOUNDS)
        SettingsTab.UPDATES -> listOf(K.UPDATE_AUTO_CHECK, K.UPDATE_BETA, K.UPDATE_BETA_SOURCE, K.UPDATE_CHANNEL)
        SettingsTab.DEBUG -> listOf(K.CACHE_CONFIG)
        else -> emptyList()
    }

    /** Player options share one stored config; each page resets only its own fields. */
    fun playback(tab: SettingsTab, c: PlaybackConfig): PlaybackConfig? {
        val d = PlaybackConfig()
        return when (tab) {
            SettingsTab.PLAYER -> c.copy(playerLook = d.playerLook, playerInterface = d.playerInterface, resume = d.resume,
                confirmExit = d.confirmExit, pip = d.pip, quickReturn = d.quickReturn, returnKey = d.returnKey, skipIntro = d.skipIntro,
                introDb = d.introDb, rememberTracks = d.rememberTracks, trailersInApp = d.trailersInApp, infoOnStart = d.infoOnStart)
            SettingsTab.AUDIO_VIDEO -> c.copy(passthrough = d.passthrough, passthroughMode = d.passthroughMode, formats = d.formats,
                audioDelayMs = d.audioDelayMs, engine = d.engine, deinterlacing = d.deinterlacing, ffmpeg = d.ffmpeg,
                ffmpegVideo = d.ffmpegVideo, tunneling = d.tunneling, dolbyVision = d.dolbyVision, frameRate = d.frameRate,
                matchResolution = d.matchResolution, buffer = d.buffer)
            SettingsTab.SUBTITLES -> c.copy(audioPick = d.audioPick)
            else -> null
        }
    }

    fun available(tab: SettingsTab) = keys(tab).isNotEmpty() || playback(tab, PlaybackConfig()) != null

    /** Written synchronously: Ember restarts straight after. */
    fun apply(context: Context, tab: SettingsTab) {
        val sp = context.getSharedPreferences("ember", Context.MODE_PRIVATE)
        val config = playback(tab, app.ember.tv.data.Prefs(context).playbackConfig)
        val e = sp.edit()
        keys(tab).forEach { e.remove(it.name) }
        if (config != null) e.putString(K.PLAYBACK_CONFIG.name, Json { ignoreUnknownKeys = true }.encodeToString(config))
        e.commit()
    }
}

@Composable
internal fun ResetPageButton(tab: SettingsTab) {
    val context = LocalContext.current
    var asking by remember { mutableStateOf(false) }
    val name = tab.title()
    Section(stringResource(R.string.st_reset_this_page), stringResource(R.string.st_puts_the_settings_on_back_to, name), id = "reset-page") {
        Chip(stringResource(R.string.st_reset, name)) { asking = true }
    }
    if (asking) ConfirmDestructive(stringResource(R.string.st_reset_page_ask, name), stringResource(R.string.st_reset_page_message), { asking = false }, stringResource(R.string.st_reset_action)) {
        SettingsReset.apply(context, tab)
        restartApp(context)
    }
}
