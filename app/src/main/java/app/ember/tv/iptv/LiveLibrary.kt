package app.ember.tv.iptv

import kotlinx.serialization.Serializable

@Serializable
data class ChannelList(val id: String, val name: String, val channelKeys: List<String> = emptyList())

/** Local organization only: provider channels and playlists are never deleted or modified. */
@Serializable
data class LiveLibrary(
    val hiddenChannels: Set<String> = emptySet(),
    val hiddenGroups: Set<String> = emptySet(),
    val lists: List<ChannelList> = emptyList(),
    val showOnNow: Boolean = true,
    val hiddenSections: Set<String> = emptySet(),
    val guideRows: Int = 6,
    val guideLogos: Boolean = true,
    val compactProgramme: Boolean = false,
    val showProgrammePanel: Boolean = true,
) {
    fun visible(channels: List<Channel>, enabledSources: Set<String>): List<Channel> = channels.filter {
        it.sourceId in enabledSources && it.key !in hiddenChannels && groupKey(it.sourceId, it.group) !in hiddenGroups
    }

    fun listChannels(id: String, channels: List<Channel>): List<Channel> {
        val byKey = channels.associateBy { it.key }
        return lists.firstOrNull { it.id == id }?.channelKeys.orEmpty().distinct().mapNotNull { byKey[it] }
    }

    fun toggleMember(id: String, channelKey: String): LiveLibrary = copy(lists = lists.map {
        if (it.id != id) it else it.copy(channelKeys = if (channelKey in it.channelKeys)
            it.channelKeys.filterNot { key -> key == channelKey } else it.channelKeys + channelKey)
    })

    fun setMembers(id: String, keys: List<String>, add: Boolean) = copy(lists = lists.map {
        if (it.id != id) it else it.copy(channelKeys = if (add) (it.channelKeys + keys).distinct() else it.channelKeys.filterNot { key -> key in keys })
    })
    fun moveList(id: String, delta: Int): LiveLibrary {
        val next = lists.toMutableList(); val from = next.indexOfFirst { it.id == id }
        if (from < 0) return this
        next.add((from + delta).coerceIn(0, next.lastIndex), next.removeAt(from))
        return copy(lists = next)
    }
    fun moveMember(id: String, key: String, delta: Int) = copy(lists = lists.map {
        if (it.id != id) it else {
            val next = it.channelKeys.toMutableList(); val from = next.indexOf(key)
            if (from < 0) it else { next.add((from + delta).coerceIn(0, next.lastIndex), next.removeAt(from)); it.copy(channelKeys = next) }
        }
    })

    companion object {
        fun groupKey(sourceId: String, group: String) = "$sourceId:$group"
    }
}
