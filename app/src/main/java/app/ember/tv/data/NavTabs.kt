package app.ember.tv.data

/** One entry in the top menu. Locked tabs cannot be hidden, so Home and Settings stay reachable. */
data class NavTab(val id: String, val label: String, val shownByDefault: Boolean, val locked: Boolean = false)

object NavTabs {
    const val HOME = "home"
    const val WATCHLIST = "watchlist"
    const val DISCOVER = "discover"
    const val LIBRARY = "library"
    const val APPS = "apps"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    /** IPTV and catalog rows chosen for Live TV. */
    const val LIVE = "live"
    /** Pages opened from Home's buttons (not in the menu): In progress and Recently watched. */
    const val PROGRESS = "progress"
    const val RECENT = "recent"

    /**
     * Default order: Home, Discover, Live, Apps, Library (Search and Settings are buttons).
     * Watchlist is a button on Home and a section of Library, so its tab starts off. My media
     * (your server libraries) is now part of Library. Live and Library start hidden
     * and are enabled when the corresponding sources are added.
     */
    val ALL = listOf(
        NavTab(HOME, "Home", shownByDefault = true, locked = true),
        NavTab(DISCOVER, "Discover", shownByDefault = true),
        NavTab(LIVE, "Live", shownByDefault = false),
        NavTab(APPS, "Apps", shownByDefault = true),
        NavTab(LIBRARY, "Library", shownByDefault = false),
        NavTab(WATCHLIST, "Watchlist", shownByDefault = false),
        NavTab(SEARCH, "Search", shownByDefault = true),
        NavTab(SETTINGS, "Settings", shownByDefault = true, locked = true),
    )

    private val byId = ALL.associateBy { it.id }

    /** Keep the user's order and other visibility choices when a source is added. */
    fun enable(tabs: List<Pair<String, Boolean>>, id: String): List<Pair<String, Boolean>> =
        tabs.map { if (it.first == id) id to true else it }

    /** Defaults apply to empty installations; an upgrade with sources keeps their pages reachable. */
    fun sourceDefaults(tabs: List<Pair<String, Boolean>>, saved: Boolean, live: Boolean, servers: Boolean) =
        if (saved) tabs else tabs.map { (id, on) -> id to (on || id == LIVE && live || id == LIBRARY && servers) }

    /** Not a page: opens the Quick settings panel (inputs, sleep timer, TV settings). */
    const val QUICK = "quick"
    /** Action button beside navigation; not a persistent page tab. */
    const val SCREENSAVER = "screensaver"

    fun label(id: String) = if (id == QUICK) "Quick settings" else byId[id]?.label ?: id
    fun locked(id: String) = byId[id]?.locked == true
    fun known(id: String) = id in byId
}
