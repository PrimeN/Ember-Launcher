package app.ember.tv.player

import android.content.Context
import app.ember.tv.data.AddonClient
import app.ember.tv.data.Episode
import app.ember.tv.data.Prefs
import app.ember.tv.data.StreamOption
import app.ember.tv.data.StreamParser
import app.ember.tv.data.SubtitleOption
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit

/** Finds a source and subtitles for the next episode, using the same addons as the title screen. */
class NextEpisodeLoader(private val context: Context, private val serverSession: app.ember.tv.mediaserver.MediaServers? = null) {
    private val addons = Prefs(context).addons
    private val client = AddonClient()
    private val permits = kotlinx.coroutines.sync.Semaphore(4)
    private val timeoutMillis = Prefs(context).addonTimeout.coerceIn(1, 120) * 1000L
    private suspend fun <T> request(load: suspend () -> T): T? = permits.withPermit {
        kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
            try { load() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        }
    }

    suspend fun load(ep: Episode, prevAddon: String?, prevQuality: String?, serverQuality: app.ember.tv.mediaserver.ServerQuality = app.ember.tv.mediaserver.ServerQuality.ORIGINAL, bingeGroup: String? = null): Pair<StreamOption, List<SubtitleOption>>? =
        coroutineScope {
            val native = ep.serverRef ?: app.ember.tv.mediaserver.ServerRef.parse(ep.id)?.value
            if (native != null) {
                val servers = serverSession ?: app.ember.tv.mediaserver.MediaServers.forPlayback(context)
                val sources = servers.sourcesFor(native)
                val pick = StreamParser.pickSimilar(sources, prevAddon, prevQuality, bingeGroup) ?: return@coroutineScope null
                val resolved = if (serverQuality == app.ember.tv.mediaserver.ServerQuality.ORIGINAL) pick else servers.playback(native, pick.serverPlayback!!.sourceId, serverQuality)
                return@coroutineScope resolved to resolved.serverPlayback?.parts?.firstOrNull()?.subtitles.orEmpty()
            }
            val infos = addons.map { async { request { client.manifest(it) } } }.awaitAll().filterNotNull()
            val streams = infos.filter { it.supports("stream", "series", ep.id) }
                .map { a -> async { request { client.streams(a, "series", ep.id) }.orEmpty() } }
            val subs = infos.filter { it.supports("subtitles", "series", ep.id) }
                .map { a -> async { request { client.subtitles(a, "series", ep.id) }.orEmpty() } }
            val pick = StreamParser.pickSimilar(streams.awaitAll().flatten(), prevAddon, prevQuality, bingeGroup)
                ?: return@coroutineScope null
            pick to subs.awaitAll().flatten().distinctBy { it.url }
        }
}
