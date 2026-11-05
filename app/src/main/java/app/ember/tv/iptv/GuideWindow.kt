package app.ember.tv.iptv

object GuideWindow {
    const val HALF_HOUR = 1_800_000L
    const val DURATION = 12 * 3_600_000L
    fun start(clock: Long): Long = Math.floorDiv(clock, HALF_HOUR) * HALF_HOUR
    fun expired(start: Long, clock: Long): Boolean = clock < start || clock >= start + DURATION

    data class Slot(val from: Long, val to: Long, val programme: Programme?)

    private fun indexAt(slots: List<Slot>, time: Long): Int {
        var lo = 0
        var hi = slots.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi).ushr(1)
            val slot = slots[mid]
            when {
                time < slot.from -> hi = mid - 1
                time >= slot.to -> lo = mid + 1
                else -> return mid
            }
        }
        return if (slots.isNotEmpty() && time >= slots.last().to) slots.lastIndex else -1
    }
    fun at(slots: List<Slot>, time: Long): Slot? = slots.getOrNull(indexAt(slots, time))
    fun move(slots: List<Slot>, time: Long, direction: Int): Long {
        if (slots.isEmpty()) return time
        val index = indexAt(slots, time).coerceAtLeast(0)
        return slots[(index + direction).coerceIn(0, slots.lastIndex)].from
    }
    fun marker(width: Float, rail: Float, elapsedPixels: Float, scroll: Float, rtl: Boolean): Float? {
        val offset = elapsedPixels - scroll
        if (offset < 0 || offset > width - rail) return null
        return if (rtl) width - rail - offset else rail + offset
    }

    /** Retain the focused programme even when it sits outside the prefetched viewport. */
    fun visible(slots: List<Slot>, from: Long, to: Long, focusedStart: Long? = null): List<Slot> =
        slots.filter { (it.to > from && it.from < to) || (focusedStart != null && it.programme?.start == focusedStart) }

    /** All rows must span the same window, including gaps and overlapping provider entries. */
    fun slots(programmes: List<Programme>, start: Long): List<Slot> = buildList {
        val end = start + DURATION
        var cursor = start
        programmes.sortedBy { it.start }.forEach { programme ->
            val from = maxOf(start, cursor, programme.start)
            val to = minOf(end, programme.stop)
            if (to > from) {
                if (from > cursor) add(Slot(cursor, from, null))
                add(Slot(from, to, programme))
                cursor = to
            }
        }
        if (cursor < end) add(Slot(cursor, end, null))
    }
}
