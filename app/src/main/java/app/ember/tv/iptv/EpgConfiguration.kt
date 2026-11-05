package app.ember.tv.iptv

import kotlinx.serialization.Serializable

@Serializable data class EpgFeed(val id: String, val name: String, val url: String, val userAgent: String = "")
@Serializable data class EpgOverride(val feedId: String, val channelId: String)
@Serializable data class EpgConfiguration(
    val feeds: List<EpgFeed> = emptyList(),
    /** Absent assignment uses the playlist guide; an empty assignment explicitly disables it. */
    val assignments: Map<String, List<String>> = emptyMap(),
    val offsets: Map<String, Int> = emptyMap(),
    val overrides: Map<String, EpgOverride> = emptyMap(),
) {
    fun feedIds(source: String, available: List<EpgFeed>) = (assignments[source] ?: listOf("provider:$source"))
        .distinct().filter { id -> available.any { it.id == id } }
    fun offset(id: String): Long = offsets[id].orEmptyMinutes() * 60_000L
    private fun Int?.orEmptyMinutes() = (this ?: 0).coerceIn(-1440, 1440)
}

@Serializable data class ParsedEpg(
    val programmes: Map<String, List<Programme>> = emptyMap(),
    val names: Map<String, String> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
    val fingerprint: String = "",
)

data class EpgMatch(val feedId: String, val channelId: String, val manual: Boolean)

object EpgMatching {
    fun select(channel: Channel, feeds: List<String>, config: EpgConfiguration, parsed: Map<String, ParsedEpg>): EpgMatch? {
        config.overrides[channel.key]?.let { custom ->
            return custom.takeIf { it.feedId in feeds && (parsed[it.feedId]?.labels?.containsKey(it.channelId) == true ||
                parsed[it.feedId]?.programmes?.containsKey(it.channelId) == true) }
                ?.let { EpgMatch(it.feedId, it.channelId, true) }
        }
        // Prefer an exact provider ID across all feeds before considering name matches.
        for (id in feeds) if (channel.epgId != null && (parsed[id]?.programmes?.containsKey(channel.epgId) == true || parsed[id]?.labels?.containsKey(channel.epgId) == true))
            return EpgMatch(id, channel.epgId, false)
        for (id in feeds) parsed[id]?.names?.get(Xmltv.norm(channel.name))?.let { return EpgMatch(id, it, false) }
        return null
    }
    fun shifted(items: List<Programme>, offset: Long, from: Long, to: Long) = items.map {
        it.copy(start = it.start + offset, stop = it.stop + offset)
    }.filter { it.stop > from && it.start < to }.sortedBy { it.start }
}
