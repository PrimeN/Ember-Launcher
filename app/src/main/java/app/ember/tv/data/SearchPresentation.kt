package app.ember.tv.data

enum class SearchFilter(val label: String) { ALL("All"), MOVIES("Movies"), SHOWS("Shows"), APPS("Apps"), WATCHLIST("Watchlist") }

/** One cell in Search; its origin stays available when providers return the same title. */
data class SearchResult(val key: String, val meta: Meta, val sources: List<String>, val saved: Boolean)

object SearchPresentation {
    fun results(rows: List<CatalogRow>, saved: List<Meta>, filter: SearchFilter, combine: Boolean): List<SearchResult> {
        if (filter == SearchFilter.APPS) return emptyList()
        val results = linkedMapOf<String, SearchResult>()
        fun add(key: String, meta: Meta, source: String, isSaved: Boolean) {
            val identity = if (combine) identity(meta) else null
            val resultKey = identity?.let { "title:$it" } ?: key
            val previous = results[resultKey]
            results[resultKey] = if (previous == null) SearchResult(resultKey, meta, listOf(source), isSaved)
                else previous.copy(sources = (previous.sources + source).distinct(), saved = previous.saved || isSaved)
        }
        saved.filter { accepts(it, filter) }.distinctBy { it.mediaKey() }.forEach {
            add("saved:${it.mediaKey()}", it, "Watchlist", true)
        }
        val occurrences = mutableMapOf<String, Int>()
        if (filter != SearchFilter.WATCHLIST) rows.forEach { row ->
            // Include the catalog occurrence as well as title identity: repeated catalog keys must
            // not crash a lazy grid, and distinct provider-only titles must not be merged by name.
            val catalog = row.catalogKey ?: "${row.addonName}:${row.title}"
            val occurrence = occurrences.getOrDefault(catalog, 0)
            occurrences[catalog] = occurrence + 1
            val origin = "$catalog:$occurrence"
            row.items.filter { accepts(it, filter) }.distinctBy { it.mediaKey() }.forEach {
                add("catalog:$origin:${it.mediaKey()}", it, row.addonName.ifBlank { row.displayTitle }, false)
            }
        }
        return results.values.toList()
    }

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

}

object RecentSearches {
    const val LIMIT = 10
    fun updated(old: List<String>, query: String): List<String> {
        val term = query.trim().take(200)
        if (term.length < 2) return old
        return (listOf(term) + old.filterNot { it.equals(term, true) }).take(LIMIT)
    }
}
