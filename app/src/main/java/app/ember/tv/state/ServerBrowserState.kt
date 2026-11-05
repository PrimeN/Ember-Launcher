package app.ember.tv.state

import androidx.core.content.edit

import android.content.Context
import androidx.compose.runtime.*
import app.ember.tv.data.Meta
import app.ember.tv.data.mediaKey
import app.ember.tv.mediaserver.*
import kotlinx.coroutines.*

/** Bounded native-library pages; filter changes cancel obsolete work and reset the cursor. */
class ServerBrowserState(
    private val scope: CoroutineScope,
    private val page: suspend (String, ServerFilter, Int) -> ServerPage,
    private val loadGenres: suspend (String) -> List<Pair<String,String>>,
    private val read: (String, String) -> String = { _, fallback -> fallback },
    private val write: (String, String) -> Unit = { _, _ -> },
) {
    constructor(context: Context, servers: MediaServers, scope: CoroutineScope) : this(scope, servers::browse, servers::genres,
        { key, fallback -> context.getSharedPreferences("server_browser", Context.MODE_PRIVATE).getString(key, fallback) ?: fallback },
        { key, value -> context.getSharedPreferences("server_browser", Context.MODE_PRIVATE).edit { putString(key, value) } })
    var library by mutableStateOf<ServerLibrary?>(null); private set
    var items by mutableStateOf<List<Meta>>(emptyList()); private set
    var filter by mutableStateOf(ServerFilter()); private set
    var genres by mutableStateOf<List<Pair<String,String>>>(emptyList()); private set
    var total by mutableStateOf<Int?>(null); private set
    var loading by mutableStateOf(false); private set
    var end by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var density by mutableStateOf("default"); private set
    var folderName by mutableStateOf<String?>(null); private set
    var restorePosition by mutableStateOf<Pair<Int,Int>?>(null); private set
    private data class Saved(val filter: ServerFilter, val items: List<Meta>, val total: Int?, val cursor: Int, val end: Boolean, val name: String?, val position: Pair<Int,Int>)
    private val parents = java.util.ArrayDeque<Saved>()
    private var generation = 0L
    private var cursor = 0
    private var job: Job? = null
    private var genresJob: Job? = null
    private fun key(ref: String) = app.ember.tv.data.AddonAccess.key(ref)
    fun open(value: ServerLibrary, folder:String?=null, folderTitle:String?=null) {
        if (library?.ref == value.ref && filter.parent==folder) return
        job?.cancel(); genresJob?.cancel(); library = value
        parents.clear(); folderName = folderTitle; restorePosition = null
        filter = ServerFilter(sort = read(key(value.ref)+".sort", "added"),parent=folder)
        density = read(key(value.ref)+".density", "default")
        genres = emptyList(); items = emptyList(); total = null
        reload()
        genresJob = scope.launch {
            try { val result = loadGenres(value.ref); ensureActive(); genres = result }
            catch (e: CancellationException) { throw e } catch (_: Exception) { /* Genre availability is independent of browsing. */ }
        }
    }
    fun clear() { parents.clear(); folderName = null; restorePosition = null; generation++; job?.cancel(); genresJob?.cancel(); library = null; items = emptyList(); loading = false }
    fun enterCollection(meta: Meta, index: Int = 0, offset: Int = 0) {
        val ref = ServerRef.of(meta) ?: return
        if (parents.size >= 8 || ref.item == filter.parent) { error = "This collection cannot be opened any deeper."; return }
        parents.addLast(Saved(filter, items, total, cursor, end, folderName, index to offset))
        filter = filter.copy(parent = ref.item, collections = false, query = "", genre = null)
        folderName = meta.name
        reload()
    }
    fun back(): Boolean {
        val saved = parents.pollLast() ?: return false
        generation++; job?.cancel(); loading = false; error = null
        filter = saved.filter; items = saved.items; total = saved.total; cursor = saved.cursor; end = saved.end
        folderName = saved.name; restorePosition = saved.position
        return true
    }
    fun restored() { restorePosition = null }
    fun applyFilter(value: ServerFilter) {
        if (value == filter) return
        filter = value
        library?.let { write(key(it.ref)+".sort",value.sort) }
        reload()
    }
    fun updateDensity(value: String) {
        density = value
        library?.let { write(key(it.ref)+".density",value) }
    }
    fun reload() { generation++; job?.cancel(); items = emptyList(); cursor = 0; end = false; loading = false; total = null; more() }
    fun retry() { if (!loading) more() }
    fun more() {
        val lib = library ?: return
        if (loading || end) return
        val query = filter; val offset = cursor; val visit = generation
        loading = true; error = null
        job = scope.launch {
            try {
                val result = page(lib.ref, query, offset)
                ensureActive()
                if (generation != visit || library?.ref != lib.ref || filter != query) return@launch
                val merged = (items + result.items).distinctBy { it.mediaKey() }
                end = result.end || result.next <= offset || result.items.isEmpty() || merged.size == items.size
                items = merged; cursor = result.next; total = result.total
            } catch (e: CancellationException) { throw e }
            catch (e: ServerHttpException) { ensureActive(); error = if (e.status in listOf(401,403)) "Access denied. Reconnect this server or check library permissions." else "The server could not load this page. Retry to keep your place." }
            catch (_: Exception) { ensureActive(); error = "Server unavailable. Check its connection and retry this page." }
            finally { if (generation == visit) loading = false }
        }
    }
}
