package app.ember.tv.iptv

/** Full authorized order stays in process memory; Intents carry only a session token. */
class LiveSession(
    requested: List<Channel>, initialKey: String,
    private val available: () -> List<Channel>,
    private val onSelected: (Channel) -> Unit,
    private val onGuide: ((Channel) -> Unit)? = null,
    private val programmes: (Channel, Long) -> Pair<Programme?, Programme?> = { _, _ -> null to null },
    private val sourceName: (Channel) -> String = { "IPTV" },
    private val history: () -> List<String> = { emptyList() },
) {
    private val order = requested.map { it.key }.distinct()
    var currentKey = initialKey; private set
    private var previousKey: String? = null
    fun channels(): List<Channel> {
        val current = available().associateBy { it.key }
        return order.mapNotNull { current[it] }
    }
    fun select(delta: Int = 0): Channel? {
        val list = channels()
        if (list.isEmpty()) return null
        val previous = list.indexOfFirst { it.key == currentKey }
        val index = if (previous < 0) 0 else Math.floorMod(previous + delta, list.size)
        return selectKey(list[index].key)
    }
    fun selectKey(key: String): Channel? = channels().firstOrNull { it.key == key }?.also {
        if (currentKey != key) previousKey = currentKey
        currentKey = key; onSelected(it)
    }
    fun sourceLabel(channel: Channel): String = sourceName(channel)
    fun recent(): List<Channel> {
        val available = channels().associateBy { it.key }
        return (history() + listOfNotNull(previousKey, currentKey)).distinct().mapNotNull { available[it] }.take(20)
    }
    fun previous(): Channel? = channels().firstOrNull { it.key == previousKey && it.key != currentKey }
        ?: LiveRemote.previous(currentKey, history(), channels())
    fun information(at: Long): LiveBanner? = channels().firstOrNull { it.key == currentKey }?.let {
        val (now, next) = programmes(it, at)
        LiveBanner(it, sourceName(it), now, next, at)
    }
    fun openGuide(): Boolean {
        val channel = channels().firstOrNull { it.key == currentKey } ?: return false
        val action = onGuide ?: return false
        action(channel); return true
    }
}

data class LiveBanner(val channel: Channel, val source: String, val now: Programme?, val next: Programme?, val clock: Long)

object LiveSessions {
    private val sessions = linkedMapOf<String, LiveSession>()
    @Synchronized fun put(session: LiveSession): String {
        while (sessions.size >= 2) sessions.remove(sessions.keys.first())
        return java.util.UUID.randomUUID().toString().also { sessions[it] = session }
    }
    @Synchronized fun get(id: String) = sessions[id]
    @Synchronized fun release(id: String?) { sessions.remove(id) }
}

object ChannelHeaders {
    const val DEFAULT_AGENT = "EmberTV/0.9"
    fun agent(channel: Channel?) = channel?.userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_AGENT
    fun properties(channel: Channel?): Map<String, String> = channel?.referrer?.takeIf { it.isNotBlank() }
        ?.let { mapOf("Referer" to it) } ?: emptyMap()
}
