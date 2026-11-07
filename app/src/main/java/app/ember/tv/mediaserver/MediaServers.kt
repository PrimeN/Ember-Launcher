package app.ember.tv.mediaserver

import app.ember.tv.data.toHex
import android.content.Context
import app.ember.tv.data.Meta
import app.ember.tv.data.StreamOption
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@kotlinx.serialization.Serializable
data class ServerRow(val title: String, val source: String, val items: List<Meta>, val inProgress: Boolean = false,val id:String=source+"|"+title)

/** One library on a server (Movies, TV Shows...), newest first, for the My media tab. */
data class ServerLibrary(val server: String, val title: String, val kind: String, val items: List<Meta>,val ref:String="",val id:String=ref.ifBlank { server+"|"+title },val total:Int?=null)

/** An addon title to look for on your media servers. [season] and [episode] are set for series. */
data class ServerQuery(
    val imdbId: String?,
    val tmdbId: Long?,
    val title: String,
    val year: Int?,
    val movie: Boolean,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val hasIds: Boolean get() = imdbId != null || tmdbId != null

    companion object {
        private fun norm(s: String) = s.lowercase().replace("&", "and").filter { it.isLetterOrDigit() }

        fun sameTitle(a: String?, b: String): Boolean = a != null && norm(a).isNotEmpty() && norm(a) == norm(b)

        /** Years within one of each other count, since release dates differ by country. */
        fun sameYear(a: Int?, b: Int?): Boolean = a == null || b == null || kotlin.math.abs(a - b) <= 1

        /** First four-digit year in text like "2019" or "2011–2019". */
        private val fourDigits = Regex("""\d{4}""")
        fun yearOf(releaseInfo: String?): Int? = releaseInfo?.let { fourDigits.find(it)?.value?.toIntOrNull() }

        fun audioLabel(codec: String?, channels: Int?): String {
            val c = when (codec?.lowercase()) {
                null, "" -> return ""
                "truehd" -> "TrueHD"
                "eac3" -> "DD+"
                "ac3" -> "DD"
                "dca", "dts" -> "DTS"
                "aac" -> "AAC"
                "flac" -> "FLAC"
                "opus" -> "Opus"
                else -> codec.orEmpty().uppercase()
            }
            val ch = when (channels) {
                null -> ""
                8 -> " 7.1"
                6 -> " 5.1"
                2 -> " 2.0"
                else -> ""
            }
            return c + ch
        }
    }
}

enum class ReportState { START, PROGRESS, PAUSE, STOP }

/** A part of an episode the server has marked: "intro", "recap" or "credits", in milliseconds. */
data class Marker(val kind: String, val startMs: Long, val endMs: Long)

/** A chapter of a server item (Options > Video > Chapters). */
data class Chapter(val name: String, val startMs: Long)

/** All signed-in media servers: rows for Home, and progress reports from the player. */
class MediaServers(val accounts: Accounts, val plex: PlexClient = PlexClient(accounts), val jellyfin: JellyfinClient = JellyfinClient(accounts)) {
    companion object {
        internal fun forPlayback(context: Context) = MediaServers(Accounts(context).playbackSnapshot())
    }
    constructor(context: Context) : this(Accounts(context)) { homeCache = ServerHomeCache(context) }
    private var homeCache: ServerHomeCache? = null
    private var cacheProfile: String? = null
    internal fun accountFingerprint(): String {
        val input=listOf(accounts.plexAccessToken.orEmpty())+accounts.servers().sortedBy {it.key}.map {it.key+":"+it.userId+":"+it.token}
        return java.security.MessageDigest.getInstance("SHA-256").digest(input.joinToString("\u0000").toByteArray()).toHex()
    }
    suspend fun restoreHome(profile: String): List<ServerRow> {
        if(cacheProfile!=profile) { rowCache.clear(); cacheProfile=profile }
        val fingerprint=accountFingerprint()
        val saved=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {homeCache?.load(profile,fingerprint)} ?: return emptyList()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if(cacheProfile!=profile || fingerprint!=accountFingerprint())return emptyList()
        val current=providers()
        current.forEach {p->saved.providers[p.id]?.let {rows->
            rowCache.putIfAbsent(p.id,rows)
            health.saved("${p.id}:rows",p.name+" · Home",saved.at)
        }}
        return current.flatMap {rowCache[it.id].orEmpty()}
    }
    suspend fun saveHome(profile:String) {
        if(profile!=cacheProfile)return
        val fingerprint=accountFingerprint();val snapshot=rowCache.toMap()
        val at=snapshot.keys.mapNotNull {health.entries["$it:rows"]?.lastSuccess}.minOrNull() ?: return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {homeCache?.save(profile,fingerprint,snapshot,at)}
    }


    val health = app.ember.tv.data.SourceHealth("servers")
    private val rowCache = mutableMapOf<String, List<ServerRow>>()
    private val libraryCache = mutableMapOf<String, List<ServerLibrary>>()
    private data class Provider(val id: String, val name: String,
        val rows: suspend ((Throwable) -> Unit) -> List<ServerRow>,
        val libraries: suspend ((Throwable) -> Unit) -> List<ServerLibrary>)
    private fun providers(): List<Provider> = buildList {
        if (plex.connected) add(Provider("plex", "Plex", { plex.rows(it) }, { plex.libraries(it) }))
        accounts.servers().forEach { account ->
            add(Provider(account.key, account.name, { jellyfin.rows(account, it) }, { jellyfin.libraries(account, it) }))
        }
    }

    suspend fun rows(only: String? = null, onUpdate: (List<ServerRow>) -> Unit = {}): List<ServerRow> = load("rows", rowCache, only,
        { p, failed -> p.rows(failed) }, { it.id }, onUpdate)

    /** Failures preserve this provider's rows; an empty success really empties them. */
    private suspend fun <T> load(kind: String, cache: MutableMap<String, List<T>>, only: String?,
        fetch: suspend (Provider, (Throwable) -> Unit) -> List<T>, key: (T) -> String, onUpdate: (List<T>) -> Unit): List<T> = coroutineScope {
        health.cancelLoading(health.entries.keys.filter { it.endsWith(":$kind") }.toSet())
        val providers = providers()
        val ids = providers.map { it.id }.toSet()
        cache.keys.retainAll(ids)
        health.retain(ids.flatMap { listOf("$it:rows", "$it:libraries") }.toSet())
        val targets = providers.filter { only == null || it.id == only }
        targets.forEach { health.start("${it.id}:$kind", it.name + if (kind == "rows") " · Home" else " · Libraries") }
        val requests = Semaphore(4)
        targets.map { provider -> async { requests.withPermit {
            var partial = false
            var failure:Throwable?=null
            val result = serverResult { fetch(provider) {error ->partial = true
                if(failure==null || app.ember.tv.data.SourceFailure.from(error)==app.ember.tv.data.SourceFailure.ACCESS)failure=error
            } }
            ensureActive()
            val id = provider.id
            val success = result.isSuccess && !partial
            val old = cache[id].orEmpty()
            if (success) cache[id] = result.getOrThrow()
            else if (result.isSuccess) cache[id] = (result.getOrThrow() + old).distinctBy(key)
            health.finish("$id:$kind", success, cached = !success && old.isNotEmpty(), failure = (failure ?: result.exceptionOrNull())?.let(app.ember.tv.data.SourceFailure::from), empty = success && cache[id].isNullOrEmpty())
            onUpdate(providers.flatMap { cache[it.id].orEmpty() })
        } } }.awaitAll()
        providers.flatMap { cache[it.id].orEmpty() }
    }

    /** True when Plex or any Jellyfin/Emby server is signed in. */
    val connected: Boolean get() = plex.connected || accounts.servers().isNotEmpty()

    /** Copies of an addon title on every signed-in server, all asked at once. */
    suspend fun find(q: ServerQuery): List<StreamOption> = coroutineScope {
        val fromPlex = async { serverResult { plex.find(q) }.getOrDefault(emptyList()) }
        val others = accounts.servers().map { a -> async { serverResult { jellyfin.find(a, q) }.getOrDefault(emptyList()) } }
        (fromPlex.await() + others.awaitAll().flatten()).sortedByDescending { it.qualityRank }
    }

    /** The file versions behind a server item, for titles opened from a server row. [ref] is Meta.serverRef. */
    suspend fun sourcesFor(ref: String): List<StreamOption> {
        val parts = ref.split('|')
        if (parts.size != 3) return emptyList()
        return serverResult {
            when (parts[0]) {
                "plex" -> plex.sourcesFor(parts[1], parts[2])
                "jellyfin", "emby" -> accounts.servers().firstOrNull { it.key == parts[1] }
                    ?.let { jellyfin.sourcesFor(it, parts[2]) }.orEmpty()
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    /** IMDb and TMDB ids for a server item (the show's, for an episode), so addons can find it too. */
    suspend fun titleIds(ref: String): Pair<String?, Long?> {
        val parts = ref.split('|')
        if (parts.size != 3) return null to null
        return serverResult {
            when (parts[0]) {
                "plex" -> plex.titleIds(parts[1], parts[2])
                "jellyfin", "emby" -> accounts.servers().firstOrNull { it.key == parts[1] }
                    ?.let { jellyfin.titleIds(it, parts[2]) } ?: (null to null)
                else -> null to null
            }
        }.getOrDefault(null to null)
    }

    /** Every movie and TV library on your servers, newest additions first (My media tab). */
    suspend fun libraries(only: String? = null, onUpdate: (List<ServerLibrary>) -> Unit = {}): List<ServerLibrary> = load("libraries", libraryCache, only,
        { p, failed -> p.libraries(failed) }, { it.id }, onUpdate)

    /** Intro, recap and credits markers for a server item (Plex markers, Jellyfin media segments, Emby chapters). */
    suspend fun markers(ref: String): List<Marker> {
        val parts = ref.split('|')
        if (parts.size != 3) return emptyList()
        return serverResult {
            when (parts[0]) {
                "plex" -> plex.markers(parts[1], parts[2])
                "jellyfin", "emby" -> accounts.servers().firstOrNull { it.key == parts[1] }
                    ?.let { jellyfin.markers(it, parts[2]) } ?: emptyList()
                else -> emptyList()
            }
        }.getOrDefault(emptyList()).filter { it.endMs > it.startMs }.sortedBy { it.startMs }
    }

    /** Chapters of a server item (Plex, Jellyfin, Emby), in order. */
    suspend fun chapters(ref: String): List<Chapter> {
        val parts = ref.split('|')
        if (parts.size != 3) return emptyList()
        return serverResult {
            when (parts[0]) {
                "plex" -> plex.chapters(parts[1], parts[2])
                "jellyfin", "emby" -> accounts.servers().firstOrNull { it.key == parts[1] }?.let { jellyfin.chapters(it, parts[2]) } ?: emptyList()
                else -> emptyList()
            }
        }.getOrDefault(emptyList()).sortedBy { it.startMs }
    }

    /** Seek thumbnails for a server item, when the server has them (Plex, Jellyfin). */
    suspend fun trickplay(ref: String,sourceId:String?=null): Trickplay? {
        val parts = ref.split('|')
        if (parts.size != 3) return null
        return serverResult {
            when (parts[0]) {
                "plex" -> plex.trickplay(parts[1], parts[2],sourceId)
                "jellyfin" -> accounts.servers().firstOrNull { it.key == parts[1] }?.let { jellyfin.trickplay(it, parts[2],sourceId) }
                else -> null
            }
        }.getOrNull()
    }

    /** Marks a server item as watched (Plex scrobble, Jellyfin/Emby played items). */
    suspend fun markWatched(ref: String,watched:Boolean=true): Boolean {
        val parts = ref.split('|')
        if (parts.size != 3) return false
        return serverResult {
            when (parts[0]) {
                "plex" -> plex.markWatched(parts[1], parts[2],watched)
                "jellyfin", "emby" -> accounts.servers().firstOrNull { it.key == parts[1] }?.let { jellyfin.markWatched(it, parts[2],watched) } ?: false
                else -> false
            }
        }.getOrDefault(false)
    }

    /** [ref] is Meta.serverRef: "plex|serverId|ratingKey" or "jellyfin|accountKey|itemId". */
    suspend fun report(ref: String, state: ReportState, positionMs: Long, durationMs: Long,session:ServerPlayback?=null) {
        val parts = ref.split('|')
        if (parts.size != 3) return
        when (parts[0]) {
            "plex" -> plex.report(parts[1], parts[2], state, positionMs, durationMs)
            "jellyfin", "emby" -> accounts.servers().firstOrNull { it.key == parts[1] }
                ?.let { jellyfin.report(it, parts[2], state, positionMs,session) }
        }
    }

    suspend fun qualities(ref:String,sourceId:String):List<ServerQuality> {
        val r=ServerRef.parse(ref)?:return listOf(ServerQuality.ORIGINAL)
        return if(r.provider=="plex")plex.qualities(ref,sourceId) else jellyfin.qualities(account(r))
    }
    suspend fun playback(ref:String,sourceId:String,quality:ServerQuality):StreamOption {
        val r=ServerRef.parse(ref)?:error("Invalid server item.")
        return if(r.provider=="plex")plex.playback(ref,sourceId,quality) else jellyfin.playback(account(r),r.item,sourceId,quality)
    }
    suspend fun stopTranscode(transport:ServerPlayback) {
        if(transport.ref.provider=="plex")plex.stopTranscode(transport) else jellyfin.stopTranscode(account(transport.ref),transport)
    }

    suspend fun actions(ref:String):ServerActionState {
        val r=ServerRef.parse(ref)?:error("Invalid item.")
        return if(r.provider=="plex")plex.actions(ref) else jellyfin.actions(account(r),r.item)
    }
    suspend fun favorite(ref:String,value:Boolean) {val r=ServerRef.parse(ref)?:error("Invalid item.");check(r.provider!="plex");jellyfin.favorite(account(r),r.item,value)}
    suspend fun hideResume(ref:String) {
        val r=ServerRef.parse(ref)?:error("Invalid item.")
        if(r.provider=="plex") plex.hideResume(ref) else jellyfin.hideResume(account(r),r.item)
    }

    private fun account(r:ServerRef)=accounts.servers().firstOrNull{it.key==r.account&&it.kind==r.provider}?:error("Reconnect this media server in Settings.")
    suspend fun item(ref:String):Meta {val r=ServerRef.parse(ref)?:error("Invalid server reference.");return if(r.provider=="plex")plex.item(ref) else jellyfin.item(account(r),r.item)}
    suspend fun seasons(ref:String):List<ServerSeason> {val r=ServerRef.parse(ref)?:return emptyList();return if(r.provider=="plex")plex.seasons(ref) else jellyfin.seasons(account(r),r.item)}
    suspend fun episodes(ref:String,season:Int,offset:Int=0):ServerPage {val r=ServerRef.parse(ref)?:error("Invalid show.");return if(r.provider=="plex")plex.episodes(ref,season,offset) else jellyfin.episodes(account(r),r.item,season,offset)}
    suspend fun browse(ref:String,filter:ServerFilter,offset:Int=0):ServerPage {val r=ServerRef.parse(ref)?:error("Invalid library.");return if(r.provider=="plex")plex.browse(ref,filter,offset) else jellyfin.browse(account(r),r.item,filter,offset)}
    suspend fun genres(ref:String):List<Pair<String,String>> {val r=ServerRef.parse(ref)?:return emptyList();return if(r.provider=="plex")plex.genres(ref) else jellyfin.genres(account(r),r.item)}
    suspend fun artwork(ref:ServerRef,path:String):Pair<String,Map<String,String>> = if(ref.provider=="plex")plex.artwork(ref,path) else jellyfin.artwork(account(ref),path)
    suspend fun nextEpisode(ref:String):Meta? {
        val current=item(ref);val series=current.serverSeriesRef?:return null
        val seasons=seasons(series).filter { it.number >= (current.season?:0) }
        for(season in seasons) {
            var skip=0;var lastPage:String?=null
            do {
                val page=episodes(series,season.number,skip)
                page.items.firstOrNull { (it.season?:0)>(current.season?:0)||(it.season==current.season&&(it.episode?:0)>(current.episode?:0)) }?.let{return it}
                val fingerprint=page.items.joinToString { it.serverRef.orEmpty() }
                if(page.end||page.next<=skip||fingerprint==lastPage)break
                lastPage=fingerprint;skip=page.next
            }while(true)
        };return null
    }
    fun refreshConnections(){plex.refreshConnections()}
    fun invalidateAccounts() { rowCache.clear(); libraryCache.clear(); plex.refreshConnections() }

    suspend fun addServer(kind: String, url: String, user: String, password: String): ServerAccount {
        val account = jellyfin.login(kind, url, user, password)
        accounts.saveServers(accounts.servers().filterNot { it.url == account.url && it.userId == account.userId } + account)
        return account
    }

    fun removeServer(key: String) = accounts.saveServers(accounts.servers().filterNot { it.key == key })
}
