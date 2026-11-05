package app.ember.tv.data

object HeroArtworkMode {
    const val STANDARD = "standard"
    const val FANART = "fanart"
    fun normalize(value: String) = if (value == FANART) FANART else STANDARD
}

/** Shared banner selection/navigation rules; never duplicate titles just to fill the carousel. */
object HeroBanner {
    const val LIMIT = 10
    const val HEIGHT_FRACTION = .78f
    const val BACKDROP_FRACTION = .86f
    fun sourceKey(row: CatalogRow) = row.catalogKey ?: "row:${row.addonName}:${row.title}"

    fun items(rows: List<CatalogRow>, source: String?, visible: (Meta) -> Boolean): List<Meta> {
        fun eligible(meta: Meta) = (!meta.background.isNullOrBlank() || !meta.poster.isNullOrBlank()) && visible(meta)
        val selected = source?.let { key -> rows.firstOrNull { sourceKey(it) == key } }
        if (selected != null) return selected.items.asSequence().filter(::eligible).distinctBy { it.mediaKey() }.take(LIMIT).toList()
        val output = ArrayList<Meta>(LIMIT)
        val seen = hashSetOf<String>()
        val iterators = rows.map { it.items.asSequence().filter(::eligible).iterator() }
        while (output.size < LIMIT) {
            var read = false
            for (iterator in iterators) {
                while (iterator.hasNext()) {
                    read = true
                    val item = iterator.next()
                    if (seen.add(item.mediaKey())) { output.add(item); break }
                }
                if (output.size == LIMIT) break
            }
            if (!read) break
        }
        return output
    }

    fun index(value: Int, count: Int): Int = if (count <= 0) 0 else value.coerceIn(0, count - 1)
    /** Indicators describe the title actually presented while the requested artwork is loading. */
    fun displayIndex(value:Int,items:List<Meta>,presented:Meta?):Int =
        presented?.let {shown->items.indexOfFirst {it.mediaKey()==shown.mediaKey()}}?.takeIf {it>=0} ?: index(value,items.size)
    fun next(value: Int, count: Int): Int = if (count <= 1) 0 else (index(value, count) + 1) % count
    fun previous(value: Int, count: Int): Int = if (count <= 1) 0 else (index(value, count) + count - 1) % count
    fun pageOnPress(keyDown: Boolean, repeat: Int, count: Int) = keyDown && repeat == 0 && count > 1
}
