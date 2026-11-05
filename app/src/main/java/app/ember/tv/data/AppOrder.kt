package app.ember.tv.data

/** Reorder a permitted subset without moving hidden or excluded apps out of their slots. */
object AppOrder {
    fun merge(current: List<String>, moved: List<String>): List<String> {
        val allowed = current.toSet()
        val subset = moved.filter { it in allowed }.distinct()
        val slots = subset.toSet()
        val next = subset.iterator()
        return current.map { if (it in slots) next.next() else it }
    }
    fun move(items: List<String>, from: Int, to: Int): List<String> {
        if (from !in items.indices || to !in items.indices) return items
        return items.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Grid movement never wraps horizontally or enters a missing slot in the final row. */
    fun target(index: Int, count: Int, columns: Int, horizontal: Int = 0, vertical: Int = 0): Int {
        if (index !in 0 until count || columns < 1) return index
        if (horizontal != 0 && vertical != 0) return index
        val next = index + horizontal.coerceIn(-1, 1) + vertical.coerceIn(-1, 1) * columns
        if (next !in 0 until count) return index
        if (horizontal != 0 && index / columns != next / columns) return index
        return next
    }
}
