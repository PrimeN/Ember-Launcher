package app.ember.tv.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.HomeViewModel.Companion.HOME_APPS
import app.ember.tv.HomeViewModel.Companion.HOME_CATALOGS
import app.ember.tv.HomeViewModel.Companion.HOME_WATCH_BUTTON
import app.ember.tv.HomeViewModel.Companion.HOME_CONTINUE
import app.ember.tv.HomeViewModel.Companion.HOME_HERO
import app.ember.tv.HomeViewModel.Companion.HOME_RECENT
import app.ember.tv.HomeViewModel.Companion.HOME_SERVERS
import app.ember.tv.HomeViewModel.Companion.HOME_UPNEXT
import app.ember.tv.HomeViewModel.Companion.HOME_WATCHLIST
import app.ember.tv.data.Prefs
import app.ember.tv.tracker.SmartRows

/** Settings > Home layout: which Home sections show, and in what order. Split out of HomeViewModel. */
class HomeSectionsState(
    private val prefs: Prefs,
    private val onSmartRowsChanged: () -> Unit,
    private val onChanged: () -> Unit,
    private val catalogSections: () -> List<Pair<SectionInfo, Boolean>> = { emptyList() },
    private val onCatalogVisibilityChanged: (Map<String, Boolean>) -> Unit = {},
    private val extraSections: () -> List<Pair<SectionInfo, Boolean>> = { emptyList() },
) {
    data class SectionInfo(val id: String, val label: String, val note: String)

    /** Every Home section, in the default order. The banner, apps and your addon catalogs are on at first. */
    private val baseSectionInfo: List<SectionInfo>
        get() = buildList {
            add(SectionInfo(HOME_HERO, "Banner", "The large rotating picture at the top, from your Home catalogs."))
            add(SectionInfo(HOME_APPS, "Your apps", "Apps, filling the row; a second row in Apps on Home below."))
            add(SectionInfo(HOME_CONTINUE, "In progress", "Part-way through: played in Ember, on your trackers and on your media servers."))
            add(SectionInfo(HOME_RECENT, "Recently watched", "Played in Ember, and your Trakt history."))
            add(SectionInfo(HOME_WATCHLIST, "Your watchlist", "Saved on this TV and on your trackers."))
            add(SectionInfo(smartSection(SmartRows.UP_NEXT), "Up next", "The next episode of shows you watch (Trakt)."))
            add(SectionInfo(HOME_SERVERS, "Your media servers", "Continue and recently added on Plex, Jellyfin or Emby."))
            SmartRows.ALL.filter { it.first != SmartRows.UP_NEXT }.forEach { (id, label) -> add(SectionInfo(smartSection(id), label, "Extra row.")) }
            addAll(extraSections().map { it.first })
            add(SectionInfo(HOME_CATALOGS, "Addon catalogs", "The catalogs switched on for Home in Settings > Addons & catalogs, in that order."))
        }

    val sectionInfo: List<SectionInfo>
        get() = baseSectionInfo.flatMap { if (it.id == HOME_CATALOGS) catalogSections().map { entry -> entry.first } else listOf(it) }

    fun smartSection(id: String) = if (id == SmartRows.UP_NEXT) HOME_UPNEXT else "smart:$id"

    var homeLayout by mutableStateOf(prefs.homeLayout); private set

    /** In progress, Recently watched and the watchlist: a row of titles, or a button opening their page. */
    var listStyles by mutableStateOf(prefs.homeListStyle); private set
    var listSourcesOff by mutableStateOf(prefs.personalListSourcesOff); private set
    fun sourceEnabled(profile: String, list: String, source: String) =
        app.ember.tv.data.PersonalListSources.enabled(listSourcesOff, profile, list, source)
    fun toggleSource(profile: String, list: String, source: String) {
        val key = app.ember.tv.data.PersonalListSources.key(profile, list, source)
        listSourcesOff = if (key in listSourcesOff) listSourcesOff - key else listSourcesOff + key
        prefs.personalListSourcesOff = listSourcesOff
        onChanged()
    }

    fun reloadHome() {homeLayout=prefs.homeLayout;listStyles=prefs.homeListStyle}

    fun listStyle(id: String): String = listStyles[id] ?: if (id == HOME_CONTINUE) "row" else "button"
    fun isList(id: String) = id == HOME_CONTINUE || id == HOME_RECENT || id == HOME_WATCHLIST

    fun toggleListStyle(id: String) {
        listStyles = listStyles + (id to if (listStyle(id) == "row") "button" else "row")
        prefs.homeListStyle = listStyles
        onChanged()
    }

    init {
        // In progress and the watchlist moved from Library to For You: show them there once, as they
        // were always visible in Library (each can still be switched off).
        if (!prefs.listsOnHomeSet) {
            prefs.listsOnHomeSet = true
            if (homeLayout.isNotEmpty()) {
                homeLayout = homeLayout.map { if (it.first == HOME_CONTINUE || it.first == HOME_WATCHLIST) it.first to true else it }
                prefs.homeLayout = homeLayout
            }
        }
        // The old "Watchlist button" section became the watchlist shown as a button.
        homeLayout.firstOrNull { it.first == HOME_WATCH_BUTTON }?.let { (_, on) ->
            homeLayout = homeLayout.filter { it.first != HOME_WATCH_BUTTON }
                .map { if (it.first == HOME_WATCHLIST && on) it.first to true else it }
                .let { l -> if (on && l.none { it.first == HOME_WATCHLIST }) l + (HOME_WATCHLIST to true) else l }
            prefs.homeLayout = homeLayout
            if (on) {
                listStyles = listStyles + (HOME_WATCHLIST to "button")
                prefs.homeListStyle = listStyles
            }
        }
        // Until build 105 the addon catalogs showed on Home whatever this switch said, so a saved
        // "off" was never chosen on purpose: switch it on once, as the rows were before.
        if (!prefs.catalogsOnHomeFixed) {
            prefs.catalogsOnHomeFixed = true
            if (homeLayout.any { it.first == HOME_CATALOGS && !it.second }) {
                homeLayout = homeLayout.map { if (it.first == HOME_CATALOGS) it.first to true else it }
                prefs.homeLayout = homeLayout
            }
        }
    }

    /** The saved order and switches, with any section not saved yet added at its default place. */
    val layout: List<Pair<String, Boolean>>
        get() {
            return catalogLayout(baseSectionInfo.map { it.id }, homeLayout,
                catalogSections().associate { it.first.id to it.second },
                setOf(HOME_HERO, HOME_APPS, HOME_CONTINUE, HOME_RECENT, HOME_WATCHLIST) + extraSections().filter { it.second }.map { it.first.id })
        }

    /** Materialize the old bulk switch before changing the layout, including an intentional Off. */
    private fun materializeLayout() {
        val effective = layout
        if (homeLayout.any { it.first == HOME_CATALOGS && !it.second }) {
            prefs.catalogHomeDefaultsOff = true
            onCatalogVisibilityChanged(catalogSections().associate { it.first.id to false })
        }
        saveLayout(effective)
    }

    fun migrateCatalogLayout() {
        if (homeLayout.any { it.first == HOME_CATALOGS } && catalogSections().isNotEmpty()) materializeLayout()
    }

    private fun saveLayout(value: List<Pair<String, Boolean>>) {
        homeLayout = retainUnavailable(value, homeLayout)
        prefs.homeLayout = homeLayout
    }

    fun reloadLayout() { homeLayout = prefs.homeLayout }

    fun sectionOn(id: String): Boolean = layout.firstOrNull { it.first == id }?.second ?: false

    fun toggleSection(id: String) {
        val on = !sectionOn(id)
        materializeLayout()
        if (isCatalogSection(id)) onCatalogVisibilityChanged(mapOf(id to on))
        saveLayout(layout.map { if (it.first == id) it.first to on else it })
        if (id.startsWith("smart:") || id == HOME_UPNEXT) onSmartRowsChanged()
        onChanged()
    }

    fun moveSection(id: String, delta: Int) {
        materializeLayout()
        val list = layout.toMutableList()
        val i = list.indexOfFirst { it.first == id }
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) return
        list.add(j, list.removeAt(i))
        saveLayout(list)
        onChanged()
    }

    fun resetLayout() {
        materializeLayout()
        homeLayout = emptyList()
        prefs.homeLayout = emptyList()
        onSmartRowsChanged()
    }

    companion object {
        // Home layout uses commas/colons as delimiters; addon keys can contain both.
        fun catalogSection(key: String) = "catalog:" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(key.toByteArray(Charsets.UTF_8))
        fun isCatalogSection(id: String) = id.startsWith("catalog:")
        fun catalogKey(id: String) = String(java.util.Base64.getUrlDecoder().decode(id.removePrefix("catalog:")), Charsets.UTF_8)

        /** Expand the old catalog slot while keeping saved positions of individual catalogs. */
        fun catalogLayout(
            base: List<String>, saved: List<Pair<String, Boolean>>,
            catalogs: Map<String, Boolean>, defaults: Set<String>,
        ): List<Pair<String, Boolean>> {
            val known = base.flatMap { if (it == HOME_CATALOGS) catalogs.keys.toList() else listOf(it) }
            val bulkOff = saved.any { it.first == HOME_CATALOGS && !it.second }
            val expanded = saved.flatMap { entry ->
                if (entry.first == HOME_CATALOGS) catalogs.map { it.key to (entry.second && it.value) } else listOf(entry)
            }.distinctBy { it.first }
            return merge(known, expanded, defaults + catalogs.filterValues { it }.keys).map { entry ->
                if (entry.first in catalogs) entry.first to (catalogs.getValue(entry.first) && !bulkOff) else entry
            }
        }

        /** Offline/uninstalled catalogs keep their saved position if another row is moved. */
        fun retainUnavailable(current: List<Pair<String, Boolean>>, previous: List<Pair<String, Boolean>>): List<Pair<String, Boolean>> {
            val out = current.toMutableList()
            previous.forEachIndexed { index, entry ->
                val unloadedBulk = entry.first == HOME_CATALOGS && current.none { isCatalogSection(it.first) }
                if ((!isCatalogSection(entry.first) && !unloadedBulk) || out.any { it.first == entry.first }) return@forEachIndexed
                val before = previous.take(index).lastOrNull { old -> out.any { it.first == old.first } }
                val at = before?.let { old -> out.indexOfFirst { it.first == old.first } + 1 } ?: 0
                out.add(at, entry)
            }
            return out
        }
        /**
         * The saved sections with any section missing from them (new in an update) put after the
         * section before it in the default order [known], on when it is in [onByDefault].
         */
        fun merge(known: List<String>, saved: List<Pair<String, Boolean>>, onByDefault: Set<String>): List<Pair<String, Boolean>> {
            val out = saved.filter { it.first in known }.toMutableList()
            known.forEachIndexed { i, id ->
                if (out.any { it.first == id }) return@forEachIndexed
                val before = known.take(i).lastOrNull { k -> out.any { it.first == k } }
                val at = before?.let { b -> out.indexOfFirst { it.first == b } + 1 } ?: 0
                out.add(at, id to (id in onByDefault))
            }
            return out
        }
    }
}
