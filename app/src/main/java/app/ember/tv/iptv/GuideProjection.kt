package app.ember.tv.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import app.ember.tv.data.toHex

internal data class GuideProjection(val matches: Map<String, EpgMatch>, val guide: Map<String, List<Programme>>)

/** Build an immutable projection off the UI thread; the controller owns publication/version checks. */
internal suspend fun buildGuideProjection(channels: List<Channel>, enabled: Set<String>, feeds: List<EpgFeed>,
    settings: EpgConfiguration, parsed: Map<String, ParsedEpg>, now: Long): GuideProjection = withContext(Dispatchers.Default) {
    val valid = parsed.filter { (id, data) -> feeds.any { it.id == id &&
        java.security.MessageDigest.getInstance("SHA-256").digest((it.url + "\n" + it.userAgent).toByteArray()).toHex() == data.fingerprint } }
    val assignments = enabled.associateWith { settings.feedIds(it, feeds) }
    val matches = hashMapOf<String, EpgMatch>()
    val guide = hashMapOf<String, List<Programme>>()
    for (channel in channels) {
        currentCoroutineContext().ensureActive()
        val ids = assignments[channel.sourceId] ?: continue
        EpgMatching.select(channel, ids, settings, valid)?.let { match ->
            matches[channel.key] = match
            guide[channel.key] = EpgMatching.shifted(valid[match.feedId]?.programmes?.get(match.channelId).orEmpty(),
                settings.offset(match.feedId), now - 2 * 3600_000L, now + 36 * 3600_000L)
        }
    }
    GuideProjection(matches, guide)
}
