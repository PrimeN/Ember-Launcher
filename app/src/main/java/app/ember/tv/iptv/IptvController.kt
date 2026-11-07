@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.ember.tv.iptv

import app.ember.tv.data.toHex
import androidx.compose.runtime.mutableLongStateOf

import androidx.core.content.edit

import app.ember.tv.data.SourceFailure
import app.ember.tv.data.SourceHealth
import app.ember.tv.data.ConfigurationGeneration
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.tracker.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * IPTV for the Live TV page: your sources (M3U playlists and Xtream Codes logins), their channels,
 * the programme guide, favourites and recently watched. Channels and the guide are cached on the
 * TV so the page opens instantly; the guide refreshes every 12 hours.
 */
class IptvController(
    context: Context,
    private val scope: CoroutineScope,
    /** Read the cached channels and guide at start (the app); the background refresh skips that. */
    loadCache: Boolean = true,
    private val onSourceAdded: () -> Unit = {},
) {
    private val app = context.applicationContext
    private val cacheGeneration=ConfigurationGeneration.current(app.filesDir)
    private val store = TokenStore(app) // sources hold passwords, so they are kept encrypted
    private val prefs = app.getSharedPreferences("ember", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val dir = File(app.filesDir, "iptv").apply { mkdirs() }
    private val encrypted = EncryptedIptvFiles(dir)

    var sources by mutableStateOf(loadSources()); private set
    var channels by mutableStateOf<List<Channel>>(emptyList()); private set
    /** Guide by channel key. */
    var guide by mutableStateOf<Map<String, List<Programme>>>(emptyMap()); private set
    var epg by mutableStateOf(runCatching { json.decodeFromString<EpgConfiguration>(store.get("iptv.epg") ?: "{}") }
        .getOrDefault(EpgConfiguration())); private set
    var parsedFeeds by mutableStateOf<Map<String, ParsedEpg>>(emptyMap()); private set
    var guideMatches by mutableStateOf<Map<String, EpgMatch>>(emptyMap()); private set
    var guideProgress by mutableStateOf<String?>(null); private set
    var favourites by mutableStateOf(loadPrivateKeys("iptv.favourites", "iptvFavourites", true).toSet()); private set
    var recent by mutableStateOf(loadPrivateKeys("iptv.recent", "iptvRecent", false)); private set
    var guideReturnKey by mutableStateOf<String?>(null); private set
    fun requestGuide(channel: Channel) { guideReturnKey = channel.key }
    fun guideReturned() { guideReturnKey = null }
    var library by mutableStateOf(runCatching {
        json.decodeFromString<LiveLibrary>(store.get("iptv.library") ?: "{}")
    }.getOrDefault(LiveLibrary())); private set
    val visibleChannels by derivedStateOf { library.visible(channels, sources.filter { it.enabled }.map { it.id }.toSet()) }

    private fun saveLibrary(value: LiveLibrary): Boolean {
        if (!store.putEncrypted("iptv.library", json.encodeToString(value))) {
            message = "Encrypted storage is unavailable. No settings were changed."
            return false
        }
        library = value
        return true
    }
    fun guideDisplay(rows: Int = library.guideRows, logos: Boolean = library.guideLogos,
        compact: Boolean = library.compactProgramme, panel: Boolean = library.showProgrammePanel) =
        saveLibrary(library.copy(guideRows = rows.coerceIn(5, 7), guideLogos = logos, compactProgramme = compact, showProgrammePanel = panel))

    fun setChannelHidden(key: String, hidden: Boolean) = saveLibrary(library.copy(
        hiddenChannels = if (hidden) library.hiddenChannels + key else library.hiddenChannels - key))

    fun setGroupHidden(sourceId: String, group: String, hidden: Boolean) {
        val key = LiveLibrary.groupKey(sourceId, group)
        saveLibrary(library.copy(hiddenGroups = if (hidden) library.hiddenGroups + key else library.hiddenGroups - key))
    }

    fun setOnNow(on: Boolean) = saveLibrary(library.copy(showOnNow = on))
    fun setSectionHidden(id: String, hidden: Boolean) = saveLibrary(library.copy(
        hiddenSections = if (hidden) library.hiddenSections + id else library.hiddenSections - id))

    fun createList(name: String, channelKey: String? = null): String? {
        val title = name.trim().take(80)
        if (title.isBlank() || library.lists.any { it.name.equals(title, ignoreCase = true) }) return null
        val id = UUID.randomUUID().toString()
        return id.takeIf { saveLibrary(library.copy(lists = library.lists + ChannelList(id, title, listOfNotNull(channelKey)))) }
    }

    fun renameList(id: String, name: String): Boolean {
        val title = name.trim().take(80)
        if (title.isBlank() || library.lists.none { it.id == id } || library.lists.any { it.id != id && it.name.equals(title, ignoreCase = true) }) return false
        return saveLibrary(library.copy(lists = library.lists.map { if (it.id == id) it.copy(name = title) else it }))
    }

    fun deleteList(id: String) = saveLibrary(library.copy(lists = library.lists.filterNot { it.id == id },
        hiddenSections = library.hiddenSections - "custom:$id"))
    fun toggleListChannel(id: String, key: String) = saveLibrary(library.toggleMember(id, key))
    val health = SourceHealth("iptv")
    var loading by mutableStateOf(false); private set
    var guideLoading by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    /** Bumped every minute while Live TV is open, so "now" and progress bars move on. */
    var clock by mutableLongStateOf(System.currentTimeMillis()); private set

    /** The guide link each playlist names in its header (url-tvg), by source id. */
    private var playlistGuides: Map<String, String> = emptyMap()
    private var guideSavedAt = 0L
    private var refreshJob: Job? = null

    @Serializable
    private data class Cache(val channels: List<Channel>, val guides: Map<String, String> = emptyMap(), val at: Long = 0,val generation:String="")

    @Serializable
    private data class GuideCache(val guide: Map<String, List<Programme>> = emptyMap(), val at: Long,
        val feeds: Map<String, ParsedEpg> = emptyMap(),val generation:String="")

    init {
        if (loadCache) shared = this
        if (loadCache) scope.launch {
            withContext(Dispatchers.IO) {
                encrypted.readMigrating("channels", File(dir, "channels.json"),
                    { json.decodeFromStream<Cache>(it) }, { value, out -> json.encodeToStream(value, out) }).getOrNull()
            }?.takeIf {it.generation==cacheGeneration && cacheGeneration==ConfigurationGeneration.current(app.filesDir)}?.let {
                channels = it.channels.filter { c -> sources.any { s -> s.id == c.sourceId } }
                playlistGuides = it.guides
            }
            withContext(Dispatchers.IO) {
                encrypted.readMigrating("guide", File(dir, "guide.json"),
                    { json.decodeFromStream<GuideCache>(it) }, { value, out -> json.encodeToStream(value, out) }).getOrNull()
            }?.takeIf {it.generation==cacheGeneration && cacheGeneration==ConfigurationGeneration.current(app.filesDir)}?.let { guide = it.guide; parsedFeeds = it.feeds; guideSavedAt = it.at; if (it.feeds.isNotEmpty()) rebuildGuide() }
            refresh(force = false)
        }
    }

    val hasSources: Boolean get() = sources.any { it.enabled }

    // ---- groups ----

    /** Groups in playlist order; with several sources each group is prefixed by its source. */
    val groups: List<String>
        get() {
            val multi = sources.count { it.enabled } > 1
            return visibleChannels.map { groupOf(it, multi) }.distinct()
        }

    fun groupOf(c: Channel, multi: Boolean = sources.count { it.enabled } > 1): String =
        if (multi) "${sources.firstOrNull { it.id == c.sourceId }?.name ?: "IPTV"} · ${c.group}" else c.group

    fun channelsIn(group: String): List<Channel> = when (group) {
        FAVOURITES -> visibleChannels.filter { it.key in favourites }
        RECENT -> visibleChannels.associateBy { it.key }.let { byKey -> recent.mapNotNull { byKey[it] } }
        ALL -> visibleChannels
        else -> visibleChannels.filter { groupOf(it) == group }
    }

    // ---- guide ----

    private val programmeLookups=ProgrammeLookupCache()
    fun nowNext(c: Channel, at: Long = clock): Pair<Programme?, Programme?> =
        programmeLookups.get(c.key,guide[c.key].orEmpty()).nowNext(at)

    fun upcoming(c: Channel, count: Int = 12, at: Long = clock): List<Programme> =
        programmeLookups.get(c.key,guide[c.key].orEmpty()).upcoming(at,count)

    fun guideMessage(c: Channel): String {
        val source = sources.firstOrNull { it.id == c.sourceId } ?: return "Source unavailable"
        val status = health.entries["${source.id}:guide"]
        return when {
            status?.loading == true -> "Refreshing programme guide…"
            status?.failed == true -> if (guide[c.key].isNullOrEmpty()) "Programme guide unavailable" else "Saved programme guide · source unavailable"
            epg.feedIds(source.id, availableFeeds()).isEmpty() -> "No programme guide configured for this playlist"
            guide[c.key].isNullOrEmpty() -> "No matching programme information for this channel"
            else -> "No programme information at this time"
        }
    }

    fun setChannelsHidden(keys: Set<String>, hidden: Boolean) = saveLibrary(library.copy(
        hiddenChannels = if (hidden) library.hiddenChannels + keys else library.hiddenChannels - keys))
    fun restoreHiddenChannels(keys: Set<String>) = saveLibrary(library.copy(hiddenChannels = keys))
    fun setFavourites(keys: Set<String>, on: Boolean) {
        val value = if (on) favourites + keys else favourites - keys
        if (store.putEncrypted("iptv.favourites", json.encodeToString(value.toList()))) favourites = value
        else message = "Encrypted storage is unavailable. Favourites were not changed."
    }
    fun setListChannels(id: String, keys: List<String>, add: Boolean) = saveLibrary(library.setMembers(id, keys, add))
    fun moveList(id: String, delta: Int) = saveLibrary(library.moveList(id, delta))
    fun moveListChannel(id: String, key: String, delta: Int) = saveLibrary(library.moveMember(id, key, delta))

    fun tick() {
        clock = System.currentTimeMillis()
    }

    // ---- favourites and recent ----

    fun toggleFavourite(c: Channel) {
        setFavourites(setOf(c.key), c.key !in favourites)
    }

    fun played(c: Channel) {
        recent = (listOf(c.key) + recent.filter { it != c.key }).take(20)
        if (!store.putEncrypted("iptv.recent", json.encodeToString(recent)))
            message = "Recent channels are available in memory; encrypted history could not be saved."
    }

    /** M3U channel keys can contain a stream URL, so favourites/history need encryption too. */
    private fun loadPrivateKeys(key: String, old: String, set: Boolean): List<String> {
        val saved = store.get(key)?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
        val legacy = if (set) prefs.getStringSet(old, emptySet()).orEmpty().toList()
            else prefs.getString(old, "").orEmpty().split("\n").filter { it.isNotBlank() }
        if (saved != null) { prefs.edit { remove(old) }; return saved }
        if (legacy.isNotEmpty()) {
            val encoded = json.encodeToString(legacy)
            if (store.putEncrypted(key, encoded) && store.get(key) == encoded) prefs.edit { remove(old) }
        }
        return legacy
    }

    // ---- sources ----

    private fun loadSources(): List<IptvSource> =
        runCatching { json.decodeFromString<List<IptvSource>>(store.get("iptv.sources") ?: "[]") }.getOrDefault(emptyList())

    private fun saveSources(list: List<IptvSource>): Boolean {
        if (!store.putEncrypted("iptv.sources", json.encodeToString(list))) {
            message = "Encrypted storage is unavailable. Provider credentials were not saved."
            return false
        }
        sources = list
        return true
    }

    /** Adds a source after checking it works. Returns an error message, or null when added. */
    suspend fun add(kind: String, name: String, url: String, username: String, password: String, epgUrl: String, userAgent: String, format: String): String? {
        val u = url.trim()
        if (u.isBlank()) return if (kind == "xtream") "Enter the server address." else "Enter the playlist link."
        if (kind == "xtream" && (username.isBlank() || password.isBlank())) return "Enter the username and password."
        val s = IptvSource(
            id = UUID.randomUUID().toString().take(8),
            name = name.trim().ifBlank { if (kind == "xtream") Xtream.base(IptvSource("", "", kind, u)).substringAfter("://").substringBefore('/') else "Playlist ${sources.size + 1}" },
            kind = kind,
            url = if (kind == "m3u" && !u.startsWith("http")) "http://$u" else u,
            username = username.trim(),
            password = password,
            epgUrl = epgUrl.trim(),
            userAgent = userAgent.trim(),
            streamFormat = format,
        )
        if (kind == "xtream") Xtream.check(s)?.let { return it }
        val got = runCatching { load(s, interactive = true) }.getOrElse {
            currentCoroutineContext().ensureActive(); return ProviderRequests.failure(it)
        }
        if (got.first.isEmpty()) return "No channels found at that link."
        if (!saveSources(sources + s)) return message
        onSourceAdded()
        refresh(force = true)
        return null
    }

    fun remove(id: String) {
        if (!saveSources(sources.filter { it.id != id })) return
        channels = channels.filter { it.sourceId != id }
        refresh(force = true)
    }

    fun setEnabled(id: String, on: Boolean) {
        if (!saveSources(sources.map { if (it.id == id) it.copy(enabled = on) else it })) return
        refresh(force = true)
    }

    /** Reloads channels (and the guide when it is older than 12 hours, or when [force]). */
    fun refresh(force: Boolean, only: String? = null) {
        refreshJob?.cancel()
        guideJob?.cancel()
        loading = false
        guideLoading = false
        health.cancelLoading()
        refreshJob = scope.launch {
            val enabled = sources.filter { it.enabled }
            val ids = enabled.map { it.id }.toSet()
            health.retain(ids + ids.map { "$it:guide" })
            if (enabled.isEmpty()) { channels = emptyList(); guide = emptyMap(); return@launch }
            val targets = enabled.filter { only == null || it.id == only }
            loading = true
            message = null
            val previous = channels.groupBy { it.sourceId }
            val refreshed = enabled.associate { it.id to previous[it.id].orEmpty() }.toMutableMap()
            val guides = playlistGuides.filterKeys { it in ids }.toMutableMap()
            targets.forEach { health.start(it.id, it.name + " channels") }
            try {
                ProgressiveSources.load(targets, fetch = { load(it) }) { source, result ->
                    result.onSuccess { (list, epg) ->
                        refreshed[source.id] = list
                        if (epg != null) guides[source.id] = epg else guides.remove(source.id)
                    }
                    health.finish(source.id, result.isSuccess, cached = result.isFailure && previous[source.id].orEmpty().isNotEmpty(),failure=result.exceptionOrNull()?.let(SourceFailure::from))
                    channels = enabled.flatMap { refreshed[it.id].orEmpty() }
                    playlistGuides = guides.toMap()
                }
                val all = channels
                if (health.entries.values.any { it.failed }) message = "Some sources are unavailable. Saved channels remain available."
                val cached = withContext(Dispatchers.IO) {
                    ConfigurationGeneration.writeIfCurrent(app.filesDir,cacheGeneration) {
                        encrypted.write("channels") { json.encodeToStream(Cache(all,guides,System.currentTimeMillis(),cacheGeneration),it) }.getOrThrow()
                    }
                }
                if (cached.isFailure) message = "Channels are available in memory; encrypted cache could not be updated."
                // Migration/re-encryption changes file mtime; freshness follows the original import.
                val guideAge = if (guideSavedAt > 0) System.currentTimeMillis() - guideSavedAt else Long.MAX_VALUE
                if (force || guideAge > 12 * 3600_000L || guide.isEmpty()) refreshGuide(only)
            } finally {
                if (refreshJob == coroutineContext[Job]) loading = false
            }
        }
    }

    /** Refreshes, then waits until the channels and the guide are done (background refresh). */
    suspend fun refreshAndWait(force: Boolean) {
        refresh(force)
        refreshJob?.join()
        guideJob?.join()
    }

    /** Channels (and the playlist's guide link) for one source. */
    private suspend fun load(s: IptvSource, interactive: Boolean = false): Pair<List<Channel>, String?> = withContext(Dispatchers.IO) {
        if (s.kind == "xtream") {
            Xtream.channels(s, interactive) to Xtream.epgUrl(s)
        } else {
            val p = ProviderRequests.read(s.id, s.url, s.userAgent, interactive) {
                it.bufferedReader().useLines { lines -> M3u.parseLines(lines, s, skipVod = true) }
            }
            p.channels.map { c -> if (c.userAgent == null && s.userAgent.isNotBlank()) c.copy(userAgent = s.userAgent) else c } to p.epgUrl
        }
    }

    /** Downloads each source's guide and keeps the next 36 hours for the channels you have. */
    fun refreshGuide(only: String? = null) {
        guideJob?.cancel()
        guideLoading = false
        health.cancelLoading(health.entries.keys.filter { it.endsWith(":guide") }.toSet())
        guideJob = scope.launch {
            val list = channels
            if (list.isEmpty()) return@launch
            guideLoading = true
            try {
                loadGuide(list, only)
            } finally {
                if (guideJob == coroutineContext[Job]) guideLoading = false
            }
        }
    }

    private var guideJob: Job? = null
    /** One guide parse at a time: big XMLTV files take a lot of memory. */
    private val parseLock = kotlinx.coroutines.sync.Mutex()

    fun availableFeeds(): List<EpgFeed> = sources.mapNotNull { source ->
        val url = source.epgUrl.ifBlank { playlistGuides[source.id].orEmpty() }
        url.takeIf { it.isNotBlank() }?.let { EpgFeed("provider:${source.id}", "${source.name} guide", it, source.userAgent) }
    } + epg.feeds

    private fun fingerprint(feed: EpgFeed) = java.security.MessageDigest.getInstance("SHA-256")
        .digest((feed.url + "\n" + feed.userAgent).toByteArray()).toHex()

    fun addGuide(name: String, url: String, agent: String): String? {
        val parsed = url.trim().toHttpUrlOrNull()
            ?: return "Enter an HTTP or HTTPS XMLTV URL."
        val feed = EpgFeed(UUID.randomUUID().toString(), name.trim().take(80).ifBlank { "Shared guide" }, parsed.toString(), agent.trim())
        return if (saveEpg(epg.copy(feeds = epg.feeds + feed))) null else "Encrypted storage is unavailable. Guide was not saved."
    }
    fun removeGuide(id: String) = saveEpg(epg.copy(feeds = epg.feeds.filterNot { it.id == id },
        assignments = epg.assignments.mapValues { (_, ids) -> ids - id }, offsets = epg.offsets - id,
        overrides = epg.overrides.filterValues { it.feedId != id }))
    fun assignGuide(source: String, feed: String, enabled: Boolean) {
        val current = epg.feedIds(source, availableFeeds())
        saveEpg(epg.copy(assignments = epg.assignments + (source to if (enabled) (current + feed).distinct() else current - feed)))
    }
    fun setGuideOffset(feed: String, minutes: Int) = saveEpg(epg.copy(offsets = epg.offsets + (feed to minutes.coerceIn(-1440, 1440))), refresh = false)
    fun matchGuide(channel: Channel, feed: String?, id: String?) = saveEpg(epg.copy(overrides =
        if (feed == null || id == null) epg.overrides - channel.key else epg.overrides + (channel.key to EpgOverride(feed, id))))
    private fun saveEpg(value: EpgConfiguration, refresh: Boolean = true): Boolean {
        if (!store.putEncrypted("iptv.epg", json.encodeToString(value))) {
            message = "Encrypted storage is unavailable. No guide settings were changed."
            return false
        }
        epg = value
        guideJob?.cancel()
        health.cancelLoading(health.entries.keys.filter { it.endsWith(":guide") }.toSet())
        rebuildGuide()
        if (refresh) refreshGuide() else guideProgress = "${guideMatches.size} channels matched · time offsets updated"
        return true
    }
    private fun rebuildGuide() {
        val feeds = availableFeeds()
        val valid = parsedFeeds.filter { (id, data) -> feeds.any { it.id == id && fingerprint(it) == data.fingerprint } }
        val matches = mutableMapOf<String, EpgMatch>()
        val result = mutableMapOf<String, List<Programme>>()
        val from = System.currentTimeMillis() - 2 * 3600_000L
        val to = System.currentTimeMillis() + 36 * 3600_000L
        for (channel in channels.filter { c -> sources.any { it.id == c.sourceId && it.enabled } }) {
            EpgMatching.select(channel, epg.feedIds(channel.sourceId, feeds), epg, valid)?.let { match ->
                matches[channel.key] = match
                result[channel.key] = EpgMatching.shifted(valid[match.feedId]?.programmes?.get(match.channelId).orEmpty(),
                    epg.offset(match.feedId), from, to)
            }
        }
        guideMatches = matches; guide = result
    }
    private suspend fun loadGuide(list: List<Channel>, only: String?) {
        val enabled = sources.filter { it.enabled }
        val feeds = availableFeeds()
        val assignments = enabled.associate { it.id to epg.feedIds(it.id, feeds) }
        val requested = enabled.filter { only == null || it.id == only }.flatMap { assignments[it.id].orEmpty() }.toSet()
        val targets = feeds.filter { it.id in requested }.distinctBy { it.id }
        val working = parsedFeeds.filter { (id, data) -> feeds.any { it.id == id && fingerprint(it) == data.fingerprint } }.toMutableMap()
        val outcomes = mutableMapOf<String, Boolean>()
        enabled.filter { only == null || it.id == only }.forEach { health.start("${it.id}:guide", it.name + " guide") }
        targets.forEachIndexed { index, feed ->
            guideProgress = "Loading guide ${index + 1}/${targets.size} · ${feed.name}"
            val members = list.filter { feed.id in assignments[it.sourceId].orEmpty() }
            val ids = members.mapNotNull { it.epgId }.toSet() + epg.overrides.values.filter { it.feedId == feed.id }.map { it.channelId }
            val names = members.map { Xmltv.norm(it.name) }.toSet()
            val offset = epg.offset(feed.id)
            val now = System.currentTimeMillis()
            val from = now - 2 * 3600_000L - offset
            val to = now + 36 * 3600_000L - offset
            val parsed = parseLock.withLock {
                runCatching { ProviderRequests.read(sources.firstOrNull { "provider:${it.id}" == feed.id && it.kind == "xtream" }
                    ?.let { Xtream.budgetKey(it) } ?: "guide:${feed.id}", feed.url, feed.userAgent) {
                    Xmltv.parseDetailed(it, ids, names, from, to)
                } }
            }
            currentCoroutineContext().ensureActive()
            outcomes[feed.id] = parsed.isSuccess
            parsed.onSuccess { fresh ->
                val old = working[feed.id]
                val programmes = fresh.programmes.toMutableMap()
                old?.programmes?.forEach { (id, values) ->
                    val history = values.filter { it.stop <= now - offset && it.stop > from }
                    if (history.isNotEmpty()) programmes[id] = (history + programmes[id].orEmpty())
                        .distinctBy { Triple(it.start, it.stop, it.title) }.sortedBy { it.start }
                }
                working[feed.id] = fresh.copy(programmes = programmes, fingerprint = fingerprint(feed))
            }
            parsedFeeds = working.toMap()
            rebuildGuide()
        }
        enabled.filter { only == null || it.id == only }.forEach { source ->
            val ids = assignments[source.id].orEmpty()
            if (ids.isEmpty()) health.retain(health.entries.keys - "${source.id}:guide")
            else health.finish("${source.id}:guide", ids.all { outcomes[it] == true }, cached = ids.any { it in working })
        }
        currentCoroutineContext().ensureActive()
        parsedFeeds = working.toMap(); rebuildGuide()
        guideProgress = "${guideMatches.size}/${channels.count { c -> enabled.any { it.id == c.sourceId } }} channels matched · ${targets.size} guides checked"
        val importedAt = if (outcomes.values.any { !it }) guideSavedAt else System.currentTimeMillis()
        val saved = withContext(Dispatchers.IO) {
            ConfigurationGeneration.writeIfCurrent(app.filesDir,cacheGeneration) {
                encrypted.write("guide") { json.encodeToStream(GuideCache(at=importedAt,feeds=working,generation=cacheGeneration),it) }.getOrThrow()
            }
        }
        if (saved.isSuccess) guideSavedAt = importedAt
        if (saved.isFailure) message = "Guide is available in memory; encrypted cache could not be updated."
    }

    /** False once the owner's scope is cancelled: refresh() would then launch nothing. */
    val alive get() = scope.isActive

    companion object {
        private val sharedOwner=ActiveController<IptvController> {it.alive}
        /** The app's own controller, so the background refresh updates it instead of making a second one.
         * Once its owner (the view model) is cleared it is dropped, so the refresh makes its own again. */
        var shared: IptvController?
            get()=sharedOwner.get()
            set(value) {sharedOwner.set(value)}

        const val FAVOURITES = "★ Favourites"
        const val RECENT = "Recently watched"
        const val ALL = "All channels"
    }
}
