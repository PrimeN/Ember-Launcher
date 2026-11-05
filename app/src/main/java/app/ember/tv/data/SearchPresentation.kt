package app.ember.tv.data

enum class SearchFilter(val label: String) { ALL("All"), MOVIES("Movies"), SHOWS("Shows"), APPS("Apps"), WATCHLIST("Watchlist") }

object SearchPresentation {
    fun rank(name: String, query: String): Int = when {
        name.equals(query.trim(), true) -> 0
        name.startsWith(query.trim(), true) -> 1
        else -> 2
    }
    fun accepts(meta: Meta, filter: SearchFilter) = when (filter) {
        SearchFilter.MOVIES -> meta.type == "movie"
        SearchFilter.SHOWS -> meta.type in setOf("series", "show", "tv", "anime")
        SearchFilter.APPS -> false
        else -> true
    }
    /** Never merge by name/year; distinguish a show from its seasons/episodes. */
    fun identity(meta: Meta): String? {
        val id = meta.imdb?.takeIf { it.isNotBlank() }?.let { "imdb:$it" }
            ?: meta.tmdbId?.let { "tmdb:$it" } ?: return null
        val kind = when (meta.type) { "series", "show", "tv", "anime" -> "show"; else -> meta.type }
        val episode = if (meta.season == null && meta.episode == null && meta.imdb != null && meta.id.startsWith("${meta.imdb}:"))
            meta.id.substringAfter(':') else "${meta.season}:${meta.episode}"
        return "$kind:$id:$episode"
    }
    fun groups(rows: List<CatalogRow>, filter: SearchFilter, combine: Boolean): List<CatalogRow> {
        if (filter == SearchFilter.APPS || filter == SearchFilter.WATCHLIST) return emptyList()
        val sources = mutableMapOf<String, LinkedHashSet<String>>()
        rows.forEach { row -> row.items.filter { accepts(it, filter) }.forEach { meta ->
            identity(meta)?.let { sources.getOrPut(it) { linkedSetOf() }.add(row.addonName) }
        } }
        val seen = mutableSetOf<String>()
        return rows.mapNotNull { row ->
            val items = row.items.filter { accepts(it, filter) }.mapNotNull { meta ->
                val key = identity(meta)
                if (combine && key != null && !seen.add(key)) null
                else if (combine && key != null && sources[key].orEmpty().size > 1)
                    meta.copy(badge = "Sources: " + sources.getValue(key).joinToString())
                else meta
            }
            row.copy(items = items).takeIf { items.isNotEmpty() }
        }
    }
}

object RecentSearches {
    const val LIMIT = 10
    fun updated(old: List<String>, query: String): List<String> {
        val term = query.trim().take(200)
        if (term.length < 2) return old
        return (listOf(term) + old.filterNot { it.equals(term, true) }).take(LIMIT)
    }
}
