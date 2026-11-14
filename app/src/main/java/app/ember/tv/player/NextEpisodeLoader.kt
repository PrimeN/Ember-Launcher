package app.ember.tv.player

import android.content.Context
import app.ember.tv.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Playback selection and supplementary subtitle requests have separate lifetimes. */
class NextEpisodeLoader(private val context: Context, private val serverSession: app.ember.tv.mediaserver.MediaServers? = null) {
    private val addons = Prefs(context).addons
    private val client = AddonClient()
    private val permits = Semaphore(4)
    private val timeoutMillis = Prefs(context).addonTimeout.coerceIn(1, 120) * 1000L
    private suspend fun <T> request(load: suspend () -> T): T? = permits.withPermit {
        withTimeoutOrNull(timeoutMillis) {
            try { load() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        }
    }
    internal data class Selected(val stream: StreamOption, val subtitles: suspend () -> List<SubtitleOption>)

    internal suspend fun select(ep: Episode, prevAddon: String?, prevQuality: String?,
        serverQuality: app.ember.tv.mediaserver.ServerQuality = app.ember.tv.mediaserver.ServerQuality.ORIGINAL,
        bingeGroup: String? = null): Selected? = coroutineScope {
        val native = ep.serverRef ?: app.ember.tv.mediaserver.ServerRef.parse(ep.id)?.value
        if (native != null) {
            val servers = serverSession ?: app.ember.tv.mediaserver.MediaServers.forPlayback(context)
            val pick = StreamParser.pickSimilar(servers.sourcesFor(native), prevAddon, prevQuality, bingeGroup) ?: return@coroutineScope null
            val resolved = if (serverQuality == app.ember.tv.mediaserver.ServerQuality.ORIGINAL) pick
                else servers.playback(native, pick.serverPlayback!!.sourceId, serverQuality)
            return@coroutineScope Selected(resolved) { resolved.serverPlayback?.parts?.firstOrNull()?.subtitles.orEmpty() }
        }
        val infos = addons.map { async { request { client.manifest(it) } } }.awaitAll().filterNotNull()
        val streams = infos.filter { it.supports("stream", "series", ep.id) }.map { addon ->
            async { request { client.streams(addon, "series", ep.id) }.orEmpty() }
        }.awaitAll().flatten()
        val pick = StreamParser.pickSimilar(streams, prevAddon, prevQuality, bingeGroup) ?: return@coroutineScope null
        Selected(pick) {
            coroutineScope {
                infos.filter { it.supports("subtitles", "series", ep.id) }.map { addon ->
                    async { request { client.subtitles(addon, "series", ep.id) }.orEmpty() }
                }.awaitAll().flatten().distinctBy { it.url }
            }
        }
    }
    /** Compatibility for callers that explicitly need a complete source/subtitle pair. */
    suspend fun load(ep: Episode, prevAddon: String?, prevQuality: String?,
        serverQuality: app.ember.tv.mediaserver.ServerQuality = app.ember.tv.mediaserver.ServerQuality.ORIGINAL,
        bingeGroup: String? = null): Pair<StreamOption, List<SubtitleOption>>? =
        select(ep, prevAddon, prevQuality, serverQuality, bingeGroup)?.let { it.stream to it.subtitles() }
}
