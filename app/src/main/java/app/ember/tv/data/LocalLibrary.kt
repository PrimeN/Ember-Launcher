package app.ember.tv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One title you played in Ember: what it is, how far you got and when. */
@Serializable
data class LibraryEntry(
    /** The player's resume key: the episode id, or the title's IMDb or own id. */
    val key: String,
    /** The title (for an episode, the show, with its season and episode). */
    val meta: Meta,
    val progress: Float = 0f,
    val finished: Boolean = false,
    /** When it was last played (ms). */
    val at: Long = 0,
)

/** A watchlist change that has not reached your trackers yet (offline, or a tracker error). */
@Serializable
data class PendingWatchlist(
    val add: Boolean,
    val meta: Meta,
    /** Null migrates an old queue entry to the currently supported connected services. */
    val targets: Set<String>? = null,
    val revision: String = "",
)

/**
 * Ember's own copy of In progress, Recently watched and the watchlist, kept on the TV:
 *
 * - every play in Ember is recorded here (the player saves its progress as it goes), with or without
 *   trackers, so these lists work with no tracker at all;
 * - what your trackers said last time is kept too, so their lists still show when they cannot be reached;
 * - watchlist changes that could not reach a tracker wait here and are sent on the next refresh.
 *
 * The launcher and the player run in the same process, so both use this one copy. Saved to
 * files/library.json in the background, the whole file at once.
 */
object LocalLibrary {
    @Serializable private data class Activity(val plays:List<LibraryEntry> = emptyList(),val hidden:List<String> = emptyList(),
        val dismissed: Map<String, List<Meta>> = emptyMap())
    @Serializable
    private data class Data(
        val plays: List<LibraryEntry> = emptyList(),
        val trackerProgress: List<Meta> = emptyList(),
        val trackerHistory: List<Meta> = emptyList(),
        val trackerWatchlist: List<Meta> = emptyList(),
        val pending: List<PendingWatchlist> = emptyList(),
        /** Titles taken off Recently watched (also when they come from Trakt), by id. */
        val hidden: List<String> = emptyList(),
        val activities:Map<String,Activity> = emptyMap(),
        val dismissed: Map<String, List<Meta>> = emptyMap(),
    )

    /** Titles kept for In progress and Recently watched (and in each tracker copy), so the file stays small. */
    const val MAX_TITLES = 10
    private const val MAX_HIDDEN = 100
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "ember-library").apply { isDaemon = true } }
    private var file: File? = null
    private var initialization:FutureTask<Data>?=null
    private val dismissedIndices = LinkedHashMap<Pair<String, String>, Pair<List<Meta>, TitleIndex>>()
    @Volatile private var data = Data()

    private var selectedScope by androidx.compose.runtime.mutableStateOf(LocalActivity.SHARED)
    fun selectScope(scope:String) {selectedScope=scope}
    private fun view(d:Data,scope:String=selectedScope):Data = if(scope==LocalActivity.SHARED)d else d.activities[scope].let {d.copy(plays=it?.plays.orEmpty(),hidden=it?.hidden.orEmpty(),dismissed=it?.dismissed.orEmpty())}
    private fun updateActivity(scope:String=selectedScope,change:(Data)->Data)=update {root->
        if(scope==LocalActivity.SHARED)change(root) else change(view(root,scope)).let {changed->
            root.copy(activities=root.activities+(scope to Activity(changed.plays,changed.hidden,changed.dismissed)))
        }
    }

    /** Changes whenever the lists change, so screens reading it redraw. */
    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context)=initFile(File(context.filesDir,"library.json"))

    /** Queue disk IO before any writes; publishing on main never holds up the serial writer. */
    @Synchronized
    internal fun initFile(f:File,read:()->String={f.readText()}) {
        if (file != null) return
        file = f
        val task=FutureTask {
            android.os.Trace.beginSection("Ember.library.load")
            try {
                val loaded = runCatching { json.decodeFromString(Data.serializer(), read()) }.getOrDefault(Data()).let { d ->
                    // Files from before the limit: trimmed on the next save.
                    d.copy(
                        plays = d.plays.distinctBy { it.meta.id }.take(MAX_TITLES),
                        trackerProgress = d.trackerProgress.take(MAX_TITLES),
                        trackerHistory = d.trackerHistory.take(MAX_TITLES),
                    )
                }
                val safe=protect(loaded)
                if(safe!=loaded)AtomicFile.write(f,json.encodeToString(Data.serializer(),safe))
                safe
            } finally {android.os.Trace.endSection()}
        }
        initialization=task
        writer.execute {
            task.run()
            onMain {synchronized(this) {finishInitialization(task)}}
        }
    }

    private fun onMain(action:()->Unit) {android.os.Handler(android.os.Looper.getMainLooper()).post {action()}}

    /** Call under the library monitor. The IO task never needs that monitor to finish. */
    private fun finishInitialization(expected:FutureTask<Data>?=initialization) {
        if(expected==null || initialization!==expected)return
        data=expected.get()
        initialization=null
        onMain {version++}
    }

    /** Startup refresh can wait without blocking Home's first frame. */
    suspend fun awaitReady()=withContext(Dispatchers.IO) {synchronized(this@LocalLibrary) {finishInitialization()}}

    internal fun validateBackup(text:String) {json.decodeFromString(Data.serializer(),text)}

    /** Export after initialization and every earlier queued write, on the same IO executor. */
    @Synchronized internal fun <T> snapshotFiles(read:()->T):T {
        finishInitialization()
        val snapshot=data
        val target=file
        return try {writer.submit<T> {
            // An earlier background write may have failed; do not export an older disk copy.
            target?.let {AtomicFile.write(it,json.encodeToString(Data.serializer(),snapshot)).getOrElse {
                throw Backup.BackupException("Could not save the current library. No backup was created.")
            }}
            read()
        }.get()} catch(error:java.util.concurrent.ExecutionException) {throw (error.cause ?: error)}
    }

    private fun protect(d: Data): Data = d.copy(
        dismissed = protectDismissed(d.dismissed),
        activities=d.activities.mapValues {(_,a)->a.copy(plays=a.plays.take(MAX_TITLES).map {it.copy(meta=app.ember.tv.mediaserver.ServerMetadata.safe(it.meta))},hidden=a.hidden.take(MAX_HIDDEN),dismissed=protectDismissed(a.dismissed))},
        plays=d.plays.map { it.copy(meta=app.ember.tv.mediaserver.ServerMetadata.safe(it.meta)) },
        trackerProgress=d.trackerProgress.map(app.ember.tv.mediaserver.ServerMetadata::safe),
        trackerHistory=d.trackerHistory.map(app.ember.tv.mediaserver.ServerMetadata::safe),
        trackerWatchlist=d.trackerWatchlist.map(app.ember.tv.mediaserver.ServerMetadata::safe),
        pending=d.pending.map { it.copy(meta=app.ember.tv.mediaserver.ServerMetadata.safe(it.meta)) })

    private fun protectDismissed(values: Map<String, List<Meta>>) = values.filterKeys { it in setOf("continue", "watchlist") }
        .mapValues { (_, list) -> list.take(MAX_HIDDEN).map { it.withoutServerSecrets() } }

    @Synchronized
    private fun update(change: (Data) -> Data) {
        // Early actions wait for the queued read instead of overwriting saved progress/watchlists.
        finishInitialization()
        data = change(data)
        data=protect(data)
        val snapshot = data
        val f = file ?: return
        writer.execute {
            AtomicFile.write(f,json.encodeToString(Data.serializer(),snapshot))
        }
        onMain {version++}
    }

    /** Drain older writes before restoring files, then replace the in-memory library too. */
    @Synchronized
    internal fun restoreFiles(restore: () -> Unit) {
        finishInitialization()
        try {writer.submit { restore() }.get()}
        catch(error:java.util.concurrent.ExecutionException) {throw (error.cause ?: error)}
        data = file?.let { f ->
            runCatching { json.decodeFromString(Data.serializer(), f.readText()) }.getOrNull()
        } ?: Data()
        data = protect(data)
        file?.let { f -> app.ember.tv.data.AtomicFile.write(f, json.encodeToString(Data.serializer(), data)) }
        android.os.Handler(android.os.Looper.getMainLooper()).post { version++ }
    }

    fun isFinished(key:String,scope:String=selectedScope):Boolean {version;return view(data,scope).plays.any {it.key==key && it.finished}}
    fun metaFor(key:String,scope:String=selectedScope):Meta? = view(data,scope).plays.firstOrNull {it.key==key}?.meta

    // ---- plays (the built-in player and any player started from Ember) ----

    /** A play started: remember the title under the player's key, as the latest thing watched. */
    fun started(key: String, meta: Meta,scope:String=selectedScope) = updateActivity(scope) { d ->
        val old = d.plays.firstOrNull { it.key == key }
        // Kept whole (a server title keeps its link, so it plays again from here); the file is private to Ember.
        // Watching again after the end starts from nothing (not "100% in progress").
        val from = old?.takeIf { !it.finished }?.progress ?: 0f
        val entry = LibraryEntry(key, meta.copy(progress = null), from, false, System.currentTimeMillis())
        // One entry per title (a show keeps its latest episode), the last ten titles only.
        d.copy(
            plays = (listOf(entry) + d.plays.filter { it.key != key && it.meta.id != meta.id }).take(MAX_TITLES),
            hidden = d.hidden - meta.id,
            dismissed = d.dismissed + ("continue" to d.dismissed["continue"].orEmpty().filterNot { it.sameTitle(meta) }),
        )
    }

    /** How far the player got (0..1); near the end counts as watched. */
    fun progress(key: String, fraction: Float, durationMs: Long? = null,scope:String=selectedScope) = updateActivity(scope) { d ->
        if(!fraction.isFinite())return@updateActivity d
        val old = d.plays.firstOrNull { it.key == key } ?: return@updateActivity d
        val measured = durationMs?.takeIf { it > 0 }?.let { "${kotlin.math.ceil(it / 60_000.0).toInt()} min" }
        val entry = old.copy(meta = old.meta.copy(runtime = measured ?: old.meta.runtime), progress = fraction.coerceIn(0f, 1f), finished = fraction >= 0.92f, at = System.currentTimeMillis())
        d.copy(plays = listOf(entry) + d.plays.filter { it.key != key })
    }

    /**
     * An external player that reports no position: keep the title on In progress (with a sliver
     * of progress) rather than leaving it at 0%, which In progress hides. A finished title is left alone.
     */
    fun playedElsewhere(key: String, scope: String = selectedScope) = updateActivity(scope) { d ->
        val old = d.plays.firstOrNull { it.key == key } ?: return@updateActivity d
        if (old.finished || old.progress >= 0.01f) return@updateActivity d
        d.copy(plays = listOf(old.copy(progress = 0.02f, at = System.currentTimeMillis())) + d.plays.filter { it.key != key })
    }

    fun finished(key: String,scope:String=selectedScope) = updateActivity(scope) { d ->
        val old = d.plays.firstOrNull { it.key == key } ?: return@updateActivity d
        d.copy(plays = listOf(old.copy(progress = 1f, finished = true, at = System.currentTimeMillis())) + d.plays.filter { it.key != key })
    }

    /** The next episode started by auto-play: the same show, another episode. */
    fun startedEpisode(previousKey: String, key: String, season: Int, episode: Int, title: String?,scope:String=selectedScope) {
        synchronized(this) {finishInitialization()}
        val show = view(data,scope).plays.firstOrNull { it.key == previousKey }?.meta ?: return
        started(key, show.copy(season = season, episode = episode, badge = "S$season · E$episode" + (title?.takeIf { it.isNotBlank() }?.let { "  $it" } ?: "")),scope)
    }

    /** Takes a title off In progress (and Recently watched). */
    fun forget(meta: Meta) = updateActivity { d -> d.copy(plays = d.plays.filterNot { it.meta.sameTitle(meta) }) }

    fun hideList(list: String, meta: Meta) = updateActivity { d ->
        d.copy(dismissed = d.dismissed + (list to (listOf(meta) + d.dismissed[list].orEmpty()
            .filterNot { it.sameTitle(meta) }).take(MAX_HIDDEN)))
    }
    fun revealList(list: String, meta: Meta) = updateActivity { d ->
        d.copy(dismissed = d.dismissed + (list to d.dismissed[list].orEmpty().filterNot { it.sameTitle(meta) }))
    }
    @Synchronized fun isListHidden(list: String, meta: Meta): Boolean {
        version
        val items = view(data).dismissed[list].orEmpty()
        val key = selectedScope to list
        val cached = dismissedIndices[key]
        val index = if (cached?.first === items) cached.second else TitleIndex(items).also {
            if (dismissedIndices.size >= 32) dismissedIndices.clear()
            dismissedIndices[key] = items to it
        }
        return meta in index
    }
    fun restoreList(list: String) = updateActivity { it.copy(dismissed = it.dismissed - list) }

    /** Takes a title off Recently watched: Ember's play goes, and a Trakt entry stays hidden. */
    fun hideRecent(meta: Meta) = updateActivity { d ->
        d.copy(
            plays = d.plays.filterNot { it.meta.sameTitle(meta) && (it.finished || it.progress < 0.01f) },
            hidden = (listOf(meta.id) + d.hidden.filter { it != meta.id }).take(MAX_HIDDEN),
        )
    }

    /** Clears Recently watched: Ember's finished plays go and every title shown ([shown]) stays hidden. */
    fun clearRecent(shown: List<Meta>) = updateActivity { d ->
        d.copy(
            plays = d.plays.filter { !it.finished && it.progress >= 0.01f },
            hidden = (shown.map { it.id } + d.hidden).distinct().take(MAX_HIDDEN),
        )
    }

    fun isHidden(meta: Meta) = meta.id in view(data).hidden

    /** Empties everything (tests). */
    @Synchronized internal fun resetForTest() {
        finishInitialization()
        writer.submit {}.get()
        file=null
        data = Data()
        dismissedIndices.clear()
        selectedScope=LocalActivity.SHARED
    }

    /** Part-way through, newest first; one entry per title (a show's latest episode). */
    val inProgress: List<Meta>
        get() = view(data).plays.distinctBy { it.meta.id } // the latest play of each title decides
            .filter { !it.finished && it.progress >= 0.01f && !isListHidden("continue", it.meta) }
            .map { it.meta.copy(progress = it.progress, progressSource = it.meta.serverName ?: "Ember", badge = it.meta.badge ?: episodeLabel(it.meta)) }

    /** Everything played lately, newest first; one entry per title. */
    val recent: List<Meta>
        get() = view(data).plays.distinctBy { it.meta.id }.filter { it.meta.id !in view(data).hidden }.take(MAX_TITLES)
            .map { it.meta.copy(progress = if (it.finished) null else it.progress.takeIf { p -> p > 0.01f }, badge = it.meta.badge ?: episodeLabel(it.meta)) }

    private fun episodeLabel(m: Meta) = if (m.season != null && m.episode != null) "S${m.season} · E${m.episode}" else null

    // ---- copies of what the trackers said ----

    val trackerProgress: List<Meta> get() = data.trackerProgress
    val trackerHistory: List<Meta> get() = data.trackerHistory
    val trackerWatchlist: List<Meta> get() = data.trackerWatchlist

    // Only the first ten of each are kept offline.
    fun saveTrackerProgress(list: List<Meta>) = update { it.copy(trackerProgress = list.take(MAX_TITLES).map { m -> m.withoutServerSecrets().copy(progress = m.safeProgress) }) }
    fun saveTrackerHistory(list: List<Meta>) = update { it.copy(trackerHistory = list.take(MAX_TITLES).map { m -> m.withoutServerSecrets() }) }
    fun saveTrackerWatchlist(list: List<Meta>) = update { it.copy(trackerWatchlist = list.map { m -> m.withoutServerSecrets() }) }

    // ---- watchlist changes waiting for the trackers ----

    val pending: List<PendingWatchlist> get() = data.pending

    fun queue(add: Boolean, meta: Meta, targets: Set<String>? = null) = update { d ->
        // The latest change to a title wins.
        d.copy(pending = d.pending.filterNot { it.meta.sameTitle(meta) } + PendingWatchlist(add, meta.withoutServerSecrets(), targets, java.util.UUID.randomUUID().toString()))
    }

    fun done(item: PendingWatchlist) = remaining(item, emptySet())

    /** A stale completion must never erase a newer action for the same title. */
    fun remaining(item: PendingWatchlist, targets: Set<String>) = update { d ->
        d.copy(pending = d.pending.mapNotNull { current ->
            if (current != item) current else targets.takeIf { it.isNotEmpty() }?.let { current.copy(targets = it) }
        })
    }
}
