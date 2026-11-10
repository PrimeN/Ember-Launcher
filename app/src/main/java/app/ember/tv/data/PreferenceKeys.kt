package app.ember.tv.data

import android.content.SharedPreferences

/** One key/type/default/backup definition shared by preference readers and portable backups. */
internal enum class PreferenceType(val tag:String) {STRING("s"),SET("set"),BOOLEAN("b"),INT("i"),LONG("l")}
internal data class PreferenceKey<T>(val name:String,val type:PreferenceType,val default:T,val portable:Boolean=true)
internal object PreferenceKeys {
    val ACCENT = PreferenceKey<Int>("accent", PreferenceType.INT, 0)
    val ACCENT2 = PreferenceKey<Int>("accent2", PreferenceType.INT, 0)
    val ACCENT3 = PreferenceKey<Int>("accent3", PreferenceType.INT, 0)
    val ADDON_NAMES = PreferenceKey<String?>("addonNames", PreferenceType.STRING, "{}")
    val ADDON_TIMEOUT = PreferenceKey<Int>("addonTimeout", PreferenceType.INT, 10)
    val ADDONS = PreferenceKey<String?>("addons", PreferenceType.STRING, "")
    val AI_DISCOVERY_ENABLED = PreferenceKey<Boolean>("aiDiscoveryEnabled", PreferenceType.BOOLEAN, false)
    val APP_FOCUS_COLORS = PreferenceKey<Boolean>("appFocusColors", PreferenceType.BOOLEAN, true)
    val APP_ICONS = PreferenceKey<String?>("appIcons", PreferenceType.STRING, "circle")
    /** The addon's name after each catalog row's title (#144). */
    val ROW_ADDON_LABELS = PreferenceKey<Boolean>("rowAddonLabels", PreferenceType.BOOLEAN, true)
    /** One-time: the Play button switched back on after the beta "Auto select" toggle (#145). */
    val PLAY_BUTTON_RESTORED = PreferenceKey<Boolean>("playButtonRestored", PreferenceType.BOOLEAN, false)
    val APPS_PAGE_OFF = PreferenceKey<Set<String>>("appsPageOff", PreferenceType.SET, emptySet())
    val ARTWORK_QUALITY = PreferenceKey<String?>("artworkQuality", PreferenceType.STRING, "balanced")
    val ASK_PROFILE = PreferenceKey<Boolean>("askProfile", PreferenceType.BOOLEAN, false)
    val AUTOPLAY = PreferenceKey<Boolean>("autoplay", PreferenceType.BOOLEAN, true)
    val BACKGROUND_REFRESH = PreferenceKey<Boolean>("backgroundRefresh", PreferenceType.BOOLEAN, true)
    val BADGE_MODE = PreferenceKey<String?>("badgeMode", PreferenceType.STRING, "legacy")
    val DETAILED_BADGE_CATEGORIES_OFF = PreferenceKey<Set<String>>("detailedBadgeCategoriesOff", PreferenceType.SET, emptySet())
    val BLUR_UNWATCHED_EPISODES = PreferenceKey<Boolean>("blurUnwatchedEpisodes", PreferenceType.BOOLEAN, false)
    val CACHE_CONFIG = PreferenceKey<String?>("cacheConfig", PreferenceType.STRING, "{}")
    val CARD_FOCUS_STYLE = PreferenceKey<String?>("cardFocusStyle", PreferenceType.STRING, null)
    /** Tile kinds without a label underneath (#43): "apps", "media", "collections". */
    val TILE_LABELS_OFF = PreferenceKey<Set<String>>("tileLabelsOff", PreferenceType.SET, emptySet())
    val CATALOG_DISCOVER_OFF = PreferenceKey<Set<String>>("catalogDiscoverOff", PreferenceType.SET, emptySet())
    val CATALOG_HOME = PreferenceKey<String?>("catalogHome", PreferenceType.STRING, "{}")
    val CATALOG_HOME_DEFAULTS_OFF = PreferenceKey<Boolean>("catalogHomeDefaultsOff", PreferenceType.BOOLEAN, false)
    val CATALOG_LIVE = PreferenceKey<String?>("catalogLive", PreferenceType.STRING, "{}")
    val CATALOG_NAMES = PreferenceKey<String?>("catalogNames", PreferenceType.STRING, "{}")
    val CATALOG_ORDER = PreferenceKey<String?>("catalogOrder", PreferenceType.STRING, "")
    val CATALOGS_ON_HOME_FIXED = PreferenceKey<Boolean>("catalogsOnHomeFixed", PreferenceType.BOOLEAN, false)
    /** "Load more as I scroll" (#109): every paged list, on unless switched off. */
    val COLLECTION_AUTO_PAGE = PreferenceKey<Boolean>("collectionAutoPage", PreferenceType.BOOLEAN, true)
    val COLLECTION_FOCUS_BACKGROUND = PreferenceKey<String?>("collectionFocusBackground", PreferenceType.STRING, null)
    val COLLECTION_FOLDERS_OFF = PreferenceKey<Set<String>>("collectionFoldersOff", PreferenceType.SET, emptySet())
    val COLLECTION_HERO_PREVIEWS = PreferenceKey<Boolean>("collectionHeroPreviews", PreferenceType.BOOLEAN, false)
    val COLLECTIONS_DISCOVER = PreferenceKey<Set<String>>("collectionsDiscover", PreferenceType.SET, emptySet())
    val COLLECTIONS_OFF = PreferenceKey<Set<String>>("collectionsOff", PreferenceType.SET, emptySet())
    val CONTENT_DENSITY = PreferenceKey<String?>("contentDensity", PreferenceType.STRING, "standard")
    val COUNTDOWN = PreferenceKey<Int>("countdown", PreferenceType.INT, 10)
    val CURRENT_PROFILE = PreferenceKey<String?>("currentProfile", PreferenceType.STRING, Profile.MAIN)
    val CUSTOM_PERFORMANCE = PreferenceKey<String?>("customPerformance", PreferenceType.STRING, "{}")
    val DEVICE_PERFORMANCE = PreferenceKey<String?>("devicePerformance", PreferenceType.STRING, "auto")
    val DISCOVER_SECTIONS = PreferenceKey<String?>("discoverSections", PreferenceType.STRING, "{}")
    val EMBER_X_INTERFACE_MIGRATED = PreferenceKey<Boolean>("emberXInterfaceMigrated", PreferenceType.BOOLEAN, false)
    val FAHRENHEIT = PreferenceKey<Boolean>("fahrenheit", PreferenceType.BOOLEAN, false)
    val FEATURES_OFF = PreferenceKey<Set<String>>("featuresOff", PreferenceType.SET, emptySet())
    val HERO_ARTWORK_MODE = PreferenceKey<String?>("heroArtworkMode", PreferenceType.STRING, HeroArtworkMode.STANDARD)
    val HERO_CATALOG = PreferenceKey<String?>("heroCatalog", PreferenceType.STRING, null)
    val HERO_ON = PreferenceKey<Boolean>("heroOn", PreferenceType.BOOLEAN, true)
    val HIDDEN = PreferenceKey<Set<String>>("hidden", PreferenceType.SET, emptySet())
    val HOME_APP_ROWS = PreferenceKey<Int>("homeAppRows", PreferenceType.INT, 1)
    val HOME_AT_BOOT = PreferenceKey<Boolean>("homeAtBoot", PreferenceType.BOOLEAN, true)
    val HOME_HIDDEN_TITLES = PreferenceKey<Set<String>>("homeHiddenTitles", PreferenceType.SET, emptySet())
    val HOME_LAYOUT = PreferenceKey<String?>("homeLayout", PreferenceType.STRING, "")
    val HOME_LIST_STYLE = PreferenceKey<String?>("homeListStyle", PreferenceType.STRING, "")
    val PERSONAL_LIST_SOURCES_OFF = PreferenceKey<Set<String>>("personalListSourcesOff", PreferenceType.SET, emptySet())
    val HOME_OVERRIDE = PreferenceKey<Boolean>("homeOverride", PreferenceType.BOOLEAN, true)
    val HOME_OVERRIDE_MODE = PreferenceKey<String?>("homeOverrideMode", PreferenceType.STRING, "screen")
    val IPTV_FAVOURITES = PreferenceKey<Set<String>>("iptvFavourites", PreferenceType.SET, emptySet())
    val IPTV_RECENT = PreferenceKey<String?>("iptvRecent", PreferenceType.STRING, null)
    val IPTV_VIEW = PreferenceKey<String?>("iptvView", PreferenceType.STRING, "grid")
    val LIGHT_WALLPAPER = PreferenceKey<String?>("lightWallpaper", PreferenceType.STRING, "art")
    val PERSONAL_HOMES = PreferenceKey<String?>("personalHomes", PreferenceType.STRING, "{}")
    val LAUNCHER_EXTRAS = PreferenceKey<String?>("launcherExtras", PreferenceType.STRING, "{}")
    val LIGHT_HOME = PreferenceKey<String?>("lightHome", PreferenceType.STRING, "{}")
    val LISTS_ON_HOME_SET = PreferenceKey<Boolean>("listsOnHomeSet", PreferenceType.BOOLEAN, false)
    val MEDIA_FOCUS_BACKGROUND = PreferenceKey<String?>("mediaFocusBackground", PreferenceType.STRING, null)
    val MENU_POSITION = PreferenceKey<String?>("menuPosition", PreferenceType.STRING, "top")
    val META_CONFIG = PreferenceKey<String?>("metaConfig", PreferenceType.STRING, "{}")
    val MODERN_CARDS = PreferenceKey<String?>("modernCards", PreferenceType.STRING, "wide")
    val NAV = PreferenceKey<String?>("nav", PreferenceType.STRING, null)
    val NAV_DEFAULTS2 = PreferenceKey<Boolean>("navDefaults2", PreferenceType.BOOLEAN, false)
    val NAVIGATION_GLASS = PreferenceKey<Boolean>("navigationGlass", PreferenceType.BOOLEAN, false)
    val NAVIGATION_GLASS_METHOD = PreferenceKey<String?>("navigationGlassMethod", PreferenceType.STRING, "live")
    val OLED = PreferenceKey<Boolean>("oled", PreferenceType.BOOLEAN, false)
    val ORDER = PreferenceKey<String?>("order", PreferenceType.STRING, "")
    val PAUSED_ADDON_KEYS = PreferenceKey<Set<String>>("pausedAddonKeys", PreferenceType.SET, emptySet())
    val PIN_HASH = PreferenceKey<String?>("pinHash", PreferenceType.STRING, null)
    val PIN_SALT = PreferenceKey<String?>("pinSalt", PreferenceType.STRING, null)
    val PLAY_RULES = PreferenceKey<String?>("playRules", PreferenceType.STRING, "{}")
    val PLAYBACK_CONFIG = PreferenceKey<String?>("playbackConfig", PreferenceType.STRING, "{}")
    val PLAYER = PreferenceKey<String?>("player", PreferenceType.STRING, Prefs.PLAYER_BUILTIN)
    val POSTER_CONFIG = PreferenceKey<String?>("posterConfig", PreferenceType.STRING, "{}")
    val PROFILES = PreferenceKey<String?>("profiles", PreferenceType.STRING, "[]")
    val RATINGS_OFF = PreferenceKey<Set<String>>("ratingsOff", PreferenceType.SET, emptySet())
    val RATINGS_ON = PreferenceKey<Boolean>("ratingsOn", PreferenceType.BOOLEAN, true)
    val RATINGS_ORDER = PreferenceKey<String?>("ratingsOrder", PreferenceType.STRING, "")
    val REMOTE_SETTINGS_KEY = PreferenceKey<String?>("remoteSettingsKey", PreferenceType.STRING, "ember")
    val REMOTE_SOUNDS = PreferenceKey<Boolean>("remoteSounds", PreferenceType.BOOLEAN, false)
    val SCREEN_MARGIN = PreferenceKey<String?>("screenMargin", PreferenceType.STRING, "0")
    val SEEDED_TEST_ADDONS = PreferenceKey<Set<String>>("seededTestAddons", PreferenceType.SET, emptySet())
    val SERVER_HERO_ON = PreferenceKey<Boolean>("serverHeroOn", PreferenceType.BOOLEAN, false)
    val SERVER_HERO_SOURCE = PreferenceKey<String?>("serverHeroSource", PreferenceType.STRING, null)
    val SERVER_SECTIONS_OFF = PreferenceKey<Set<String>>("serverSectionsOff", PreferenceType.SET, emptySet())
    val SERVICES_FETCHED_AT = PreferenceKey<Long>("servicesFetchedAt", PreferenceType.LONG, 0L)
    val SERVICES_JSON = PreferenceKey<String?>("servicesJson", PreferenceType.STRING, null)
    val SERVICES_OFF = PreferenceKey<Set<String>>("servicesOff", PreferenceType.SET, emptySet())
    val SERVICES_ON = PreferenceKey<Set<String>>("servicesOn", PreferenceType.SET, emptySet())
    val SERVICES_SOURCE = PreferenceKey<String?>("servicesSource", PreferenceType.STRING, "")
    /** Retired (#149): Settings always opens as the full page. Kept so old backups still read. */
    val SETTINGS_STYLE = PreferenceKey<String?>("settingsStyle", PreferenceType.STRING, "standard")
    val SETUP_CHECKLIST_HIDDEN = PreferenceKey<Boolean>("setupChecklistHidden", PreferenceType.BOOLEAN, false)
    val SHOW_WATCHED_BADGES = PreferenceKey<Boolean>("showWatchedBadges", PreferenceType.BOOLEAN, true)
    val SMART_ROWS_OFF = PreferenceKey<Set<String>>("smartRowsOff", PreferenceType.SET, emptySet())
    val SOURCES_COMBINED = PreferenceKey<Boolean>("sourcesCombined", PreferenceType.BOOLEAN, true)
    val STREAM_DESC_TEMPLATE = PreferenceKey<String?>("streamDescTemplate", PreferenceType.STRING, null)
    val STREAM_FORMAT_ON = PreferenceKey<Boolean>("streamFormatOn", PreferenceType.BOOLEAN, false)
    val STREAM_NAME_TEMPLATE = PreferenceKey<String?>("streamNameTemplate", PreferenceType.STRING, null)
    val SUBTITLE_CONFIG = PreferenceKey<String?>("subtitleConfig", PreferenceType.STRING, "{}")
    val SUBTITLES_ON = PreferenceKey<Boolean>("subtitlesOn", PreferenceType.BOOLEAN, false)
    val TEXT_SIZE = PreferenceKey<String?>("textSize", PreferenceType.STRING, "default")
    val THEME = PreferenceKey<String?>("theme", PreferenceType.STRING, "night")
    val TRACKERS_OFF = PreferenceKey<Set<String>>("trackersOff", PreferenceType.SET, emptySet())
    /** Trackers not told what you watch (#80). Separate from [TRACKERS_OFF], which is Home only. */
    val TRACKERS_QUIET = PreferenceKey<Set<String>>("trackersQuiet", PreferenceType.SET, emptySet())
    /** Keep connected trackers in sync with each other (#90). Off until switched on. */
    val TRACKER_SYNC = PreferenceKey<Boolean>("trackerSync", PreferenceType.BOOLEAN, false)
    /** Interface size on the scale before #38; read only to carry a choice over to [UI_SCALE]. */
    val UI_SIZE = PreferenceKey<String?>("uiSize", PreferenceType.STRING, "default")
    /** Interface size on the current scale (ui/UiSizes.kt, #38). */
    val UI_SCALE = PreferenceKey<String?>("uiScale", PreferenceType.STRING, null)
    val UI_STYLE = PreferenceKey<String?>("uiStyle", PreferenceType.STRING, InterfaceStyles.MODERN)
    val MEDIA_PAGE_LAYOUT = PreferenceKey<String?>("mediaPageLayout", PreferenceType.STRING, null)
    val NAVBAR_STYLE = PreferenceKey<String?>("navbarStyle", PreferenceType.STRING, NavbarStyles.EMBER)
    val UPDATE_AUTO_CHECK = PreferenceKey<Boolean>("updateAutoCheck", PreferenceType.BOOLEAN, true)
    val UPDATE_BETA = PreferenceKey<Boolean>("updateBeta", PreferenceType.BOOLEAN, false)
    val UPDATE_BETA_SOURCE = PreferenceKey<String?>("updateBetaSource", PreferenceType.STRING, "releases")
    val UPDATE_CHANNEL = PreferenceKey<String?>("updateChannel", PreferenceType.STRING, "releases")
    val UPDATE_CHECKED_AT = PreferenceKey<Long>("updateCheckedAt", PreferenceType.LONG, 0L)
    val WATCH_PROVIDER = PreferenceKey<String?>("watchProvider", PreferenceType.STRING, "off")
    val WATCH_REGION = PreferenceKey<String?>("watchRegion", PreferenceType.STRING, null)
    val WEATHER_PROVIDER=PreferenceKey<String?>("weatherProvider",PreferenceType.STRING,"openmeteo")
    val WEATHER_FALLBACK=PreferenceKey<Boolean>("weatherFallback",PreferenceType.BOOLEAN,true)
    val WEATHER_CACHE=PreferenceKey<String?>("weatherCache",PreferenceType.STRING,null)
    val WEATHER_PLACE = PreferenceKey<String?>("weatherPlace", PreferenceType.STRING, null)
    val WL = PreferenceKey<String?>("wl", PreferenceType.STRING, "[]")
    val all = listOf(
        ACCENT, ACCENT2, ACCENT3, ADDON_NAMES, ADDON_TIMEOUT, ADDONS,
        AI_DISCOVERY_ENABLED, APP_FOCUS_COLORS, APP_ICONS, ROW_ADDON_LABELS, PLAY_BUTTON_RESTORED, APPS_PAGE_OFF, ARTWORK_QUALITY, ASK_PROFILE,
        AUTOPLAY, BACKGROUND_REFRESH, BADGE_MODE, BLUR_UNWATCHED_EPISODES, CACHE_CONFIG, CARD_FOCUS_STYLE, TILE_LABELS_OFF, CATALOG_DISCOVER_OFF,
        CATALOG_HOME, CATALOG_HOME_DEFAULTS_OFF, CATALOG_LIVE, CATALOG_NAMES, CATALOG_ORDER, CATALOGS_ON_HOME_FIXED,
        COLLECTION_AUTO_PAGE, COLLECTION_FOCUS_BACKGROUND, COLLECTION_FOLDERS_OFF, COLLECTION_HERO_PREVIEWS, COLLECTIONS_DISCOVER, COLLECTIONS_OFF,
        CONTENT_DENSITY, COUNTDOWN, CURRENT_PROFILE, CUSTOM_PERFORMANCE, DETAILED_BADGE_CATEGORIES_OFF, DEVICE_PERFORMANCE, DISCOVER_SECTIONS,
        EMBER_X_INTERFACE_MIGRATED, FAHRENHEIT, FEATURES_OFF, HERO_ARTWORK_MODE, HERO_CATALOG, HERO_ON,
        HIDDEN, HOME_APP_ROWS, HOME_AT_BOOT, HOME_HIDDEN_TITLES, HOME_LAYOUT, HOME_LIST_STYLE, PERSONAL_LIST_SOURCES_OFF,
        HOME_OVERRIDE, HOME_OVERRIDE_MODE, IPTV_FAVOURITES, IPTV_RECENT, IPTV_VIEW, LIGHT_WALLPAPER, LIGHT_HOME, PERSONAL_HOMES, LAUNCHER_EXTRAS,
        LISTS_ON_HOME_SET, MEDIA_FOCUS_BACKGROUND, MENU_POSITION, META_CONFIG, MODERN_CARDS, NAV,
        NAV_DEFAULTS2, NAVIGATION_GLASS, NAVIGATION_GLASS_METHOD, NAVBAR_STYLE, OLED, ORDER, PAUSED_ADDON_KEYS,
        PIN_HASH, PIN_SALT, PLAY_RULES, PLAYBACK_CONFIG, PLAYER, POSTER_CONFIG,
        PROFILES, RATINGS_OFF, RATINGS_ON, RATINGS_ORDER, REMOTE_SETTINGS_KEY, REMOTE_SOUNDS, SCREEN_MARGIN,
        SEEDED_TEST_ADDONS, SERVER_HERO_ON, SERVER_HERO_SOURCE, SERVER_SECTIONS_OFF, SERVICES_FETCHED_AT, SERVICES_JSON,
        SERVICES_OFF, SERVICES_ON, SERVICES_SOURCE, SETTINGS_STYLE, SETUP_CHECKLIST_HIDDEN, SHOW_WATCHED_BADGES,
        SMART_ROWS_OFF, SOURCES_COMBINED, STREAM_DESC_TEMPLATE, STREAM_FORMAT_ON, STREAM_NAME_TEMPLATE, SUBTITLE_CONFIG,
        SUBTITLES_ON, TEXT_SIZE, THEME, TRACKERS_OFF, TRACKERS_QUIET, TRACKER_SYNC, UI_SIZE, UI_SCALE, UI_STYLE, MEDIA_PAGE_LAYOUT,
        UPDATE_AUTO_CHECK, UPDATE_BETA, UPDATE_BETA_SOURCE, UPDATE_CHANNEL, UPDATE_CHECKED_AT, WATCH_PROVIDER, WATCH_REGION,
        WEATHER_PLACE, WEATHER_PROVIDER, WEATHER_FALLBACK, WEATHER_CACHE, WL,
    )
    val stores=listOf("ember","screensaver","positions","trackMemory","server_browser","ember_watched")
    fun expectedType(store:String,name:String):String?=when(store) {
        "ember_watched"->"set"
        "positions","trackMemory","server_browser"->"s"
        "screensaver"->when(name) {"sources"->"set";"collectionsAt"->"l";"slides","art","space","photoFolder","weather"->"s";else->null}
        "ember"->expectedType(name) ?: when {
            name.startsWith("explore:") || name.startsWith("folderSource:") || name.startsWith("widgets:")->"s"
            name.startsWith("search:")->if(name.endsWith("enabled"))"b" else "s"
            else->null
        }
        else->null
    }
    private val byName=all.associateBy {it.name}
    fun definition(name:String):PreferenceKey<*>?=byName[name]
    fun expectedType(name:String):String?=definition(name)?.takeIf {it.portable}?.type?.tag
}

@Suppress("UNCHECKED_CAST")
internal fun <T> SharedPreferences.read(key:PreferenceKey<T>,default:T=key.default):T = when(key.type) {
    PreferenceType.STRING->getString(key.name,default as String?)
    PreferenceType.SET->getStringSet(key.name,default as Set<String>)?.toSet() ?: default
    PreferenceType.BOOLEAN->getBoolean(key.name,default as Boolean)
    PreferenceType.INT->getInt(key.name,default as Int)
    PreferenceType.LONG->getLong(key.name,default as Long)
} as T
