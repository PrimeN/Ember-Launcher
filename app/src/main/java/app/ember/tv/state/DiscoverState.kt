package app.ember.tv.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.AddonClient
import app.ember.tv.data.CatalogDef
import app.ember.tv.data.Meta
import app.ember.tv.data.mediaKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Discover: browse every catalog your addons offer. Split out of HomeViewModel.
 * [loadCatalogs] gives the catalogs shown here, in the order set in Settings > Catalogs.
 */
class DiscoverState(
    private val scope: CoroutineScope,
    private val client: AddonClient,
    private val loadCatalogs: suspend () -> List<CatalogDef>,
) {
    var discoverCatalogs by mutableStateOf<List<CatalogDef>>(emptyList()); private set
    var discoverIndex by mutableIntStateOf(0); private set
    var discoverGenre by mutableStateOf<String?>(null); private set
    var discoverItems by mutableStateOf<List<Meta>>(emptyList()); private set
    var discoverLoading by mutableStateOf(false); private set
    var discoverEnd by mutableStateOf(false); private set
    var discoverNote by mutableStateOf<String?>(null); private set
    var failed by mutableStateOf(false); private set
    private var discoverJob: Job? = null
    var extraArgs by mutableStateOf<Map<String, String>>(emptyMap()); private set
    private var nextSkip = 0
    private var seenItems = emptySet<String>()
    internal var browse: suspend (CatalogDef, String?, Int) -> List<Meta> = { def, genre, skip -> client.browse(def, genre, skip) }
    internal var filterItems: suspend (List<Meta>) -> List<Meta> = {it}
    fun retry() {
        discoverEnd = false
        if (discoverCatalogs.isEmpty()) openDiscover() else loadDiscover(reset = false)
    }
    private var catalogsJob: Job? = null
    private var loadRequested = false
    private var catalogOpened = false

    val discoverCatalog: CatalogDef? get() = discoverCatalogs.getOrNull(discoverIndex)

    /** Loads the catalog list the first time Discover opens, and again after addons change. */
    fun openDiscover(loadItems: Boolean = true) {
        loadRequested = loadRequested || loadItems
        if (discoverCatalogs.isNotEmpty()) {
            if (loadRequested && !catalogOpened) selectCatalog(discoverIndex)
            return
        }
        if (discoverLoading) return
        failed = false
        discoverLoading = true
        discoverNote = null
        catalogsJob = scope.launch {
            val result = runCatching { loadCatalogs() }
            ensureActive()
            val catalogs = result.getOrElse {
                failed = true
                discoverLoading = false
                discoverNote = "Could not load catalogs. Retry to check your sources again."
                return@launch
            }
            discoverCatalogs = catalogs
            discoverLoading = false
            val wanted = pending
            pending = null
            when {
                wanted != null -> {
                    var index = discoverCatalogs.indexOfFirst { it.key == wanted.key }
                    if (index < 0) { discoverCatalogs = listOf(wanted) + discoverCatalogs; index = 0 }
                    selectCatalog(index)
                }
                discoverCatalogs.isEmpty() -> discoverNote = "No catalogs to browse. Add an addon, or switch catalogs on for Discover in Settings > Addons & catalogs."
                loadRequested -> selectCatalog(0)
            }
        }
    }

    /** A catalog to open once the list has loaded ("See all" on For You). */
    private var pending: CatalogDef? = null

    /** "See all" on a For You row: opens that catalog here, adding it to the list if it is not in Discover. */
    fun show(def: CatalogDef) {
        if (discoverCatalogs.isEmpty()) {
            pending = def
            openDiscover()
            return
        }
        var i = discoverCatalogs.indexOfFirst { it.key == def.key }
        if (i < 0) {
            discoverCatalogs = listOf(def) + discoverCatalogs
            i = 0
        }
        selectCatalog(i)
    }

    fun selectCatalog(index: Int) {
        val def = discoverCatalogs.getOrNull(index) ?: return
        catalogOpened = true
        discoverIndex = index
        extraArgs = def.defaultExtras
        discoverGenre = def.defaultExtras["genre"] ?: if (def.genreRequired) def.genres.firstOrNull() else null
        loadDiscover(reset = true)
    }

    fun applyExtras(values: Map<String, String>) {
        discoverGenre = values["genre"]?.ifBlank { null }
        extraArgs = values
        loadDiscover(reset = true)
    }

    fun selectGenre(genre: String?) {
        discoverGenre = genre
        extraArgs = extraArgs + ("genre" to genre.orEmpty())
        loadDiscover(reset = true)
    }

    fun loadMoreDiscover() {
        if (!discoverLoading && !discoverEnd) loadDiscover(reset = false)
    }

    private fun loadDiscover(reset: Boolean) {
        val def = discoverCatalog ?: return
        discoverJob?.cancel()
        if (reset) {
            discoverItems = emptyList()
            discoverEnd = false
            nextSkip = 0
            seenItems = emptySet()
        }
        val values = extraArgs + listOfNotNull(discoverGenre?.let { "genre" to it }).toMap()
        def.extraError(values)?.let { discoverLoading = false; discoverNote = it; return }
        failed = false
        discoverLoading = true
        discoverNote = null
        val skip = nextSkip
        val genre = discoverGenre
        val args = extraArgs
        discoverJob = scope.launch {
            val page = runCatching {
                val items=if (args.isEmpty()) browse(def, genre, skip) else client.browse(def, genre, skip, extras = args)
                val visible=filterItems(items)
                ensureActive()
                items to visible
            }
            ensureActive()
            page.onSuccess { (items,visible) ->
                val keys=items.map {it.mediaKey()}.toSet()
                val repeated=keys.all {it in seenItems}
                seenItems=seenItems+keys
                val merged = (discoverItems + visible).distinctBy { it.mediaKey() }
                nextSkip = skip + items.size
                // Addons signal the end with an empty page, or by repeating what we already have.
                discoverEnd = !def.supportsSkip || items.isEmpty() || repeated
                discoverItems = merged
                if (merged.isEmpty()) discoverNote = if(items.isNotEmpty())"No titles on this page match the active content lock." else "This catalog is empty."
            }.onFailure {
                failed = true
                discoverEnd = true
                discoverNote = "Could not load ${def.label} from ${def.addon.name}."
            }
            discoverLoading = false
        }
    }

    /** Addons or catalog settings changed: the list reloads next time Discover opens. */
    fun reset() {
        catalogsJob?.cancel()
        discoverJob?.cancel()
        pending = null
        discoverItems = emptyList()
        discoverLoading = false
        discoverEnd = false
        discoverNote = null
        discoverIndex = 0
        discoverGenre = null
        nextSkip = 0
        seenItems = emptySet()
        failed = false
        discoverCatalogs = emptyList()
        loadRequested = false
        catalogOpened = false
    }
}
