package app.ember.tv.state

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ember.tv.data.AddonClient
import app.ember.tv.data.AddonInfo
import app.ember.tv.data.ApiKeys
import app.ember.tv.data.AppEntry
import app.ember.tv.data.AppRepository
import app.ember.tv.data.Backup
import app.ember.tv.data.CatalogDef
import app.ember.tv.data.CatalogRow
import app.ember.tv.data.Credits
import app.ember.tv.data.Episode
import app.ember.tv.data.Features
import app.ember.tv.data.LocalLibrary
import app.ember.tv.data.Meta
import app.ember.tv.data.mediaKey
import app.ember.tv.data.MetaConfig
import app.ember.tv.data.MetaEnhance
import app.ember.tv.data.MetaExtra
import app.ember.tv.data.NavTabs
import app.ember.tv.data.Person
import app.ember.tv.data.PosterConfig
import app.ember.tv.data.Posters
import app.ember.tv.data.Prefs
import app.ember.tv.data.RatingSource
import app.ember.tv.data.Ratings
import app.ember.tv.data.StreamFormatDefaults
import app.ember.tv.data.StreamFormatter
import app.ember.tv.data.StreamOption
import app.ember.tv.data.StreamParser
import app.ember.tv.data.SubtitleOption
import app.ember.tv.iptv.IptvController
import app.ember.tv.mediaserver.FoundServer
import app.ember.tv.mediaserver.LanDiscovery
import app.ember.tv.mediaserver.MediaServers
import app.ember.tv.mediaserver.ServerQuery
import app.ember.tv.player.PlayRequest
import app.ember.tv.player.PlayerApp
import app.ember.tv.player.PlayerApps
import app.ember.tv.screensaver.ArtSources
import app.ember.tv.screensaver.Photos
import app.ember.tv.screensaver.ScreensaverFeed
import app.ember.tv.state.BackupState
import app.ember.tv.state.CollectionsState
import app.ember.tv.state.DiscoverState
import app.ember.tv.state.HomeSectionsState
import app.ember.tv.state.IptvSetupState
import app.ember.tv.state.LookState
import app.ember.tv.state.MetaSettingsState
import app.ember.tv.state.PinState
import app.ember.tv.state.PlayerSettingsState
import app.ember.tv.state.PlayingState
import app.ember.tv.state.ProfilesState
import app.ember.tv.state.SearchState
import app.ember.tv.state.SleepState
import app.ember.tv.state.SourceFormatState
import app.ember.tv.state.UpdatesState
import app.ember.tv.state.WeatherState
import app.ember.tv.state.WhereToWatchState
import app.ember.tv.streaming.Offer
import app.ember.tv.streaming.ServiceApps
import app.ember.tv.streaming.StreamingService
import app.ember.tv.streaming.TmdbWatch
import app.ember.tv.streaming.Watchmode
import app.ember.tv.tracker.Kind
import app.ember.tv.tracker.LinkSession
import app.ember.tv.tracker.SmartRows
import app.ember.tv.tracker.TitleRef
import app.ember.tv.tracker.Tmdb
import app.ember.tv.tracker.TrackedItem
import app.ember.tv.tracker.TrackerHub
import app.ember.tv.tracker.arr
import app.ember.tv.tracker.bool
import app.ember.tv.tracker.list
import app.ember.tv.tracker.obj
import app.ember.tv.tracker.str
import app.ember.tv.ui.FocusMemory
import app.ember.tv.ui.Ui
import app.ember.tv.weather.Place
import app.ember.tv.weather.Weather
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import app.ember.tv.DetailsUi
import kotlinx.coroutines.CoroutineScope

/**
 * The title screen (split out of HomeViewModel): details from your addons, episodes, sources from
 * every stream addon and your media servers, subtitles, cast, ratings, Where to watch and the
 * AniList / TVDB extras. HomeViewModel forwards openDetails, details and the rest to it.
 */
class TitleState(
    private val scope: CoroutineScope,
    private val client: AddonClient,
    private val servers: () -> MediaServers,
    private val watch: () -> WhereToWatchState,
    private val metadata: () -> MetaSettingsState,
    private val featureOn: (String) -> Boolean,
    private val addonInfos: suspend () -> List<AddonInfo>,
    private val addonTimeout: () -> Int,
    private val noteSlow: (String, Boolean) -> Unit,
    private val serversConnected: () -> Boolean,
) {
    private val visit = MediaVisit(scope)
    var details by mutableStateOf<DetailsUi?>(null)
    private var detailsJob: Job? = null
    private var streamsJob: Job? = null
    private var serverJob: Job? = null
    private var offersJob: Job? = null
    val sourceHealth = app.ember.tv.data.SourceHealth().apply {diagnosticGroup="playback"}
    private var streamInfos: List<AddonInfo> = emptyList()
    private val streamResults = linkedMapOf<String, List<StreamOption>>()
    private val subtitleResults = linkedMapOf<String, List<SubtitleOption>>()

    fun retrySource(url: String) {
        val d = details ?: return
        loadStreams(d.meta.addonType, d.episode?.id ?: directVideoId ?: d.meta.lookupId, only = url)
    }

    fun retrySources() {
        val d = details ?: return
        if (app.ember.tv.mediaserver.ServerRef.of(d.meta) != null) {
            servers().refreshConnections()
            (d.episode?.serverRef ?: d.meta.serverRef)?.let(::loadNativeSources)
            return
        }
        if (!d.meta.playsDirectly && d.episode == null && directVideoId == null) openDetails(d.meta, fromServer = pinnedServer != null)
        else {
            loadOffers(d.meta)
            loadStreams(d.meta.addonType, d.episode?.id ?: directVideoId ?: d.meta.lookupId, retainResults = true)
        }
    }

    /**
     * The Stremio type addons are asked with: "movie", a live type ("tv", "channel", "events") as
     * given (#70), otherwise "series" (anime is listed as series).
     */
    private val Meta.addonType: String get() = when {
        type == "movie" -> "movie"
        type.lowercase(java.util.Locale.ROOT) in LIVE_TYPES -> type
        else -> "series"
    }
    /** One playable item, not a show with episodes: movies and live channels or events. */
    private val Meta.playsDirectly: Boolean get() = type == "movie" || type.lowercase(java.util.Locale.ROOT) in LIVE_TYPES
    /** The meta's behaviorHints.defaultVideoId: the video Stremio plays for it (#70). */
    private var directVideoId: String? = null

    /** IMDb id when known, otherwise the addon's own id (e.g. kitsu:123). */
    private val Meta.lookupId: String get() = imdb ?: id

    /** Cast, directors and trailer as Stremio addons such as Cinemeta give them. */
    private fun creditsFromMeta(m: kotlinx.serialization.json.JsonElement?): Credits? {
        if (m == null) return null
        fun names(key: String) = m.arr(key).list().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
        val links = m.arr("links").list()
        fun linked(category: String) = links.filter { it.str("category").equals(category, true) }.mapNotNull { it.str("name") }
        val directors = (names("director") + linked("Directors")).distinct()
        val cast = (names("cast") + linked("Cast")).distinct().take(10)
        val trailer = m.arr("trailerStreams").list().firstNotNullOfOrNull { it.str("ytId") }
            ?: m.arr("trailers").list().firstOrNull { it.str("type").equals("Trailer", true) }.str("source")
            ?: m.arr("trailers").list().firstNotNullOfOrNull { it.str("source") }
        val people = directors.map { Person(it, "Director", isCrew = true) } + cast.map { Person(it, null) }
        if (people.isEmpty() && trailer == null) return null
        return Credits(people, trailer)
    }

    /** TMDB adds photos, characters and a trailer when a TMDB key is set. Runs beside the source search. */
    private fun loadTmdbCredits(meta: Meta) {
        if (!Tmdb.available || !featureOn(app.ember.tv.data.Features.CAST)) return
        val movie = meta.type == "movie"
        if (meta.tmdbId == null && meta.imdb == null) return
        visit.scope.launch {
            val t = Tmdb.credits(movie, meta.tmdbId, meta.imdb) ?: return@launch
            ensureActive()
            if (!featureOn(app.ember.tv.data.Features.CAST)) return@launch
            val d = details ?: return@launch
            if (d.meta.id != meta.id) return@launch
            details = d.copy(tmdbCredits = t)
        }
    }

    /** The server file a title was opened from, kept among the sources once addons are asked too. */
    private data class PinnedServer(val season: Int?, val episode: Int?, val streams: List<StreamOption>)
    private var pinnedServer: PinnedServer? = null

    fun open(meta: Meta) {
        FocusMemory.pending = meta.mediaKey() // focus returns to this card afterwards
        openDetails(meta, fromServer = false)
    }

    private fun openDetails(meta: Meta, fromServer: Boolean) {
        visit.reset()
        sourceHealth.retain(emptySet())
        streamInfos = emptyList(); streamResults.clear(); subtitleResults.clear()
        detailsJob?.cancel()
        streamsJob?.cancel()
        serverJob?.cancel()
        if (!fromServer) pinnedServer = null
        val nativeRef = app.ember.tv.mediaserver.ServerRef.of(meta)?.value
        if (nativeRef != null) {
            details = DetailsUi(meta = meta, loadingEpisodes = meta.type != "movie", loadingServer = meta.type == "movie")
            detailsJob = visit.scope.launch {
                try {
                    val item = servers().item(nativeRef)
                    val full = item.copy(background=item.background ?: meta.background,poster=item.poster ?: meta.poster,logo=item.logo ?: meta.logo)
                    ensureActive()
                    if (details?.meta?.id != meta.id) return@launch
                    details = details?.copy(meta = full)
                    visit.scope.launch {
                        val (imdb, tmdb) = if(full.imdb!=null || full.tmdbId!=null) full.imdb to full.tmdbId else servers().titleIds(nativeRef)
                        ensureActive()
                        if (details?.meta?.id == full.id) {
                            val enriched = full.copy(imdbId = imdb ?: full.imdbId, tmdbId = tmdb ?: full.tmdbId)
                            details = details?.copy(meta = enriched)
                            loadTmdbCredits(enriched); loadRatings(enriched, null); loadOffers(enriched)
                            loadNativeAddons()
                        }
                    }
                    if (full.type == "movie" || full.episode != null) loadNativeSources(full.serverRef ?: nativeRef)
                    else {
                        val seasons = servers().seasons(nativeRef).map { it.number }.distinct().sorted()
                        ensureActive()
                        details = details?.copy(nativeSeasons = seasons, loadingEpisodes = false)
                        val first = meta.season?.takeIf { it in seasons } ?: seasons.firstOrNull()
                        if (first != null) loadNativeSeason(first) else details = details?.copy(note = "No episodes available on this server.")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { details = details?.copy(loadingEpisodes = false, loadingServer = false, note = "Server unavailable. Check the connection and retry.") }
            }
            return
        }
        directVideoId = null
        details = DetailsUi(meta = meta, loadingEpisodes = !meta.playsDirectly,
            loadingStreams = meta.playsDirectly, loadingOffers = watch().whereToWatchReady)
        detailsJob = visit.scope.launch {
            // TMDB-only titles (Hidden gems) need an IMDb id before addons can find them.
            @Suppress("NAME_SHADOWING")
            val meta = withImdb(meta)
            ensureActive()
            loadOffers(meta)
            loadTmdbCredits(meta)
            val type = meta.addonType
            val id = meta.lookupId
            val infos = addonInfos()
            val metaAddon = infos.firstOrNull { it.supports("meta", type, id) }

            var full = meta
            var episodes = emptyList<Episode>()
            var addonImdbRating: String? = null
            if (metaAddon != null) {
                val fetched = runCatching { client.meta(metaAddon, type, id) }.getOrNull()
                directVideoId = fetched?.first.obj("behaviorHints").str("defaultVideoId")?.takeIf { it.isNotBlank() && it != id }
                // runCatching also swallows cancellation; stop here if another title was opened meanwhile.
                ensureActive()
                if (details?.meta?.id != meta.id) return@launch
                if (featureOn(app.ember.tv.data.Features.CAST)) {
                    fetched?.first?.let { m -> creditsFromMeta(m)?.let { c -> details = details?.copy(addonCredits = c) } }
                }
                addonImdbRating = fetched?.first.str("imdbRating")
                fetched?.let { (m, eps) ->
                    // Specials are season 0; skip them unless that is all there is.
                    episodes = eps.filter { it.season > 0 }.ifEmpty { eps }
                    full = meta.copy(
                        description = m.str("description") ?: meta.description,
                        background = meta.background ?: m.str("background"),
                        poster = meta.poster ?: m.str("poster"),
                        releaseInfo = meta.releaseInfo ?: m.str("releaseInfo"),
                        runtime = meta.runtime ?: m.str("runtime"),
                        ageRating = app.ember.tv.data.addonAgeRating(m) ?: meta.ageRating,
                        adult = if(meta.adult==true || m.bool("adult")==true)true else meta.adult,
                        genres = meta.genres ?: m.arr("genres").list().mapNotNull { g -> (g as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull },
                    )
                }
            }

            loadRatings(full, addonImdbRating)
            if (meta.playsDirectly || (episodes.isEmpty() && directVideoId != null)) {
                details = details?.copy(meta = full, loadingEpisodes = false, loadingStreams = true)
                enhanceMeta(full)
                loadStreams(type, directVideoId ?: id)
                return@launch
            }
            if (episodes.isEmpty()) {
                details = details?.copy(
                    meta = full,
                    loadingEpisodes = false,
                    note = if (metaAddon == null) "None of your addons can list episodes for this title." else "No episodes found.",
                )
                enhanceMeta(full)
                return@launch
            }
            // Open on the episode a tracker said you were watching, if there is one.
            val start = episodes.firstOrNull { it.season == meta.season && it.episode == meta.episode } ?: episodes.first()
            details = details?.copy(meta = full, episodes = episodes, season = start.season, episode = start, loadingEpisodes = false)
            enhanceMeta(full)
            loadStreams(type, start.id)
        }
    }

    private fun serverSource(m: Meta): StreamOption {
        val server = m.serverName ?: "your media server"
        return StreamOption(
            addonName = server,
            name = server,
            title = "Play from $server",
            url = m.playUrl,
            infoHash = null,
            fileIdx = null,
            quality = "",
            hdr = "",
            codec = "",
            audio = "",
            source = "",
            releaseGroup = "",
            sizeGb = null,
            seeders = null,
            cached = false,
            serverRef = m.serverRef,
        )
    }

    private suspend fun withImdb(m: Meta): Meta {
        if (m.imdb != null) return m
        val tmdb = m.tmdbId ?: return m
        val kind = if (m.type == "movie") Kind.MOVIE else Kind.SHOW
        return Tmdb.imdbFor(kind, tmdb)?.let { m.copy(imdbId = it) } ?: m
    }

    fun selectSeason(season: Int) {
        val d = details ?: return
        if (app.ember.tv.mediaserver.ServerRef.of(d.meta) != null) { loadNativeSeason(season); return }
        val first = d.episodes.firstOrNull { it.season == season } ?: return
        selectEpisode(first)
    }

    fun selectEpisode(ep: Episode) {
        if (details == null) return
        if (ep.serverRef != null) clearEpisodeSources()
        details = details?.copy(episode = ep, season = ep.season)
        if (ep.serverRef != null) loadNativeSources(ep.serverRef) else loadStreams("series", ep.id)
    }

    private fun loadNativeAddons() {
        val d = details ?: return
        val imdb = d.meta.imdb ?: return
        if (!d.meta.playsDirectly && d.meta.episode == null && d.episode == null) return
        val season = d.episode?.season ?: d.meta.season
        val episode = d.episode?.episode ?: d.meta.episode
        val id = if (season != null && episode != null) "$imdb:$season:$episode" else imdb
        loadStreams(d.meta.addonType, id)
    }

    private fun loadNativeSources(ref: String) {
        serverJob?.cancel()
        details = details?.copy(serverStreams = emptyList(), loadingServer = true, note = null)
        serverJob = visit.scope.launch {
            val found = servers().sourcesFor(ref)
            ensureActive()
            loadNativeAddons()
            details = details?.copy(serverStreams = found, loadingServer = false,
                note = if (found.isEmpty()) "No streaming option available for this title. Check the server connection and retry." else null)
        }
    }

    private fun clearEpisodeSources() {
        streamsJob?.cancel()
        sourceHealth.cancelLoading()
        sourceHealth.retain(emptySet())
        streamInfos = emptyList()
        streamResults.clear()
        subtitleResults.clear()
        details = details?.copy(streams = emptyList(), subtitles = emptyList(),
            serverStreams = emptyList(), loadingStreams = false, loadingServer = false)
    }

    private fun loadNativeSeason(season: Int, append: Boolean = false) {
        val d = details ?: return
        val ref = app.ember.tv.mediaserver.ServerRef.of(d.meta)?.value ?: return
        detailsJob?.cancel()
        val offset = if (append) d.episodeCursor ?: return else 0
        if (!append) {
            serverJob?.cancel()
            clearEpisodeSources()
        }
        details = details?.copy(season = season, loadingEpisodes = true, note = null,
            episodes = if (append) d.episodes else emptyList(), episode = if (append) d.episode else null,
            episodeCursor = if (append) d.episodeCursor else null,
            serverStreams = if (append) d.serverStreams else emptyList())
        detailsJob = visit.scope.launch {
            try {
                val page = servers().episodes(ref, season, offset)
                ensureActive()
                val eps = ((if (append) details?.episodes.orEmpty() else emptyList()) + page.items.map(app.ember.tv.mediaserver.ServerMetadata::episode)).distinctBy { it.id }
                val selected = if (append) details?.episode else eps.firstOrNull { it.episode == d.meta.episode } ?: eps.firstOrNull()
                details = details?.copy(episodes = eps, episode = selected, loadingEpisodes = false,
                    episodeCursor = page.next.takeIf { !page.end && it > offset && page.items.isNotEmpty() },
                    note = if (eps.isEmpty()) "No episodes available for this season." else null)
                if (!append) selected?.serverRef?.let(::loadNativeSources)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { details = details?.copy(loadingEpisodes = false, note = "Could not load episodes. Select the season to retry.") }
        }
    }

    fun moreServerEpisodes() { val d = details ?: return; if (!d.loadingEpisodes) d.season?.let { loadNativeSeason(it, true) } }

    fun close() {
        visit.cancel()
        detailsJob?.cancel()
        streamsJob?.cancel()
        serverJob?.cancel()
        details = null
    }


    /** Asks every addon that serves this title for sources and subtitles, all at once. */
    private fun loadStreams(type: String, id: String, only: String? = null, retainResults: Boolean = false) {
        streamsJob?.cancel()
        sourceHealth.cancelLoading()
        val keep = only != null || retainResults
        if (!keep) {
            streamResults.clear(); subtitleResults.clear(); sourceHealth.retain(emptySet())
        }
        details = details?.copy(streams = if (keep) details!!.streams else emptyList(),
            subtitles = if (keep) details!!.subtitles else emptyList(), loadingStreams = true, note = null)
        val forTitle = details?.meta?.id
        val forEpisode = details?.episode?.id
        if (only == null && app.ember.tv.mediaserver.ServerRef.of(details?.meta ?: Meta()) == null) loadServerCopies()
        streamsJob = visit.scope.launch {
            val infos = if (only == null) addonInfos() else streamInfos.filter { it.url == only }
            ensureActive()
            if (only == null) streamInfos = infos
            val streamAddons = infos.filter { it.supports("stream", type, id) }
            val subAddons = infos.filter { it.supports("subtitles", type, id) }
            if (only == null) {
                val keys = streamAddons.map { it.url }.toSet()
                sourceHealth.retain(keys); streamResults.keys.retainAll(keys)
                subtitleResults.keys.retainAll(subAddons.map { it.url }.toSet())
            }
            streamAddons.forEach { sourceHealth.start(it.url, it.name) }
            val limit = addonTimeout() * 2000L
            val subsJob = async {
                subAddons.map { addon -> async {
                    val result = kotlinx.coroutines.withTimeoutOrNull(limit) { runCatching { client.subtitles(addon, type, id) } }
                    ensureActive()
                    result?.onSuccess { subtitleResults[addon.url] = it }
                } }.awaitAll()
                subtitleResults.values.flatten()
            }
            coroutineScope {
                streamAddons.forEach { addon -> launch {
                    val result = kotlinx.coroutines.withTimeoutOrNull(limit) { runCatching { client.streams(addon, type, id) } }
                    ensureActive()
                    if (details?.meta?.id != forTitle || details?.episode?.id != forEpisode) return@launch
                    noteSlow(addon.name, result == null)
                    result?.onSuccess { streamResults[addon.url] = it }
                    sourceHealth.finish(addon.url, result?.isSuccess == true,
                        cached = result?.isSuccess != true && streamResults[addon.url]?.isNotEmpty() == true,
                        failure=if(result==null)app.ember.tv.data.SourceFailure.TIMEOUT else result.exceptionOrNull()?.let(app.ember.tv.data.SourceFailure::from))
                    details = details?.copy(streams = streamResults.values.flatten().sortedWith(StreamParser.order))
                } }
            }
            val subs = subsJob.await()
            ensureActive()
            if (details?.meta?.id != forTitle || details?.episode?.id != forEpisode) return@launch
            val streams = streamResults.values.flatten().sortedWith(StreamParser.order)
            details = details?.copy(streams = streams, subtitles = subs.distinctBy { it.url }, loadingStreams = false, note = null)
        }
    }

    /** Looks for the open title (and episode) on your media servers, alongside the addons. */
    private fun loadServerCopies() {
        serverJob?.cancel()
        val d = details ?: return
        if (!serversConnected() || !featureOn(app.ember.tv.data.Features.SERVERS)) {
            details = d.copy(serverStreams = pinnedServer?.streams.orEmpty(), loadingServer = false)
            return
        }
        val m = d.meta
        val movie = m.type == "movie"
        val ep = d.episode
        if (!movie && ep == null) return
        details = d.copy(serverStreams = emptyList(), loadingServer = true)
        val forTitle = m.id
        val forEpisode = ep?.id
        serverJob = visit.scope.launch {
            val found = servers().find(
                ServerQuery(
                    imdbId = m.imdb,
                    tmdbId = m.tmdbId,
                    title = m.name,
                    year = ServerQuery.yearOf(m.releaseInfo),
                    movie = movie,
                    season = ep?.season,
                    episode = ep?.episode,
                )
            )
            ensureActive()
            val now = details ?: return@launch
            if (now.meta.id != forTitle || now.episode?.id != forEpisode) return@launch
            // Keep the file the title was opened from, even if the server's search missed it.
            val pin = pinnedServer?.takeIf { movie || (it.season == ep?.season && it.episode == ep?.episode) }
            val merged = (pin?.streams.orEmpty() + found).distinctBy { it.serverRef + "|" + it.url?.substringBefore("api_key")?.substringBefore("X-Plex-Token") }
            details = now.copy(serverStreams = merged, loadingServer = false)
        }
    }


    private fun loadOffers(meta: Meta) {
        offersJob?.cancel()
        if (!watch().whereToWatchReady) {
            details = details?.copy(offers = emptyList(), loadingOffers = false)
            return
        }
        details = details?.copy(loadingOffers = true)
        val provider = watch().watchProvider
        val region = watch().watchRegion
        val keysRevision = app.ember.tv.data.ApiKeys.revision
        offersJob = visit.scope.launch {
            val offers = runCatching {
                if (provider == "tmdb") {
                    TmdbWatch.offers(meta.type == "movie", meta.tmdbId, meta.imdb, region)
                } else if (provider == "streaming") {
                    meta.imdb?.let { app.ember.tv.streaming.StreamingAvailability.offers(it, region) }.orEmpty()
                } else {
                    meta.imdb?.let { Watchmode.offers(it, region) }.orEmpty()
                }
            }.getOrNull()
            ensureActive()
            if (details?.meta?.id == meta.id) {
                val current = watch().whereToWatchReady && provider == watch().watchProvider && region == watch().watchRegion && keysRevision == app.ember.tv.data.ApiKeys.revision
                details = details?.copy(offers = if (current) offers ?: details?.offers.orEmpty() else emptyList(), loadingOffers = false)
            }
        }
    }


    // ---- metadata from AniList and TVDB (Settings > Metadata) ----
    private var enhanceJob: Job? = null

    /** Asks AniList (anime) and TVDB (TV shows) for better details, then merges them into the open title. */
    private fun enhanceMeta(meta: Meta) {
        val c = metadata().metaConfig
        val anime = Ratings.looksLikeAnime(meta)
        val useAni = c.anilistOn && anime
        val useTvdb = c.tvdbOn && meta.type != "movie" && (!anime || c.tvdbForAnime)
        if (!useAni && !useTvdb) return
        enhanceJob?.cancel()
        enhanceJob = visit.scope.launch {
            val (a, t) = coroutineScope {
                val ani = async { if (useAni) MetaEnhance.anilist(meta, c) else null }
                val tv = async { if (useTvdb) MetaEnhance.tvdb(meta, c) else null }
                ani.await() to tv.await()
            }
            ensureActive()
            val d = details ?: return@launch
            if (d.meta.id != meta.id || metadata().metaConfig != c || (a == null && t == null)) return@launch
            details = mergeExtras(d, a, t, c)
        }
    }

    private fun mergeExtras(d: DetailsUi, a: MetaExtra?, t: MetaExtra?, c: MetaConfig): DetailsUi {
        fun pick(current: String?, better: String?): String? {
            val cur = current?.takeIf { it.isNotBlank() }
            val new = better?.takeIf { it.isNotBlank() }
            return if (c.replace) new ?: cur else cur ?: new
        }
        val desc = (if (c.anilistDescription) a?.description else null) ?: (if (c.tvdbDescription) t?.description else null)
        val poster = (if (c.anilistArtwork) a?.poster else null) ?: (if (c.tvdbArtwork) t?.poster else null)
        val backdrop = (if (c.anilistArtwork) a?.background else null) ?: (if (c.tvdbArtwork) t?.background else null)
        val m = d.meta
        val meta = m.copy(
            name = a?.name ?: m.name,
            description = pick(m.description, desc),
            poster = pick(m.poster, poster),
            background = pick(m.background, backdrop),
            genres = if (c.anilistGenres && a != null && a.genres.isNotEmpty() && (c.replace || m.genres.isNullOrEmpty())) a.genres else m.genres,
        )
        val placeholder = Regex("""^(Episode|Ep\.?)\s*\d+$""", RegexOption.IGNORE_CASE)
        val eps = if (t != null && c.tvdbEpisodes && t.episodes.isNotEmpty()) {
            d.episodes.map { e ->
                val x = t.episodes[e.season to e.episode] ?: return@map e
                e.copy(
                    title = if (x.title != null && (c.replace || e.title.isBlank() || placeholder.matches(e.title))) x.title else e.title,
                    overview = pick(e.overview, x.overview),
                    released = e.released ?: x.aired,
                    thumbnail = pick(e.thumbnail, x.thumbnail),
                )
            }
        } else d.episodes
        val facts = ((if (c.anilistDetails) a?.facts.orEmpty() else emptyList()) +
            (if (c.tvdbDetails) t?.facts.orEmpty() else emptyList()))
            .distinctBy { it.substringBefore(' ') }
        return d.copy(
            meta = meta,
            episodes = eps,
            episode = d.episode?.let { cur -> eps.firstOrNull { it.id == cur.id } } ?: d.episode,
            facts = facts,
            metaSources = listOfNotNull(a?.source, t?.source),
        )
    }


    // ---- ratings (Settings > Ratings) ----
    /** Fetches ratings for the open title from the services that are on. */
    private fun loadRatings(meta: Meta, addonImdbRating: String?) {
        if (!metadata().ratingsOn) return
        val wanted = Ratings.SOURCES.map { it.id }.toSet() - metadata().ratingsOff
        if (wanted.isEmpty()) return
        visit.scope.launch {
            val r = Ratings.load(meta, addonImdbRating, wanted)
            ensureActive()
            if (!metadata().ratingsOn || wanted != Ratings.SOURCES.map { it.id }.toSet() - metadata().ratingsOff) return@launch
            val d = details ?: return@launch
            if (d.meta.id == meta.id && r.isNotEmpty()) details = d.copy(ratings = r)
        }
    }

}

/** Stremio's live content types; played as one item with their own type (#70). */
private val LIVE_TYPES = setOf("tv", "channel", "channels", "events")
