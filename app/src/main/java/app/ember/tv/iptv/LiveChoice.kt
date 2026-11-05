package app.ember.tv.iptv

data class LiveChoice(val kind: String = "none", val source: String? = null, val group: String? = null, val list: String? = null) {
    fun channels(available: List<Channel>, library: LiveLibrary, favourites: Set<String>, recent: List<String>): List<Channel> = when (kind) {
        "source" -> available.filter { it.sourceId == source && (group == null || it.group == group) }
        "favourites" -> available.filter { it.key in favourites }
        "recent" -> available.associateBy { it.key }.let { byKey -> recent.mapNotNull { byKey[it] } }
        "custom" -> library.listChannels(list.orEmpty(), available)
        else -> emptyList()
    }
    fun repaired(sources: List<IptvSource>, library: LiveLibrary, channels: List<Channel> = emptyList()): LiveChoice = when {
        (if (kind == "custom") "custom:$list" else kind) in library.hiddenSections -> LiveChoice()
        kind == "custom" && library.lists.none { it.id == list } -> LiveChoice()
        kind == "source" && sources.none { it.id == source && it.enabled } -> LiveChoice()
        kind == "source" && group != null && LiveLibrary.groupKey(source.orEmpty(), group) in library.hiddenGroups -> copy(group = null)
        kind == "source" && group != null && channels.any { it.sourceId == source } && channels.none { it.sourceId == source && it.group == group } -> copy(group = null)
        else -> this
    }
}
