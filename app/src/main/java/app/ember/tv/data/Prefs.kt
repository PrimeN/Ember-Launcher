package app.ember.tv.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Small local store for the user's launcher choices. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ember", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    init { CatalogIdentity.migratePreferences(sp) }

    var cacheConfig: CacheConfig
        get() = runCatching { json.decodeFromString<CacheConfig>(sp.read(PreferenceKeys.CACHE_CONFIG)!!).also { require(it.valid()) } }.getOrDefault(CacheConfig())
        set(value) { require(value.valid()); sp.edit { putString(PreferenceKeys.CACHE_CONFIG.name, json.encodeToString(value)) } }

    private fun searchKey(profile: String) = "search:" + HomeTitleVisibility.prefix(profile)
    fun recentSearchesEnabled(profile: String) = sp.getBoolean(searchKey(profile) + "enabled", false)
    fun setRecentSearchesEnabled(profile: String, enabled: Boolean) {
        sp.edit { putBoolean(searchKey(profile) + "enabled", enabled) }
        if (!enabled) clearRecentSearches(profile)
    }
    fun recentSearches(profile: String): List<String> = if (!recentSearchesEnabled(profile)) emptyList() else
        runCatching { json.decodeFromString<List<String>>(sp.getString(searchKey(profile), "[]")!!) }.getOrDefault(emptyList())
    fun recordRecentSearch(profile: String, query: String) {
        if (recentSearchesEnabled(profile)) sp.edit { putString(searchKey(profile),
            json.encodeToString(RecentSearches.updated(recentSearches(profile), query))) }
    }
    fun clearRecentSearches(profile: String) { sp.edit { remove(searchKey(profile)) } }

    var discoverSections: Map<String, Boolean>
        get() = runCatching { json.decodeFromString<Map<String, Boolean>>(sp.read(PreferenceKeys.DISCOVER_SECTIONS)!!) }.getOrDefault(emptyMap())
        set(value) = sp.edit { putString(PreferenceKeys.DISCOVER_SECTIONS.name, json.encodeToString(value)) }

    var aiDiscoveryEnabled: Boolean
        get() = runCatching { sp.read(PreferenceKeys.AI_DISCOVERY_ENABLED) }.getOrDefault(false)
        set(value) = sp.edit { putBoolean(PreferenceKeys.AI_DISCOVERY_ENABLED.name, value) }

    var serverSectionsOff:Set<String>
        get()=sp.read(PreferenceKeys.SERVER_SECTIONS_OFF)
        set(value)=sp.edit {putStringSet(PreferenceKeys.SERVER_SECTIONS_OFF.name,value)}
    var serverHeroOn:Boolean
        get()=sp.read(PreferenceKeys.SERVER_HERO_ON)
        set(value)=sp.edit {putBoolean(PreferenceKeys.SERVER_HERO_ON.name,value)}
    var serverHeroSource:String?
        get()=sp.read(PreferenceKeys.SERVER_HERO_SOURCE)
        set(value)=sp.edit {if(value==null)remove(PreferenceKeys.SERVER_HERO_SOURCE.name) else putString(PreferenceKeys.SERVER_HERO_SOURCE.name,value)}

    var heroArtworkMode: String
        get() = runCatching { HeroArtworkMode.normalize(sp.read(PreferenceKeys.HERO_ARTWORK_MODE).orEmpty()) }.getOrDefault(HeroArtworkMode.STANDARD)
        set(value) = sp.edit { putString(PreferenceKeys.HERO_ARTWORK_MODE.name, HeroArtworkMode.normalize(value)) }
    var appFocusColors: Boolean
        get() = runCatching { sp.read(PreferenceKeys.APP_FOCUS_COLORS) }.getOrDefault(true)
        set(value) = sp.edit { putBoolean(PreferenceKeys.APP_FOCUS_COLORS.name, value) }
    var showWatchedBadges:Boolean
        get()=runCatching {sp.read(PreferenceKeys.SHOW_WATCHED_BADGES)}.getOrDefault(true)
        set(value)=sp.edit {putBoolean(PreferenceKeys.SHOW_WATCHED_BADGES.name,value)}
    var blurUnwatchedEpisodes:Boolean
        get()=runCatching {sp.read(PreferenceKeys.BLUR_UNWATCHED_EPISODES)}.getOrDefault(false)
        set(value)=sp.edit {putBoolean(PreferenceKeys.BLUR_UNWATCHED_EPISODES.name,value)}
    var cardFocusStyle:String
        get()=runCatching {CardFocusStyle.normalize(sp.read(PreferenceKeys.CARD_FOCUS_STYLE))}.getOrDefault(CardFocusStyle.SUBTLE)
        set(value)=sp.edit {putString(PreferenceKeys.CARD_FOCUS_STYLE.name,CardFocusStyle.normalize(value))}
    var mediaFocusBackground: String
        get() = runCatching { FocusBackground.normalize(sp.read(PreferenceKeys.MEDIA_FOCUS_BACKGROUND)) }.getOrDefault(FocusBackground.COLORS)
        set(value) = sp.edit { putString(PreferenceKeys.MEDIA_FOCUS_BACKGROUND.name, FocusBackground.normalize(value)) }
    var collectionFocusBackground: String
        get() = runCatching { FocusBackground.normalize(sp.read(PreferenceKeys.COLLECTION_FOCUS_BACKGROUND)) }.getOrDefault(FocusBackground.COLORS)
        set(value) = sp.edit { putString(PreferenceKeys.COLLECTION_FOCUS_BACKGROUND.name, FocusBackground.normalize(value)) }
    var heroCatalog: String?
        get() = runCatching { sp.read(PreferenceKeys.HERO_CATALOG)?.takeIf { it.isNotBlank() } }.getOrNull()
        set(value) = sp.edit { if (value.isNullOrBlank()) remove(PreferenceKeys.HERO_CATALOG.name) else putString(PreferenceKeys.HERO_CATALOG.name, value) }

    fun explorePresets(profile: String): List<app.ember.tv.discover.SavedExplore> = runCatching {
        json.decodeFromString<List<app.ember.tv.discover.SavedExplore>>(sp.getString("explore:" + HomeTitleVisibility.prefix(profile), "[]")!!)
    }.getOrDefault(emptyList())
    fun setExplorePresets(profile: String, value: List<app.ember.tv.discover.SavedExplore>) {
        sp.edit { putString("explore:" + HomeTitleVisibility.prefix(profile), json.encodeToString(value)) }
    }

    var hidden: Set<String>
        get() = sp.read(PreferenceKeys.HIDDEN)
        set(v) = sp.edit { putStringSet(PreferenceKeys.HIDDEN.name, v) }

    var order: List<String>
        get() = sp.read(PreferenceKeys.ORDER)!!.split("\n").filter { it.isNotBlank() }
        set(v) = sp.edit { putString(PreferenceKeys.ORDER.name, v.joinToString("\n")) }

    private val addonTokens by lazy { app.ember.tv.tracker.TokenStore(context) }
    private val addonConfiguration by lazy { AddonConfiguration(
        read = { addonTokens.get("addons.config") }, writeEncrypted = { addonTokens.putEncrypted("addons.config", it) },
        legacy = { if (sp.contains(PreferenceKeys.ADDONS.name)) sp.read(PreferenceKeys.ADDONS).orEmpty().lines().filter { it.isNotBlank() } else listOf(DEFAULT_ADDON) },
        hasLegacy = { sp.contains(PreferenceKeys.ADDONS.name) }, removeLegacy = { sp.edit { remove(PreferenceKeys.ADDONS.name) } }) }
    var addons: List<String>
        get() = addonConfiguration.load()
        set(value) { check(saveAddons(value)) { "Encrypted add-on storage is unavailable." } }
    fun saveAddons(value: List<String>): Boolean = addonConfiguration.save(value)

    var collectionHeroPreviews: Boolean
        get() = sp.read(PreferenceKeys.COLLECTION_HERO_PREVIEWS)
        set(value) = sp.edit { putBoolean(PreferenceKeys.COLLECTION_HERO_PREVIEWS.name, value) }

    var collectionAutoPage: Boolean
        get() = sp.read(PreferenceKeys.COLLECTION_AUTO_PAGE)
        set(value) = sp.edit { putBoolean(PreferenceKeys.COLLECTION_AUTO_PAGE.name, value) }

    var pausedAddons: Set<String>
        get() = sp.read(PreferenceKeys.PAUSED_ADDON_KEYS)
        set(value) = sp.edit { putStringSet(PreferenceKeys.PAUSED_ADDON_KEYS.name, value) }

    // ---- catalogs (Settings > Catalogs); keys are CatalogDef.key ----
    /** Catalog order for Home rows and Discover. Catalogs not listed follow in addon order. */
    var catalogOrder: List<String>
        get() = sp.read(PreferenceKeys.CATALOG_ORDER)!!.split("\n").filter { it.isNotBlank() }
        set(v) = sp.edit { putString(PreferenceKeys.CATALOG_ORDER.name, v.joinToString("\n")) }

    /** Home on/off chosen by the user; catalogs not listed use the default (first six per addon). */
    var catalogHome: Map<String, Boolean>
        get() = runCatching { json.decodeFromString<Map<String, Boolean>>(sp.read(PreferenceKeys.CATALOG_HOME)!!) }.getOrDefault(emptyMap())
        set(v) = sp.edit { putString(PreferenceKeys.CATALOG_HOME.name, json.encodeToString(v)) }

    /** Carries an intentional legacy bulk-Off choice into individual catalog defaults. */
    var catalogHomeDefaultsOff: Boolean
        get() = sp.read(PreferenceKeys.CATALOG_HOME_DEFAULTS_OFF)
        set(v) = sp.edit { putBoolean(PreferenceKeys.CATALOG_HOME_DEFAULTS_OFF.name, v) }

    var catalogNames: Map<String, String>
        get() = runCatching { json.decodeFromString<Map<String, String>>(sp.read(PreferenceKeys.CATALOG_NAMES)!!) }.getOrDefault(emptyMap())
        set(v) = sp.edit { putString(PreferenceKeys.CATALOG_NAMES.name, json.encodeToString(v)) }

    /** Live TV on/off chosen by the user; catalogs not listed use the default (channel and TV catalogs). */
    var catalogLive: Map<String, Boolean>
        get() = runCatching { json.decodeFromString<Map<String, Boolean>>(sp.read(PreferenceKeys.CATALOG_LIVE)!!) }.getOrDefault(emptyMap())
        set(v) = sp.edit { putString(PreferenceKeys.CATALOG_LIVE.name, json.encodeToString(v)) }

    var catalogDiscoverOff: Set<String>
        get() = sp.read(PreferenceKeys.CATALOG_DISCOVER_OFF)
        set(v) = sp.edit { putStringSet(PreferenceKeys.CATALOG_DISCOVER_OFF.name, v) }

    // ---- ratings on title screens (Settings > Ratings) ----
    var ratingsOn: Boolean
        get() = sp.read(PreferenceKeys.RATINGS_ON)
        set(v) = sp.edit { putBoolean(PreferenceKeys.RATINGS_ON.name, v) }

    var ratingsOrder: List<String>
        get() = sp.read(PreferenceKeys.RATINGS_ORDER)!!.split(",").filter { it.isNotBlank() }
        set(v) = sp.edit { putString(PreferenceKeys.RATINGS_ORDER.name, v.joinToString(",")) }

    var ratingsOff: Set<String>
        get() = sp.read(PreferenceKeys.RATINGS_OFF)
        set(v) = sp.edit { putStringSet(PreferenceKeys.RATINGS_OFF.name, v) }

    /** Subtitle providers, languages, matching and look (Settings > Subtitles). */
    var subtitleConfig: SubtitleConfig
        get() = runCatching { json.decodeFromString<SubtitleConfig>(sp.read(PreferenceKeys.SUBTITLE_CONFIG)!!) }.getOrDefault(SubtitleConfig())
        set(v) = sp.edit { putString(PreferenceKeys.SUBTITLE_CONFIG.name, json.encodeToString(v)) }

    var profiles: List<Profile>
        get() = runCatching { json.decodeFromString<List<Profile>>(sp.read(PreferenceKeys.PROFILES)!!) }.getOrDefault(emptyList())
            .filter { it.id.isNotBlank() }.distinctBy { it.id }
            .ifEmpty { listOf(Profile(Profile.MAIN, "Me")) }
        set(v) = sp.edit { putString(PreferenceKeys.PROFILES.name, json.encodeToString(v)) }

    /** Refresh Home rows and the IPTV guide every few hours in the background. */
    var backgroundRefresh: Boolean
        get() = sp.read(PreferenceKeys.BACKGROUND_REFRESH)
        set(v) = sp.edit { putBoolean(PreferenceKeys.BACKGROUND_REFRESH.name, v) }

    /** Seconds an addon gets for a catalog or its details (sources get twice as long). */
    var addonTimeout: Int
        get() = sp.read(PreferenceKeys.ADDON_TIMEOUT)
        set(v) = sp.edit { putInt(PreferenceKeys.ADDON_TIMEOUT.name, v) }

    /** Show "Who's watching?" when Ember starts. */
    /** Settings > Ember as Home: the Home button service sends Google TV's home screen to Ember. */
    var homeOverride: Boolean
        get() = sp.read(PreferenceKeys.HOME_OVERRIDE)
        set(v) = sp.edit { putBoolean(PreferenceKeys.HOME_OVERRIDE.name, v) }

    /** "screen": when Google TV's home appears (keeps long-press Home); "key": catch the Home press itself. */
    var homeOverrideMode: String
        get() = sp.read(PreferenceKeys.HOME_OVERRIDE_MODE)!!
        set(v) = sp.edit { putString(PreferenceKeys.HOME_OVERRIDE_MODE.name, v) }

    /** Open Ember when the TV starts (the service is started by the system at boot). */
    var homeAtBoot: Boolean
        get() = sp.read(PreferenceKeys.HOME_AT_BOOT)
        set(v) = sp.edit { putBoolean(PreferenceKeys.HOME_AT_BOOT.name, v) }

    var askProfile: Boolean
        get() = sp.read(PreferenceKeys.ASK_PROFILE)
        set(v) = sp.edit { putBoolean(PreferenceKeys.ASK_PROFILE.name, v) }

    var currentProfile: String
        get() = sp.read(PreferenceKeys.CURRENT_PROFILE) ?: Profile.MAIN
        set(v) = sp.edit { putString(PreferenceKeys.CURRENT_PROFILE.name, v) }

    /** Salted hash of the profile PIN, or null when none is set. */
    var pinHash: String?
        get() = sp.read(PreferenceKeys.PIN_HASH)
        set(v) = sp.edit { putString(PreferenceKeys.PIN_HASH.name, v) }

    val pinSalt: String
        get() = sp.read(PreferenceKeys.PIN_SALT) ?: java.util.UUID.randomUUID().toString().also { sp.edit { putString(PreferenceKeys.PIN_SALT.name, it) } }

    /** Built-in player: passthrough, decoders, display mode, buffer (Settings > Player). */
    var playRules: PlayRules
        get() = runCatching { json.decodeFromString<PlayRules>(sp.read(PreferenceKeys.PLAY_RULES)!!) }.getOrDefault(PlayRules())
        set(v) = sp.edit { putString(PreferenceKeys.PLAY_RULES.name, json.encodeToString(v)) }

    var playbackConfig: app.ember.tv.player.PlaybackConfig
        get() = runCatching { json.decodeFromString<app.ember.tv.player.PlaybackConfig>(sp.read(PreferenceKeys.PLAYBACK_CONFIG)!!) }
            .getOrDefault(app.ember.tv.player.PlaybackConfig())
        set(v) = sp.edit { putString(PreferenceKeys.PLAYBACK_CONFIG.name, json.encodeToString(v)) }

    /** AniList and TVDB metadata (Settings > Metadata). */
    var metaConfig: MetaConfig
        get() = runCatching { json.decodeFromString<MetaConfig>(sp.read(PreferenceKeys.META_CONFIG)!!) }.getOrDefault(MetaConfig())
        set(v) = sp.edit { putString(PreferenceKeys.META_CONFIG.name, json.encodeToString(v)) }

    /** Poster enhancement (Settings > Posters). */
    var posterConfig: PosterConfig
        get() = runCatching { json.decodeFromString<PosterConfig>(sp.read(PreferenceKeys.POSTER_CONFIG)!!) }.getOrDefault(PosterConfig())
        set(v) = sp.edit { putString(PreferenceKeys.POSTER_CONFIG.name, json.encodeToString(v)) }

    /** Features switched off in Settings > Features (see Features). Off features load nothing at start. */
    /**
     * The remote's Settings and Menu buttons: "ember" opens Ember's settings (and the player's info
     * panel), "tv" leaves them to the TV or projector (picture modes, keystone and so on).
     */
    var remoteSounds: Boolean
        get() = sp.read(PreferenceKeys.REMOTE_SOUNDS)
        set(v) = sp.edit { putBoolean(PreferenceKeys.REMOTE_SOUNDS.name, v) }

    var remoteSettingsKey: String
        get() = sp.read(PreferenceKeys.REMOTE_SETTINGS_KEY)!!
        set(v) = sp.edit { putString(PreferenceKeys.REMOTE_SETTINGS_KEY.name, v) }

    /** The tab last used in a collection folder. */
    fun folderSelection(profile: String, collection: String, folder: String): String? = sp.getString(selectionKey(profile, collection, folder), null)
    fun setFolderSelection(profile: String, collection: String, folder: String, source: String) = sp.edit { putString(selectionKey(profile, collection, folder), source) }
    private fun selectionKey(vararg parts: String) = "folderSource:" + AddonAccess.key(parts.joinToString("") { "${it.length}:$it" })

    /** Collections hidden on Home (their ids). */
    var collectionsOff: Set<String>
        get() = sp.read(PreferenceKeys.COLLECTIONS_OFF)
        set(v) = sp.edit { putStringSet(PreferenceKeys.COLLECTIONS_OFF.name, v) }

    /** Collections shown on the Discover page (their ids). */
    var collectionsDiscover: Set<String>
        get() = sp.read(PreferenceKeys.COLLECTIONS_DISCOVER)
        set(v) = sp.edit { putStringSet(PreferenceKeys.COLLECTIONS_DISCOVER.name, v) }

    /** Folders switched off ("collection id/folder id"). */
    var collectionFoldersOff: Set<String>
        get() = sp.read(PreferenceKeys.COLLECTION_FOLDERS_OFF)
        set(v) = sp.edit { putStringSet(PreferenceKeys.COLLECTION_FOLDERS_OFF.name, v) }

    var featuresOff: Set<String>
        get() = sp.read(PreferenceKeys.FEATURES_OFF)
        set(v) = sp.edit { putStringSet(PreferenceKeys.FEATURES_OFF.name, v) }

    /** Live TV page layout: "list" or "grid". */
    var iptvView: String
        get() = sp.read(PreferenceKeys.IPTV_VIEW)!!
        set(v) = sp.edit { putString(PreferenceKeys.IPTV_VIEW.name, v) }

    /** Names the user gave addons, by manifest link. */
    var addonNames: Map<String, String>
        get() = runCatching { json.decodeFromString<Map<String, String>>(sp.read(PreferenceKeys.ADDON_NAMES)!!) }.getOrDefault(emptyMap())
        set(v) = sp.edit { putString(PreferenceKeys.ADDON_NAMES.name, json.encodeToString(v)) }

    /** Test addons already added once, so removing one in Settings is not undone on the next start. */
    var seededTestAddons: Set<String>
        get() = sp.read(PreferenceKeys.SEEDED_TEST_ADDONS)
        set(v) = sp.edit { putStringSet(PreferenceKeys.SEEDED_TEST_ADDONS.name, v) }

    // ---- streaming services (Watchmode) ----
    /** Two-letter region for "Where to watch"; defaults to the TV's country. */
    var watchRegion: String
        get() = sp.read(PreferenceKeys.WATCH_REGION) ?: "US"
        set(v) = sp.edit { putString(PreferenceKeys.WATCH_REGION.name, v.uppercase()) }

    /** Where-to-watch source: "off" (hidden everywhere), "watchmode" or "tmdb" (JustWatch data). */
    var watchProvider: String
        get() = sp.read(PreferenceKeys.WATCH_PROVIDER)!!
        set(v) = sp.edit { putString(PreferenceKeys.WATCH_PROVIDER.name, v) }

    /** Provider and region the saved service list belongs to, e.g. "tmdb:US". */
    var servicesSource: String
        get() = sp.read(PreferenceKeys.SERVICES_SOURCE)!!
        set(v) = sp.edit { putString(PreferenceKeys.SERVICES_SOURCE.name, v) }

    /** Services switched on by hand (installed apps are on unless switched off). */
    var servicesOn: Set<String>
        get() = sp.read(PreferenceKeys.SERVICES_ON)
        set(v) = sp.edit { putStringSet(PreferenceKeys.SERVICES_ON.name, v) }

    var servicesOff: Set<String>
        get() = sp.read(PreferenceKeys.SERVICES_OFF)
        set(v) = sp.edit { putStringSet(PreferenceKeys.SERVICES_OFF.name, v) }

    /** Watchmode's service list, saved so it is fetched at most once a week. */
    var servicesJson: String?
        get() = sp.read(PreferenceKeys.SERVICES_JSON)
        set(v) = sp.edit { putString(PreferenceKeys.SERVICES_JSON.name, v) }

    var servicesFetchedAt: Long
        get() = sp.read(PreferenceKeys.SERVICES_FETCHED_AT)
        set(v) = sp.edit { putLong(PreferenceKeys.SERVICES_FETCHED_AT.name, v) }

    /** Tracker ids the user switched off for Home. */
    var trackersOff: Set<String>
        get() = sp.read(PreferenceKeys.TRACKERS_OFF)
        set(v) = sp.edit { putStringSet(PreferenceKeys.TRACKERS_OFF.name, v) }

    /** "builtin", "ask", or the package name of an installed player. */
    var player: String
        get() = sp.read(PreferenceKeys.PLAYER)!!
        set(v) = sp.edit { putString(PreferenceKeys.PLAYER.name, v) }

    /** Turn on a subtitle in the TV's language when a title starts. */
    var subtitlesOn: Boolean
        get() = sp.read(PreferenceKeys.SUBTITLES_ON)
        set(v) = sp.edit { putBoolean(PreferenceKeys.SUBTITLES_ON.name, v) }

    /** Title screens: server files and addon sources in one list (true) or in two groups. */
    var sourcesCombined: Boolean
        get() = sp.read(PreferenceKeys.SOURCES_COMBINED)
        set(v) = sp.edit { putBoolean(PreferenceKeys.SOURCES_COMBINED.name, v) }

    // ---- Stremio stream result format ----
    /** Show addon sources with the name and description templates instead of the built-in layout. */
    /** Source badges: "legacy" = Basic (default), "modern" = bundled Detailed images, or "custom" (data/Badges.kt). */
    var badgeMode: String
        get() = sp.read(PreferenceKeys.BADGE_MODE)!!
        set(v) = sp.edit { putString(PreferenceKeys.BADGE_MODE.name, v) }

    var detailedBadgeCategoriesOff: Set<String>
        get() = sp.read(PreferenceKeys.DETAILED_BADGE_CATEGORIES_OFF)
        set(v) = sp.edit { putStringSet(PreferenceKeys.DETAILED_BADGE_CATEGORIES_OFF.name, v.toSet()) }

    var streamFormatOn: Boolean
        get() = sp.read(PreferenceKeys.STREAM_FORMAT_ON)
        set(v) = sp.edit { putBoolean(PreferenceKeys.STREAM_FORMAT_ON.name, v) }

    /** Saved templates; null means the built-in default. */
    var streamNameTemplate: String?
        get() = sp.read(PreferenceKeys.STREAM_NAME_TEMPLATE)
        set(v) = sp.edit { if (v == null) remove(PreferenceKeys.STREAM_NAME_TEMPLATE.name) else putString(PreferenceKeys.STREAM_NAME_TEMPLATE.name, v) }

    var streamDescTemplate: String?
        get() = sp.read(PreferenceKeys.STREAM_DESC_TEMPLATE)
        set(v) = sp.edit { if (v == null) remove(PreferenceKeys.STREAM_DESC_TEMPLATE.name) else putString(PreferenceKeys.STREAM_DESC_TEMPLATE.name, v) }

    /** Play the next episode when one ends. */
    var autoplay: Boolean
        get() = sp.read(PreferenceKeys.AUTOPLAY)
        set(v) = sp.edit { putBoolean(PreferenceKeys.AUTOPLAY.name, v) }

    /** Seconds the "Up next" countdown waits before playing. */
    var countdown: Int
        get() = sp.read(PreferenceKeys.COUNTDOWN)
        set(v) = sp.edit { putInt(PreferenceKeys.COUNTDOWN.name, v) }

    /** Home rows (SmartRows ids) the user switched off. */
    var smartRowsOff: Set<String>
        get() = sp.read(PreferenceKeys.SMART_ROWS_OFF)
        set(v) = sp.edit { putStringSet(PreferenceKeys.SMART_ROWS_OFF.name, v) }

    /** Saved weather place as "name|lat|lon", or null when weather is off. */
    var weatherProvider:String
        get()=sp.read(PreferenceKeys.WEATHER_PROVIDER) ?: "openmeteo"
        set(value)=sp.edit {putString(PreferenceKeys.WEATHER_PROVIDER.name,value)}
    var weatherFallback:Boolean
        get()=sp.read(PreferenceKeys.WEATHER_FALLBACK)
        set(value)=sp.edit {putBoolean(PreferenceKeys.WEATHER_FALLBACK.name,value)}
    var weatherCache:String?
        get()=sp.read(PreferenceKeys.WEATHER_CACHE)
        set(value)=sp.edit {putString(PreferenceKeys.WEATHER_CACHE.name,value)}

    var weatherPlace: String?
        get() = sp.read(PreferenceKeys.WEATHER_PLACE)
        set(v) = sp.edit { putString(PreferenceKeys.WEATHER_PLACE.name, v) }

    var fahrenheit: Boolean
        get() = sp.read(PreferenceKeys.FAHRENHEIT)
        set(v) = sp.edit { putBoolean(PreferenceKeys.FAHRENHEIT.name, v) }

    /**
     * Top menu: tab ids in order, each with whether it is shown. Tabs added in later versions
     * are appended with their default, and locked tabs are always shown.
     */
    val hasSavedNav: Boolean get() = sp.contains(PreferenceKeys.NAV.name)

    var nav: List<Pair<String, Boolean>>
        get() {
            // New tabs adopt defaults below; an older saved/custom menu must survive migration.
            if (!sp.read(PreferenceKeys.NAV_DEFAULTS2)) sp.edit { putBoolean(PreferenceKeys.NAV_DEFAULTS2.name, true) }
            val saved = sp.read(PreferenceKeys.NAV)?.split(',')?.mapNotNull { entry ->
                val p = entry.split(':')
                if (p.size == 2 && NavTabs.known(p[0])) p[0] to (p[1] == "1" || NavTabs.locked(p[0])) else null
            }?.distinctBy { it.first } ?: emptyList()
            val missing = NavTabs.ALL.filter { t -> saved.none { it.first == t.id } }.map { it.id to it.shownByDefault }
            // Tabs added in an update go just before Settings, which stays last.
            val at = saved.indexOfFirst { it.first == NavTabs.SETTINGS }.takeIf { it >= 0 } ?: saved.size
            return saved.take(at) + missing + saved.drop(at)
        }
        set(v) = sp.edit { putString(PreferenceKeys.NAV.name, v.joinToString(",") { "${it.first}:${if (it.second) 1 else 0}" }); putBoolean(PreferenceKeys.NAV_DEFAULTS2.name,true) }

    /** Home sections in order, with on/off ("hero:1,apps:1,continue:0,..."). Empty: the default (banner and apps). */
    var homeLayout: List<Pair<String, Boolean>>
        get() = sp.read(PreferenceKeys.HOME_LAYOUT)!!.split(',').mapNotNull { e ->
            val i = e.lastIndexOf(':')
            if (i <= 0) null else e.substring(0, i) to (e.substring(i + 1) == "1")
        }
        set(v) = sp.edit { putString(PreferenceKeys.HOME_LAYOUT.name, v.joinToString(",") { "${it.first}:${if (it.second) 1 else 0}" }) }

    /** How In progress, Recently watched and the watchlist show on For You: "row" or "button", by section id. */
    var homeListStyle: Map<String, String>
        get() = sp.read(PreferenceKeys.HOME_LIST_STYLE)!!.split(',').mapNotNull { e ->
            val i = e.indexOf(':')
            if (i <= 0) null else e.substring(0, i) to e.substring(i + 1)
        }.toMap()
        set(v) = sp.edit { putString(PreferenceKeys.HOME_LIST_STYLE.name, v.entries.joinToString(",") { "${it.key}:${it.value}" }) }

    var personalListSourcesOff: Set<String>
        get() = sp.read(PreferenceKeys.PERSONAL_LIST_SOURCES_OFF)
        set(v) = sp.edit { putStringSet(PreferenceKeys.PERSONAL_LIST_SOURCES_OFF.name, v) }

    /** One-time switch-on of the addon catalogs section (see HomeSectionsState). */
    /** One-time switch-on of In progress and the watchlist on For You, when they moved there from Library. */
    var listsOnHomeSet: Boolean
        get() = sp.read(PreferenceKeys.LISTS_ON_HOME_SET)
        set(v) = sp.edit { putBoolean(PreferenceKeys.LISTS_ON_HOME_SET.name, v) }

    var catalogsOnHomeFixed: Boolean
        get() = sp.read(PreferenceKeys.CATALOGS_ON_HOME_FIXED)
        set(v) = sp.edit { putBoolean(PreferenceKeys.CATALOGS_ON_HOME_FIXED.name, v) }

    /** Rows of apps on Home: 1 or 2. */
    var homeAppRows: Int
        get() = sp.read(PreferenceKeys.HOME_APP_ROWS)
        set(v) = sp.edit { putInt(PreferenceKeys.HOME_APP_ROWS.name, v) }

    /** Apps hidden from the Apps page (they can be shown again there). */
    var appsPageOff: Set<String>
        get() = sp.read(PreferenceKeys.APPS_PAGE_OFF)
        set(v) = sp.edit { putStringSet(PreferenceKeys.APPS_PAGE_OFF.name, v) }

    var heroOn: Boolean
        get() = sp.read(PreferenceKeys.HERO_ON)
        set(v) = sp.edit { putBoolean(PreferenceKeys.HERO_ON.name, v) }

    /** Modern looks: "night" (default), "day", or "auto" (day from 7 am to 7 pm). */
    var theme: String
        get() = sp.read(PreferenceKeys.THEME) ?: "night"
        set(v) = sp.edit { putString(PreferenceKeys.THEME.name, v) }

    /** True black at night for OLED screens. */
    var oled: Boolean
        get() = sp.read(PreferenceKeys.OLED)
        set(v) = sp.edit { putBoolean(PreferenceKeys.OLED.name, v) }

    /** Space kept clear at the screen edges, in percent ("0", "2.5" or "5"), for projectors and TVs that crop the picture. */
    var screenMargin: String
        get() {
            val stored=sp.read(PreferenceKeys.SCREEN_MARGIN)
            return ScreenMargins.normalize(stored).also {safe->
                if(stored!=safe)sp.edit { putString(PreferenceKeys.SCREEN_MARGIN.name,safe) }
            }
        }
        set(v) = sp.edit { putString(PreferenceKeys.SCREEN_MARGIN.name, ScreenMargins.normalize(v)) }

    /** Size of everything on screen: "xsmall", "small", "default", "large" or "xlarge" (ui/UiSizes.kt). */
    var uiSize: String
        get() = sp.read(PreferenceKeys.UI_SIZE) ?: "default"
        set(v) = sp.edit { putString(PreferenceKeys.UI_SIZE.name, v) }

    /** Text size on top of that: "xsmall" to "xlarge", as above. */
    var textSize: String
        get() = sp.read(PreferenceKeys.TEXT_SIZE) ?: "default"
        set(v) = sp.edit { putString(PreferenceKeys.TEXT_SIZE.name, v) }

    /** Updates from public main: "releases" (tagged versions, default) or "builds" (published main builds). */
    var updateChannel: String
        get() = (sp.read(PreferenceKeys.UPDATE_CHANNEL) ?: "releases").let { if (it == "beta") "builds" else it }
        set(v) = sp.edit { putString(PreferenceKeys.UPDATE_CHANNEL.name, v) }

    /** Beta updates on: check the beta branch's "beta build" instead of the stable channel above.
     * A saved "beta" channel (the earlier three-way choice) counts as on. */
    var updateBeta: Boolean
        get() = sp.read(PreferenceKeys.UPDATE_BETA, sp.read(PreferenceKeys.UPDATE_CHANNEL, null) == "beta")
        set(v) = sp.edit { putBoolean(PreferenceKeys.UPDATE_BETA.name, v) }

    /** The beta source survives channel changes, restarts and configuration backups. */
    var updateBetaSource: String
        get() = if (sp.read(PreferenceKeys.UPDATE_BETA_SOURCE) == "actions") "actions" else "releases"
        set(v) = sp.edit { putString(PreferenceKeys.UPDATE_BETA_SOURCE.name, if (v == "actions") "actions" else "releases") }

    /** Look for an update once a day when Ember starts. */
    var updateAutoCheck: Boolean
        get() = sp.read(PreferenceKeys.UPDATE_AUTO_CHECK)
        set(v) = sp.edit { putBoolean(PreferenceKeys.UPDATE_AUTO_CHECK.name, v) }

    var updateCheckedAt: Long
        get() = sp.read(PreferenceKeys.UPDATE_CHECKED_AT)
        set(v) = sp.edit { putLong(PreferenceKeys.UPDATE_CHECKED_AT.name, v) }

    /** "standard" (a full page, default) or "floating" (a panel over the screen, opened from the menu or the remote's Menu key). */
    var settingsStyle: String
        get() = sp.read(PreferenceKeys.SETTINGS_STYLE) ?: "standard"
        set(v) = sp.edit { putString(PreferenceKeys.SETTINGS_STYLE.name, v) }

    /** Modern look: menu along the "top" (default) or down the "side". */
    var menuPosition: String
        get() = sp.read(PreferenceKeys.MENU_POSITION) ?: "top"
        set(v) = sp.edit { putString(PreferenceKeys.MENU_POSITION.name, v) }

    /** Modern look: Your apps as "banner" (wide TV banners) or "circle" (default). */
    var appIcons: String
        get() = sp.read(PreferenceKeys.APP_ICONS) ?: "circle"
        set(v) = sp.edit { putString(PreferenceKeys.APP_ICONS.name, v) }

    var personalHomesJson: String
        get() = sp.read(PreferenceKeys.PERSONAL_HOMES) ?: "{}"
        set(value) = sp.edit { putString(PreferenceKeys.PERSONAL_HOMES.name, value) }
    var launcherExtrasJson: String
        get() = sp.read(PreferenceKeys.LAUNCHER_EXTRAS) ?: "{}"
        set(value) = sp.edit { putString(PreferenceKeys.LAUNCHER_EXTRAS.name, value) }

    var lightHome: LightHomeOptions
        get() = runCatching { json.decodeFromString<LightHomeOptions>(sp.read(PreferenceKeys.LIGHT_HOME) ?: "{}").normalized() }.getOrDefault(LightHomeOptions())
        set(value) = sp.edit { putString(PreferenceKeys.LIGHT_HOME.name, json.encodeToString(value.normalized())) }

    /** Light look's five backgrounds; older art/ember/black preferences retain their meaning. */
    var lightWallpaper: String
        get() = sp.read(PreferenceKeys.LIGHT_WALLPAPER) ?: "art"
        set(v) = sp.edit { putString(PreferenceKeys.LIGHT_WALLPAPER.name, v) }

    /** One-time migration of the old full-interface colour choice, including restored backups. */
    private fun migrateEmberXInterface() {
        if (!sp.read(PreferenceKeys.EMBER_X_INTERFACE_MIGRATED)) sp.edit {
            if (sp.read(PreferenceKeys.ACCENT3) == ColorThemes.CINEMA_RED) putString(PreferenceKeys.UI_STYLE.name, InterfaceStyles.EMBER_X)
            putBoolean(PreferenceKeys.EMBER_X_INTERFACE_MIGRATED.name, true)
        }
    }

    /** Interface selection is independent of colour, menu and playback preferences. */
    var uiStyle: String
        get() {
            migrateEmberXInterface()
            val stored = sp.read(PreferenceKeys.UI_STYLE)
            return InterfaceStyles.normalize(stored).also { normalized ->
                if (stored != normalized) sp.edit { putString(PreferenceKeys.UI_STYLE.name, normalized) }
            }
        }
        set(v) = sp.edit {
            putString(PreferenceKeys.UI_STYLE.name, InterfaceStyles.normalize(v))
            putBoolean(PreferenceKeys.EMBER_X_INTERFACE_MIGRATED.name, true)
        }

    /** Modern look: "wide" 16:9 artwork cards (default) or "poster" cards on Home rows. */
    var homeHiddenTitles: Set<String>
        get() = sp.read(PreferenceKeys.HOME_HIDDEN_TITLES)
        set(v) = sp.edit { putStringSet(PreferenceKeys.HOME_HIDDEN_TITLES.name, v) }

    var contentDensity: String
        get() = sp.read(PreferenceKeys.CONTENT_DENSITY) ?: "standard"
        set(v) = sp.edit { putString(PreferenceKeys.CONTENT_DENSITY.name, v) }
    var devicePerformance:String
        get()=DevicePerformance.normalize(sp.read(PreferenceKeys.DEVICE_PERFORMANCE))
        set(v)=sp.edit { putString(PreferenceKeys.DEVICE_PERFORMANCE.name,DevicePerformance.normalize(v)) }
    var customPerformance:CustomPerformance
        get()=runCatching {json.decodeFromString<CustomPerformance>(sp.read(PreferenceKeys.CUSTOM_PERFORMANCE)!!).normalized()}.getOrDefault(CustomPerformance())
        set(value)=sp.edit {putString(PreferenceKeys.CUSTOM_PERFORMANCE.name,json.encodeToString(value.normalized()))}
    var setupChecklistHidden: Boolean
        get()=sp.read(PreferenceKeys.SETUP_CHECKLIST_HIDDEN)
        set(value)=sp.edit { putBoolean(PreferenceKeys.SETUP_CHECKLIST_HIDDEN.name,value) }
    var navigationGlassMethod: String
        get()=GlassStyle.normalize(sp.read(PreferenceKeys.NAVIGATION_GLASS_METHOD))
        set(value)=sp.edit { putString(PreferenceKeys.NAVIGATION_GLASS_METHOD.name,GlassStyle.normalize(value)) }
    var navigationGlass: Boolean
        get() = sp.read(PreferenceKeys.NAVIGATION_GLASS)
        set(v) = sp.edit { putBoolean(PreferenceKeys.NAVIGATION_GLASS.name, v) }
    var artworkQuality: String
        get() = sp.read(PreferenceKeys.ARTWORK_QUALITY) ?: "balanced"
        set(v) = sp.edit { putString(PreferenceKeys.ARTWORK_QUALITY.name, v) }

    var modernCards: String
        get() = sp.read(PreferenceKeys.MODERN_CARDS) ?: "wide"
        set(v) = sp.edit { putString(PreferenceKeys.MODERN_CARDS.name, v) }

    var accent: Int
        // Accents are Ember, Shadow Ember, Blue ember, Green flame, Rose ember, Modern Ember. Older choices map to the
        // nearest: Sky -> Blue ember, Mint -> Green flame, Rose -> Rose ember, Amber -> Ember.
        get() {
            migrateEmberXInterface()
            return when {
            sp.contains(PreferenceKeys.ACCENT3.name) -> sp.read(PreferenceKeys.ACCENT3)
            sp.contains(PreferenceKeys.ACCENT2.name) -> when (sp.read(PreferenceKeys.ACCENT2)) { 1 -> 1; 3 -> 3; 4 -> 2; 5 -> 4; else -> 0 }
            else -> when (sp.read(PreferenceKeys.ACCENT)) { 1 -> 3; 2 -> 2; 3 -> 4; else -> 0 }
        }
        }
        set(v) = sp.edit { putInt(PreferenceKeys.ACCENT3.name, ColorThemes.normalize(v)); putBoolean(PreferenceKeys.EMBER_X_INTERFACE_MIGRATED.name, true) }

    var watchlist: List<Meta>
        get() = runCatching { json.decodeFromString<List<Meta>>(sp.read(PreferenceKeys.WL)!!) }
            .getOrDefault(emptyList())
        set(v) = sp.edit { putString(PreferenceKeys.WL.name, json.encodeToString(v)) }

    companion object {
        // Public Cinemeta addon, the catalog source Stremio ships with.
        const val DEFAULT_ADDON = "https://v3-cinemeta.strem.io/manifest.json"
        const val PLAYER_BUILTIN = "builtin"
        const val PLAYER_ASK = "ask"
    }
}

/** Whole features that can be switched off so Ember starts faster (Settings > Features). */
object Features {
    const val LIVE = "live"
    const val SERVERS = "servers"
    const val TRACKERS = "trackers"
    const val SMART_ROWS = "smartRows"
    const val CAST = "cast"
}
