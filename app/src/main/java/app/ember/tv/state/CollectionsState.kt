package app.ember.tv.state

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.AddonClient
import app.ember.tv.data.CatalogDef
import app.ember.tv.data.Collections
import app.ember.tv.data.Meta
import app.ember.tv.data.MediaCollection
import app.ember.tv.data.CollectionFolder
import app.ember.tv.data.Prefs
import app.ember.tv.data.CollectionSource
import app.ember.tv.data.CollectionPage
import app.ember.tv.data.distinctCollectionTitles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** One tab (or row) of an open folder: a source's titles, or why it could not load. */
data class FolderTab(
    val label: String,
    val items: List<Meta> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** No more pages to load. */
    val end: Boolean = false,
    val failed: Boolean = false,
    val nextSkip: Int = 0,
    val seenPages: Set<String> = emptySet(),
)

/**
 * Collections (Settings > Stremio > Collections, data/Collections.kt): the collections you
 * imported, which of them show on Home, and the folder that is open.
 */
class CollectionsState(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val client: AddonClient,
    /** Every catalog your addons offer, to find a collection's addon catalogs. */
    private val catalogs: suspend () -> List<CatalogDef>,
    private val onChanged: () -> Unit,
    private val sourceAllowed: (CollectionSource) -> Boolean = { true },
    private val explorePage: suspend (CollectionSource, Int) -> CollectionPage = { _, _ -> error("Discover is unavailable.") },
    private val cacheContext: () -> String = { "" },
    private val profileId: () -> String = { app.ember.tv.data.Profile.MAIN },
) {
    internal var nowMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() }
    var list by mutableStateOf(Collections.load(context)); private set
    init { app.ember.tv.ui.IconArtworkCache.configuration("collections", list.toString()) }
    /** Collections switched off on Home (by id). */
    var hidden by mutableStateOf(prefs.collectionsOff); private set
    var message by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var revision by androidx.compose.runtime.mutableLongStateOf(0L); private set
    var heroPreviews by mutableStateOf(prefs.collectionHeroPreviews); private set
    fun enableHeroPreviews(value: Boolean) { heroPreviews = value; prefs.collectionHeroPreviews = value }
    var autoPage by mutableStateOf(prefs.collectionAutoPage); private set
    fun updateAutoPage(value: Boolean) { autoPage = value; prefs.collectionAutoPage = value }
    private data class Cached(val page: CollectionPage, val at: Long)
    private data class CacheKey(val source: String, val context: String, val catalogs: List<String>)
    private val firstPages = LinkedHashMap<CacheKey, Cached>(16, .75f, true)
    private suspend fun fetch(source: CollectionSource, defs: List<CatalogDef>, skip: Int, priority: Boolean = false): Result<CollectionPage> {
        if (!sourceAllowed(source)) return Result.failure(IllegalStateException("This source is unavailable for this profile."))
        val key = CacheKey(sourceKey(source), cacheContext(), if (source.provider == "addon") defs.filter { Collections.matches(it, source) }.map { it.key } else emptyList())
        if (skip == 0) synchronized(firstPages) { firstPages[key]?.takeIf { app.ember.tv.data.runtimeFresh(it.at, nowMillis(), 5 * 60_000L) } }?.let { return Result.success(it.page) }
        val flightKey = FlightKey(key, skip, cacheGeneration)
        val flight = synchronized(flights) {
            if (priority) flights[flightKey]?.takeIf { !it.priority && !it.started }?.let { flights.remove(flightKey); it.task.cancel() }
            flights.getOrPut(flightKey) {
                lateinit var entry: Flight
                val task = scope.async(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    suspend fun request(): Result<CollectionPage> = requests.withPermit {
                        entry.started = true
                        loadPage(source, defs, skip)
                    }
                    if (priority) request() else background.withPermit { request() }
                }
                entry = Flight(task, priority = priority); entry
            }.also { it.readers++ }
        }
        val result = try { flight.task.start(); flight.task.await() } catch (cancelled: CancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (cacheGeneration != flightKey.generation) return Result.failure(IllegalStateException("Sources refreshed. Retry this source."))
            return fetch(source, defs, skip, priority)
        } finally {
            synchronized(flights) { flight.readers--; if (flight.readers == 0) { if (flights[flightKey] === flight) flights.remove(flightKey); if (!flight.task.isCompleted) flight.task.cancel() } }
        }
        if (cacheGeneration != flightKey.generation || cacheContext() != key.context || !sourceAllowed(source)) return Result.failure(IllegalStateException("The active source context changed."))
        if (skip == 0) result.onSuccess { page -> if (page.items.isNotEmpty() || page.end) synchronized(firstPages) {
            firstPages[key] = Cached(page, nowMillis())
            while (firstPages.size > 16) firstPages.remove(firstPages.keys.first())
        } }
        return result
    }
    @Volatile private var cacheGeneration = 0L
    private data class FlightKey(val key: CacheKey, val skip: Int, val generation: Long)
    private class Flight(val task: kotlinx.coroutines.Deferred<Result<CollectionPage>>, var readers: Int = 0, val priority: Boolean = false, @Volatile var started: Boolean = false)
    private val flights = mutableMapOf<FlightKey, Flight>()
    private val background = kotlinx.coroutines.sync.Semaphore(3)
    fun invalidatePages() {
        synchronized(firstPages) { firstPages.clear(); cacheGeneration++ }
        val old = synchronized(flights) { flights.values.toList().also { flights.clear() } }
        old.forEach { it.task.cancel() }
    }
    fun refreshFolder() {
        app.ember.tv.ui.IconArtworkCache.refresh()
        invalidatePages()
        open?.let { openFolder(it.first, it.second) }
    }

    /** The open folder and its collection, or null. */
    var open by mutableStateOf<Pair<MediaCollection, CollectionFolder>?>(null); private set
    var tabs by mutableStateOf<List<FolderTab>>(emptyList()); private set
    private var loadJob: Job? = null
    private val pageJobs = mutableListOf<Job>()
    private val sourceJobs = mutableMapOf<Int, Job>()
    private val requests = kotlinx.coroutines.sync.Semaphore(4)

    /** Collections shown in Discover (by id); none until you add them. */
    var inDiscover by mutableStateOf(prefs.collectionsDiscover); private set
    /** Folders switched off, as "collection id/folder id". */
    var foldersOff by mutableStateOf(prefs.collectionFoldersOff); private set

    fun folderOn(c: MediaCollection, f: CollectionFolder) = "${c.id}/${f.id}" !in foldersOff

    /** A collection with only its folders that are on. */
    private fun shown(c: MediaCollection) = c.copy(folders = c.folders.filter { folderOn(c, it) && it.allSources.any(sourceAllowed) })

    val onHome: List<MediaCollection> get() = list.filter { it.id !in hidden }.map(::shown).filter { it.folders.isNotEmpty() }
    val onDiscover: List<MediaCollection> get() = list.filter { it.id in inDiscover }.map(::shown).filter { it.folders.isNotEmpty() }

    fun toggleDiscover(id: String) {
        inDiscover = if (id in inDiscover) inDiscover - id else inDiscover + id
        prefs.collectionsDiscover = inDiscover
    }

    fun toggleFolder(c: MediaCollection, f: CollectionFolder) {
        val k = "${c.id}/${f.id}"
        foldersOff = if (k in foldersOff) foldersOff - k else foldersOff + k
        prefs.collectionFoldersOff = foldersOff
        onChanged()
    }

    private val edits = Mutex()
    internal var persistCollections: suspend (List<MediaCollection>) -> Result<Unit> = { Collections.save(context, it) }

    /** Evaluate edits in order against the last durable state; failed writes never publish success. */
    private suspend fun update(success: String, transform: (List<MediaCollection>) -> List<MediaCollection>?): String? = edits.withLock {
        val next = transform(list) ?: return@withLock null
        persistUpdate(success, next)
    }

    private suspend fun persistUpdate(success: String, next: List<MediaCollection>): String? {
        // An atomic replacement cannot be cancelled halfway between its commit and UI publication.
        return withContext(NonCancellable) {
            var committed = next
            val saved = withContext(Dispatchers.IO) {
                try {
                    committed = app.ember.tv.data.CollectionSecrets.seal(next) { key, value -> app.ember.tv.tracker.TokenStore(context).putEncrypted(key, value) }
                    persistCollections(committed)
                } catch (error: Exception) { Result.failure(error) }
            }
            if (saved.isFailure) {
                message = "Could not save collections. The previous collection pack was kept. Check available storage and retry."
                message
            } else {
                app.ember.tv.ui.IconArtworkCache.configuration("collections", committed.toString())
                list = committed
                revision++
                open?.let { (oldCollection, oldFolder) ->
                    val collection = committed.firstOrNull { it.id == oldCollection.id }
                    val folder = collection?.folders?.firstOrNull { it.id == oldFolder.id }
                    if (collection == null || folder == null) closeFolder()
                    else if (collection != oldCollection || folder.allSources.filter(sourceAllowed) != oldFolder.allSources) openFolder(collection, folder)
                }
                message = success
                onChanged()
                null
            }
        }
    }

    fun moveFolder(c: MediaCollection, f: CollectionFolder, delta: Int) {
        scope.launch { update("Folder order saved.") { current ->
            val collection = current.firstOrNull { it.id == c.id } ?: return@update null
            val i = collection.folders.indexOfFirst { it.id == f.id }; val j = i + delta
            if (i < 0 || j !in collection.folders.indices) return@update null
            val folders = collection.folders.toMutableList().apply { add(j, removeAt(i)) }
            current.map { if (it.id == c.id) it.copy(folders = folders) else it }
        } }
    }

    /** Replaces matching ids only after the entire incoming pack validates and is saved. */
    suspend fun importText(text: String, mode: String = "merge", expectedRevision: Long? = null): String? {
        val incoming = Collections.inspect(text)
        incoming.error?.let { error -> return edits.withLock { message = error; error } }
        return edits.withLock {
            if (expectedRevision != null && expectedRevision != revision) return@withLock "Collections changed after this preview. Preview the pack again."
            if (mode !in setOf("merge", "replace", "copy")) return@withLock "Choose an import mode."
            val existing = list.map { it.id }.toSet()
            val values = incoming.collections.map { c -> if (mode == "copy" && c.id in existing)
                c.copy(id = java.util.UUID.randomUUID().toString(), title = c.title + " (copy)",
                    folders = c.folders.map { it.copy(id = java.util.UUID.randomUUID().toString()) }) else c }
            val ids = values.map { it.id }.toSet()
            persistUpdate("Saved ${values.size} collection(s), ${values.sumOf { it.folders.size }} folder(s).",
                if (mode == "replace") values else list.filterNot { it.id in ids } + values)
        }
    }

    suspend fun saveCollection(value: MediaCollection, original: MediaCollection? = null, delta: Int = 0): String? {
        Collections.inspect(Collections.encode(listOf(value))).error?.let { return it }
        return edits.withLock {
            val current = list.firstOrNull { it.id == value.id }
            if (current != original) return@withLock "This collection changed while you were editing. Reopen it before saving."
            val next = (if (current == null) list + value else list.map { if (it.id == value.id) value else it }).toMutableList()
            val at = next.indexOfFirst { it.id == value.id }; val to = (at + delta).coerceIn(0, next.lastIndex)
            if (at != to) next.add(to, next.removeAt(at))
            persistUpdate("Collection saved.", next)
        }
    }

    suspend fun addSource(collectionId: String?, folderId: String?, collectionName: String, folderName: String, source: CollectionSource): String? {
        if (!sourceAllowed(source)) return "This source is unavailable for the active profile."
        return edits.withLock {
            if (collectionId != null && list.none { it.id == collectionId }) return@withLock "That collection no longer exists."
            val collection = collectionId?.let { id -> list.first { it.id == id } }
                ?: MediaCollection(id = java.util.UUID.randomUUID().toString(), title = collectionName.trim())
            if (folderId != null && collection.folders.none { it.id == folderId }) return@withLock "That folder no longer exists."
            val folder = folderId?.let { id -> collection.folders.first { it.id == id } }
                ?: CollectionFolder(id = java.util.UUID.randomUUID().toString(), title = folderName.trim())
            if (folder.allSources.any { sourceKey(it) == sourceKey(source) }) return@withLock "That source is already in this folder."
            val updated = folder.copy(sources = folder.allSources + source, catalogSources = emptyList())
            val value = collection.copy(folders = if (folderId == null) collection.folders + updated else collection.folders.map { if (it.id == updated.id) updated else it })
            Collections.inspect(Collections.encode(listOf(value))).error?.let { return@withLock it }
            persistUpdate("Source added to collection.", if (collectionId == null) list + value else list.map { if (it.id == value.id) value else it })
        }
    }

    fun importUrl(url: String) {
        if (busy || url.isBlank()) return
        busy = true
        message = "Downloading..."
        scope.launch {
            try { importText(Collections.download(url)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Could not download the collection pack. Check the link and connection, then retry." }
            finally { busy = false }
        }
    }

    fun toggle(id: String) {
        hidden = if (id in hidden) hidden - id else hidden + id
        prefs.collectionsOff = hidden
        onChanged()
    }

    fun move(id: String, delta: Int) {
        scope.launch { update("Collection order saved.") { current ->
            val i = current.indexOfFirst { it.id == id }; val j = i + delta
            if (i < 0 || j !in current.indices) null else current.toMutableList().apply { add(j, removeAt(i)) }
        } }
    }

    fun remove(id: String) {
        scope.launch { update("Collection removed.") { current -> current.filterNot { it.id == id } } }
    }

    fun removeAll() {
        scope.launch { update("Collections removed.") { emptyList() } }
    }

    // ---- an open folder ----

    /** Catalogs as they were when the folder opened, for loading more pages. */
    private var folderDefs: List<CatalogDef> = emptyList()
    private var folderCatalogsFailed = false
    private var folderDefsReady = false
    internal var loadPage: suspend (CollectionSource, List<CatalogDef>, Int) -> Result<CollectionPage> =
        { source, defs, skip ->
            if (!sourceAllowed(source)) Result.failure(IllegalStateException("This source is unavailable for this profile."))
            else if (source.provider.equals("ember", true)) runCatching { explorePage(source, skip) }
            else Collections.page(source, defs, client, skip) { app.ember.tv.tracker.TokenStore(context).get(it) }
        }

    /** The tab last used in each folder ("collection/folder"), kept between visits. */
    fun sourceKey(source: CollectionSource): String = app.ember.tv.data.CollectionSourceIdentity.key(source) { app.ember.tv.tracker.TokenStore(context).get(it) }
    fun tabKeys(c: MediaCollection, f: CollectionFolder): List<String> =
        (if (c.showAllTab && f.allSources.size > 1) listOf("all") else emptyList()) + f.allSources.map(::sourceKey)
    fun savedTab(c: MediaCollection, f: CollectionFolder): Int = tabKeys(c, f).indexOf(prefs.folderSelection(profileId(), c.id, f.id)).coerceAtLeast(0)
    fun saveTab(c: MediaCollection, f: CollectionFolder, tab: Int) {
        tabKeys(c, f).getOrNull(tab)?.let { prefs.setFolderSelection(profileId(), c.id, f.id, it) }
    }

    /** The next page of one source (or of every source, for the All tab, when [index] is -1). */
    fun loadMore(index: Int) {
        val f = open?.second ?: return
        if (folderCatalogsFailed) { open?.let { openFolder(it.first, it.second) }; return }
        val defs = folderDefs
        pageJobs.removeAll { it.isCompleted }
        val which = if (index < 0) tabs.indices.toList() else listOf(index)
        which.forEach { i ->
            val t = tabs.getOrNull(i) ?: return@forEach
            val s = f.allSources.getOrNull(i) ?: return@forEach
            if (t.loading || t.end) return@forEach
            tabs = tabs.toMutableList().also { it[i] = t.copy(loading = true, error = null, failed = false) }
            pageJobs += scope.launch {
                val result = fetch(s, defs, t.nextSkip, priority = index >= 0)
                ensureActive()
                val now = tabs.getOrNull(i) ?: return@launch
                tabs = tabs.toMutableList().also { updated -> updated[i] = result.fold(
                    { page ->
                        val repeated = page.fingerprint != null && page.fingerprint in now.seenPages
                        now.copy(items = distinctCollectionTitles(now.items + page.items), loading = false, error = null, failed = false,
                            nextSkip = page.nextSkip, end = page.end || repeated,
                            seenPages = now.seenPages + listOfNotNull(page.fingerprint))
                    },
                    { now.copy(loading = false, failed = true, error = "Could not load this source. Retry keeps the titles already loaded.") },
                ) }
            }
        }
    }

    fun openFolder(c: MediaCollection, f: CollectionFolder) {
        closeFolder()
        val allowed = f.allSources.filter(sourceAllowed)
        if (allowed.isEmpty()) { message = "No sources are available for the active profile."; return }
        val visibleFolder = f.copy(sources = allowed)
        open = c to visibleFolder
        tabs = allowed.map { FolderTab(Collections.label(it, emptyList())) }
        loadJob = scope.launch {
            val result = runCatching { catalogs() }
            ensureActive()
            val defs = result.getOrElse {
                folderCatalogsFailed = true
                tabs = tabs.map { it.copy(loading = false, failed = true, error = "Could not load collection sources. Retry to check again.") }
                return@launch
            }
            folderDefs = defs; folderDefsReady = true
            val sources = allowed
            tabs = sources.map { FolderTab(Collections.label(it, defs)) }
            val selected = savedTab(c, visibleFolder) - if (c.showAllTab && sources.size > 1) 1 else 0
            val order = listOf(selected).filter { it in sources.indices } + sources.indices.filter { it != selected }
            order.forEach { i -> startSource(i, sources[i], defs, i == selected) }

        }
    }

    private fun startSource(index: Int, source: CollectionSource, defs: List<CatalogDef>, priority: Boolean) {
        sourceJobs[index]?.cancel()
        sourceJobs[index] = scope.launch {
            val result = fetch(source, defs, 0, priority)
            ensureActive()
            val tab = tabs.getOrNull(index) ?: return@launch
            tabs = tabs.toMutableList().also { it[index] = result.fold(
                { page -> tab.copy(items = distinctCollectionTitles(page.items), loading = false,
                    error = if (page.items.isEmpty()) emptySourceMessage(source) else null,
                    end = page.end, nextSkip = page.nextSkip, seenPages = listOfNotNull(page.fingerprint).toSet()) },
                { failure -> tab.copy(loading = false, failed = true, error = sourceFailureMessage(failure)) }) }
        }
    }
    /** Why a source shows nothing, so an empty folder explains itself. */
    private fun emptySourceMessage(source: CollectionSource): String = when (source.provider.lowercase()) {
        "tmdb" -> "TMDB returned no titles for this source's filters."
        "trakt" -> "This Trakt list has no titles of this type."
        else -> "No titles loaded yet."
    }
    /** The reason a source failed (missing key, HTTP status, missing add-on), without addresses or keys. */
    private fun sourceFailureMessage(error: Throwable): String {
        val reason = (error as? IllegalStateException)?.message?.takeIf { it.isNotBlank() && "://" !in it && it.length < 160 }
        return (reason?.let { "$it " } ?: "Could not load this source. ") + "Retry to check again."
    }
    fun prioritize(index: Int) {
        val source = open?.second?.allSources?.getOrNull(index) ?: return
        if (tabs.getOrNull(index)?.loading == true && !folderCatalogsFailed && folderDefsReady) startSource(index, source, folderDefs, true)
    }
    fun sourceArguments(source: CollectionSource): Result<Map<String,String>> = runCatching { app.ember.tv.data.CollectionSecrets.resolve(source) { app.ember.tv.tracker.TokenStore(context).get(it) } }
    suspend fun duplicateCollection(id: String): String? = edits.withLock {
        val value = list.firstOrNull { it.id == id } ?: return@withLock "That collection no longer exists."
        persistUpdate("Collection duplicated.", list + app.ember.tv.data.CollectionDrafts.duplicate(value))
    }
    suspend fun duplicateFolder(collectionId: String, folderId: String): String? = edits.withLock {
        val c = list.firstOrNull { it.id == collectionId } ?: return@withLock "That collection no longer exists."
        val f = c.folders.firstOrNull { it.id == folderId } ?: return@withLock "That folder no longer exists."
        persistUpdate("Folder duplicated.", list.map { if (it.id == c.id) it.copy(folders = it.folders + app.ember.tv.data.CollectionDrafts.duplicate(f).copy(title = f.title + " (copy)")) else it })
    }
    fun exportPack(id: String, passphrase: String): String {
        val c = list.firstOrNull { it.id == id } ?: error("That collection no longer exists.")
        return app.ember.tv.data.CollectionTransfer.seal(listOf(c), passphrase) { app.ember.tv.tracker.TokenStore(context).get(it) }
    }

    fun closeFolder() {
        loadJob?.cancel()
        sourceJobs.values.forEach { it.cancel() }; sourceJobs.clear()
        pageJobs.forEach { it.cancel() }
        pageJobs.clear()
        folderDefs = emptyList(); folderDefsReady = false
        folderCatalogsFailed = false
        open = null
        tabs = emptyList()
    }

    /** Every source together, for the "All" tab: interleaved so each source shows near the top. */
    val allItems: List<Meta>
        get() {
            val lists = tabs.map { it.items }
            val out = ArrayList<Meta>()
            var i = 0
            while (lists.any { i < it.size }) {
                lists.forEach { l -> l.getOrNull(i)?.let { out += it } }
                i++
            }
            return distinctCollectionTitles(out)
        }
}
