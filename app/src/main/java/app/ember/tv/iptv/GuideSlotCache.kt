package app.ember.tv.iptv

/** Bounded, screen-owned cache. Controller guide lists are immutable snapshots. */
internal class GuideSlotCache(private val limit: Int = 64) {
    private data class Entry(val programmes: List<Programme>, val start: Long, val slots: List<GuideWindow.Slot>)
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)

    fun slots(channel: String, programmes: List<Programme>, start: Long): List<GuideWindow.Slot> {
        entries[channel]?.takeIf { it.programmes === programmes && it.start == start }?.let { return it.slots }
        val slots = GuideWindow.slots(programmes, start)
        entries[channel] = Entry(programmes, start, slots)
        while (entries.size > limit.coerceAtLeast(1)) entries.remove(entries.keys.first())
        return slots
    }
}
