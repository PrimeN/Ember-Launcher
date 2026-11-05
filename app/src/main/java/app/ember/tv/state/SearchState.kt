package app.ember.tv.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.AddonClient
import app.ember.tv.data.AppEntry
import app.ember.tv.data.CatalogDef
import app.ember.tv.data.CatalogRow
import app.ember.tv.data.Meta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Search: addons that can search, your apps and your watchlist. Split out of HomeViewModel.
 * [apps] and [saved] are what can match at once; [searchable] gives the addon catalogs that search.
 */
data class SearchProvider(val id: String, val name: String, val load: suspend () -> List<CatalogDef>)

class SearchState(
    private val scope: CoroutineScope,
    private val client: AddonClient,
    private val apps: () -> List<AppEntry>,
    private val saved: () -> List<Meta>,
    private val searchable: suspend () -> List<CatalogDef>,
) {
    var searchQuery by mutableStateOf(""); private set
    var searchRows by mutableStateOf<List<CatalogRow>>(emptyList()); private set
    var searchApps by mutableStateOf<List<AppEntry>>(emptyList()); private set
    var searchSaved by mutableStateOf<List<Meta>>(emptyList()); private set
    var searching by mutableStateOf(false); private set
    var searchNote by mutableStateOf<String?>(null); private set
    private var searchJob: Job? = null
    val health = app.ember.tv.data.SourceHealth().apply {diagnosticGroup="search"}
    private var definitions: List<CatalogDef> = emptyList()
    private var usableCatalogs = emptySet<String>()
    var sourceUnavailable by mutableStateOf(false); private set
    internal var browse: suspend (CatalogDef, String) -> List<Meta> = { def, term -> client.browse(def, search = term) }
    internal var filterItems: suspend (List<Meta>) -> List<Meta> = {it}
    /** Each manifest can become searchable independently; null retains the simple loader API. */
    var providers: (() -> List<SearchProvider>)? = null
    private var generation = 0L
    fun retry(id: String) {
        val running = searchJob
        val expected = generation
        val query = searchQuery
        if (searching && running?.isActive == true) scope.launch {
            running.join()
            if (expected == generation) setQuery(query, immediate = true, only = id)
        } else setQuery(query, immediate = true, only = id)
    }

    /** Set when the remote's search key asks for voice input; the Search page clears it when it starts. */
    var voicePending by mutableStateOf(false); private set
    fun requestVoice() { voicePending = true }
    fun voiceHandled() { voicePending = false }

    /** Drop results and in-flight work when the active profile changes. */
    fun reset() {
        voicePending = false
        setQuery("")
    }

    /**
     * Apps and watchlist matches show at once; addon results follow after a short pause in
     * typing ([immediate] skips the pause, e.g. for voice input).
     */
    fun setQuery(q: String, immediate: Boolean = false, only: String? = null) {
        generation++
        val changed = q.trim() != searchQuery.trim()
        searchQuery = q
        searchJob?.cancel()
        health.cancelLoading()
        val term = q.trim()
        if (changed || term.length < 2) {
            searchRows = emptyList()
            definitions = emptyList()
            health.retain(emptySet())
            usableCatalogs = emptySet()
        }
        if (term.length < 2) {
            searchRows = emptyList()
            searchApps = emptyList()
            searchSaved = emptyList()
            searchNote = null
            searching = false
            sourceUnavailable = false
            return
        }
        searchApps = apps().filter { it.label.contains(term, ignoreCase = true) }.sortedBy { app.ember.tv.data.SearchPresentation.rank(it.label, term) }
        searchSaved = saved().filter { it.name.contains(term, ignoreCase = true) }.sortedBy { app.ember.tv.data.SearchPresentation.rank(it.name, term) }
        searching = true
        searchNote = null
        sourceUnavailable = false
        if (only == null) usableCatalogs = emptySet()
        val request=generation
        searchJob = scope.launch {
          try {
            if (!immediate) delay(500)
            val permits = Semaphore(4)
            val completed = searchRows.associateBy { it.catalogKey }.toMutableMap()
            fun publish() { searchRows = definitions.mapNotNull { completed[it.key] } }
            suspend fun fetch(def: CatalogDef) = permits.withPermit {
                health.start(def.key, def.addon.name + " · " + def.label)
                val result = kotlinx.coroutines.withTimeoutOrNull(20_000L) { runCatching { browse(def, term) } }
                ensureActive()
                val filtered=runCatching {filterItems(if(result?.isSuccess==true)result.getOrThrow() else completed[def.key]?.items.orEmpty())}
                ensureActive()
                val success = result?.isSuccess == true && filtered.isSuccess
                usableCatalogs = if (success) usableCatalogs + def.key else usableCatalogs - def.key
                val items = filtered.getOrElse {completed[def.key]?.items.orEmpty()}
                health.finish(def.key, success, !success && items.isNotEmpty(), failure=if(result==null)app.ember.tv.data.SourceFailure.TIMEOUT else result.exceptionOrNull()?.let(app.ember.tv.data.SourceFailure::from),empty=success && items.isEmpty())
                if (items.isEmpty()) completed.remove(def.key)
                else completed[def.key] = CatalogRow(def.addon.name, def.originalLabel, items, catalogKey = def.key)
                publish()
            }
            val catalog = definitions.firstOrNull { it.key == only }
            if (only != null && catalog != null) fetch(catalog)
            else {
                val all = providers?.invoke() ?: listOf(SearchProvider("discovery", "Search sources", searchable))
                val selected = all.filter { only == null || it.id == only }
                if (selected.isEmpty() && only != null) {
                    searching = false
                    finishNote(term)
                    return@launch
                }
                val bySource = all.associate { source -> source.id to definitions.filter {
                    if (providers == null) true else source.id == "manifest:${it.addon.url}"
                } }.toMutableMap()
                if (only == null) health.retain(all.map { it.id }.toSet() + definitions.map { it.key })
                selected.forEach { health.start(it.id, it.name) }
                coroutineScope {
                    selected.map { source -> async {
                        val result = permits.withPermit {
                            kotlinx.coroutines.withTimeoutOrNull(20_000L) { runCatching { source.load() } }
                        }
                        ensureActive()
                        val success = result?.isSuccess == true
                        usableCatalogs = usableCatalogs - bySource[source.id].orEmpty().map { it.key }.toSet()
                        health.finish(source.id, success, !success && bySource[source.id].orEmpty().isNotEmpty(), failure=if(result==null)app.ember.tv.data.SourceFailure.TIMEOUT else result.exceptionOrNull()?.let(app.ember.tv.data.SourceFailure::from))
                        if (success) bySource[source.id] = result.getOrThrow().distinctBy { it.key }
                        definitions = all.flatMap { bySource[it.id].orEmpty() }.distinctBy { it.key }
                        usableCatalogs = usableCatalogs.intersect(definitions.map { it.key }.toSet())
                        health.retain(all.map { it.id }.toSet() + definitions.map { it.key })
                        publish()
                        // A working source can search before a slow manifest completes.
                        if (success) coroutineScope {
                            bySource[source.id].orEmpty().map { def -> async { fetch(def) } }.awaitAll()
                        }
                    } }.awaitAll()
                }
            }
            ensureActive()
            finishNote(term)
          } catch(e:kotlinx.coroutines.CancellationException) {throw e}
          catch(_:Exception) {
            if(request==generation) {
                health.cancelLoading()
                val hasResults=searchRows.any {it.items.isNotEmpty()} || searchApps.isNotEmpty() || searchSaved.isNotEmpty()
                sourceUnavailable=!hasResults && usableCatalogs.isEmpty()
                searchNote=if(hasResults)null else "Could not finish the search. Retry or check your sources."
            }
          } finally {if(request==generation)searching=false}
        }
    }

    private fun finishNote(term: String) {
        val hasResults = searchRows.any { it.items.isNotEmpty() } || searchApps.isNotEmpty() || searchSaved.isNotEmpty()
        sourceUnavailable = !hasResults && usableCatalogs.isEmpty()
        searchNote = when {
            hasResults -> null
            sourceUnavailable && health.entries.values.any { it.failed } -> "Search sources are unavailable. Retry or check your sources."
            sourceUnavailable -> "No search sources are available. Add a searchable catalog in Source settings."
            else -> "No matches for \"$term\". Try a different title."
        }
    }
}
