package app.ember.tv.state

import androidx.compose.runtime.*
import app.ember.tv.data.CatalogRow
import app.ember.tv.data.Prefs
import app.ember.tv.discover.*
import kotlinx.coroutines.*

enum class DiscoverSection { EXPLORE, CATALOGS, COLLECTIONS }

/** A separate state owner keeps add-on catalog paging and Explore queries independent. */
class ExploreState(
    private val scope: CoroutineScope,
    private val source: ExploreSource,
    private val prefs: Prefs,
    private val profileId: () -> String,
    private val allowed: () -> Boolean,
    private val region: () -> String,
    private val onPinsChanged: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var section by mutableStateOf(DiscoverSection.EXPLORE)
    var addonMode: Boolean
        get() = section == DiscoverSection.CATALOGS
        set(value) { section = if (value) DiscoverSection.CATALOGS else DiscoverSection.EXPLORE }
    var query by mutableStateOf(ExploreQuery()); private set
    var results by mutableStateOf<List<ExploreTitle>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var complete by mutableStateOf(false); private set
    var pagingLimit by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var saved by mutableStateOf<List<SavedExplore>>(emptyList()); private set
    var homeRows by mutableStateOf<List<CatalogRow>>(emptyList()); private set
    var homeError by mutableStateOf<String?>(null); private set
    var revision by mutableIntStateOf(0); private set
    val configured get() = source.configured
    var pagesChecked by mutableIntStateOf(0); private set
    private var owner: String? = null
    private var session: ExploreSession? = null
    private var sessionRegion: String? = null
    private var job: Job? = null
    private var homeJob: Job? = null
    private var homeKey: String? = null
    private var homeLoadedAt = 0L
    private var homeRegion: String? = null
    private var homeQueries: Map<String, ExploreQuery> = emptyMap()
    private var generation = 0

    private fun ensureOwner(): Boolean {
        if (!allowed()) { reset(); return false }
        val id = profileId()
        if (owner != id) { reset(); owner = id; saved = prefs.explorePresets(id).filter { it.query.error() == null }.take(24) }
        return true
    }
    fun open() {
        if (!ensureOwner()) return
        if (session == null || sessionRegion != region()) apply(query)
    }
    fun apply(value: ExploreQuery) {
        if (!ensureOwner()) return
        value.error()?.let { error = it; return }
        job?.cancel()
        generation++
        query = value
        results = emptyList(); error = null; complete = false; loading = false; pagingLimit = false
        revision++; pagesChecked = 0
        sessionRegion = region()
        session = ExploreSession(value, region(), source)
        more()
    }
    fun more() {
        if (!ensureOwner() || loading) return
        val current = session ?: return
        val version = generation
        val id = owner
        loading = true; error = null
        job = scope.launch {
            try {
                do {
                    current.advance(budget = 4) { items ->
                        ensureActive()
                        if (generation == version && profileId() == id && allowed()) {
                            results = items
                            pagesChecked = current.pagesChecked
                        }
                    }
                    ensureActive()
                    if (!current.complete) delay(250)
                } while (!current.complete)
                ensureActive()
                if (generation == version && profileId() == id && allowed()) {
                    results = current.results; complete = current.complete; pagingLimit = current.limitReached
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (generation == version && profileId() == id && allowed()) error =
                    if (!source.configured) "Add a TMDB API key in Settings > API keys to use Explore."
                    else if (query.oscars != OscarMode.ANY) "Could not verify Oscar records or load matching titles. Retry keeps your filters and loaded results."
                    else "Could not load matching titles. Check the connection or TMDB key, then retry."
            } finally { if (generation == version) loading = false }
        }
    }
    fun refresh() {
        if (!ensureOwner()) return
        pause()
        homeJob?.cancel(); homeKey = null
        val version = generation; val id = owner; val value = query
        loading = true; error = null
        job = scope.launch {
            try {
                source.invalidate()
                ensureActive()
                if (generation == version && profileId() == id && allowed()) apply(value)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (generation == version) error = "Could not refresh discovery data. Retry your search." }
            finally { if (generation == version) loading = false }
        }
    }
    fun background() { pause(); homeJob?.cancel(); homeKey = null }
    fun pause() {
        generation++
        job?.cancel()
        loading = false
    }
    fun save(name: String): String? {
        if (!ensureOwner()) return "Explore is unavailable for this profile."
        val label = name.trim().take(80)
        if (label.isBlank()) return "Enter a name for this search."
        val existing = saved.firstOrNull { it.name.equals(label, true) }
        if (existing == null && saved.size >= 24) return "You can save up to 24 searches. Remove an old one first."
        val preset = existing?.copy(query = query) ?: SavedExplore(java.util.UUID.randomUUID().toString(), label, query)
        updateSaved(saved.filterNot { it.id == preset.id } + preset)
        return null
    }
    fun load(id: String) { if (ensureOwner()) saved.firstOrNull { it.id == id }?.let { addonMode = false; apply(it.query) } }
    fun delete(id: String) {
        if (!ensureOwner()) return
        updateSaved(saved.filterNot { it.id == id })
        prefs.homeLayout = prefs.homeLayout.filterNot { it.first == "explore:$id" }
        onPinsChanged()
    }
    fun toggleHome(id: String) {
        if (!ensureOwner()) return
        updateSaved(saved.map { if (it.id == id) it.copy(home = !it.home) else it })
        prefs.homeLayout = prefs.homeLayout.filterNot { it.first == "explore:$id" }
        onPinsChanged()
    }
    private fun updateSaved(value: List<SavedExplore>) {
        saved = value
        prefs.setExplorePresets(owner ?: return, value)
        homeKey = null
        homeJob?.cancel()
        homeRows = homeRows.filter { row -> value.any { it.home && it.section == row.catalogKey } }
    }
    fun refreshHome(visible: (String) -> Boolean) {
        if (!ensureOwner()) return
        val pins = saved.filter { it.home && visible(it.section) }
        val signature = "${owner}:${region()}:$pins"
        if (homeKey == signature && (homeJob?.isActive == true || clock() - homeLoadedAt < 15 * 60_000L)) return
        homeJob?.cancel(); homeKey = signature; homeError = null
        val country = region()
        homeRows = homeRows.filter { row -> homeRegion == country && pins.any {
            it.section == row.catalogKey && homeQueries[it.section] == it.query
        } }
        homeRegion = country
        val id = owner
        homeJob = scope.launch {
            val rows = homeRows.associateBy { it.catalogKey }.toMutableMap()
            var failed = false
            for (pin in pins) {
                try {
                    val search = ExploreSession(pin.query, country, source, limit = 20)
                    search.advance(budget = 4)
                    ensureActive()
                    if (search.results.isNotEmpty()) rows[pin.section] = CatalogRow("Saved Discover · $country", pin.name,
                        search.results.map { it.meta }, catalogKey = pin.section)
                    else rows.remove(pin.section)
                    homeQueries = homeQueries + (pin.section to pin.query)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    failed = true
                    if (owner == id) homeError = "A saved Discover row could not refresh. Saved titles remain available; open Discover to retry."
                }
                ensureActive()
                if (owner == id && profileId() == id && allowed() && homeKey == signature) homeRows = pins.mapNotNull { rows[it.section] }
            }
            if (owner == id && homeKey == signature) {
                homeRows = pins.mapNotNull { rows[it.section] }
                if (failed) homeKey = null else homeLoadedAt = clock()
            }
        }
    }
    fun reset() {
        generation++
        job?.cancel(); homeJob?.cancel()
        owner = null; session = null; sessionRegion = null; homeKey = null
        homeRegion = null; homeQueries = emptyMap()
        results = emptyList(); saved = emptyList(); homeRows = emptyList(); homeError = null
        query = ExploreQuery(); loading = false; complete = false; error = null; addonMode = false; pagesChecked = 0; pagingLimit = false
        revision++
    }
}
