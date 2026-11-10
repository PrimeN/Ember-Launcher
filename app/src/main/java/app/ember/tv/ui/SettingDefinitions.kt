package app.ember.tv.ui

/** Values, visible labels and exact-section search destinations share one definition. */
internal data class SettingDefinition(
    val id: String, val tab: SettingsTab, val title: String, val keywords: String,
    val options: List<Pair<String, String>> = emptyList(),
    /** Earlier names of the setting's choices, so searching an old name still lands here (#148). */
    val formerly: List<String> = emptyList(),
) {
    val searchText get() = "$title $keywords ${options.joinToString(" ") { it.second }}".lowercase(java.util.Locale.ROOT)
}

internal object SettingDefinitions {
    val interfaceStyle = SettingDefinition("interface", SettingsTab.APPEARANCE, "Interface", "look launcher style Ember Ember Light home navigation apps grid",
        app.ember.tv.data.InterfaceStyles.options)
    val theme = SettingDefinition("theme", SettingsTab.APPEARANCE, "Theme", "OLED black day night automatic",
        listOf("night" to "Night", "day" to "Day", "auto" to "Auto"))
    val mediaPageLayout = SettingDefinition("media-page-layout", SettingsTab.APPEARANCE, "Media page layout",
        "movie show details episodes sources independent Modern Ember X right panel",
        app.ember.tv.data.MediaPageLayouts.options, formerly = listOf("Modern"))
    val navbarStyle=SettingDefinition("navbar-style",SettingsTab.APPEARANCE,"Navbar style",
        "navigation header collapsed cinema bar black independent menu",app.ember.tv.data.NavbarStyles.options)
    val colourTheme = SettingDefinition("accent_colour", SettingsTab.APPEARANCE, "Colour theme", "color palette accent charcoal dark grey dark gray blue navy green rose pink red google tv app icon",
        Accents.mapIndexed { index, (label, _) -> index.toString() to label },
        formerly = listOf("Shadow Ember", "Blue ember", "Green flame", "Rose ember", "Modern Ember", "Cinema red"))
    val playerInterface = SettingDefinition("player_look", SettingsTab.PLAYER, "Player interface", "player look GUI controls basic simple advanced Ember Modern Ember Glass Ember X cinema streaming layout independent",
        app.ember.tv.player.PlayerInterfaces.options)
    val interfaceSize = SettingDefinition("interface-size", SettingsTab.SIZE, "Interface size", "screen scale", UiSizes.LABELS)
    val textSize = SettingDefinition("text-size", SettingsTab.SIZE, "Text size", "font accessibility", UiSizes.LABELS)
    val density = SettingDefinition("density", SettingsTab.SIZE, "Media card density", "spacing posters comfortable standard dense",
        listOf("comfortable" to "Comfortable", "standard" to "Standard", "dense" to "Dense"))
    val performance=SettingDefinition("device-performance",SettingsTab.PERFORMANCE,"Performance profile","device performance speed memory RAM low medium high ultra 4K auto custom image quality economy balanced prefetch resolution animation refresh analysis progressive collection motion",
        app.ember.tv.data.DevicePerformance.options)
    val artwork = SettingDefinition("artwork", SettingsTab.PERFORMANCE, "Glass", "modern frosted navigation glass blur popups dock live prepared liquid refraction tint vibrancy highlights google tv android tv stock solid opaque plain")
    val focusBackgrounds = SettingDefinition("focus-backgrounds", SettingsTab.PERFORMANCE, "Focused backgrounds", "app colors media rows collections tile colors artwork backdrop theme only focus hover background palette", app.ember.tv.data.FocusBackground.choices)
    val rowAddonLabels=SettingDefinition("row-addon-labels",SettingsTab.APPEARANCE,"Addon names on rows","show hide addon add-on names catalog source label after row title home live")
    val tileLabels=SettingDefinition("tile-labels",SettingsTab.APPEARANCE,"Labels under tiles","hide remove text names titles under below tiles cards apps collections folders captions",app.ember.tv.data.TileLabels.choices)
    val cardFocus=SettingDefinition("card-focus",SettingsTab.PERFORMANCE,"Card focus effects","selected apps media glow pulse lift scale animation effects blinking",app.ember.tv.data.CardFocusStyle.choices)
    val watched=SettingDefinition("watch-status",SettingsTab.WATCH_STATUS,"Watched indicators","watched unwatched mark check badge movie show episode")
    val spoilers=SettingDefinition("episode-spoilers",SettingsTab.WATCH_STATUS,"Episode spoilers","blur unwatched episode artwork thumbnail image")
    val updates=SettingDefinition("updates",SettingsTab.UPDATES,"Updates","update checks download install beta stable automatic release actions apk source")
    val debug=SettingDefinition("debug-build",SettingsTab.DEBUG,"Build information","debug diagnostics build type version")
    val cacheSize=SettingDefinition("cache-size",SettingsTab.CACHE,"Current Cache Size","cache management storage usage clear cache data")
    val cacheImages=SettingDefinition("image-duration",SettingsTab.CACHE,"Image cache duration (Days)","image expiration retention days")
    val cacheMetadata=SettingDefinition("metadata-duration",SettingsTab.CACHE,"Metadata cache duration (Hours)","metadata expiration retention hours")
    val cacheCatalogs=SettingDefinition("catalog-duration",SettingsTab.CACHE,"Catalog cache duration (Hours)","catalog expiration retention hours")
    val cacheNetwork=SettingDefinition("network-duration",SettingsTab.CACHE,"Network cache duration (Hours)","network response expiration retention hours")
    val imageCache=SettingDefinition("image-cache",SettingsTab.DEBUG,"Artwork cache","image pictures cache storage clear disk downloaded")
    val crash=SettingDefinition("crash-log",SettingsTab.DEBUG,"Crash log","crash reports log diagnostics troubleshoot")
    val resume = SettingDefinition("resume", SettingsTab.PLAYER, "Resume", "continue playback start over",
        listOf("ask" to "Ask: Resume or Start over", "resume" to "Always resume", "start" to "Always start over"))
    val leaving = SettingDefinition("leaving", SettingsTab.PLAYER, "Leaving the player", "confirm exit back button home button stop watching ask picture-in-picture pip return last video quick resume multitasking",
        listOf("leave" to "Leave straight away", "ask" to "Ask first"))
    val skipIntro = SettingDefinition("skip-intro", SettingsTab.PLAYER, "Skip intro", "IntroDB credits recap automatic community fallback",
        listOf("button" to "Show a Skip button", "auto" to "Skip by itself", "off" to "Off"))
    val audio = SettingDefinition("audio-timing", SettingsTab.AUDIO_VIDEO, "Sound timing", "lip sync audio delay milliseconds")
    val output = SettingDefinition("audio-output", SettingsTab.AUDIO_VIDEO, "Audio output", "passthrough bitstream Atmos TrueHD DTS receiver soundbar")
    val subtitles = SettingDefinition("subtitle-start", SettingsTab.SUBTITLES, "Subtitles at start", "foreign audio automatically language",
        listOf("foreign" to "When the audio is not my language", "always" to "Always", "off" to "Never"))
    val iptvAdvanced=SettingDefinition("iptv-advanced",SettingsTab.LIVE,"Advanced IPTV options","guide XMLTV EPG user agent playlist provider")
    val hero = SettingDefinition("hero-banner", SettingsTab.HOME_ROWS, "Hero banner", "hero banner carousel featured artwork Fanart standard catalog source ten titles watch now watchlist dots")
    val aiDiscovery = SettingDefinition("ai-discovery", SettingsTab.DISCOVER, "AI-assisted discovery", "Mistral describe what to watch suggested filters optional API")
    val setup = SettingDefinition("setup-checklist",SettingsTab.FEATURES,"Setup checklist","readiness onboarding sources servers accounts optional setup ready")
    val calibration=SettingDefinition("calibration-patterns",SettingsTab.DEVICE_TOOLS,"Calibration patterns","4K UHD sharpness overscan brightness contrast grayscale color judder motion")
    val calibrationVideo=SettingDefinition("calibration-video",SettingsTab.DEVICE_TOOLS,"Video calibration","SDR HDR10 Dolby Vision test clips included offline Mehanik HDR Test Patterns")
    val deviceMenus=SettingDefinition("device-menus",SettingsTab.DEVICE_TOOLS,"Device menus","MediaTek Amlogic Xiaomi FengOS Formovie XGIMI projector focus picture")
    val engineeringMenus=SettingDefinition("engineering-menus",SettingsTab.DEVICE_TOOLS,"Engineering menus","firmware factory service menu MediaTek Amlogic Xiaomi FengOS Formovie XGIMI")
    val deviceInputs=SettingDefinition("device-inputs",SettingsTab.DEVICE_TOOLS,"Inputs","HDMI AV input shortcuts")
    val providerDiagnostics=SettingDefinition("provider-diagnostics",SettingsTab.DEBUG,"Provider diagnostics","source request timing latency timeout auth quota offline redacted export")
    val remoteSounds = SettingDefinition("remote-sounds", SettingsTab.REMOTE, "Remote sounds", "click navigation sound effects arrows OK beep")
    val autoLoad = SettingDefinition("auto-load", SettingsTab.REMOTE, "Load more as I scroll", "load more pages next page automatically paging infinite scroll Discover collections libraries episodes")
    val hiddenApps = SettingDefinition("hidden-apps", SettingsTab.HOME_TOOLS, "Hidden apps", "unhide show restore apps removed from Home Ember Light page tiles More Apps")
    val all = listOf(hiddenApps,cacheSize,cacheImages,cacheMetadata,cacheCatalogs,cacheNetwork,remoteSounds,autoLoad,providerDiagnostics,engineeringMenus,calibration,calibrationVideo,deviceMenus,deviceInputs,setup,aiDiscovery,hero,iptvAdvanced,interfaceStyle,mediaPageLayout,navbarStyle, theme, colourTheme, playerInterface, interfaceSize, textSize, performance, density, artwork, focusBackgrounds,cardFocus,tileLabels,rowAddonLabels,watched,spoilers,updates,debug,imageCache,crash, resume, leaving, skipIntro, audio, output, subtitles)
    fun matching(query: String): List<SettingDefinition> {
        val normalized = query.trim().lowercase(java.util.Locale.ROOT)
        val exact = all.filter { it.title.lowercase(java.util.Locale.ROOT)==normalized || it.options.any { (_,label)->label.lowercase(java.util.Locale.ROOT)==normalized } ||
            it.formerly.any { name -> name.lowercase(java.util.Locale.ROOT)==normalized } }
        if(exact.isNotEmpty())return exact
        val words = normalized.split(Regex("\\s+")).filter { it.length >= 2 }
        return if (words.isEmpty()) emptyList() else all.filter { d -> words.all { it in d.searchText } }
    }
}
