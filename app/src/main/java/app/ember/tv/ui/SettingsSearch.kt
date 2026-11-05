package app.ember.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * Settings > Search settings: finds the page an option is on. Each page lists the words people
 * look for on it; a result opens that page.
 */
internal val SETTINGS_INDEX: List<Pair<SettingsTab, String>> = listOf(
    SettingsTab.FEATURES to "Features: switch off Live, media servers, trackers, extra rows, cast; background refresh",
    SettingsTab.TOP_MENU to "Top menu: tabs, order, hide tabs, Home, Discover, Live, Apps, Library, Watchlist, Search",
    SettingsTab.DISCOVER to "Discover: show hide enable disable Explore Collections Catalogs sections automatic defaults AI-assisted discovery Mistral describe suggested filters",
    SettingsTab.HOME_TOOLS to "Apps and folders: app folders custom artwork icons per-app PIN locks personal Home per profile",
    SettingsTab.HOME_EXTRAS to "Extras: Now Playing media controls notification access, presentation photo video slideshow keep awake",
    SettingsTab.HOME_ROWS to "Home layout: hero banner carousel, Standard Fanart artwork, hero catalog source ten items dots Watch now, your apps, rows of apps, watchlist button, continue watching, up next, servers, catalogs, order of sections, omni-channel list sources include exclude Plex Jellyfin Emby local trackers restore hidden",
    SettingsTab.APPEARANCE to "Appearance: interface Modern Light Ember X EmberFlix cinema, theme day night auto, OLED black, accent colour color palette Cinema red Modern Ember blue violet teal Ember Shadow Ember charcoal dark grey dark gray Blue ember Green flame Rose ember pink red app icon colour, menu top side, settings style floating",
    SettingsTab.SIZE to "Size and spacing: interface size, text size, font, screen margins overscan, media card density comfortable standard dense",
    SettingsTab.PERFORMANCE to "Performance and effects: device performance speed memory RAM low high auto custom, artwork quality economy balanced high, frosted glass navigation blur live prepared, focused backgrounds app colors media rows collections tile colors artwork backdrop theme only, card focus glow pulse lift",
    SettingsTab.REMOTE to "Remote: remote settings button menu key TV projector, remote sounds click navigation sound effects",
    SettingsTab.LIGHT_HOME to "Light Home: wallpaper background title art aurora ocean ember glow black, carousel, Continue Watching Watch Next permission, recommendations per app, wallpaper colours, short clips, credits, idle full screen, motion",
    SettingsTab.POSTERS to "Posters and ratings: RPDB, Better Posters, textless, landscape cards, title logo, ratings IMDb Rotten Tomatoes Metacritic, MDBList",
    SettingsTab.METADATA to "Metadata: AniList, TVDB, descriptions, artwork, episodes, anime",
    SettingsTab.WEATHER to "Weather: location, city, units, Celsius, Fahrenheit, provider fallback saved forecast Open-Meteo MET Norway wttr.in",
    SettingsTab.PLAYER to "Player: player interface GUI look Basic Ember Modern Ember X Streaming cinema advanced simple, default player, external player, skip intro IntroDB, resume, leaving the player, confirm exit, back to last video, autoplay next episode, countdown, picture-in-picture, trailers, info at start",
    SettingsTab.AUDIO_VIDEO to "Audio and video: passthrough, bitstream, Dolby Atmos TrueHD DTS, receiver soundbar, FFmpeg sound video decoding, software, MPEG-H, deinterlacing, playback engine compatibility, tunneled playback, frame rate matching, resolution, buffer, Dolby Vision HDR10, sound timing lip sync",
    SettingsTab.PLAY_BUTTON to "Play button: best source, 4K, HDR, Dolby Vision, codecs, file size, cached only, server first",
    SettingsTab.ADDONS to "Addons: add, remove, rename, Stremio, manifest, time limit, slow addons",
    SettingsTab.CATALOGS to "Catalogs: Home, Discover, Live, order, reset",
    SettingsTab.STREMIO to "Stremio: badges basic detailed custom images, source format, templates, name, description, AIOStreams, collections, folders, Animated collections videos GIF previews on off",
    SettingsTab.SUBTITLES to "Languages and subtitles: preferred audio track language, remember tracks, subtitle languages, size, colour, background, outline, position, OpenSubtitles, foreign audio, hearing impaired",
    SettingsTab.LIVE to "Live TV: IPTV, M3U, Xtream Codes, playlist, guide XMLTV, EPG, user agent, channels",
    SettingsTab.STREAMING to "Where to watch: streaming services, Streaming Availability RapidAPI, Watchmode, TMDB, JustWatch, region, country, Netflix, Disney",
    SettingsTab.PROFILES to "Profiles: who's watching, kids, PIN, allowed apps and catalogs, ask at start, content lock, genre, maximum age TV rating, parental controls",
    SettingsTab.TRACKERS to "Trackers: Trakt, Simkl, AniList, MyAnimeList, WeTrakr, sign in, watchlist, scrobble",
    SettingsTab.SERVERS to "Media servers: Library sections hide rows recently added movies shows hero banner source list, Plex Home user switch profile PIN, Jellyfin Emby username password, find servers on network, sign in",
    SettingsTab.KEYS to "API keys: Mistral AI discovery, Streaming Availability RapidAPI, TMDB, Fanart.tv hero artwork backgrounds logos, RPDB, MDBList, Watchmode, Trakt client id, OpenSubtitles, GitHub token",
    SettingsTab.DEFAULT_HOME to "Startup: Ember as Home, default launcher, Home button, accessibility service, ADB, Google TV home, start with the TV boot wake standby destination app input HDMI presentation",
    SettingsTab.DEVICE_TOOLS to "TV and device: display inputs calibration patterns 4K UHD sharpness overscan brightness contrast grayscale colors judder motion SDR HDR10 Dolby Vision clips menus MediaTek Amlogic Xiaomi FengOS Formovie XGIMI projector focus picture HDMI AV input shortcuts, engineering menus firmware factory service menu",
    SettingsTab.PHONE to "Phone setup: on off switch, 15 minute timer, session expiry, QR code, add from phone, keys, backup to phone, badges, collections",
    SettingsTab.SCREENSAVER to "Screensaver: sources, photos, backdrops, Ember screensaver",
    SettingsTab.BACKUP to "Back up and restore: backup file, passphrase, restore, Downloads",
    SettingsTab.WATCH_STATUS to "Watched and spoilers: watched unwatched indicators check badge mark movie show episode spoiler blur artwork",
    SettingsTab.UPDATES to "Updates: check install beta stable channel release GitHub automatic download",
    SettingsTab.DEBUG to "Debug diagnostics: provider source request timings duration latency timeout auth quota offline redacted export build type crash log reports troubleshoot",
    SettingsTab.ABOUT to "About Ember: version, credits, changelog",
    SettingsTab.LEGAL to "Legal: privacy policy, open source, licences, services, disclaimer, calibration sample credits Mehanik HDR Test Patterns",
)

@Composable
internal fun SettingsSearchPage(vm: app.ember.tv.HomeViewModel, query: String, onQuery: (String) -> Unit, onOpen: (SettingsTab, String?) -> Unit) {
    Section("Search settings", "Type a word (for example subtitles, Atmos, PIN, Trakt or theme); the pages that have it are listed.") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextInput(query, onQuery, "Search", width = 520)
            val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
            if (words.isNotEmpty()) {
                val exact = SettingDefinitions.matching(query)
                exact.forEach { definition ->
                    Chip(definition.title + " · " + definition.tab.title()) { onOpen(definition.tab, definition.id) }
                }
                val hits = SETTINGS_INDEX.filter { (_, text) -> words.all { w -> w in text.lowercase() } }.filter { (tab, _) -> exact.none { it.tab == tab } }
                if (hits.isEmpty() && exact.isEmpty()) Note("Nothing found for \"${query.trim()}\".")
                hits.forEach { (tab, text) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Chip(tab.title() + " ›") { onOpen(tab, null) }
                        // The matching words of the page's list, so you see why it came up.
                        val parts = text.substringAfter(": ").split(", ").filter { p -> words.any { it in p.lowercase() } }
                        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), fontSize = 12.sp, color = Mute)
                    }
                }
            }
        }
    }
    Section("Search preferences", "Recent searches are stored for each profile after submitting typed or voice input. They are included if you export an encrypted settings backup.") {
        ToggleRow("Remember recent searches", "Off by default. Turning off also deletes this profile’s history.", vm.recentSearchesEnabled) { vm.setRecentSearchesEnabled(!vm.recentSearchesEnabled) }
        if (vm.recentSearches.isNotEmpty()) Chip("Clear recent searches") { vm.clearRecentSearches() }
    }

}
