package app.ember.tv.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.CollectionPage
import app.ember.tv.data.CollectionSource
import app.ember.tv.data.HomeWidget
import app.ember.tv.data.HomeWidgets
import app.ember.tv.data.Meta
import app.ember.tv.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Your Home widgets (#161, data/HomeWidgets.kt): the saved list for the active profile and the
 * titles each one loaded. Their order and whether they show live in the Home layout.
 */
class WidgetsState(
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val profileId: () -> String,
    private val allowed: () -> Boolean,
    /** The first page of a source, through the same loader and cache as collection folders. */
    private val page: suspend (CollectionSource) -> Result<CollectionPage>,
    private val onChanged: () -> Unit,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private var owner = profileId()
    var list by mutableStateOf(HomeWidgets.decode(prefs.homeWidgets(owner))); private set
    /** Loaded titles by widget id. Your own lists are read live on Home instead. */
    var items by mutableStateOf<Map<String, List<Meta>>>(emptyMap()); private set
    var loading by mutableStateOf<Set<String>>(emptySet()); private set
    var failed by mutableStateOf<Set<String>>(emptySet()); private set
    var revision by mutableLongStateOf(0L); private set
    private val loadedAt = HashMap<String, Long>()
    private val loadedSource = HashMap<String, CollectionSource>()
    private var job: Job? = null

    /** The profile changed, or a backup was restored. */
    fun reload() {
        job?.cancel(); job = null
        owner = profileId()
        list = HomeWidgets.decode(prefs.homeWidgets(owner))
        items = emptyMap(); loading = emptySet(); failed = emptySet()
        loadedAt.clear(); loadedSource.clear()
        revision++
    }

    /** Loads the widgets that show on Home and are new, changed or older than [FRESH_MILLIS]. */
    fun refresh(force: Boolean = false, visible: (String) -> Boolean) {
        if (profileId() != owner) reload()
        if (!allowed()) return
        val now = clock()
        val due = list.filter { w ->
            w.source.provider != HomeWidgets.LIST && visible(HomeWidgets.section(w.id)) &&
                (force || loadedSource[w.id] != w.source || now - (loadedAt[w.id] ?: Long.MIN_VALUE / 2) > FRESH_MILLIS)
        }
        if (due.isEmpty() || (job?.isActive == true && !force)) return
        job?.cancel()
        val id = owner
        loading = due.map { it.id }.toSet()
        job = scope.launch {
            for (w in due) {
                val result = try { page(w.source) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
                ensureActive()
                if (profileId() != id || owner != id) return@launch
                result.onSuccess { loaded ->
                    items = items + (w.id to loaded.items.take(w.limit))
                    loadedAt[w.id] = clock(); loadedSource[w.id] = w.source
                    failed = failed - w.id
                }.onFailure { failed = failed + w.id }
                loading = loading - w.id
            }
        }
    }

    /** Adds the widget or replaces the one with its id. Returns what is wrong, or null when saved. */
    fun save(value: HomeWidget): String? {
        if (!allowed()) return "Widgets are unavailable for this profile."
        if (profileId() != owner) reload()
        val widget = HomeWidgets.normalize(value)
        HomeWidgets.error(widget)?.let { return it }
        val existing = list.any { it.id == widget.id }
        if (!existing && list.size >= HomeWidgets.MAX_WIDGETS) return "You have the most widgets Home can hold (${HomeWidgets.MAX_WIDGETS}). Delete one first."
        persist(if (existing) list.map { if (it.id == widget.id) widget else it } else list + widget)
        return null
    }

    /** Imported widgets replace ones with the same id and add the rest (#163). Returns a message for the user. */
    fun importAll(incoming: List<HomeWidget>): String {
        if (!allowed()) return "Widgets are unavailable for this profile."
        if (profileId() != owner) reload()
        val usable = incoming.map(HomeWidgets::normalize).filter { HomeWidgets.error(it) == null }.distinctBy { it.id }
        if (usable.isEmpty()) return "The pack has no widgets that can be used here."
        val ids = usable.map { it.id }.toSet()
        val updated = list.count { it.id in ids }
        val merged = (list.map { old -> usable.firstOrNull { it.id == old.id } ?: old } + usable.filter { w -> list.none { it.id == w.id } }).take(HomeWidgets.MAX_WIDGETS)
        val added = merged.size - list.size
        persist(merged)
        val skipped = usable.size - updated - added
        return listOfNotNull("Imported: $added added", "$updated updated", if (skipped > 0) "$skipped left out (Home holds ${HomeWidgets.MAX_WIDGETS})" else null).joinToString(", ") + "."
    }

    fun remove(id: String): Boolean {
        if (profileId() != owner) reload()
        if (list.none { it.id == id }) return false
        persist(list.filterNot { it.id == id })
        items = items - id; loading = loading - id; failed = failed - id
        loadedAt.remove(id); loadedSource.remove(id)
        return true
    }

    private fun persist(next: List<HomeWidget>) {
        prefs.setHomeWidgets(owner, HomeWidgets.encode(next))
        list = next
        revision++
        onChanged()
    }

    companion object { const val FRESH_MILLIS = 15 * 60_000L }
}
